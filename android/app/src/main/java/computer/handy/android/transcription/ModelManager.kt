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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

sealed interface ModelState {
    data object Missing : ModelState
    data object Queued : ModelState
    data class Downloading(val downloaded: Long, val total: Long) : ModelState
    data object Extracting : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Downloads, installs and deletes speech models in app-private storage, one download at a
 * time. Archives are resumable (HTTP Range) so an interrupted download continues where it
 * stopped; only the files a model needs are extracted and the archive is deleted afterwards.
 */
class ModelManager(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "models")

    private val _states = MutableStateFlow(
        ModelCatalog.ALL.associate { it.id to (if (isInstalled(it)) ModelState.Ready else ModelState.Missing) },
    )
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    private val queue = ArrayDeque<SpeechModel>()
    private var current: SpeechModel? = null
    private var job: Job? = null

    fun dir(model: SpeechModel) = File(root, model.id)

    /** Directory with the model files, or null when not installed. */
    fun installedDir(model: SpeechModel): File? = if (isInstalled(model)) dir(model) else null

    fun isInstalled(model: SpeechModel): Boolean {
        val dir = dir(model)
        return File(dir, COMPLETE_MARKER).exists() && model.files.values.all { File(dir, it).length() > 0 }
    }

    fun installedModels(): List<SpeechModel> = ModelCatalog.ALL.filter(::isInstalled)

    private fun archive(model: SpeechModel) = File(appContext.cacheDir, "${model.id}.tar.bz2.part")

    private fun setState(model: SpeechModel, state: ModelState) =
        _states.update { it + (model.id to state) }

    @Synchronized
    fun download(model: SpeechModel) {
        if (isInstalled(model) || model == current || model in queue) return
        queue.addLast(model)
        setState(model, ModelState.Queued)
        if (job?.isActive != true) startNext()
    }

    @Synchronized
    private fun startNext() {
        val model = queue.removeFirstOrNull() ?: run {
            current = null
            return
        }
        current = model
        job = scope.launch(Dispatchers.IO) {
            try {
                val needed = model.downloadBytes + model.installedMb * 1_000_000L - archive(model).length()
                if (appContext.filesDir.usableSpace < needed + SPACE_MARGIN) {
                    setState(model, ModelState.Failed(NOT_ENOUGH_SPACE))
                    return@launch
                }
                fetchArchive(model)
                setState(model, ModelState.Extracting)
                extract(model)
                archive(model).delete()
                setState(model, ModelState.Ready)
            } catch (e: kotlinx.coroutines.CancellationException) {
                setState(model, if (isInstalled(model)) ModelState.Ready else ModelState.Missing)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download of ${model.id} failed", e)
                setState(model, ModelState.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                startNext()
            }
        }
    }

    @Synchronized
    fun cancel(model: SpeechModel) {
        if (queue.remove(model)) {
            setState(model, ModelState.Missing)
            return
        }
        if (model == current) job?.cancel()
    }

    fun delete(model: SpeechModel) {
        cancel(model)
        archive(model).delete()
        dir(model).deleteRecursively()
        setState(model, ModelState.Missing)
    }

    private suspend fun fetchArchive(model: SpeechModel) {
        val archive = archive(model)
        var url = URL(model.url)
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
                    }
                    416 -> return // Range not satisfiable: already complete.
                    200, 206 -> {
                        val append = code == 206
                        val start = if (append) existing else 0L
                        val length = conn.contentLengthLong
                        val total = if (length > 0) start + length else model.downloadBytes
                        copyWithProgress(model, conn, archive, append, start, total)
                        return
                    }
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun copyWithProgress(
        model: SpeechModel,
        conn: HttpURLConnection,
        archive: File,
        append: Boolean,
        start: Long,
        total: Long,
    ) {
        val buffer = ByteArray(256 * 1024)
        var done = start
        var lastReport = 0L
        setState(model, ModelState.Downloading(done, total))
        conn.inputStream.use { input ->
            FileOutputStream(archive, append).use { output ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    if (done - lastReport > 2_000_000) {
                        lastReport = done
                        setState(model, ModelState.Downloading(done, total))
                    }
                }
            }
        }
        if (done < total) throw IOException("Download interrupted (${done / 1_000_000} / ${total / 1_000_000} MB)")
    }

    private suspend fun extract(model: SpeechModel) {
        val staging = File(root, "${model.id}.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val wanted = model.files.values.toSet()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive(model).inputStream(), 1 shl 20))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in wanted) continue
                FileOutputStream(File(staging, name)).use { tar.copyTo(it, 1 shl 20) }
            }
        }
        val missing = wanted.filter { File(staging, it).length() == 0L }
        if (missing.isNotEmpty()) throw IOException("Archive is missing $missing")
        val dir = dir(model)
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) throw IOException("Could not install the model")
        File(dir, COMPLETE_MARKER).writeText(model.id)
    }

    companion object {
        private const val TAG = "HandyModels"
        private const val COMPLETE_MARKER = ".complete"
        private const val SPACE_MARGIN = 100_000_000L
        const val NOT_ENOUGH_SPACE = "not_enough_space"
    }
}
