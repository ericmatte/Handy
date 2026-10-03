package computer.handy.android.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Evidence for the language of the transcription output (not the UI language).
 * Unknown output languages fail closed: language-gated fillers are kept.
 *
 * Port of `OutputLanguageEvidence` in desktop Handy's `src-tauri/src/audio_toolkit/text.rs`.
 */
sealed interface OutputLanguage {
    val code: String?

    /** The user picked this language in settings. */
    data class UserSelected(override val code: String) : OutputLanguage
    /** The model itself identified the language (Whisper / SenseVoice auto mode). */
    data class ModelDetected(override val code: String) : OutputLanguage
    /** Detected from the text with high confidence; weakest accepted evidence. */
    data class TextDetected(override val code: String) : OutputLanguage
    data object TranslatedToEnglish : OutputLanguage {
        override val code = "en"
    }
    data object Unknown : OutputLanguage {
        override val code: String? = null
    }
}

/**
 * Transcript cleanup applied before insertion, ported 1:1 from desktop Handy's
 * `audio_toolkit/text.rs`: custom-word correction (Levenshtein + Soundex, n-grams up to three
 * words), filler-word removal (universal + language-gated lists), stutter collapsing and
 * whitespace normalization.
 */
object TextCleanup {

    /** Desktop default for `word_correction_threshold`. */
    const val DEFAULT_WORD_CORRECTION_THRESHOLD = 0.18

    // --- Custom words -------------------------------------------------------------------

    private class MatchKey(val wordIndex: Int, val key: String)

    private fun buildMatchKey(word: String): String = buildString {
        word.forEach { if (it.isLetterOrDigit()) append(it.lowercase()) }
    }

    private fun buildNgram(words: List<String>): String = words.joinToString("") { buildMatchKey(it) }

    private fun isSupportedFuzzyKey(key: String) =
        key.isNotEmpty() && key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    private fun supportsSoundex(key: String) =
        key.isNotEmpty() && key.all { it in 'a'..'z' || it in 'A'..'Z' }

    private fun buildCustomWordMatchKeys(word: String, index: Int): List<MatchKey> {
        val primary = buildMatchKey(word)
        val keys = mutableListOf<MatchKey>()
        // ASCII-only fallback matcher, as on desktop: Soundex and whitespace tokenization are
        // not suitable for CJK scripts.
        if (isSupportedFuzzyKey(primary)) keys += MatchKey(index, primary)
        if ('&' in word) {
            val expanded = buildMatchKey(word.replace("&", " and "))
            if (isSupportedFuzzyKey(expanded) && expanded != primary) keys += MatchKey(index, expanded)
        }
        return keys
    }

    private fun findBestMatch(
        candidate: String,
        customWords: List<String>,
        keys: List<MatchKey>,
        threshold: Double,
    ): Pair<String, Double>? {
        if (!isSupportedFuzzyKey(candidate) || candidate.length > 50) return null
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        for (k in keys) {
            val candidateLen = candidate.length
            val keyLen = k.key.length
            val lenDiff = abs(candidateLen - keyLen).toDouble()
            val maxLen = max(candidateLen, keyLen).toDouble()
            // At most 25% length difference (at least 2 chars), so n-grams don't match
            // much shorter custom words.
            if (lenDiff > max(maxLen * 0.25, 2.0)) continue

            val levenshteinScore = if (maxLen > 0) levenshtein(candidate, k.key) / maxLen else 1.0
            val phonetic = supportsSoundex(candidate) && supportsSoundex(k.key) &&
                soundex(candidate) == soundex(k.key)
            val combined = if (phonetic) levenshteinScore * 0.3 else levenshteinScore
            if (combined < threshold && combined < bestScore) {
                best = customWords[k.wordIndex]
                bestScore = combined
            }
        }
        return best?.let { it to bestScore }
    }

