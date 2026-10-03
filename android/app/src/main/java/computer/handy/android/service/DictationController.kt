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
import computer.handy.android.R
import computer.handy.android.audio.AudioRecorder
import computer.handy.android.audio.MicPermissionException
import computer.handy.android.audio.MicUnavailableException
import computer.handy.android.overlay.ButtonState
import computer.handy.android.overlay.OverlayController
import computer.handy.android.settings.HandyPrefs
import computer.handy.android.transcription.DictationPipeline
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
 * Runs on the main thread; audio and transcription hop to background dispatchers.
 */
class DictationController(
    private val context: Context,
    private val overlay: OverlayController,
    private val prefs: HandyPrefs,
    private val pipeline: DictationPipeline,
    private val scope: CoroutineScope,
) {
    private enum class Phase { IDLE, RECORDING, PROCESSING, FEEDBACK }

    private var phase = Phase.IDLE
    private var recorder: AudioRecorder? = null
    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private val inserter = TextInserter(context)

    /** True from the tap until the success/error feedback is over. */
    val isBusy: Boolean get() = phase != Phase.IDLE

    /** Button tap: starts a dictation into [target], or stops the one in progress. */
    fun toggle(target: AccessibilityNodeInfo?) {
        when (phase) {
            Phase.IDLE -> start(target)
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

    private fun start(target: AccessibilityNodeInfo?) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            job = scope.launch { fail(R.string.error_mic_permission) }
            openSettings()
            return
        }
        val rec = AudioRecorder(context).also { recorder = it }
        phase = Phase.RECORDING
        overlay.setState(ButtonState.RECORDING)
        vibrate(VibrationEffect.EFFECT_TICK)

        job = scope.launch {
            try {
                val recording = try {
                    // Best effort: if refused, the direct attempt below may still work (and
                    // AudioRecorder reports a silenced mic as an error rather than empty text).
                    MicrophoneForegroundService.start(context)
                    rec.record(prefs.silenceTimeoutMs) { level -> main.post { overlay.setLevel(level) } }
                } finally {
                    MicrophoneForegroundService.stop()
                    recorder = null
                }
                vibrate(VibrationEffect.EFFECT_CLICK)

                if (recording.pcm.size < MIN_SAMPLES) {
                    fail(R.string.error_too_short)
                    return@launch
                }

                phase = Phase.PROCESSING
                overlay.setState(ButtonState.PROCESSING)
                val text = withContext(Dispatchers.Default) {
                    pipeline.run(recording.pcm, recording.sampleRate)
                }
                if (text.isBlank()) {
                    fail(R.string.error_nothing_heard)
                    return@launch
                }

                when (inserter.insert(target, text)) {
                    TextInserter.Outcome.CLIPBOARD_ONLY -> toast(R.string.toast_copied_to_clipboard)
                    else -> Unit
                }
                succeed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MicPermissionException) {
                fail(R.string.error_mic_permission)
            } catch (e: MicUnavailableException) {
                Log.w(TAG, "Microphone unavailable", e)
                fail(R.string.error_mic_unavailable)
            } catch (e: Exception) {
                Log.e(TAG, "Dictation failed", e)
                fail(R.string.error_generic)
            } finally {
                phase = Phase.IDLE
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
        private const val SUCCESS_MS = 900L
        private const val ERROR_MS = 1_200L
    }
}
