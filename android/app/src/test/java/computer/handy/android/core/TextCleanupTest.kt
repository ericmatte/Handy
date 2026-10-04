package computer.handy.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the tests in desktop Handy's src-tauri/src/audio_toolkit/text.rs. */
class TextCleanupTest {

    private fun filter(text: String, language: String, custom: List<String>? = null): String {
        val filtered = TextCleanup.removeFillerWords(text, OutputLanguage.UserSelected(language), custom, true)
        return TextCleanup.normalize(filtered)
    }

    private fun custom(text: String, words: List<String>, threshold: Double = 0.5) =
        TextCleanup.applyCustomWords(text, words, threshold)

    @Test fun `custom words exact match`() =
        assertEquals("Hello World", custom("hello world", listOf("Hello", "World")))

    @Test fun `custom words fuzzy match`() =
        assertEquals("hello world", custom("helo wrold", listOf("hello", "world")))

    @Test fun `preserve case pattern`() {
        assertEquals("WORLD", TextCleanup.preserveCasePattern("HELLO", "world"))
        assertEquals("World", TextCleanup.preserveCasePattern("Hello", "world"))
        assertEquals("WORLD", TextCleanup.preserveCasePattern("hello", "WORLD"))
    }

    @Test fun `extract punctuation`() {
        assertEquals("" to "", TextCleanup.extractPunctuation("hello"))
        assertEquals("!" to "?", TextCleanup.extractPunctuation("!hello?"))
        assertEquals("..." to "...", TextCleanup.extractPunctuation("...hello..."))
    }

    @Test fun `extract punctuation uses unicode boundaries`() {
        assertEquals("" to "。", TextCleanup.extractPunctuation("你好。"))
        assertEquals("「" to "」", TextCleanup.extractPunctuation("「你好」"))
        assertEquals("" to "！", TextCleanup.extractPunctuation("你好！"))
    }

    @Test fun `empty custom words`() = assertEquals("hello world", custom("hello world", emptyList()))

    @Test fun `filter filler words`() =
        assertEquals("So I was thinking about this", filter("So uhm I was thinking uh about this", "en"))

    @Test fun `filter filler words case insensitive`() =
        assertEquals("This is a test", filter("UHM this is UH a test", "en"))

    @Test fun `filter filler words with punctuation`() =
        assertEquals("Well, I think, that's right", filter("Well, uhm, I think, uh. that's right", "en"))

    @Test fun `filter cleans whitespace`() = assertEquals("Hello world test", filter("Hello    world   test", "en"))

    @Test fun `filter trims`() = assertEquals("Hello world", filter("  Hello world  ", "en"))

    @Test fun `filter combined`() =
        assertEquals("So I was, thinking about this", filter("  Uhm, so I was, uh, thinking about this  ", "en"))

    @Test fun `leading filler keeps sentence capital`() {
        assertEquals("So I think we should ship it.", filter("Um, so I think we should ship it.", "en"))
        assertEquals("That works. Let me check.", filter("That works. Um, let me check.", "en"))
        assertEquals("He said, not today.", filter("He said, Um, not today.", "en"))
    }

    @Test fun `filter preserves valid text`() =
        assertEquals("This is a completely normal sentence.", filter("This is a completely normal sentence.", "en"))

    @Test fun `stutter collapse`() = assertEquals("w wh why", filter("w wh wh wh wh wh wh wh wh wh why", "en"))

    @Test fun `stutter short words`() = assertEquals("I think so", filter("I I I I think so so so so", "en"))

    @Test fun `stutter longer words`() =
        assertEquals("Check data doc documentation.", filter("Check data doc doc doc doc documentation.", "en"))

    @Test fun `stutter mixed case`() = assertEquals("No", filter("No NO no NO no", "en"))

    @Test fun `stutter preserves two repetitions`() = assertEquals("no no is fine", filter("no no is fine", "en"))

    @Test fun `english removes um`() = assertEquals("I think this is good", filter("um I think um this is good", "en"))

    @Test fun `portuguese preserves um`() = assertEquals("um gato bonito", filter("um gato bonito", "pt"))

    @Test fun `spanish preserves ha`() = assertEquals("ha sido un buen día", filter("ha sido un buen día", "es"))

    @Test fun `language code with region`() = assertEquals("um gato bonito", filter("um gato bonito", "pt-BR"))

    @Test fun `custom filler words override`() =
        assertEquals("so I think this works", filter("okay so I think right this works", "en", listOf("okay", "right")))

    @Test fun `empty custom filler list disables removal`() =
        assertEquals("So uhm I was thinking uh about this", filter("So uhm I was thinking uh about this", "en", emptyList()))

    @Test fun `unknown language still removes universal fillers`() =
        assertEquals("I think this works", filter("uh I think uhm this works", "xx"))

    @Test fun `unknown language does not remove um`() =
        assertEquals("um I think this works", filter("um I think this works", "xx"))

    @Test fun `unknown evidence removes universal keeps gated`() {
        val filtered = TextCleanup.removeFillerWords("uhh bueno hmm creo que um ha llegado", OutputLanguage.Unknown, null, true)
        assertEquals("bueno creo que um ha llegado", TextCleanup.normalize(filtered))
        val cyrillic = TextCleanup.removeFillerWords("хм я думаю ммм это работает", OutputLanguage.Unknown, null, true)
        assertEquals("я думаю это работает", TextCleanup.normalize(cyrillic))
    }

