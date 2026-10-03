package computer.handy.android.transcription

import android.util.Log

/** Recording -> transcription -> post-processing. */
class DictationPipeline(
    private val transcriber: Transcriber,
    private val postProcessor: PostProcessor,
) {
    data class Result(
        val text: String,
        /** Post-processing was attempted and failed: [text] is the raw transcript. */
        val postProcessFailed: Boolean = false,
    )

    suspend fun run(pcm: ShortArray, sampleRate: Int): Result {
        val raw = transcriber.transcribe(pcm, sampleRate).trim()
        if (raw.isEmpty()) return Result("")
        return try {
            val processed = postProcessor.process(raw).trim()
            // An empty rewrite would silently lose the dictation: keep the raw text instead.
            if (processed.isEmpty()) Result(raw) else Result(processed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HandyPipeline", "Post-processing failed, keeping the raw transcript", e)
            Result(raw, postProcessFailed = true)
        }
    }
}
