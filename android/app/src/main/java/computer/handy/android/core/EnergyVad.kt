package computer.handy.android.core

import kotlin.math.sqrt

/**
 * Minimal energy-based voice activity detector used to auto-stop recording after silence.
 * Placeholder for Silero VAD (the desktop app's model), kept behind the same tiny surface:
 * feed frames, read [shouldStop].
 *
 * The noise floor adapts to the room, so it works without calibration: a frame counts as
 * speech when its RMS is well above the running floor.
 */
class EnergyVad(
    private val sampleRate: Int,
    private val silenceTimeoutMs: Long,
    /** Give up if nobody speaks at all within this window. */
    private val noSpeechTimeoutMs: Long = 8_000,
    /** Hard cap on a single dictation. */
    private val maxDurationMs: Long = 120_000,
    private val speechRatio: Float = 3.0f,
    private val minSpeechRms: Float = 350f,
) {
    var heardSpeech: Boolean = false
        private set

    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var elapsedMs = 0L
    private var silenceMs = 0L

    /** Normalized 0..1 loudness of the last frame, for the pulsing ring. */
    var level: Float = 0f
        private set

    /** Processes one frame of PCM16 samples. */
    fun accept(frame: ShortArray, length: Int = frame.size) {
        if (length <= 0) return
        val rms = rms(frame, length)
        val frameMs = length * 1000L / sampleRate
        elapsedMs += frameMs

        val isSpeech = rms > minSpeechRms && rms > noiseFloor * speechRatio
        if (isSpeech) {
            heardSpeech = true
            silenceMs = 0
        } else {
            silenceMs += frameMs
            // Track the floor only on non-speech frames, rising slowly and falling fast.
            noiseFloor = if (rms < noiseFloor) rms * 0.5f + noiseFloor * 0.5f else rms * 0.05f + noiseFloor * 0.95f
        }
        level = (rms / 6000f).coerceIn(0f, 1f)
    }

    fun shouldStop(): Boolean = when {
        elapsedMs >= maxDurationMs -> true
        heardSpeech -> silenceMs >= silenceTimeoutMs
        else -> elapsedMs >= noSpeechTimeoutMs
    }

    companion object {
        /** Typical phone mic RMS in a quiet room; adapts within ~1 s to the real room. */
        private const val INITIAL_NOISE_FLOOR = 150f

        fun rms(frame: ShortArray, length: Int = frame.size): Float {
            if (length <= 0) return 0f
            var sum = 0.0
            for (i in 0 until length) {
                val s = frame[i].toDouble()
                sum += s * s
            }
            return sqrt(sum / length).toFloat()
        }
    }
}
