package computer.handy.android

import android.app.Application
import computer.handy.android.history.HistoryStore
import computer.handy.android.settings.HandyPrefs
import computer.handy.android.settings.SecretStore
import computer.handy.android.transcription.ClaudePostProcessor
import computer.handy.android.transcription.DictationPipeline
import computer.handy.android.transcription.ModelCatalog
import computer.handy.android.transcription.ModelManager
import computer.handy.android.transcription.ModelState
import computer.handy.android.transcription.PostProcessor
import computer.handy.android.transcription.SherpaTranscriber
import computer.handy.android.transcription.SpeechModel
import computer.handy.android.transcription.TranscriptionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Process-wide singletons shared by the settings UI and the accessibility service (same
 * process): model downloads survive leaving the settings screen, and the loaded model is
 * shared rather than loaded twice.
 */
class HandyApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs by lazy { HandyPrefs(this) }
    val secrets by lazy { SecretStore(this) }
    val models by lazy { ModelManager(this, appScope) }
    val history by lazy { HistoryStore(this) }
    val transcriber by lazy { SherpaTranscriber(models, ::transcriptionSettings) }

    override fun onCreate() {
        super.onCreate()
        // After a download, use the new model if the selected one isn't installed (first run,
        // or the selected model was deleted).
        appScope.launch {
            models.states.collect { states ->
                if (states[prefs.selectedModelId] != ModelState.Ready) {
                    val ready = ModelCatalog.ALL.firstOrNull { states[it.id] == ModelState.Ready }
                    if (ready != null) prefs.selectedModelId = ready.id
                }
            }
        }
    }

    private var unloadJob: Job? = null

    /** Number of dictations in progress (floating button and keyboard share the model). */
    @Volatile
    var activeDictations = 0

    /** A dictation is starting: keep the model loaded. */
    fun cancelModelUnload() {
        unloadJob?.cancel()
    }

    /** Desktop `model_unload_timeout`: free the model's memory after a while without dictation. */
    fun scheduleModelUnload() {
        unloadJob?.cancel()
        val timeout = prefs.modelUnloadTimeout.millis ?: return
        unloadJob = appScope.launch {
            delay(timeout)
            if (activeDictations == 0) transcriber.release()
        }
    }

    /** Memory pressure: drop the model now unless a dictation needs it. */
    fun releaseModelIfIdle() {
        if (activeDictations > 0) return
        unloadJob?.cancel()
        appScope.launch { transcriber.release() }
    }

    fun selectedModel(): SpeechModel = ModelCatalog.byId(prefs.selectedModelId) ?: ModelCatalog.DEFAULT

    fun transcriptionSettings() = TranscriptionSettings(
        model = selectedModel(),
        language = prefs.selectedLanguage,
        translateToEnglish = prefs.translateToEnglish,
        deviceLanguage = Locale.getDefault().language,
    )

    /** Built per dictation so settings changes apply immediately. */
    fun pipeline(withPostProcess: Boolean): DictationPipeline =
        DictationPipeline(transcriber, prefs.cleanupOptions(), if (withPostProcess) postProcessor() else null)

    /** Null when post-processing is off or not configured (desktop skips it the same way). */
    fun postProcessor(): PostProcessor? {
        if (!prefs.postProcessEnabled) return null
        val key = secrets.apiKey
        val prompt = prefs.selectedPrompt().prompt
        if (key.isEmpty() || prompt.isBlank()) return null
        return ClaudePostProcessor(key, prefs.claudeModel, prompt)
    }
}
