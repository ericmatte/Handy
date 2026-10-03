package computer.handy.android.transcription

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import computer.handy.android.core.PostProcessPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * Rewrites the transcript with Claude (the user's own API key and prompt), like desktop Handy's
 * post-processing. Failures throw; [DictationPipeline] then keeps the raw transcript.
 */
class ClaudePostProcessor(
    private val apiKey: String,
    private val model: String,
    private val prompt: String,
) : PostProcessor {

    override suspend fun process(text: String): String = withContext(Dispatchers.IO) {
        val request = PostProcessPrompt.build(prompt, text)
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .maxRetries(1)
            .build()
        try {
            val params = MessageCreateParams.builder()
                .model(model.ifBlank { DEFAULT_MODEL })
                .maxTokens(MAX_TOKENS)
                .apply { request.system?.let { system(it) } }
                .addUserMessage(request.user)
                .build()
            client.messages().create(params).content()
                .mapNotNull { block -> block.text().orElse(null)?.text() }
                .joinToString("")
                .trim()
        } finally {
            client.close()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-4-5"
        private const val MAX_TOKENS = 4096L
        private const val TIMEOUT_SECONDS = 20L
    }
}
