package computer.handy.android.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.core.AppEntry
import computer.handy.android.settings.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun LazyListScope.exclusionsItems(app: HandyApp) {
    item { ExclusionsSection(app) }
}

@Composable
private fun ExclusionsSection(app: HandyApp) {
    val prefs = app.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val excluded by rememberPref(prefs) { prefs.excludedPackages }
    var picking by remember { mutableStateOf(false) }

    Section(stringResource(R.string.settings_exclusions_title)) {
        Hint(stringResource(R.string.settings_exclusions_explanation))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picking = true }) { Text(stringResource(R.string.settings_exclusions_add)) }
            OutlinedButton(onClick = {
                scope.launch {
                    val added = withContext(Dispatchers.IO) { InstalledApps.redetect(context.applicationContext, prefs) }
                    Toast.makeText(
                        context,
                        context.resources.getQuantityString(R.plurals.settings_redetect_result, added.size, added.size),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }) { Text(stringResource(R.string.settings_exclusions_redetect)) }
        }
        if (excluded.isEmpty()) Hint(stringResource(R.string.settings_exclusions_empty))
        val labelled = remember(excluded) {
            excluded.map { AppEntry(it, InstalledApps.label(context, it)) }.sortedBy { it.label.lowercase() }
        }
        labelled.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                    Text(entry.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                TextButton(onClick = { prefs.removeExcluded(entry.packageName) }) {
                    Text(stringResource(R.string.settings_exclusions_remove))
                }
            }
        }
    }

    if (picking) {
        AppPickerDialog(
            excluded = excluded,
            onToggle = { pkg, checked -> if (checked) prefs.addExcluded(pkg) else prefs.removeExcluded(pkg) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun AppPickerDialog(
    excluded: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { InstalledApps.launchable(context.applicationContext) }
    }
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
        title = { Text(stringResource(R.string.settings_picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_picker_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filtered = list.filter {
                        query.isBlank() ||
                            it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = { it.packageName }) { entry ->
                            val checked = entry.packageName in excluded
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(entry.packageName, !checked) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { onToggle(entry.packageName, it) })
                                Column {
                                    Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        entry.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
