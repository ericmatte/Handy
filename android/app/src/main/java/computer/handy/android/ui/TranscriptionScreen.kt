package computer.handy.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.core.TextCleanup
import computer.handy.android.core.UnloadTimeout
import computer.handy.android.transcription.ModelCatalog
import java.util.Locale

/** "Français", "English"… in the phone's language; "Auto" for automatic detection. */
@Composable
fun languageName(code: String): String =
    if (code == ModelCatalog.AUTO) {
        stringResource(R.string.language_auto)
    } else {
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            .ifEmpty { code }
    }

fun LazyListScope.transcriptionItems(app: HandyApp) {
    item { LanguageSection(app) }
    item { CustomWordsSection(app) }
    item { FillerSection(app) }
    item { PerformanceSection(app) }
}

@Composable
private fun LanguageSection(app: HandyApp) {
    val prefs = app.prefs
    val modelId by rememberPref(prefs) { prefs.selectedModelId }
    val model = ModelCatalog.byId(modelId) ?: ModelCatalog.DEFAULT
    var language by rememberPref(prefs) { prefs.selectedLanguage }
    var translate by rememberPref(prefs) { prefs.translateToEnglish }
    var picking by remember { mutableStateOf(false) }

    Section(stringResource(R.string.transcription_language_title)) {
        NavRow(stringResource(R.string.transcription_language), languageName(language)) { picking = true }
        val effective = ModelCatalog.decodeLanguage(model, language, Locale.getDefault().language)
        Hint(
            when {
                model.languages.size == 1 -> stringResource(R.string.transcription_language_fixed, model.name, languageName(model.languages.first()))
                effective.isEmpty() -> stringResource(R.string.transcription_language_auto_hint, model.name)
                effective != language -> stringResource(R.string.transcription_language_unsupported, model.name, languageName(effective))
                else -> stringResource(R.string.transcription_language_hint)
            },
        )
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.transcription_translate),
            subtitle = if (model.supportsTranslation) {
                stringResource(R.string.transcription_translate_hint)
            } else {
                stringResource(R.string.transcription_translate_unsupported, model.name)
            },
            checked = translate && model.supportsTranslation,
            enabled = model.supportsTranslation,
            onCheckedChange = {
                translate = it
                prefs.translateToEnglish = it
            },
        )
    }

    if (picking) {
        val options = listOf(ModelCatalog.AUTO) + model.languages.sortedBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        ChoiceDialog(
            title = stringResource(R.string.transcription_language),
            options = options,
            selected = language,
            label = { languageName(it) },
            onSelect = {
                language = it
                prefs.selectedLanguage = it
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun CustomWordsSection(app: HandyApp) {
    val prefs = app.prefs
    var words by rememberPref(prefs) { prefs.customWords }
    var threshold by rememberPref(prefs) { prefs.wordCorrectionThreshold.toFloat() }
    var input by remember { mutableStateOf("") }

    Section(stringResource(R.string.transcription_custom_words)) {
        Hint(stringResource(R.string.transcription_custom_words_hint))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.transcription_custom_words_placeholder)) },
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = input.isNotBlank(),
                onClick = {
                    words = (words + input.split(',')).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    prefs.customWords = words
                    input = ""
                },
            ) { Text(stringResource(R.string.action_add)) }
        }
        // Simple wrapping list of removable chips.
        words.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { word ->
                    AssistChip(
                        onClick = {
                            words = words - word
                            prefs.customWords = words
                        },
                        label = { Text("$word  ✕") },
                    )
                }
            }
        }
        if (words.isNotEmpty()) {
            Text(stringResource(R.string.transcription_threshold, threshold))
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { prefs.wordCorrectionThreshold = threshold.toDouble() },
                valueRange = 0.05f..0.5f,
            )
            Hint(stringResource(R.string.transcription_threshold_hint, TextCleanup.DEFAULT_WORD_CORRECTION_THRESHOLD))
        }
    }
}

@Composable
private fun FillerSection(app: HandyApp) {
    val prefs = app.prefs
    var enabled by rememberPref(prefs) { prefs.fillerWordRemoval }
    var custom by remember { mutableStateOf(prefs.customFillerWords?.joinToString(", ") ?: "") }

    Section(stringResource(R.string.transcription_fillers_title)) {
        SwitchRow(
            title = stringResource(R.string.transcription_fillers),
            subtitle = stringResource(R.string.transcription_fillers_hint),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                prefs.fillerWordRemoval = it
            },
        )
        if (enabled) {
            OutlinedTextField(
                value = custom,
                onValueChange = {
                    custom = it
                    val list = it.split(',').map(String::trim).filter(String::isNotEmpty)
                    prefs.customFillerWords = list.ifEmpty { null }
                },
                label = { Text(stringResource(R.string.transcription_custom_fillers)) },
                supportingText = { Text(stringResource(R.string.transcription_custom_fillers_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PerformanceSection(app: HandyApp) {
    val prefs = app.prefs
    var unload by rememberPref(prefs) { prefs.modelUnloadTimeout }
    var vad by rememberPref(prefs) { prefs.vadEnabled }
    var picking by remember { mutableStateOf(false) }

    Section(stringResource(R.string.transcription_performance_title)) {
        NavRow(stringResource(R.string.transcription_unload), unloadLabel(unload)) { picking = true }
        Hint(stringResource(R.string.transcription_unload_hint))
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.transcription_vad),
            subtitle = stringResource(R.string.transcription_vad_hint),
            checked = vad,
            onCheckedChange = {
                vad = it
                prefs.vadEnabled = it
            },
        )
    }
    if (picking) {
        ChoiceDialog(
            title = stringResource(R.string.transcription_unload),
            options = UnloadTimeout.entries,
            selected = unload,
            label = { unloadLabel(it) },
            onSelect = {
                unload = it
                prefs.modelUnloadTimeout = it
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun unloadLabel(timeout: UnloadTimeout): String = stringResource(
    when (timeout) {
        UnloadTimeout.NEVER -> R.string.unload_never
        UnloadTimeout.IMMEDIATELY -> R.string.unload_immediately
        UnloadTimeout.MIN2 -> R.string.unload_2min
        UnloadTimeout.MIN5 -> R.string.unload_5min
        UnloadTimeout.MIN10 -> R.string.unload_10min
        UnloadTimeout.MIN15 -> R.string.unload_15min
        UnloadTimeout.HOUR1 -> R.string.unload_1h
    },
)
