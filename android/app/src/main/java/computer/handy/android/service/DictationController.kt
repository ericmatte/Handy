package computer.handy.android.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.audio.AudioRecorder
import computer.handy.android.audio.FeedbackSounds
import computer.handy.android.audio.MicPermissionException
import computer.handy.android.audio.MicUnavailableException
import computer.handy.android.audio.SileroVad
import computer.handy.android.core.SpeechSegments
import computer.handy.android.history.HistoryEntry
import computer.handy.android.overlay.ButtonState
import computer.handy.android.overlay.OverlayController
import computer.handy.android.transcription.ModelMissingException
import computer.handy.android.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tap-to-talk state machine: idle -> recording -> processing -> (success | error) -> idle.
 * Mirrors desktop Handy's transcribe action: record (Silero VAD), transcribe, clean up,
 * optionally post-process, insert, save to history, then unload the model after the
 * configured timeout. Runs on the main thread; audio and inference hop to background threads.
 */
class DictationController(
    private val context: Context,
    private val app: HandyApp,
    private val overlay: OverlayController,
    private val scope: CoroutineScope,
) {
    private enum class Phase { IDLE, RECORDING, PROCESSING, FEEDBACK }

    private val prefs = app.prefs
    private val transcriber = app.transcriber
    private var phase = Phase.IDLE
    private var recorder: AudioRecorder? = null
    private var job: Job? = null
    private var unloadJob: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private val inserter = TextInserter(context)
    private var sounds: FeedbackSounds? = null

    /** True from the tap until the success/error feedback is over. */
    val isBusy: Boolean get() = phase != Phase.IDLE

    /**
     * Button tap: starts a dictation into [target], or stops the one in progress.
     * @param withPostProcess desktop's "transcribe with post-process" action
     */
    fun toggle(target: AccessibilityNodeInfo?, withPostProcess: Boolean) {
        when (phase) {
            Phase.IDLE -> start(target, withPostProcess && prefs.postProcessEnabled)
            Phase.RECORDING -> recorder?.requestStop()
            Phase.PROCESSING, Phase.FEEDBACK -> Unit
        }
    }

    fun cancel() {
        recorder?.requestStop()
        job?.cancel()
        MicrophoneForegroundService.stop()
        phase = Phase.IDLE
        overlay.setState(ButtonState.IDLE)
    }

    fun release() {
        cancel()
        sounds?.release()
        sounds = null
    }

    private fun playSound(start: Boolean) {
        if (!prefs.audioFeedback) return
        val pool = sounds ?: FeedbackSounds(context).also { sounds = it }
        if (start) pool.playStart(prefs.soundTheme, prefs.audioFeedbackVolume)
        else pool.playStop(prefs.soundTheme, prefs.audioFeedbackVolume)
    }

    private fun start(target: AccessibilityNodeInfo?, withPostProcess: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            job = scope.launch { fail(R.string.error_mic_permission) }
            openSettings()
            return
        }
        if (!transcriber.isModelInstalled()) {
            job = scope.launch { fail(R.string.error_model_missing) }
            openSettings()
            return
        }
        val rec = AudioRecorder(context).also { recorder = it }
        phase = Phase.RECORDING
        overlay.setState(ButtonState.RECORDING)
        vibrate(VibrationEffect.EFFECT_TICK)

        // Load the model while the user speaks, so it is ready when they stop.
        unloadJob?.cancel()
        val preload = scope.launch {
            try {
                transcriber.preload()
            } catch (e: Exception) {
                Log.w(TAG, "Model preload failed", e)
            }
        }

        job = scope.launch {
            try {
                val vad = if (prefs.vadEnabled) SileroVad.create(context.assets) else null
                val recording = try {
                    // Best effort: if refused, the direct attempt below may still work (and
                    // AudioRecorder reports a silenced mic as an error rather than empty text).
                    MicrophoneForegroundService.start(context)
                    rec.record(
                        silenceTimeoutMs = prefs.silenceTimeoutMs,
                        vad = vad,
                        onReady = { main.post { playSound(start = true) } },
                        onLevel = { level -> main.post { overlay.setLevel(level) } },
                    )
                } finally {
                    MicrophoneForegroundService.stop()
                    recorder = null
                }
                vibrate(VibrationEffect.EFFECT_CLICK)
                playSound(start = false)

                // Like desktop's VAD filtering: transcribe only the voiced parts.
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
                overlay.setState(ButtonState.PROCESSING)
                preload.join()
                val result = withContext(Dispatchers.Default) {
                    app.pipeline(withPostProcess).run(audio, recording.sampleRate)
                }
                if (result.text.isBlank()) {
                    fail(R.string.error_nothing_heard)
                    return@launch
                }

                val outcome = inserter.insert(
                    target,
                    result.text,
                    trailingSpace = prefs.appendTrailingSpace,
                    autoSubmit = prefs.autoSubmit,
                )
                when (outcome) {
                    TextInserter.Outcome.CLIPBOARD_ONLY -> toast(R.string.toast_copied_to_clipboard)
                    else -> if (result.postProcessFailed) toast(R.string.toast_post_process_failed)
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
                succeed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicPermissionException) {
                fail(R.string.error_mic_permission)
            } catch (e: ModelMissingException) {
                fail(R.string.error_model_missing)
                openSettings()
            } catch (e: MicUnavailableException) {
                Log.w(TAG, "Microphone unavailable", e)
                fail(R.string.error_mic_unavailable)
            } catch (e: Exception) {
                Log.e(TAG, "Dictation failed", e)
                fail(R.string.error_generic)
            } finally {
                phase = Phase.IDLE
                scheduleUnload()
            }
        }
    }

    private suspend fun succeed() {
        phase = Phase.FEEDBACK
        overlay.setState(ButtonState.SUCCESS)
        delay(SUCCESS_MS)
        overlay.setState(ButtonState.IDLE)
        phase = Phase.IDLE
    }

    private suspend fun fail(messageRes: Int) {
        phase = Phase.FEEDBACK
        overlay.setState(ButtonState.ERROR)
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
        toast(messageRes)
        delay(ERROR_MS)
        overlay.setState(ButtonState.IDLE)
        phase = Phase.IDLE
    }

    /** Desktop `model_unload_timeout`: free the model's memory after a while without dictation. */
    fun scheduleUnload() {
        unloadJob?.cancel()
        val timeout = prefs.modelUnloadTimeout.millis ?: return
        unloadJob = scope.launch {
            delay(timeout)
            if (!isBusy) transcriber.release()
        }
    }

    /** Called on memory pressure: drop the model now unless a dictation needs it. */
    fun releaseModelIfIdle() {
        if (isBusy) return
        unloadJob?.cancel()
        scope.launch { transcriber.release() }
    }

    private fun toast(res: Int) = Toast.makeText(context, res, Toast.LENGTH_SHORT).show()

    private fun openSettings() {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun vibrate(effect: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() == true) vibrator.vibrate(VibrationEffect.createPredefined(effect))
    }

    companion object {
        private const val TAG = "HandyDictation"
        /** 0.3 s at 16 kHz: anything shorter is a mis-tap. */
        private const val MIN_SAMPLES = AudioRecorder.SAMPLE_RATE * 3 / 10
        /** 200 ms around each speech segment, so VAD onsets don't clip syllables. */
        private const val VAD_PAD_SAMPLES = AudioRecorder.SAMPLE_RATE / 5
        private const val SUCCESS_MS = 900L
        private const val ERROR_MS = 1_200L
    }
}
