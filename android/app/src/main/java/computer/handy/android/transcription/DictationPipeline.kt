package computer.handy.android.transcription

/** Recording -> transcription -> post-processing, with no Android dependencies. */
class DictationPipeline(
    private val transcriber: Transcriber,
    private val postProcessor: PostProcessor,
) {
    suspend fun run(pcm: ShortArray, sampleRate: Int): String {
        val raw = transcriber.transcribe(pcm, sampleRate).trim()
        if (raw.isEmpty()) return ""
        return postProcessor.process(raw).trim()
    }
}
