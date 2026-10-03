package computer.handy.android.transcription

import kotlinx.coroutines.delay

/** Test double: returns a fixed sentence mentioning the clip length. */
class FakeTranscriber(
    private val latencyMs: Long = 0,
) : Transcriber {
    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): Transcript {
        delay(latencyMs)
        if (pcm.isEmpty()) return Transcript("")
        val seconds = pcm.size.toDouble() / sampleRate
        return Transcript("Bonjour depuis Handy (test %.1f s).".format(java.util.Locale.ROOT, seconds))
    }
}
