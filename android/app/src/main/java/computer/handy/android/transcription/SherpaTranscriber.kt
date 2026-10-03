package computer.handy.android.transcription

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device transcription with sherpa-onnx (ONNX Runtime) and Parakeet TDT 0.6B v3: the same
 * model as desktop Handy's "Parakeet V3", 25 European languages with automatic detection,
 * punctuation and casing included.
 *
 * The model (~650 MB in RAM) is loaded lazily, ideally while the user is still speaking
 * ([preload]), and released by [release] after a period of inactivity.
 */
class SherpaTranscriber(private val models: ModelManager) : Transcriber {

    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null

    val isLoaded: Boolean get() = recognizer != null

    val isModelInstalled: Boolean get() = models.installedDir() != null

    /** Loads the model in the background if it is installed and not loaded yet. */
    suspend fun preload() {
        if (models.installedDir() == null) return
        withContext(Dispatchers.Default) { mutex.withLock { ensureLoaded() } }
    }

    override suspend fun transcribe(pcm: ShortArray, sampleRate: Int): String =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val r = ensureLoaded()
                val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
                val stream = r.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    r.decode(stream)
                    r.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        }

    /** Frees the native model memory; the next dictation reloads it. */
    suspend fun release() {
        mutex.withLock {
            recognizer?.release()
            recognizer = null
        }
    }

    private fun ensureLoaded(): OfflineRecognizer {
        recognizer?.let { return it }
        val dir = models.installedDir() ?: throw ModelMissingException()
        val started = System.currentTimeMillis()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, ParakeetModel.ENCODER).path,
                    decoder = File(dir, ParakeetModel.DECODER).path,
                    joiner = File(dir, ParakeetModel.JOINER).path,
                ),
                tokens = File(dir, ParakeetModel.TOKENS).path,
                modelType = "nemo_transducer",
                numThreads = threadCount(),
                provider = "cpu",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(config = config).also {
            recognizer = it
            Log.i(TAG, "Parakeet loaded in ${System.currentTimeMillis() - started} ms")
        }
    }

    /** Big cores only, roughly: more threads than that slows ONNX Runtime down on phones. */
    private fun threadCount(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    companion object {
        private const val TAG = "HandySherpa"
    }
}
