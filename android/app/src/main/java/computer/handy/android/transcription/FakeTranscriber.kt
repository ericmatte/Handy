package computer.handy.android.transcription

import kotlinx.coroutines.delay

/**
 * Stand-in until a real on-device model (whisper.cpp or sherpa-onnx/Parakeet) is wired in.
 * Returns a fixed sentence that mentions the clip length, after a short delay so the
 * "processing" state of the button is visible.
 */
class FakeTranscriber(
    private val latencyMs: Long = 600,
) : Transcriber {
    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String {
        delay(latencyMs)
        if (pcm.isEmpty()) return ""
        val seconds = pcm.size.toDouble() / sampleRate
        return "Bonjour depuis Handy (test %.1f s).".format(java.util.Locale.ROOT, seconds)
    }
}
