package computer.handy.android.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.audio.AudioRecorder
import computer.handy.android.audio.FeedbackSounds
import computer.handy.android.audio.MicPermissionException
import computer.handy.android.audio.MicUnavailableException
import computer.handy.android.audio.SileroVad
import computer.handy.android.core.FieldInfo
import computer.handy.android.core.ImeText
import computer.handy.android.core.SensitiveFieldDetector
import computer.handy.android.core.SpeechSegments
import computer.handy.android.core.TapAction
import computer.handy.android.history.HistoryEntry
import computer.handy.android.overlay.ButtonState
import computer.handy.android.service.MicrophoneForegroundService
import computer.handy.android.transcription.ModelMissingException
import computer.handy.android.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handy as a voice-only keyboard. Gboard (or any keyboard) stays the main one: switch to
 * Handy with its globe key, Handy starts listening right away, shows the voice level, inserts
 * the transcript through the InputConnection (works in every app, Chrome and Compose fields
 * included) and switches back to the previous keyboard.
 *
 * Same pipeline as the floating button: Silero VAD trimming, transcription, desktop cleanup,
 * optional Claude post-processing, history, sounds and the model unload timer.
 */
class HandyVoiceIme : InputMethodService(), VoicePanel.Callbacks {

    private enum class Phase { IDLE, RECORDING, PROCESSING, FEEDBACK }
    private enum class Blocker { NONE, SENSITIVE, NO_MIC, NO_MODEL }

    private val app get() = application as HandyApp
    private val prefs get() = app.prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var panel: VoicePanel? = null
    private var phase = Phase.IDLE
    private var recorder: AudioRecorder? = null
    private var job: Job? = null
    private var switchBackJob: Job? = null
    private var sounds: FeedbackSounds? = null
    private var blocker = Blocker.NONE
    private var withClaude = false

