package computer.handy.android.transcription

import computer.handy.android.core.OutputLanguage
import computer.handy.android.core.TextCleanup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationPipelineTest {

    private val pcm = ShortArray(16_000)
    private val cleanup = TextCleanup.Options()

    private fun transcriber(text: String, language: OutputLanguage = OutputLanguage.Unknown) = object : Transcriber {
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int) = Transcript(text, language)
    }

    private fun processor(block: (String) -> String) = object : PostProcessor {
        override suspend fun process(text: String) = block(text)
    }

    @Test
    fun `plain transcription is cleaned like on desktop`() = runTest {
        val result = DictationPipeline(transcriber("Um, so I I I think", OutputLanguage.UserSelected("en")), cleanup, null)
            .run(pcm, 16_000)
        assertEquals("So I think", result.text)
        assertNull(result.postProcessedText)
    }

    @Test
    fun `post-processor receives the cleaned transcript`() = runTest {
        var received = ""
        val result = DictationPipeline(transcriber("uh raw text"), cleanup, processor { received = it; "Clean: $it" })
            .run(pcm, 16_000)
        assertEquals("raw text", received)
        assertEquals("Clean: raw text", result.text)
        assertEquals("raw text", result.transcript)
        assertEquals("Clean: raw text", result.postProcessedText)
        assertFalse(result.postProcessFailed)
    }

    @Test
    fun `post-processing failure keeps the transcript`() = runTest {
        val result = DictationPipeline(transcriber("raw text"), cleanup, processor { throw IllegalStateException("401") })
            .run(pcm, 16_000)
        assertEquals("raw text", result.text)
        assertTrue(result.postProcessFailed)
    }

    @Test
    fun `empty rewrite keeps the transcript`() = runTest {
        val result = DictationPipeline(transcriber("raw text"), cleanup, processor { "  " }).run(pcm, 16_000)
        assertEquals("raw text", result.text)
    }

    @Test
    fun `blank transcript skips post-processing`() = runTest {
        var called = false
        val result = DictationPipeline(transcriber(" uhm "), cleanup, processor { called = true; it }).run(pcm, 16_000)
        assertEquals("", result.text)
        assertFalse(called)
    }

    @Test
    fun `custom words are applied`() = runTest {
        val result = DictationPipeline(transcriber("I use handee daily"), TextCleanup.Options(customWords = listOf("Handy")), null)
            .run(pcm, 16_000)
        assertEquals("I use Handy daily", result.text)
    }

    @Test
    fun `fake transcriber works through the pipeline`() = runTest {
        val result = DictationPipeline(FakeTranscriber(), cleanup, NoOpPostProcessor).run(pcm, 16_000)
        assertTrue(result.text.startsWith("Bonjour depuis Handy"))
    }
}
