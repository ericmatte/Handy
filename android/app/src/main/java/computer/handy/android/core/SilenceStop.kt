package computer.handy.android.core

/** Auto-stop rule fed by a speech/non-speech decision per frame (Silero). */
class SilenceStop(
    private val silenceTimeoutMs: Long,
    private val noSpeechTimeoutMs: Long = 8_000,
    private val maxDurationMs: Long = 120_000,
) {
    var heardSpeech = false
        private set
    private var elapsedMs = 0L
    private var silenceMs = 0L

    /** @return true when recording should stop */
    fun accept(isSpeech: Boolean, frameMs: Long): Boolean {
        elapsedMs += frameMs
        if (isSpeech) {
            heardSpeech = true
            silenceMs = 0
        } else {
            silenceMs += frameMs
        }
        return when {
            elapsedMs >= maxDurationMs -> true
            heardSpeech -> silenceMs >= silenceTimeoutMs
            else -> elapsedMs >= noSpeechTimeoutMs
        }
    }
}