    override fun onCreate() {
        super.onCreate()
        val w = window.window ?: return
        // Fill the navigation bar area under the panel with the panel's color. Android 15+
        // (edge-to-edge) leaves it transparent and ignores navigationBarColor, so the panel
        // draws under it instead (VoicePanel pads its controls above it); older versions use
        // the color.
        @Suppress("DEPRECATION")
        w.navigationBarColor = getColor(R.color.ime_background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") // still the way to stop the IME decor from fitting the nav bar
            w.setDecorFitsSystemWindows(false)
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            // Dark navigation handle and buttons on the light panel.
            w.insetsController?.setSystemBarsAppearance(
                if (night) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }

    override fun onCreateInputView(): View = VoicePanel(this, this).also { panel = it }.root

    // A voice panel never needs the fullscreen extract UI, even in landscape.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        switchBackJob?.cancel()
        // The app may restart input while we are working (e.g. a chat clearing its field after
        // our auto-submit); keep going rather than starting over.
        if (restarting && phase != Phase.IDLE) return

        withClaude = prefs.tapAction == TapAction.TRANSCRIBE_WITH_POST_PROCESS
        blocker = when {
            isSensitive(info) -> Blocker.SENSITIVE
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> Blocker.NO_MIC
            !app.transcriber.isModelInstalled() -> Blocker.NO_MODEL
            else -> Blocker.NONE
        }
        panel?.waveform?.clear()
        showIdle()
        if (restarting) return
        vibrate(VibrationEffect.EFFECT_CLICK)
        if (blocker == Blocker.NONE && prefs.imeAutoStart) start()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopEverything()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopEverything()
    }

    override fun onDestroy() {
        stopEverything()
        sounds?.release()
        sounds = null
        scope.cancel()
        super.onDestroy()
    }

    // --- Panel callbacks ---

    override fun onMainButton() {
        when (phase) {
            Phase.IDLE -> when (blocker) {
                Blocker.NONE -> {
                    vibrate(VibrationEffect.EFFECT_CLICK)
                    start()
                }
                Blocker.SENSITIVE -> Unit
                Blocker.NO_MIC, Blocker.NO_MODEL -> openHandy()
            }
            Phase.RECORDING -> recorder?.requestStop()
            Phase.PROCESSING, Phase.FEEDBACK -> Unit
        }
    }

    override fun onSwitchKeyboard() {
        stopEverything()
        switchBack()
    }

    override fun onOpenSettings() {
        stopEverything()
        openHandy()
    }

    override fun onToggleClaude() {
        withClaude = !withClaude
        // Remembered as the default tap action, shared with the floating button.
        prefs.tapAction = if (withClaude) TapAction.TRANSCRIBE_WITH_POST_PROCESS else TapAction.TRANSCRIBE
        refreshClaudeChip()
    }

    override fun onAction() = openHandy()

    // --- Dictation ---

    private fun claudeAvailable() = app.postProcessor() != null

    private fun refreshClaudeChip() {
        panel?.setClaude(visible = claudeAvailable(), enabled = withClaude)
    }

    private fun showIdle() {
        val p = panel ?: return
        refreshClaudeChip()
        val (text, action) = when (blocker) {
            Blocker.NONE -> R.string.ime_status_ready to null
            Blocker.SENSITIVE -> R.string.ime_status_sensitive to null
            Blocker.NO_MIC -> R.string.error_mic_permission to R.string.ime_open_handy
            Blocker.NO_MODEL -> R.string.error_model_missing to R.string.ime_open_handy
        }
        p.setAction(action)
        p.show(ButtonState.IDLE, getString(text), listening = false)
    }

    private fun start() {
        val p = panel ?: return
        val rec = AudioRecorder(this).also { recorder = it }
        val postProcess = withClaude && claudeAvailable()
        phase = Phase.RECORDING
        p.setAction(null)
        p.waveform.clear()
        p.show(ButtonState.RECORDING, getString(R.string.ime_status_starting), listening = true)

        // Load the model while the user speaks, so it is ready when they stop.
        app.activeDictations++
        app.cancelModelUnload()
        val preload = scope.launch(Dispatchers.Default) {
            try {
                app.transcriber.preload()
            } catch (e: Exception) {
                Log.w(TAG, "Model preload failed", e)
            }
        }

        job = scope.launch {
            try {
                val vad = if (prefs.vadEnabled) SileroVad.create(assets) else null
                val recording = try {
                    MicrophoneForegroundService.start(this@HandyVoiceIme)
                    rec.record(
                        silenceTimeoutMs = prefs.silenceTimeoutMs,
                        vad = vad,
                        onReady = {
                            scope.launch {
                                panel?.status?.setText(R.string.ime_status_listening)
                                playSound(start = true)
                            }
                        },
                        onLevel = { level ->
                            scope.launch {
                                panel?.waveform?.push(level)
                                panel?.button?.setLevel(level)
                            }
                        },
                    )
                } finally {
                    MicrophoneForegroundService.stop()
                    recorder = null
                }
                // Recording stopped (silence or tap): a short tap, like the one on opening.
                vibrate(VibrationEffect.EFFECT_CLICK)
                playSound(start = false)

                val speech = recording.speech
                if (speech != null && speech.isEmpty()) {
                    fail(R.string.error_nothing_heard)
                    return@launch
                }
                val audio = if (speech != null) {
                    SpeechSegments.extract(
                        recording.pcm,
                        SpeechSegments.padAndMerge(speech, recording.pcm.size, VAD_PAD_SAMPLES),
                    )
                } else {
                    recording.pcm
                }
                if (audio.size < MIN_SAMPLES) {
                    fail(R.string.error_too_short)
                    return@launch
                }

                phase = Phase.PROCESSING
                panel?.show(ButtonState.PROCESSING, getString(R.string.ime_status_transcribing), listening = false)
                preload.join()
                val result = withContext(Dispatchers.Default) {
                    app.pipeline(postProcess).run(audio, recording.sampleRate) {
                        scope.launch { panel?.status?.setText(R.string.ime_status_post_processing) }
                    }
                }
                if (result.text.isBlank()) {
                    fail(R.string.error_nothing_heard)
                    return@launch
                }

                if (!insert(result.text)) {
                    fail(R.string.ime_error_no_field)
                    return@launch
                }
                app.history.add(
                    HistoryEntry(
                        timestamp = System.currentTimeMillis(),
                        text = result.transcript,
                        postProcessedText = result.postProcessedText,
                        promptName = if (result.postProcessedText != null) prefs.selectedPrompt().name else null,
                        modelName = app.selectedModel().name,
                    ),
                    prefs.historyLimit,
                )
                succeed(if (result.postProcessFailed) R.string.toast_post_process_failed else R.string.ime_status_inserted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicPermissionException) {
                fail(R.string.error_mic_permission)
            } catch (e: ModelMissingException) {
                fail(R.string.error_model_missing)
            } catch (e: MicUnavailableException) {
                Log.w(TAG, "Microphone unavailable", e)
                fail(R.string.error_mic_unavailable)
            } catch (e: Exception) {
                Log.e(TAG, "Dictation failed", e)
                fail(R.string.error_generic)
            } finally {
                if (phase != Phase.FEEDBACK) phase = Phase.IDLE
                app.activeDictations--
                app.scheduleModelUnload()
            }
        }
    }

    /** Commits [text] at the cursor with desktop-like spacing; false if no field is connected. */
    private fun insert(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)
        val after = ic.getTextAfterCursor(CONTEXT_CHARS, 0)
        val insertion = ImeText.compose(before, after, text, prefs.appendTrailingSpace)
        ic.beginBatchEdit()
        ic.finishComposingText()
        val ok = ic.commitText(insertion, 1)
        ic.endBatchEdit()
        if (ok && prefs.autoSubmit) submit()
        return ok
    }

    /** Desktop's auto-submit: the field's action (send, search, go) or a plain Enter. */
    private fun submit() {
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private suspend fun succeed(messageRes: Int) {
        phase = Phase.FEEDBACK
        panel?.show(ButtonState.SUCCESS, getString(messageRes), listening = false)
        delay(SUCCESS_MS)
        phase = Phase.IDLE
        if (prefs.imeReturnToPrevious) switchBack() else showIdle()
    }

    private suspend fun fail(messageRes: Int) {
        phase = Phase.FEEDBACK
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
        // The message stays until the next tap: the panel is the only feedback here.
        panel?.show(ButtonState.ERROR, getString(messageRes), listening = false)
        delay(ERROR_MS)
        phase = Phase.IDLE
        panel?.button?.setState(ButtonState.IDLE)
    }

    private fun stopEverything() {
        switchBackJob?.cancel()
        recorder?.requestStop()
        job?.cancel()
        job = null
        recorder = null
        MicrophoneForegroundService.stop()
        if (phase != Phase.IDLE) {
            phase = Phase.IDLE
            panel?.waveform?.active = false
            panel?.button?.setState(ButtonState.IDLE)
        }
    }

    private fun switchBack() {
        if (!switchToPreviousInputMethod() && !switchToNextInputMethod(false)) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    private fun isSensitive(info: EditorInfo): Boolean {
        return SensitiveFieldDetector.isSensitive(
            FieldInfo(
                isEditable = true,
                inputType = info.inputType,
                hint = info.hintText?.toString(),
            ),
        )
    }

    private fun openHandy() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun playSound(start: Boolean) {
        if (!prefs.audioFeedback) return
        val pool = sounds ?: FeedbackSounds(this).also { sounds = it }
        if (start) pool.playStart(prefs.soundTheme, prefs.audioFeedbackVolume)
        else pool.playStop(prefs.soundTheme, prefs.audioFeedbackVolume)
    }

    private fun vibrate(effect: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createPredefined(effect))
    }

    companion object {
        private const val TAG = "HandyIme"
        private const val CONTEXT_CHARS = 64
        private const val MIN_SAMPLES = AudioRecorder.SAMPLE_RATE * 3 / 10
        private const val VAD_PAD_SAMPLES = AudioRecorder.SAMPLE_RATE / 5
        private const val SUCCESS_MS = 600L
        private const val ERROR_MS = 1_200L
    }
}