    fun applyCustomWords(text: String, customWords: List<String>, threshold: Double): String {
        if (customWords.isEmpty()) return text
        val keys = customWords.flatMapIndexed { i, w -> buildCustomWordMatchKeys(w, i) }
        val words = splitWhitespace(text)
        val result = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            var best: Triple<Int, String, Double>? = null
            // Consider n-grams up to three words and keep the closest match.
            for (n in 3 downTo 1) {
                if (i + n > words.size) continue
                val ngramWords = words.subList(i, i + n)
                // Never consume across punctuation ("Charge B, che" stops at "B,").
                if (ngramWords.dropLast(1).any { extractPunctuation(it).second.isNotEmpty() }) continue
                val match = findBestMatch(buildNgram(ngramWords), customWords, keys, threshold) ?: continue
                if (best == null || match.second < best.third) best = Triple(n, match.first, match.second)
            }
            if (best != null) {
                val (n, replacement, _) = best
                val prefix = extractPunctuation(words[i]).first
                val suffix = extractPunctuation(words[i + n - 1]).second
                result += prefix + preserveCasePattern(words[i], replacement) + suffix
                i += n
            } else {
                result += words[i]
                i++
            }
        }
        return result.joinToString(" ")
    }

    internal fun preserveCasePattern(original: String, replacement: String): String = when {
        original.all { it.isUpperCase() } -> replacement.uppercase()
        original.firstOrNull()?.isUpperCase() == true ->
            replacement.replaceFirstChar { it.uppercase() }
        else -> replacement
    }

    /** Leading and trailing non-alphanumeric characters of a word. */
    internal fun extractPunctuation(word: String): Pair<String, String> {
        val prefixEnd = word.indexOfFirst { it.isLetterOrDigit() }.let { if (it < 0) word.length else it }
        val suffixStart = word.indexOfLast { it.isLetterOrDigit() }.let { if (it < 0) 0 else it + 1 }
        val prefix = if (prefixEnd > 0) word.substring(0, prefixEnd) else ""
        val suffix = if (suffixStart < word.length) word.substring(suffixStart) else ""
        return prefix to suffix
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /** American Soundex (letter + 3 digits), as used by the `natural` crate on desktop. */
    internal fun soundex(word: String): String {
        val upper = word.uppercase().filter { it in 'A'..'Z' }
        if (upper.isEmpty()) return ""
        fun code(c: Char): Char = when (c) {
            'B', 'F', 'P', 'V' -> '1'
            'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2'
            'D', 'T' -> '3'
            'L' -> '4'
            'M', 'N' -> '5'
            'R' -> '6'
            else -> '0' // vowels, H, W, Y
        }
        val out = StringBuilder().append(upper[0])
        var last = code(upper[0])
        for (c in upper.drop(1)) {
            val d = code(c)
            if (d != '0' && d != last) out.append(d)
            // H and W don't separate letters with the same code; vowels do.
            if (c != 'H' && c != 'W') last = d
            if (out.length == 4) break
        }
        while (out.length < 4) out.append('0')
        return out.toString()
    }

    // --- Filler words -------------------------------------------------------------------

    /** Not lexical words in any language the models output: always safe to remove. */
    val UNIVERSAL_FILLER_WORDS = listOf(
        "uh", "uhm", "umm", "uhh", "uhhh", "ehh", "ehm", "ahm", "hmm", "hm", "mmm", "хм", "ммм",
    )

    /** Only removed with evidence of the output language ("um" is a word in pt/de). */
    fun gatedFillerWords(language: String): List<String> =
        when (language.split('-', '_').first()) {
            "en" -> listOf("um", "ah", "eh")
            "de" -> listOf("äh", "ähm")
            "fr" -> listOf("euh")
            else -> emptyList()
        }

    private fun fillerPattern(word: String) = Regex(
        "(?<![\\p{L}\\p{N}_])" + Regex.escape(word) + "(?![\\p{L}\\p{N}_])[,.]?",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @param customFillerWords null = built-in lists; empty list = removal disabled; otherwise
     *   replaces the built-in lists, without needing language evidence.
     */
    fun removeFillerWords(
        text: String,
        language: OutputLanguage,
        customFillerWords: List<String>?,
        enabled: Boolean,
    ): String {
        if (!enabled) return text
        val words = customFillerWords
            ?: (UNIVERSAL_FILLER_WORDS + (language.code?.let(::gatedFillerWords) ?: emptyList()))
        var filtered = text
        for (word in words.filter { it.isNotBlank() }) {
            filtered = removeFillerMatches(filtered, fillerPattern(word.trim()))
        }
        return filtered
    }

    /** A capitalized filler opening a sentence hands its capital to the next word. */
    private fun removeFillerMatches(text: String, pattern: Regex): String {
        val kept = StringBuilder()
        var resume = 0
        var capitalOwed = false
        for (m in pattern.findAll(text)) {
            capitalOwed = pushRestoringCapital(kept, text.substring(resume, m.range.first), capitalOwed)
            val capitalized = m.value.firstOrNull()?.isUpperCase() == true
            capitalOwed = capitalOwed || (capitalized && opensSentence(kept))
            resume = m.range.last + 1
        }
        pushRestoringCapital(kept, text.substring(resume), capitalOwed)
        return kept.toString()
    }

    private fun opensSentence(kept: CharSequence): Boolean {
        val last = kept.trimEnd().lastOrNull() ?: return true
        return last == '.' || last == '!' || last == '?' || last == '…'
    }

    /** @return whether a capital is still owed after appending [segment]. */
    private fun pushRestoringCapital(kept: StringBuilder, segment: String, capitalOwed: Boolean): Boolean {
        if (capitalOwed) {
            val index = segment.indexOfFirst { it.isLetterOrDigit() }
            if (index >= 0) {
                kept.append(segment, 0, index)
                kept.append(segment[index].uppercase())
                kept.append(segment, index + 1, segment.length)
                return false
            }
        }
        kept.append(segment)
        return capitalOwed
    }

    // --- Normalization ------------------------------------------------------------------

    /** Collapses 3+ consecutive repetitions of a word ("I I I I" -> "I"). */
    internal fun collapseStutters(text: String): String {
        val words = splitWhitespace(text)
        if (words.isEmpty()) return text
        val result = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val word = words[i]
            val lower = word.lowercase()
            if (lower.all { it.isLetter() }) {
                var count = 1
                while (i + count < words.size && words[i + count].lowercase() == lower) count++
                result += word
                i += if (count >= 3) count else 1
            } else {
                result += word
                i++
            }
        }
        return result.joinToString(" ")
    }

    private val MULTI_SPACE = Regex("\\s{2,}")

    fun normalize(text: String): String = MULTI_SPACE.replace(collapseStutters(text), " ").trim()

    // --- Whole pipeline -----------------------------------------------------------------

    data class Options(
        val customWords: List<String> = emptyList(),
        val wordCorrectionThreshold: Double = DEFAULT_WORD_CORRECTION_THRESHOLD,
        val fillerRemovalEnabled: Boolean = true,
        val customFillerWords: List<String>? = null,
    )

    /**
     * Desktop order: custom words -> filler removal -> normalization. When the language is
     * unknown and it matters (built-in gated fillers), fall back to detecting it from the text.
     */
    fun process(raw: String, language: OutputLanguage, options: Options): String {
        val evidence = if (language == OutputLanguage.Unknown &&
            options.fillerRemovalEnabled && options.customFillerWords == null
        ) {
            TextLanguageDetector.detect(raw)?.let { OutputLanguage.TextDetected(it) } ?: language
        } else {
            language
        }
        val corrected = if (options.customWords.isNotEmpty()) {
            applyCustomWords(raw, options.customWords, options.wordCorrectionThreshold)
        } else {
            raw
        }
        val withoutFillers = removeFillerWords(
            corrected,
            evidence,
            options.customFillerWords,
            options.fillerRemovalEnabled,
        )
        return normalize(withoutFillers)
    }

    private fun splitWhitespace(text: String): List<String> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }
}

