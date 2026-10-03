package computer.handy.android.transcription

/** Speech-to-text engine. Implementations must be safe to call from a background dispatcher. */
interface Transcriber {
    /**
     * @param pcm mono PCM16 samples
     * @param sampleRate samples per second (the recorder always uses 16 kHz)
     * @return the transcript, empty when nothing intelligible was said
     */
    suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String
}
