package computer.handy.android.core

/** A named post-processing prompt, as on desktop (`LLMPrompt`). */
data class LlmPrompt(val id: String, val name: String, val prompt: String) {
    companion object {
        /** Desktop's built-in prompt id and name. */
        val DEFAULT = LlmPrompt("default_improve_transcriptions", "Improve Transcriptions", PostProcessPrompt.DEFAULT)
    }
}

/** Desktop `ModelUnloadTimeout`: how long the model stays in memory after a dictation. */
enum class UnloadTimeout(val millis: Long?) {
    NEVER(null),
    IMMEDIATELY(0L),
    MIN2(2 * 60_000L),
    MIN5(5 * 60_000L),
    MIN10(10 * 60_000L),
    MIN15(15 * 60_000L),
    HOUR1(60 * 60_000L),
}

/** What a tap on the button does; the long-press menu offers the other one. */
enum class TapAction { TRANSCRIBE, TRANSCRIBE_WITH_POST_PROCESS }

/** Desktop sound themes: marimba_start.wav, pop_stop.wav etc. in src-tauri/resources. */
enum class SoundTheme { MARIMBA, POP }
