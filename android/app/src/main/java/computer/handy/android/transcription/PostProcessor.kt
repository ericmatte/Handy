package computer.handy.android.transcription

/**
 * Rewrites a raw transcript (punctuation, custom prompt, etc.). The desktop app does this with
 * an LLM; on Android the planned implementation calls Claude with the user's own API key.
 */
interface PostProcessor {
    suspend fun process(text: String): String
}

/** Default: returns the transcript unchanged. */
object NoOpPostProcessor : PostProcessor {
    override suspend fun process(text: String): String = text
}
