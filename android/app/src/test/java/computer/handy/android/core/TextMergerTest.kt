package computer.handy.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextMergerTest {

    private fun merge(
        existing: String?,
        start: Int,
        end: Int = start,
        insert: String,
        showingHint: Boolean = false,
        hint: String? = null,
    ) = TextMerger.merge(existing, showingHint, hint, start, end, insert)

    @Test
    fun `empty field gets the text and cursor at the end`() {
        val r = merge("", 0, insert = "Hello world")
        assertEquals("Hello world", r.text)
        assertEquals(11, r.cursor)
    }

    @Test
    fun `null text is treated as empty`() {
        assertEquals("Hi", merge(null, -1, insert = "Hi").text)
    }

    @Test
    fun `placeholder flagged by isShowingHintText is not merged`() {
        val r = merge("Type a message", 0, insert = "Hello", showingHint = true)
        assertEquals("Hello", r.text)
        assertEquals(5, r.cursor)
    }

    @Test
    fun `placeholder equal to the hint is not merged even without the flag`() {
        val r = merge("Search", 0, insert = "cats", hint = "Search")
        assertEquals("cats", r.text)
    }

    @Test
    fun `text equal to the hint is kept when the cursor is inside it`() {
        // The user really typed "Search" and the cursor is after it.
        val r = merge("Search", 6, insert = "cats", hint = "Search")
        assertEquals("Search cats", r.text)
    }

    @Test
    fun `appends with a space after existing text`() {
        val r = merge("Hello", 5, insert = "world")
        assertEquals("Hello world", r.text)
        assertEquals(11, r.cursor)
    }

    @Test
    fun `no double space when existing text ends with a space`() {
        assertEquals("Hello world", merge("Hello ", 6, insert = "world").text)
    }

    @Test
    fun `inserts in the middle with spaces on both sides`() {
        val r = merge("Hello world", 5, insert = "big")
        assertEquals("Hello big world", r.text)
        assertEquals("Hello big".length, r.cursor)
    }

    @Test
    fun `inserts before a word without gluing`() {
        val r = merge("world", 0, insert = "Hello")
        assertEquals("Hello world", r.text)
        assertEquals(6, r.cursor)
    }

    @Test
    fun `replaces the selection`() {
        val r = merge("Hello there world", 6, 11, insert = "big")
        assertEquals("Hello big world", r.text)
        assertEquals(9, r.cursor)
    }

    @Test
    fun `reversed selection is normalized`() {
        assertEquals("Hello big world", merge("Hello there world", 11, 6, insert = "big").text)
    }

    @Test
    fun `unknown selection appends at the end`() {
        assertEquals("Hello world", merge("Hello", -1, insert = "world").text)
    }

    @Test
    fun `stale selection beyond the text appends at the end`() {
        assertEquals("Hello world", merge("Hello", 42, insert = "world").text)
    }

    @Test
    fun `no space before punctuation that follows`() {
        val r = merge("Hello.", 5, insert = "world")
        assertEquals("Hello world.", r.text)
    }

    @Test
    fun `no space after an opening bracket`() {
        assertEquals("(note)", merge("()", 1, insert = "note").text)
    }

    @Test
    fun `transcript whitespace is trimmed`() {
        assertEquals("Hello world", merge("Hello", 5, insert = "  world \n").text)
    }

    @Test
    fun `blank transcript leaves the text untouched`() {
        val r = merge("Hello", 3, insert = "   ")
        assertEquals("Hello", r.text)
        assertEquals(3, r.cursor)
        assertEquals("", r.inserted)
    }

    @Test
    fun `inserted segment includes added spaces for the paste fallback`() {
        assertEquals(" big", merge("Hello world", 5, insert = "big").inserted)
        assertEquals("big ", merge("Hello world", 6, insert = "big").inserted)
    }

    @Test
    fun `emoji and accents keep correct cursor offsets`() {
        // Offsets are UTF-16 units, like Android's: "👍" counts as two.
        val r = merge("Ça va 👍", 6, insert = "très bien")
        assertEquals("Ça va très bien 👍", r.text)
        assertEquals("Ça va très bien ".length, r.cursor)
    }

    @Test
    fun `a cursor inside an emoji never splits it`() {
        val r = merge("Ok 👍", 4, insert = "merci")
        assertEquals("Ok 👍 merci", r.text)
        assertEquals("Ok 👍 merci".length, r.cursor)
    }
}
