package computer.handy.android.settings

import android.content.Context
import android.content.SharedPreferences
import computer.handy.android.core.PostProcessPrompt
import computer.handy.android.transcription.ClaudePostProcessor

/** User settings, shared by the settings UI and the accessibility service (same process). */
class HandyPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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

    /** Silence after speech that ends a recording automatically. */
    var silenceTimeoutMs: Long
        get() = prefs.getLong(KEY_SILENCE, DEFAULT_SILENCE_MS)
        set(value) = prefs.edit { putLong(KEY_SILENCE, value.coerceIn(MIN_SILENCE_MS, MAX_SILENCE_MS)) }

    /** Rewrite transcripts with Claude (needs an API key, see [SecretStore]). */
    var postProcessEnabled: Boolean
        get() = prefs.getBoolean(KEY_POST_PROCESS, false)
        set(value) = prefs.edit { putBoolean(KEY_POST_PROCESS, value) }

    var claudeModel: String
        get() = prefs.getString(KEY_CLAUDE_MODEL, null) ?: ClaudePostProcessor.DEFAULT_MODEL
        set(value) = prefs.edit { putString(KEY_CLAUDE_MODEL, value.trim()) }

    /** `${output}` is replaced by the transcript, as on desktop. */
    var postProcessPrompt: String
        get() = prefs.getString(KEY_PROMPT, null) ?: PostProcessPrompt.DEFAULT
        set(value) = prefs.edit { putString(KEY_PROMPT, value) }

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

    private inline fun SharedPreferences.edit(block: SharedPreferences.Editor.() -> Unit) =
        edit().apply(block).apply()

    companion object {
        private const val FILE = "handy_settings"
        const val KEY_EXCLUDED = "excluded_packages"
        private const val KEY_SEEDED = "excluded_defaults_seeded"
        const val KEY_OPACITY = "button_opacity"
        const val KEY_SIZE = "button_size_dp"
        const val KEY_SILENCE = "silence_timeout_ms"
        private const val KEY_OFFSET_PREFIX = "offset_"
        const val KEY_POST_PROCESS = "post_process_enabled"
        private const val KEY_CLAUDE_MODEL = "claude_model"
        private const val KEY_PROMPT = "post_process_prompt"

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
