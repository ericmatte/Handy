package computer.handy.android.transcription

import android.util.Log
import computer.handy.android.core.TextCleanup

/**
 * Transcription -> desktop text cleanup (custom words, fillers, stutters) -> optional
 * post-processing, in the same order as desktop Handy.
 */
class DictationPipeline(
    private val transcriber: Transcriber,
    private val cleanup: TextCleanup.Options,
    /** null for a plain transcription (desktop's "transcribe" shortcut). */
    private val postProcessor: PostProcessor?,
) {
    data class Result(
        /** What to insert. */
        val text: String,
        /** The cleaned transcript before post-processing. */
        val transcript: String,
        val postProcessedText: String? = null,
        /** Post-processing was attempted and failed: [text] is the transcript. */
        val postProcessFailed: Boolean = false,
    )

    /** @param onPostProcessing called just before the transcript goes to the post-processor */
    suspend fun run(pcm: ShortArray, sampleRate: Int, onPostProcessing: () -> Unit = {}): Result {
        val raw = transcriber.transcribe(pcm, sampleRate)
        val cleaned = TextCleanup.process(raw.text, raw.language, cleanup)
        if (cleaned.isBlank()) return Result("", "")
        val processor = postProcessor ?: return Result(cleaned, cleaned)
        onPostProcessing()
        return try {
            val processed = processor.process(cleaned).trim()
            // An empty rewrite would silently lose the dictation: keep the transcript instead.
            if (processed.isEmpty()) Result(cleaned, cleaned) else Result(processed, cleaned, processed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HandyPipeline", "Post-processing failed, keeping the transcript", e)
            Result(cleaned, cleaned, postProcessFailed = true)
        }
    }
}
