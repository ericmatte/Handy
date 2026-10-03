package computer.handy.android.transcription

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The on-device speech model: Parakeet TDT 0.6B v3 (int8), as packaged for sherpa-onnx. */
object ParakeetModel {
    const val ID = "parakeet-tdt-0.6b-v3-int8"
    const val URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    val FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)

    /** Archive size, for progress before the server reports it. */
    const val DOWNLOAD_BYTES = 487_170_055L
    /** Archive + extracted files must fit at the same time. */
    const val REQUIRED_FREE_BYTES = 1_300_000_000L
}

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val downloaded: Long, val total: Long) : ModelState
    data object Extracting : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads and installs the speech model into app-private storage. The archive is resumable
 * (HTTP Range) so a killed download continues where it stopped. Only the four files sherpa-onnx
 * needs are extracted; the archive is deleted afterwards.
 */
class ModelManager(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, "models/${ParakeetModel.ID}")
    private val completeMarker = File(modelDir, ".complete")
    private val archive = File(appContext.cacheDir, "${ParakeetModel.ID}.tar.bz2.part")

    private val _state = MutableStateFlow(if (isInstalled()) ModelState.Ready else ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private var job: Job? = null

    /** Directory with the model files, or null when not installed. */
    fun installedDir(): File? = if (isInstalled()) modelDir else null

    private fun isInstalled(): Boolean =
        completeMarker.exists() && ParakeetModel.FILES.all { File(modelDir, it).length() > 0 }

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val free = appContext.filesDir.usableSpace
                if (free < ParakeetModel.REQUIRED_FREE_BYTES - archive.length()) {
                    _state.value = ModelState.Failed("Not enough storage: about 1.3 GB free space is needed.")
                    return@launch
                }
                fetchArchive()
                _state.value = ModelState.Extracting
                extract()
                archive.delete()
                _state.value = ModelState.Ready
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = if (isInstalled()) ModelState.Ready else ModelState.Missing
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Model download failed", e)
                _state.value = ModelState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete() {
        cancel()
        archive.delete()
        modelDir.deleteRecursively()
        _state.value = ModelState.Missing
    }

    private suspend fun fetchArchive() {
        var url = URL(ParakeetModel.URL)
        var redirects = 0
        while (true) {
            val existing = archive.length()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        // GitHub redirects to its asset CDN; follow by hand to keep the Range header.
                        val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                        url = URL(url, location)
                        if (++redirects > 5) throw IOException("Too many redirects")
                        continue
                    }
                    416 -> return // Range not satisfiable: the archive is already complete.
                    200, 206 -> {
                        val append = code == 206
                        val start = if (append) existing else 0L
                        val length = conn.contentLengthLong
                        val total = if (length > 0) start + length else ParakeetModel.DOWNLOAD_BYTES
                        copyWithProgress(conn, append, start, total)
                        return
                    }
                    else -> throw IOException("Download failed: HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun copyWithProgress(conn: HttpURLConnection, append: Boolean, start: Long, total: Long) {
        val buffer = ByteArray(256 * 1024)
        var done = start
        var lastReport = 0L
        _state.value = ModelState.Downloading(done, total)
        conn.inputStream.use { input ->
            FileOutputStream(archive, append).use { output ->
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (done - lastReport > 2_000_000) {
                        lastReport = done
                        _state.value = ModelState.Downloading(done, total)
                    }
                }
            }
        }
        if (done < total) throw IOException("Download interrupted (${done / 1_000_000} / ${total / 1_000_000} MB)")
    }

    private suspend fun extract() {
        val staging = File(modelDir.parentFile, "${ParakeetModel.ID}.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val wanted = ParakeetModel.FILES.toSet()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 20))).use { tar ->
            while (true) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in wanted) continue
                FileOutputStream(File(staging, name)).use { tar.copyTo(it, 1 shl 20) }
            }
        }
        val missing = wanted.filter { File(staging, it).length() == 0L }
        if (missing.isNotEmpty()) throw IOException("Archive is missing $missing")
        modelDir.deleteRecursively()
        if (!staging.renameTo(modelDir)) throw IOException("Could not install the model")
        completeMarker.writeText(ParakeetModel.ID)
    }

    companion object {
        private const val TAG = "HandyModels"
    }
}
