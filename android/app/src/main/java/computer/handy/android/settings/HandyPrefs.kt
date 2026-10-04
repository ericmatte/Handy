package computer.handy.android.settings

import android.content.Context
import android.content.SharedPreferences
import computer.handy.android.core.LlmPrompt
import computer.handy.android.core.SoundTheme
import computer.handy.android.core.TapAction
import computer.handy.android.core.TextCleanup
import computer.handy.android.core.UnloadTimeout
import computer.handy.android.transcription.ClaudePostProcessor
import computer.handy.android.transcription.ModelCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * User settings, shared by the settings UI and the accessibility service (same process).
 * Names and defaults follow desktop Handy's `AppSettings` where the setting exists there.
 */
class HandyPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Overlay ------------------------------------------------------------------------

    var excludedPackages: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_EXCLUDED, value.toSet()) }

    var defaultsSeeded: Boolean
        get() = prefs.getBoolean(KEY_SEEDED, false)
        set(value) = prefs.edit { putBoolean(KEY_SEEDED, value) }

    /** Resting opacity of the button, 0.3..1. It dims further after 3 s without interaction. */
    var buttonOpacity: Float
        get() = prefs.getFloat(KEY_OPACITY, DEFAULT_OPACITY)
        set(value) = prefs.edit { putFloat(KEY_OPACITY, value.coerceIn(MIN_OPACITY, 1f)) }

    var buttonSizeDp: Int
        get() = prefs.getInt(KEY_SIZE, DEFAULT_SIZE_DP)
        set(value) = prefs.edit { putInt(KEY_SIZE, value.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)) }

    /** The floating button over text fields (accessibility mode). Off by default: the voice
     *  keyboard is the main way in. */
    var floatingButtonEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_BUTTON, false)
        set(value) = prefs.edit { putBoolean(KEY_FLOATING_BUTTON, value) }

    /** Voice keyboard: start listening as soon as it opens. */
    var imeAutoStart: Boolean
        get() = prefs.getBoolean(KEY_IME_AUTO_START, true)
        set(value) = prefs.edit { putBoolean(KEY_IME_AUTO_START, value) }

    /** Voice keyboard: go back to the previous keyboard (Gboard) after inserting the text. */
    var imeReturnToPrevious: Boolean
        get() = prefs.getBoolean(KEY_IME_RETURN, true)
        set(value) = prefs.edit { putBoolean(KEY_IME_RETURN, value) }

    var tapAction: TapAction
        get() = enumOr(prefs.getString(KEY_TAP_ACTION, null), TapAction.TRANSCRIBE)
        set(value) = prefs.edit { putString(KEY_TAP_ACTION, value.name) }

    // --- Recording ----------------------------------------------------------------------

    /** Silence after speech that ends a recording automatically. */
    var silenceTimeoutMs: Long
        get() = prefs.getLong(KEY_SILENCE, DEFAULT_SILENCE_MS)
        set(value) = prefs.edit { putLong(KEY_SILENCE, value.coerceIn(MIN_SILENCE_MS, MAX_SILENCE_MS)) }

    /** desktop `vad_enabled` (default true): Silero VAD trims silence before transcription. */
    var vadEnabled: Boolean
        get() = prefs.getBoolean(KEY_VAD, true)
        set(value) = prefs.edit { putBoolean(KEY_VAD, value) }

    /** desktop `audio_feedback` (default false). */
    var audioFeedback: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_FEEDBACK, false)
        set(value) = prefs.edit { putBoolean(KEY_AUDIO_FEEDBACK, value) }

    var soundTheme: SoundTheme
        get() = enumOr(prefs.getString(KEY_SOUND_THEME, null), SoundTheme.MARIMBA)
        set(value) = prefs.edit { putString(KEY_SOUND_THEME, value.name) }

    /** desktop `audio_feedback_volume`, 0..1. */
    var audioFeedbackVolume: Float
        get() = prefs.getFloat(KEY_AUDIO_VOLUME, 1f)
        set(value) = prefs.edit { putFloat(KEY_AUDIO_VOLUME, value.coerceIn(0f, 1f)) }

    // --- Transcription ------------------------------------------------------------------

    var selectedModelId: String
        get() = prefs.getString(KEY_MODEL, null) ?: ModelCatalog.DEFAULT.id
        set(value) = prefs.edit { putString(KEY_MODEL, value) }

    /** desktop `selected_language`: "auto" or an ISO 639-1 code. */
    var selectedLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, null) ?: ModelCatalog.AUTO
        set(value) = prefs.edit { putString(KEY_LANGUAGE, value) }

    var translateToEnglish: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE, false)
        set(value) = prefs.edit { putBoolean(KEY_TRANSLATE, value) }

    var modelUnloadTimeout: UnloadTimeout
        get() = enumOr(prefs.getString(KEY_UNLOAD, null), UnloadTimeout.MIN5)
        set(value) = prefs.edit { putString(KEY_UNLOAD, value.name) }

    var customWords: List<String>
        get() = stringList(KEY_CUSTOM_WORDS) ?: emptyList()
        set(value) = putStringList(KEY_CUSTOM_WORDS, value.map { it.trim() }.filter { it.isNotEmpty() }.distinct())

    var wordCorrectionThreshold: Double
        get() = prefs.getFloat(KEY_WORD_THRESHOLD, TextCleanup.DEFAULT_WORD_CORRECTION_THRESHOLD.toFloat()).toDouble()
        set(value) = prefs.edit { putFloat(KEY_WORD_THRESHOLD, value.toFloat().coerceIn(0f, 1f)) }

    var fillerWordRemoval: Boolean
        get() = prefs.getBoolean(KEY_FILLER, true)
        set(value) = prefs.edit { putBoolean(KEY_FILLER, value) }

    /** null = built-in lists (desktop `custom_filler_words: None`). */
    var customFillerWords: List<String>?
        get() = stringList(KEY_CUSTOM_FILLERS)
        set(value) = if (value == null) {
            prefs.edit { remove(KEY_CUSTOM_FILLERS) }
        } else {
            putStringList(KEY_CUSTOM_FILLERS, value.map { it.trim() }.filter { it.isNotEmpty() })
        }

    fun cleanupOptions() = TextCleanup.Options(
        customWords = customWords,
        wordCorrectionThreshold = wordCorrectionThreshold,
        fillerRemovalEnabled = fillerWordRemoval,
        customFillerWords = customFillerWords,
    )

    // --- Output -------------------------------------------------------------------------

    var appendTrailingSpace: Boolean
        get() = prefs.getBoolean(KEY_TRAILING_SPACE, false)
        set(value) = prefs.edit { putBoolean(KEY_TRAILING_SPACE, value) }

    /** desktop `auto_submit`: press Enter (the field's IME action) after inserting. */
    var autoSubmit: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SUBMIT, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_SUBMIT, value) }

    /** desktop `history_limit` (default 5). */
    var historyLimit: Int
        get() = prefs.getInt(KEY_HISTORY_LIMIT, 5)
        set(value) = prefs.edit { putInt(KEY_HISTORY_LIMIT, value.coerceIn(0, 500)) }

    // --- Post-processing ----------------------------------------------------------------

    /** desktop `post_process_enabled`: makes the "with Claude" action available. */
    var postProcessEnabled: Boolean
        get() = prefs.getBoolean(KEY_POST_PROCESS, false)
        set(value) = prefs.edit { putBoolean(KEY_POST_PROCESS, value) }

    var claudeModel: String
        get() = prefs.getString(KEY_CLAUDE_MODEL, null) ?: ClaudePostProcessor.DEFAULT_MODEL
        set(value) = prefs.edit { putString(KEY_CLAUDE_MODEL, value.trim()) }

    var prompts: List<LlmPrompt>
        get() {
            val raw = prefs.getString(KEY_PROMPTS, null) ?: return listOf(migratedDefaultPrompt())
            return try {
                val array = JSONArray(raw)
                List(array.length()) { i ->
                    val o = array.getJSONObject(i)
                    LlmPrompt(o.getString("id"), o.getString("name"), o.getString("prompt"))
                }.ifEmpty { listOf(LlmPrompt.DEFAULT) }
            } catch (e: Exception) {
                listOf(LlmPrompt.DEFAULT)
            }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt)) }
            prefs.edit { putString(KEY_PROMPTS, array.toString()) }
        }

    var selectedPromptId: String
        get() = prefs.getString(KEY_SELECTED_PROMPT, null) ?: LlmPrompt.DEFAULT.id
        set(value) = prefs.edit { putString(KEY_SELECTED_PROMPT, value) }

    fun selectedPrompt(): LlmPrompt = prompts.let { list -> list.firstOrNull { it.id == selectedPromptId } ?: list.first() }

    /** The single-prompt setting of the previous version becomes the default prompt. */
    private fun migratedDefaultPrompt(): LlmPrompt {
        val legacy = prefs.getString(KEY_LEGACY_PROMPT, null) ?: return LlmPrompt.DEFAULT
        return LlmPrompt.DEFAULT.copy(prompt = legacy)
    }

    // --- Per-app button offsets ---------------------------------------------------------

    fun addExcluded(packageName: String) {
        excludedPackages = excludedPackages + packageName
    }

    fun removeExcluded(packageName: String) {
        excludedPackages = excludedPackages - packageName
    }

    /** Vertical drag offset of the button for one app, in dp (density-independent). */
    fun offsetDp(packageName: String): Int = prefs.getInt(KEY_OFFSET_PREFIX + packageName, 0)

    fun setOffsetDp(packageName: String, offsetDp: Int) =
        prefs.edit { putInt(KEY_OFFSET_PREFIX + packageName, offsetDp) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private fun stringList(key: String): List<String>? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun putStringList(key: String, value: List<String>) =
        prefs.edit { putString(key, JSONArray(value).toString()) }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

    private inline fun SharedPreferences.edit(block: SharedPreferences.Editor.() -> Unit) =
        edit().apply(block).apply()

    companion object {
        private const val FILE = "handy_settings"
        const val KEY_EXCLUDED = "excluded_packages"
        private const val KEY_SEEDED = "excluded_defaults_seeded"
        const val KEY_OPACITY = "button_opacity"
        const val KEY_SIZE = "button_size_dp"
        private const val KEY_TAP_ACTION = "tap_action"
        const val KEY_FLOATING_BUTTON = "floating_button_enabled"
        private const val KEY_IME_AUTO_START = "ime_auto_start"
        private const val KEY_IME_RETURN = "ime_return_to_previous"
        const val KEY_SILENCE = "silence_timeout_ms"
        private const val KEY_VAD = "vad_enabled"
        private const val KEY_AUDIO_FEEDBACK = "audio_feedback"
        private const val KEY_SOUND_THEME = "sound_theme"
        private const val KEY_AUDIO_VOLUME = "audio_feedback_volume"
        const val KEY_MODEL = "selected_model"
        const val KEY_LANGUAGE = "selected_language"
        const val KEY_TRANSLATE = "translate_to_english"
        const val KEY_UNLOAD = "model_unload_timeout"
        private const val KEY_CUSTOM_WORDS = "custom_words"
        private const val KEY_WORD_THRESHOLD = "word_correction_threshold"
        private const val KEY_FILLER = "filler_word_removal_enabled"
        private const val KEY_CUSTOM_FILLERS = "custom_filler_words"
        private const val KEY_TRAILING_SPACE = "append_trailing_space"
        private const val KEY_AUTO_SUBMIT = "auto_submit"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        private const val KEY_OFFSET_PREFIX = "offset_"
        const val KEY_POST_PROCESS = "post_process_enabled"
        private const val KEY_CLAUDE_MODEL = "claude_model"
        private const val KEY_PROMPTS = "post_process_prompts"
        private const val KEY_SELECTED_PROMPT = "post_process_selected_prompt_id"
        private const val KEY_LEGACY_PROMPT = "post_process_prompt"

        const val DEFAULT_OPACITY = 0.7f
        const val MIN_OPACITY = 0.3f
        const val DEFAULT_SIZE_DP = 40
        const val MIN_SIZE_DP = 32
        const val MAX_SIZE_DP = 52
        const val DEFAULT_SILENCE_MS = 1_500L
        const val MIN_SILENCE_MS = 500L
        const val MAX_SILENCE_MS = 5_000L
    }
}
