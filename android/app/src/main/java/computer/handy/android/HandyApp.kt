package computer.handy.android

import android.app.Application
import computer.handy.android.settings.HandyPrefs
import computer.handy.android.settings.SecretStore
import computer.handy.android.transcription.ClaudePostProcessor
import computer.handy.android.transcription.DictationPipeline
import computer.handy.android.transcription.ModelManager
import computer.handy.android.transcription.NoOpPostProcessor
import computer.handy.android.transcription.PostProcessor
import computer.handy.android.transcription.SherpaTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons shared by the settings UI and the accessibility service (same
 * process): the model download survives leaving the settings screen, and the loaded model is
 * shared rather than loaded twice.
 */
class HandyApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs by lazy { HandyPrefs(this) }
    val secrets by lazy { SecretStore(this) }
    val models by lazy { ModelManager(this, appScope) }
    val transcriber by lazy { SherpaTranscriber(models) }

    /** Built per dictation so settings changes apply immediately. */
    fun pipeline(): DictationPipeline = DictationPipeline(transcriber, postProcessor())

    fun postProcessor(): PostProcessor {
        if (!prefs.postProcessEnabled) return NoOpPostProcessor
        val key = secrets.apiKey
        if (key.isEmpty()) return NoOpPostProcessor
        return ClaudePostProcessor(key, prefs.claudeModel, prefs.postProcessPrompt)
    }
}
