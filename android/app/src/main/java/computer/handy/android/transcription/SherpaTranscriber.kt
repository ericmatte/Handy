package computer.handy.android.transcription

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import computer.handy.android.core.OutputLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Which model to run and how; a change reloads the recognizer. */
data class TranscriptionSettings(
    val model: SpeechModel,
    /** ISO code or [ModelCatalog.AUTO]. */
    val language: String,
    val translateToEnglish: Boolean,
    /** Used when a model cannot detect the language and none is selected. */
    val deviceLanguage: String,
)

/**
 * On-device transcription with sherpa-onnx (ONNX Runtime, CPU) for every model in
 * [ModelCatalog]. Configurations were checked against each model's sample audio with
 * sherpa-onnx 1.13.8 (see android/README.md).
 *
 * The recognizer is loaded lazily, ideally while the user is still speaking ([preload]), and
 * released by [release] after the configured idle time.
 */
class SherpaTranscriber(
    private val models: ModelManager,
    private val settings: () -> TranscriptionSettings,
) : Transcriber {

    private data class LoadKey(val modelId: String, val language: String, val translate: Boolean)

    private class Loaded(val key: LoadKey, val recognizer: OfflineRecognizer)

    private val mutex = Mutex()
    private var loaded: Loaded? = null

    fun isModelInstalled(): Boolean = models.isInstalled(settings().model)

    /** Loads the selected model in the background if it is installed. */
    suspend fun preload() {
        val s = settings()
        if (!models.isInstalled(s.model)) return
        withContext(Dispatchers.Default) { mutex.withLock { ensureLoaded(s) } }
    }

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): Transcript =
        withContext(Dispatchers.Default) {
            val s = settings()
            mutex.withLock {
                val recognizer = ensureLoaded(s)
                val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    Transcript(result.text.trim(), outputLanguage(s, result.lang))
                } finally {
                    stream.release()
                }
            }
        }

    /** Frees the native model memory; the next dictation reloads it. */
    suspend fun release() {
        mutex.withLock {
            loaded?.recognizer?.release()
            loaded = null
        }
    }

    private fun decodeLanguage(s: TranscriptionSettings) =
        ModelCatalog.decodeLanguage(s.model, s.language, s.deviceLanguage)

    private fun translate(s: TranscriptionSettings) = s.translateToEnglish && s.model.supportsTranslation

    /** Desktop's language evidence rules, used to unlock language-gated filler words. */
    private fun outputLanguage(s: TranscriptionSettings, detected: String?): OutputLanguage {
        if (translate(s)) return OutputLanguage.TranslatedToEnglish
        val decode = decodeLanguage(s)
        if (decode.isNotEmpty()) return OutputLanguage.UserSelected(decode)
        val code = detected?.trim()?.removePrefix("<|")?.removeSuffix("|>")?.lowercase()
        return if (!code.isNullOrEmpty() && code in s.model.languages) {
            OutputLanguage.ModelDetected(code)
        } else {
            OutputLanguage.Unknown
        }
    }

    private fun ensureLoaded(s: TranscriptionSettings): OfflineRecognizer {
        val key = LoadKey(s.model.id, decodeLanguage(s), translate(s))
        loaded?.let {
            if (it.key == key) return it.recognizer
            it.recognizer.release()
            loaded = null
        }
        val dir = models.installedDir(s.model) ?: throw ModelMissingException()
        val started = System.currentTimeMillis()
        val recognizer = OfflineRecognizer(config = buildConfig(s.model, dir, key))
        loaded = Loaded(key, recognizer)
        Log.i(TAG, "${s.model.id} loaded in ${System.currentTimeMillis() - started} ms")
        return recognizer
    }

    private fun buildConfig(model: SpeechModel, dir: File, key: LoadKey): OfflineRecognizerConfig {
        fun path(role: String) = File(dir, model.file(role)).path
        val tokens = path(ModelCatalog.TOKENS)
        val threads = threadCount()
        val modelConfig = when (model.engine) {
            Engine.PARAKEET -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path(ModelCatalog.ENCODER),
                    decoder = path(ModelCatalog.DECODER),
                    joiner = path(ModelCatalog.JOINER),
                ),
                modelType = "nemo_transducer",
                tokens = tokens,
                numThreads = threads,
            )
            Engine.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path(ModelCatalog.ENCODER),
                    decoder = path(ModelCatalog.DECODER),
                    language = key.language,
                    task = if (key.translate) "translate" else "transcribe",
                ),
                tokens = tokens,
                numThreads = threads,
            )
            Engine.MOONSHINE_V2 -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    encoder = path(ModelCatalog.ENCODER),
                    mergedDecoder = path(ModelCatalog.DECODER),
                ),
                tokens = tokens,
                numThreads = threads,
            )
            Engine.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = path(ModelCatalog.MODEL),
                    language = key.language,
                    useInverseTextNormalization = true,
                ),
                tokens = tokens,
                numThreads = threads,
            )
            Engine.CANARY -> OfflineModelConfig(
                canary = OfflineCanaryModelConfig(
                    encoder = path(ModelCatalog.ENCODER),
                    decoder = path(ModelCatalog.DECODER),
                    srcLang = key.language,
                    tgtLang = if (key.translate) "en" else key.language,
                    usePnc = true,
                ),
                tokens = tokens,
                numThreads = threads,
            )
        }
        val featureDim = if (model.engine == Engine.CANARY) 128 else 80
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = featureDim),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
    }

    /** Roughly the big cores: more threads than that slows ONNX Runtime down on phones. */
    private fun threadCount(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    companion object {
        private const val TAG = "HandySherpa"
    }
}
