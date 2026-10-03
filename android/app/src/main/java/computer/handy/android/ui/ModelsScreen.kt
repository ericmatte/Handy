package computer.handy.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.transcription.ModelCatalog
import computer.handy.android.transcription.ModelManager
import computer.handy.android.transcription.ModelState
import computer.handy.android.transcription.SpeechModel

fun LazyListScope.modelsItems(app: HandyApp) {
    item { Hint(stringResource(R.string.models_intro)) }
    items(ModelCatalog.ALL, key = { it.id }) { model -> ModelCard(app, model) }
}

@Composable
private fun ModelCard(app: HandyApp, model: SpeechModel) {
    val prefs = app.prefs
    val states by app.models.states.collectAsState()
    val selectedId by rememberPref(prefs) { prefs.selectedModelId }
    val state = states[model.id] ?: ModelState.Missing
    val selected = selectedId == model.id
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected,
                    enabled = state == ModelState.Ready,
                    onClick = { prefs.selectedModelId = model.id },
                )
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Hint(model.description)
                }
            }
            Hint(
                stringResource(
                    R.string.models_meta,
                    (model.downloadBytes / 1_000_000).toInt(),
                    model.installedMb,
                    languagesSummary(model),
                ),
            )
            when (state) {
                ModelState.Missing -> Button(onClick = { app.models.download(model) }) {
                    Text(stringResource(R.string.settings_model_download_size, (model.downloadBytes / 1_000_000).toInt()))
                }
                ModelState.Queued -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint(stringResource(R.string.models_queued))
                    TextButton(onClick = { app.models.cancel(model) }) { Text(stringResource(R.string.settings_model_cancel)) }
                }
                is ModelState.Downloading -> {
                    val fraction = if (state.total > 0) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Hint(
                            stringResource(
                                R.string.settings_model_downloading,
                                (state.downloaded / 1_000_000).toInt(),
                                (state.total / 1_000_000).toInt(),
                            ),
                        )
                        TextButton(onClick = { app.models.cancel(model) }) { Text(stringResource(R.string.settings_model_cancel)) }
                    }
                }
                ModelState.Extracting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Hint(stringResource(R.string.settings_model_extracting))
                }
                ModelState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!selected) {
                        OutlinedButton(onClick = { prefs.selectedModelId = model.id }) {
                            Text(stringResource(R.string.models_use))
                        }
                    } else {
                        StatusLine(true, stringResource(R.string.models_in_use), "")
                    }
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.settings_model_delete)) }
                }
                is ModelState.Failed -> {
                    Text(
                        stringResource(
                            R.string.settings_model_failed,
                            if (state.message == ModelManager.NOT_ENOUGH_SPACE) stringResource(R.string.models_not_enough_space) else state.message,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { app.models.download(model) }) { Text(stringResource(R.string.settings_model_retry)) }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.models_delete_title, model.name)) },
            text = { Text(stringResource(R.string.models_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    app.models.delete(model)
                }) { Text(stringResource(R.string.settings_model_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun languagesSummary(model: SpeechModel): String = when {
    model.languages.size > 6 -> stringResource(R.string.models_languages_count, model.languages.size)
    else -> model.languages.joinToString(", ") { languageName(it) }
}
