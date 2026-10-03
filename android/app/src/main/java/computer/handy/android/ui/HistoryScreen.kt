package computer.handy.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.history.HistoryEntry

private val HISTORY_LIMITS = listOf(0, 5, 10, 25, 50, 100)

fun LazyListScope.historyItems(app: HandyApp) {
    item { HistorySettings(app) }
    item { HistoryList(app) }
}

@Composable
private fun HistorySettings(app: HandyApp) {
    val prefs = app.prefs
    var limit by rememberPref(prefs) { prefs.historyLimit }
    var picking by remember { mutableStateOf(false) }
    Section(stringResource(R.string.history_settings)) {
        NavRow(stringResource(R.string.history_limit), limitLabel(limit)) { picking = true }
        Hint(stringResource(R.string.history_privacy))
        TextButton(onClick = { app.history.clear() }) { Text(stringResource(R.string.history_clear)) }
    }
    if (picking) {
        ChoiceDialog(
            title = stringResource(R.string.history_limit),
            options = HISTORY_LIMITS,
            selected = limit,
            label = { limitLabel(it) },
            onSelect = {
                limit = it
                prefs.historyLimit = it
                app.history.trim(it)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun limitLabel(limit: Int) =
    if (limit == 0) stringResource(R.string.history_off) else stringResource(R.string.history_limit_value, limit)

@Composable
private fun HistoryList(app: HandyApp) {
    val entries by app.history.entries.collectAsState()
    if (entries.isEmpty()) {
        Hint(stringResource(R.string.history_empty))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEach { entry -> HistoryCard(app, entry) }
    }
}

@Composable
private fun HistoryCard(app: HandyApp, entry: HistoryEntry) {
    val context = LocalContext.current
    val copied = stringResource(R.string.history_copied)
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Handy", text))
        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
    }
    val time = DateUtils.getRelativeTimeSpanString(entry.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    Section("$time · ${entry.modelName}") {
        Text(entry.finalText, style = MaterialTheme.typography.bodyLarge)
        entry.postProcessedText?.let {
            Hint(stringResource(R.string.history_original, entry.promptName ?: "", entry.text))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { copy(entry.finalText) }) { Text(stringResource(R.string.history_copy)) }
            if (entry.postProcessedText != null) {
                TextButton(onClick = { copy(entry.text) }) { Text(stringResource(R.string.history_copy_original)) }
            }
            TextButton(onClick = { app.history.delete(entry) }) { Text(stringResource(R.string.action_delete)) }
        }
    }
}
