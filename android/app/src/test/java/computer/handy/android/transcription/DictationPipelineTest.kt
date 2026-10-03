package computer.handy.android.transcription

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationPipelineTest {

    private val pcm = ShortArray(16_000)

    private fun transcriber(text: String) = object : Transcriber {
        override suspend fun transcribe(pcm: ShortArray, sampleRate: Int) = text
    }

    @Test
    fun `post-processed text is returned`() = runTest {
        val pipeline = DictationPipeline(transcriber(" raw text "), object : PostProcessor {
            override suspend fun process(text: String) = "Clean: $text"
        })
        val result = pipeline.run(pcm, 16_000)
        assertEquals("Clean: raw text", result.text)
        assertFalse(result.postProcessFailed)
    }

    @Test
    fun `post-processing failure keeps the raw transcript`() = runTest {
        val pipeline = DictationPipeline(transcriber("raw text"), object : PostProcessor {
            override suspend fun process(text: String): String = throw IllegalStateException("401")
        })
        val result = pipeline.run(pcm, 16_000)
        assertEquals("raw text", result.text)
        assertTrue(result.postProcessFailed)
    }

    @Test
    fun `empty rewrite keeps the raw transcript`() = runTest {
        val pipeline = DictationPipeline(transcriber("raw text"), object : PostProcessor {
            override suspend fun process(text: String) = "  "
        })
        assertEquals("raw text", pipeline.run(pcm, 16_000).text)
    }

    @Test
    fun `empty transcript skips post-processing`() = runTest {
        var called = false
        val pipeline = DictationPipeline(transcriber("   "), object : PostProcessor {
            override suspend fun process(text: String): String {
                called = true
                return text
            }
        })
        assertEquals("", pipeline.run(pcm, 16_000).text)
        assertFalse(called)
    }

    @Test
    fun `no-op post-processor returns the transcript`() = runTest {
        val pipeline = DictationPipeline(FakeTranscriber(latencyMs = 0), NoOpPostProcessor)
        assertTrue(pipeline.run(pcm, 16_000).text.startsWith("Bonjour depuis Handy"))
    }
}