/**
 * Last-resort language identification from the transcript, used only to unlock the
 * language-gated filler words. Desktop uses `whatlang`; this is a much smaller stopword
 * vote over the languages that matter here, with a confidence gate that fails closed.
 */
object TextLanguageDetector {

    private val STOPWORDS: Map<String, Set<String>> = mapOf(
        "en" to setOf("the", "and", "is", "are", "was", "to", "of", "that", "this", "it", "you", "i", "with", "for", "have", "not", "be", "what", "so", "we"),
        "fr" to setOf("le", "la", "les", "et", "est", "un", "une", "des", "du", "que", "qui", "pas", "je", "tu", "nous", "vous", "ce", "ça", "dans", "pour", "avec", "sur", "mais", "suis", "c'est"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ich", "du", "wir", "ein", "eine", "zu", "mit", "auf", "für", "es", "sie", "den", "dem", "auch"),
        "es" to setOf("el", "los", "las", "y", "es", "una", "que", "de", "no", "en", "por", "con", "para", "pero", "como", "yo", "está", "muy", "lo", "su"),
        "pt" to setOf("o", "os", "as", "e", "é", "um", "uma", "que", "de", "não", "em", "por", "com", "para", "mas", "eu", "você", "isso", "do", "da"),
        "it" to setOf("il", "lo", "gli", "e", "è", "un", "una", "che", "di", "non", "in", "per", "con", "ma", "io", "sono", "questo", "del", "della", "anche"),
    )

    /** ISO 639-1 code, or null when the evidence is too weak or ambiguous. */
    fun detect(text: String): String? {
        val tokens = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (tokens.size < 3) return null
        val scores = STOPWORDS.mapValues { (_, words) -> tokens.count { it in words } }
        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked[0]
        val second = ranked[1].value
        if (top.value < 2) return null
        if (top.value < second * 2) return null
        return top.key
    }
}
