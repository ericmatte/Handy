package computer.handy.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.core.LlmPrompt
import computer.handy.android.core.TapAction
import computer.handy.android.transcription.ClaudePostProcessor
import kotlinx.coroutines.launch
import java.util.UUID

fun LazyListScope.postProcessItems(app: HandyApp) {
    item { ClaudeSection(app) }
    item { PromptsSection(app) }
    item { TapActionSection(app) }
}

@Composable
private fun ClaudeSection(app: HandyApp) {
    val prefs = app.prefs
    val secrets = app.secrets
    val scope = rememberCoroutineScope()
    var enabled by rememberPref(prefs) { prefs.postProcessEnabled }
    var apiKey by remember { mutableStateOf(secrets.apiKey) }
    var model by rememberPref(prefs) { prefs.claudeModel }
    var available by remember { mutableStateOf<List<String>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    Section(stringResource(R.string.settings_pp_title)) {
        SwitchRow(
            title = stringResource(R.string.settings_pp_enable),
            subtitle = stringResource(R.string.settings_pp_explanation),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                prefs.postProcessEnabled = it
            },
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                secrets.apiKey = it
            },
            label = { Text(stringResource(R.string.settings_pp_api_key)) },
            supportingText = { Text(stringResource(R.string.settings_pp_api_key_help)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = {
                model = it
                prefs.claudeModel = it
            },
            label = { Text(stringResource(R.string.settings_pp_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = apiKey.isNotBlank(),
                onClick = {
                    loadError = null
                    scope.launch {
                        try {
                            available = ClaudePostProcessor.listModels(apiKey.trim())
                            picking = true
                        } catch (e: Exception) {
                            loadError = e.message ?: e.javaClass.simpleName
                        }
                    }
                },
            ) { Text(stringResource(R.string.settings_pp_load_models)) }
            TextButton(onClick = {
                model = ClaudePostProcessor.DEFAULT_MODEL
                prefs.claudeModel = model
            }) { Text(stringResource(R.string.settings_pp_default_model)) }
        }
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    val list = available
    if (picking && list != null) {
        ChoiceDialog(
            title = stringResource(R.string.settings_pp_model),
            options = list,
            selected = model,
            label = { it },
            onSelect = {
                model = it
                prefs.claudeModel = it
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun PromptsSection(app: HandyApp) {
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    var prompts by rememberPref(prefs) { prefs.prompts }
    var selectedId by rememberPref(prefs) { prefs.selectedPrompt().id }
    var editing by remember { mutableStateOf<LlmPrompt?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val sample = stringResource(R.string.settings_pp_sample)
    val errorFormat = stringResource(R.string.settings_pp_test_error)

    Section(stringResource(R.string.settings_pp_prompts)) {
        Hint(stringResource(R.string.settings_pp_prompt_help))
        prompts.forEachIndexed { index, prompt ->
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = prompt.id == selectedId,
                        onClick = {
                            selectedId = prompt.id
                            prefs.selectedPromptId = prompt.id
                        },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = prompt.id == selectedId, onClick = null)
                Column(Modifier.weight(1f).padding8()) {
                    Text(prompt.name, style = MaterialTheme.typography.bodyLarge)
                    Hint(prompt.prompt.lines().firstOrNull { it.isNotBlank() && !it.startsWith("<") }?.take(80) ?: "")
                }
                TextButton(onClick = { editing = prompt }) { Text(stringResource(R.string.action_edit)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = LlmPrompt(UUID.randomUUID().toString(), "", "\${output}") }) {
                Text(stringResource(R.string.settings_pp_add_prompt))
            }
            OutlinedButton(
                enabled = !testing && app.secrets.hasApiKey,
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = try {
                            ClaudePostProcessor(app.secrets.apiKey, prefs.claudeModel, prefs.selectedPrompt().prompt).process(sample)
                        } catch (e: Exception) {
                            String.format(errorFormat, e.message ?: e.javaClass.simpleName)
                        }
                        testing = false
                    }
                },
            ) { Text(stringResource(if (testing) R.string.settings_pp_testing else R.string.settings_pp_test)) }
        }
        testResult?.let {
            Hint(stringResource(R.string.settings_pp_test_input, sample))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }

    editing?.let { prompt ->
        PromptEditor(
            prompt = prompt,
            canDelete = prompts.size > 1 && prompts.any { it.id == prompt.id },
            onSave = { updated ->
                prompts = if (prompts.any { it.id == updated.id }) {
                    prompts.map { if (it.id == updated.id) updated else it }
                } else {
                    prompts + updated
                }
                prefs.prompts = prompts
                editing = null
            },
            onDelete = {
                prompts = prompts.filterNot { it.id == prompt.id }
                prefs.prompts = prompts
                if (selectedId == prompt.id) {
                    selectedId = prompts.first().id
                    prefs.selectedPromptId = selectedId
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun PromptEditor(
    prompt: LlmPrompt,
    canDelete: Boolean,
    onSave: (LlmPrompt) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(prompt.name) }
    var text by remember { mutableStateOf(prompt.prompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_pp_edit_prompt)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_pp_prompt_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.settings_pp_prompt)) },
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (prompt.id == LlmPrompt.DEFAULT.id) {
                    TextButton(onClick = { text = LlmPrompt.DEFAULT.prompt }) {
                        Text(stringResource(R.string.settings_pp_reset_prompt))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && text.isNotBlank(),
                onClick = { onSave(prompt.copy(name = name.trim(), prompt = text)) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun TapActionSection(app: HandyApp) {
    val prefs = app.prefs
    var action by rememberPref(prefs) { prefs.tapAction }
    Section(stringResource(R.string.settings_tap_action)) {
        Hint(stringResource(R.string.settings_tap_action_hint))
        TapAction.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = action == option,
                        onClick = {
                            action = option
                            prefs.tapAction = option
                        },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = action == option, onClick = null)
                Text(
                    stringResource(
                        if (option == TapAction.TRANSCRIBE) R.string.tap_transcribe else R.string.tap_transcribe_with_claude,
                    ),
                    modifier = Modifier.padding8(),
                )
            }
        }
    }
}

private fun Modifier.padding8(): Modifier = this.then(Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
