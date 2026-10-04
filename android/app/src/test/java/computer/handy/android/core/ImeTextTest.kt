package computer.handy.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeTextTest {

    @Test
    fun `empty field gets the bare text`() = assertEquals("Bonjour", ImeText.compose("", "", " Bonjour ", false))

    @Test
    fun `space added after a word`() = assertEquals(" world", ImeText.compose("Hello", "", "world", false))

    @Test
    fun `spaces on both sides in the middle of text`() = assertEquals(" big ", ImeText.compose("Hello", "world", "big", false))

    @Test
    fun `no space before following punctuation`() = assertEquals(" world", ImeText.compose("Hello", ".", "world", false))

    @Test
    fun `null context from apps that hide it`() = assertEquals("Hi", ImeText.compose(null, null, "Hi", false))

    @Test
    fun `trailing space option`() = assertEquals("Hi ", ImeText.compose("", "", "Hi", true))
}
