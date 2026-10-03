package computer.handy.android.transcription

/** How sherpa-onnx must be configured for a model family. */
enum class Engine { PARAKEET, WHISPER, MOONSHINE_V2, SENSE_VOICE, CANARY }

/**
 * A downloadable speech model. Archives come from the sherpa-onnx `asr-models` release; only
 * [files] are extracted (Whisper archives also ship fp32 weights we don't need).
 *
 * Names, descriptions and languages mirror desktop Handy's built-in models
 * (src-tauri/src/managers/model.rs) for the ones that run on a phone.
 */
data class SpeechModel(
    val id: String,
    val name: String,
    val description: String,
    val engine: Engine,
    val archive: String,
    val downloadBytes: Long,
    /** Role -> file name inside the archive. */
    val files: Map<String, String>,
    /** ISO 639-1 codes; a single entry means the model is constrained to that language. */
    val languages: List<String>,
    /** The model can detect the spoken language by itself ("auto"). */
    val autoLanguage: Boolean,
    val supportsTranslation: Boolean,
    /** Rough size of the extracted files. */
    val installedMb: Int,
) {
    val url: String get() = "$RELEASE_BASE/$archive.tar.bz2"

    fun file(role: String): String = files.getValue(role)

    companion object {
        const val RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    }
}

object ModelCatalog {

    const val ENCODER = "encoder"
    const val DECODER = "decoder"
    const val JOINER = "joiner"
    const val TOKENS = "tokens"
    const val MODEL = "model"

    /** desktop: parakeet_v3_languages */
    val PARAKEET_V3_LANGUAGES = listOf(
        "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it", "lv",
        "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
    )

    /** desktop: whisper_languages (from the Whisper tokenizer) */
    val WHISPER_LANGUAGES = listOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar",
        "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu",
        "ta", "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa",
        "lv", "bn", "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn",
        "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
        "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn",
        "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw",
        "su", "yue",
    )

    private fun whisper(size: String, id: String, name: String, description: String, bytes: Long, mb: Int, translate: Boolean) =
        SpeechModel(
            id = id,
            name = name,
            description = description,
            engine = Engine.WHISPER,
            archive = "sherpa-onnx-whisper-$size",
            downloadBytes = bytes,
            files = mapOf(
                ENCODER to "$size-encoder.int8.onnx",
                DECODER to "$size-decoder.int8.onnx",
                TOKENS to "$size-tokens.txt",
            ),
            languages = WHISPER_LANGUAGES,
            autoLanguage = true,
            supportsTranslation = translate,
            installedMb = mb,
        )

    val PARAKEET_V3 = SpeechModel(
        id = "parakeet-tdt-0.6b-v3-int8",
        name = "Parakeet V3",
        description = "Fast and accurate. Supports 25 European languages.",
        engine = Engine.PARAKEET,
        archive = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        downloadBytes = 487_170_055L,
        files = mapOf(
            ENCODER to "encoder.int8.onnx",
            DECODER to "decoder.int8.onnx",
            JOINER to "joiner.int8.onnx",
            TOKENS to "tokens.txt",
        ),
        languages = PARAKEET_V3_LANGUAGES,
        autoLanguage = true,
        supportsTranslation = false,
        installedMb = 670,
    )

    val ALL: List<SpeechModel> = listOf(
        PARAKEET_V3,
        SpeechModel(
            id = "parakeet-tdt-0.6b-v2-int8",
            name = "Parakeet V2",
            description = "English only. The best model for English speakers.",
            engine = Engine.PARAKEET,
            archive = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
            downloadBytes = 482_000_000L,
            files = mapOf(
                ENCODER to "encoder.int8.onnx",
                DECODER to "decoder.int8.onnx",
                JOINER to "joiner.int8.onnx",
                TOKENS to "tokens.txt",
            ),
            languages = listOf("en"),
            autoLanguage = false,
            supportsTranslation = false,
            installedMb = 660,
        ),
        whisper("turbo", "whisper-turbo", "Whisper Turbo", "Balanced accuracy and speed. 99 languages.", 563_000_000L, 1_000, translate = false),
        whisper("small", "whisper-small", "Whisper Small", "Fast and fairly accurate. 99 languages, translation to English.", 639_000_000L, 375, translate = true),
        whisper("base", "whisper-base", "Whisper Base", "Very small and fast, less accurate. 99 languages, translation to English.", 207_000_000L, 160, translate = true),
        SpeechModel(
            id = "moonshine-base-en",
            name = "Moonshine Base",
            description = "Very fast, English only. Handles accents well.",
            engine = Engine.MOONSHINE_V2,
            archive = "sherpa-onnx-moonshine-base-en-quantized-2026-02-27",
            downloadBytes = 111_000_000L,
            files = mapOf(
                ENCODER to "encoder_model.ort",
                DECODER to "decoder_model_merged.ort",
                TOKENS to "tokens.txt",
            ),
            languages = listOf("en"),
            autoLanguage = false,
            supportsTranslation = false,
            installedMb = 140,
        ),
        SpeechModel(
            id = "sense-voice-int8",
            name = "SenseVoice",
            description = "Very fast. Chinese, English, Japanese, Korean, Cantonese.",
            engine = Engine.SENSE_VOICE,
            archive = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
            downloadBytes = 163_000_000L,
            files = mapOf(MODEL to "model.int8.onnx", TOKENS to "tokens.txt"),
            languages = listOf("zh", "en", "ja", "ko", "yue"),
            autoLanguage = true,
            supportsTranslation = false,
            installedMb = 240,
        ),
        SpeechModel(
            id = "canary-180m-flash",
            name = "Canary 180M Flash",
            description = "English, German, Spanish, French. Supports translation. Needs the spoken language to be set.",
            engine = Engine.CANARY,
            archive = "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8",
            downloadBytes = 153_000_000L,
            files = mapOf(
                ENCODER to "encoder.int8.onnx",
                DECODER to "decoder.int8.onnx",
                TOKENS to "tokens.txt",
            ),
            languages = listOf("en", "de", "es", "fr"),
            autoLanguage = false,
            supportsTranslation = true,
            installedMb = 210,
        ),
    )

    val DEFAULT: SpeechModel = PARAKEET_V3

    fun byId(id: String?): SpeechModel? = ALL.firstOrNull { it.id == id }

    /**
     * Language the model is told to decode, or "" for its own detection. Mirrors desktop's
     * rule: a selected language is only used if the model supports it.
     */
    fun decodeLanguage(model: SpeechModel, selected: String, fallback: String): String {
        val requested = selected.takeIf { it != AUTO && it in model.languages }
        return when {
            requested != null -> requested
            model.languages.size == 1 -> model.languages.first()
            model.autoLanguage -> ""
            // Canary has no language identification: use the phone's language if supported.
            fallback in model.languages -> fallback
            else -> model.languages.first()
        }
    }

    const val AUTO = "auto"
}
