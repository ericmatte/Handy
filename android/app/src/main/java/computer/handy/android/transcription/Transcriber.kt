package computer.handy.android.transcription

import computer.handy.android.core.OutputLanguage

/** What a speech model heard, and what is known about the language it was written in. */
data class Transcript(
    val text: String,
    val language: OutputLanguage = OutputLanguage.Unknown,
)

/** Speech-to-text engine. Implementations must be safe to call from a background dispatcher. */
interface Transcriber {
    /**
     * @param pcm mono PCM16 samples
     * @param sampleRate samples per second (the recorder always uses 16 kHz)
     */
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): Transcript
}
