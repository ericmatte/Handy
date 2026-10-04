package computer.handy.android.history

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One dictation, like a row of desktop Handy's history (without the audio). */
data class HistoryEntry(
    val timestamp: Long,
    val text: String,
    val postProcessedText: String?,
    val promptName: String?,
    val modelName: String,
) {
    /** What was inserted. */
    val finalText: String get() = postProcessedText ?: text
}

/**
 * Last dictations, newest first, capped at the desktop `history_limit` setting. Kept in
 * app-private storage; never backed up (`allowBackup=false`).
 */
class HistoryStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "history.json")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    @Synchronized
    fun add(entry: HistoryEntry, limit: Int) {
        if (limit <= 0) return
        save((listOf(entry) + _entries.value).take(limit))
    }

    @Synchronized
    fun trim(limit: Int) = save(_entries.value.take(limit.coerceAtLeast(0)))

    @Synchronized
    fun delete(entry: HistoryEntry) = save(_entries.value - entry)

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<HistoryEntry>) {
        _entries.value = list
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("timestamp", it.timestamp)
                    .put("text", it.text)
                    .put("postProcessedText", it.postProcessedText ?: JSONObject.NULL)
                    .put("promptName", it.promptName ?: JSONObject.NULL)
                    .put("modelName", it.modelName),
            )
        }
        file.writeText(array.toString())
    }

    private fun load(): List<HistoryEntry> = try {
        val array = JSONArray(file.readText())
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            HistoryEntry(
                timestamp = o.getLong("timestamp"),
                text = o.getString("text"),
                postProcessedText = o.optString("postProcessedText").takeUnless { o.isNull("postProcessedText") },
                promptName = o.optString("promptName").takeUnless { o.isNull("promptName") },
                modelName = o.optString("modelName"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