    @Test fun `german gated fillers require evidence`() {
        val text = "äh ich glaube ähm das passt"
        val unknown = TextCleanup.removeFillerWords(text, OutputLanguage.Unknown, null, true)
        assertEquals(text, TextCleanup.normalize(unknown))
        assertEquals("ich glaube das passt", filter(text, "de"))
    }

    @Test fun `preserves millimetre unit`() = assertEquals("the screw is 5 mm long", filter("the screw is 5 mm long", "en"))

    @Test fun `detected evidence unlocks gated fillers`() {
        val model = TextCleanup.removeFillerWords("um I think this works", OutputLanguage.ModelDetected("en"), null, true)
        assertEquals("I think this works", TextCleanup.normalize(model))
        val text = TextCleanup.removeFillerWords("euh je pense que ça marche", OutputLanguage.TextDetected("fr"), null, true)
        assertEquals("je pense que ça marche", TextCleanup.normalize(text))
    }

    @Test fun `master toggle disables custom and builtin removal`() {
        val text = "um customword I think"
        assertEquals(text, TextCleanup.removeFillerWords(text, OutputLanguage.UserSelected("en"), listOf("customword"), false))
    }

    @Test fun `custom filler words apply without language evidence`() {
        val filtered = TextCleanup.removeFillerWords(
            "customword should be removed but um should remain", OutputLanguage.Unknown, listOf("customword"), true,
        )
        assertEquals("should be removed but um should remain", TextCleanup.normalize(filtered))
    }

    @Test fun `ngram two words`() {
        val result = custom("il cui nome è Charge B, che permette", listOf("ChargeBee"))
        assertTrue(result, result.contains("ChargeBee,"))
        assertFalse(result.contains("Charge B"))
    }

    @Test fun `ngram three words`() = assertTrue(custom("use Chat G P T for this", listOf("ChatGPT")).contains("ChatGPT"))

    @Test fun `prefers longer ngram`() = assertEquals("OpenAI GPT model", custom("Open AI GPT model", listOf("OpenAI", "GPT")))

    @Test fun `ngram preserves case`() = assertTrue(custom("CHARGE B is great", listOf("ChargeBee")).contains("CHARGEBEE"))

    @Test fun `ngram with spaces in custom word`() =
        assertEquals("using MacBook Pro", custom("using Mac Book Pro", listOf("MacBook Pro")))

    @Test fun `trailing number not doubled`() = assertFalse(custom("use GPT4 for this", listOf("GPT-4")).contains("GPT-44"))

    @Test fun `matches ampersand word`() =
        assertEquals("send it to R&D for review", custom("send it to RD for review", listOf("R&D"), 0.18))

    @Test fun `matches spoken ampersand word`() =
        assertEquals("send it to R&D for review", custom("send it to R and D for review", listOf("R&D"), 0.18))

    @Test fun `preserves ampersand word`() =
        assertEquals("send it to R&D for review", custom("send it to R&D for review", listOf("R&D"), 0.18))

    @Test fun `handles unicode punctuation`() = assertEquals("「Handy。」", custom("「Handee。」", listOf("Handy")))

    @Test fun `skips cjk fuzzy matching`() = assertEquals("你好。", custom("你好。", listOf("你号"), 1.0))

    // --- Android additions --------------------------------------------------------------

    @Test fun `soundex codes`() {
        assertEquals("R163", TextCleanup.soundex("Robert"))
        assertEquals("R163", TextCleanup.soundex("Rupert"))
        assertEquals("A261", TextCleanup.soundex("Ashcraft"))
        assertEquals("T522", TextCleanup.soundex("Tymczak"))
        assertEquals("P236", TextCleanup.soundex("Pfister"))
    }

    @Test fun `levenshtein distance`() {
        assertEquals(3, TextCleanup.levenshtein("kitten", "sitting"))
        assertEquals(0, TextCleanup.levenshtein("same", "same"))
        assertEquals(4, TextCleanup.levenshtein("", "abcd"))
    }

    @Test fun `text detection resolves french and unlocks euh`() {
        assertEquals("fr", TextLanguageDetector.detect("euh je pense que ça marche pour nous"))
        val out = TextCleanup.process("euh je pense que c'est une bonne idée", OutputLanguage.Unknown, TextCleanup.Options())
        assertEquals("je pense que c'est une bonne idée", out)
    }

    @Test fun `text detection fails closed on short or ambiguous text`() {
        assertNull(TextLanguageDetector.detect("ok"))
        assertNull(TextLanguageDetector.detect("Handy Android"))
    }

    @Test fun `text detection keeps portuguese um`() {
        val out = TextCleanup.process("eu tenho um gato e uma casa", OutputLanguage.Unknown, TextCleanup.Options())
        assertEquals("eu tenho um gato e uma casa", out)
    }

    @Test fun `pipeline applies custom words then fillers then normalization`() {
        val out = TextCleanup.process(
            "Uhm, I I I use handee every day",
            OutputLanguage.UserSelected("en"),
            TextCleanup.Options(customWords = listOf("Handy")),
        )
        assertEquals("I use Handy every day", out)
    }
}
