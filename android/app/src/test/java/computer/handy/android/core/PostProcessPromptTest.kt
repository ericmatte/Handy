package computer.handy.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessPromptTest {

    @Test
    fun `placeholder prompt becomes the user message`() {
        val r = PostProcessPrompt.build("Fix this: \${output}", "bonjour")
        assertNull(r.system)
        assertEquals("Fix this: bonjour", r.user)
    }

    @Test
    fun `every placeholder is replaced`() {
        val r = PostProcessPrompt.build("\${output} / \${output}", "a")
        assertEquals("a / a", r.user)
    }

    @Test
    fun `prompt without placeholder is the system prompt`() {
        val r = PostProcessPrompt.build("Translate to English.", "bonjour")
        assertEquals("Translate to English.", r.system)
        assertEquals("bonjour", r.user)
    }

    @Test
    fun `blank prompt falls back to the desktop default`() {
        val r = PostProcessPrompt.build("   ", "bonjour")
        assertNull(r.system)
        assertTrue(r.user.startsWith("<transcript>\nbonjour\n</transcript>"))
    }

    @Test
    fun `default prompt keeps the desktop wording and placeholder`() {
        assertTrue(PostProcessPrompt.DEFAULT.contains("\${output}"))
        assertTrue(PostProcessPrompt.DEFAULT.contains("keep it in french"))
        assertTrue(PostProcessPrompt.DEFAULT.contains("five dollars → \$5"))
        assertTrue(PostProcessPrompt.DEFAULT.endsWith("Return only the cleaned text."))
    }

    @Test
    fun `response cleanup drops a think block and invisible characters`() {
        assertEquals("Bonjour.", PostProcessPrompt.cleanResponse("<think>reasoning</think>\n Bon\u200Bjour.\uFEFF"))
        assertEquals("Plain", PostProcessPrompt.cleanResponse("  Plain  "))
        // An unterminated think block is left alone, like on desktop.
        assertEquals("<think>oops", PostProcessPrompt.cleanResponse("<think>oops"))
    }
}
