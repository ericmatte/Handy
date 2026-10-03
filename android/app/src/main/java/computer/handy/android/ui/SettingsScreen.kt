package computer.handy.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import computer.handy.android.R
import computer.handy.android.core.AppEntry
import computer.handy.android.overlay.ButtonState
import computer.handy.android.overlay.HandyButtonView
import computer.handy.android.settings.HandyPrefs
import computer.handy.android.settings.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(prefs: HandyPrefs) {
    val context = LocalContext.current

    var serviceEnabled by remember { mutableStateOf(SystemState.isServiceEnabled(context)) }
    var micGranted by remember { mutableStateOf(SystemState.hasMicPermission(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        serviceEnabled = SystemState.isServiceEnabled(context)
        micGranted = SystemState.hasMicPermission(context)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ServiceCard(serviceEnabled) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !serviceEnabled) {
                item { RestrictedSettingsCard() }
            }
            item { PermissionsCard(micGranted) { micGranted = it } }
            item { AppearanceCard(prefs) }
            item { RecordingCard(prefs) }
            item { ExclusionsCard(prefs) }
            item {
                Button(
                    onClick = { context.startActivity(Intent(context, TestActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_test_button)) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, notOkText: String) {
    Text(
        text = (if (ok) "● " else "○ ") + (if (ok) okText else notOkText),
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ServiceCard(enabled: Boolean) {
    val context = LocalContext.current
    Section(stringResource(R.string.settings_service_title)) {
        StatusLine(
            enabled,
            stringResource(R.string.settings_service_enabled),
            stringResource(R.string.settings_service_disabled),
        )
        Text(stringResource(R.string.settings_service_explanation), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { context.startActivity(SystemState.accessibilitySettingsIntent()) }) {
            Text(stringResource(R.string.settings_open_accessibility))
        }
    }
}

@Composable
private fun RestrictedSettingsCard() {
    val context = LocalContext.current
    Section(stringResource(R.string.settings_restricted_title)) {
        Text(stringResource(R.string.settings_restricted_body), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { context.startActivity(SystemState.appDetailsIntent(context)) }) {
            Text(stringResource(R.string.settings_open_app_info))
        }
    }
}

@Composable
private fun PermissionsCard(micGranted: Boolean, onMicResult: (Boolean) -> Unit) {
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onMicResult)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Section(stringResource(R.string.settings_permissions_title)) {
        StatusLine(
            micGranted,
            stringResource(R.string.settings_mic_granted),
            stringResource(R.string.settings_mic_missing),
        )
        if (!micGranted) {
            Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                Text(stringResource(R.string.settings_grant_mic))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Text(stringResource(R.string.settings_notifications_explanation), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.settings_grant_notifications))
            }
        }
    }
}

@Composable
private fun AppearanceCard(prefs: HandyPrefs) {
    var opacity by remember { mutableFloatStateOf(prefs.buttonOpacity) }
    var size by remember { mutableFloatStateOf(prefs.buttonSizeDp.toFloat()) }
    val density = LocalContext.current.resources.displayMetrics.density

    Section(stringResource(R.string.settings_appearance_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_opacity, (opacity * 100).roundToInt()))
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { prefs.buttonOpacity = opacity },
                    valueRange = HandyPrefs.MIN_OPACITY..1f,
                    steps = 13,
                )
                Text(stringResource(R.string.settings_size, size.roundToInt()))
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    onValueChangeFinished = { prefs.buttonSizeDp = size.roundToInt() },
                    valueRange = HandyPrefs.MIN_SIZE_DP.toFloat()..HandyPrefs.MAX_SIZE_DP.toFloat(),
                    steps = (HandyPrefs.MAX_SIZE_DP - HandyPrefs.MIN_SIZE_DP) / 2 - 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            // Live preview; tap it to cycle through the button states.
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx ->
                        HandyButtonView(ctx, (size * density).roundToInt()).apply {
                            listener = object : HandyButtonView.Listener {
                                override fun onTap() {
                                    val next = ButtonState.entries[(state.ordinal + 1) % ButtonState.entries.size]
                                    setState(next)
                                    setLevel(if (next == ButtonState.RECORDING) 0.5f else 0f)
                                }
                                override fun onLongPress() = Unit
                                override fun onDragStart() = Unit
                                override fun onDrag(totalDy: Int) = Unit
                                override fun onDragEnd(totalDy: Int) = Unit
                                override fun onInteraction() = Unit
                            }
                        }
                    },
                    update = { view ->
                        view.buttonSizePx = (size * density).roundToInt()
                        view.alpha = opacity
                    },
                )
            }
        }
        Text(stringResource(R.string.settings_preview_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecordingCard(prefs: HandyPrefs) {
    var silence by remember { mutableFloatStateOf(prefs.silenceTimeoutMs / 1000f) }
    Section(stringResource(R.string.settings_recording_title)) {
        Text(stringResource(R.string.settings_silence, silence))
        Slider(
            value = silence,
            onValueChange = { silence = it },
            onValueChangeFinished = { prefs.silenceTimeoutMs = (silence * 1000).roundToInt().toLong() },
            valueRange = HandyPrefs.MIN_SILENCE_MS / 1000f..HandyPrefs.MAX_SILENCE_MS / 1000f,
            steps = 17,
        )
        Text(stringResource(R.string.settings_silence_explanation), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExclusionsCard(prefs: HandyPrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var excluded by remember { mutableStateOf(prefs.excludedPackages) }
    var picking by remember { mutableStateOf(false) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == HandyPrefs.KEY_EXCLUDED) excluded = prefs.excludedPackages
        }
        prefs.registerListener(listener)
        onDispose { prefs.unregisterListener(listener) }
    }

    Section(stringResource(R.string.settings_exclusions_title)) {
        Text(stringResource(R.string.settings_exclusions_explanation), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picking = true }) { Text(stringResource(R.string.settings_exclusions_add)) }
            OutlinedButton(onClick = {
                scope.launch {
                    val added = withContext(Dispatchers.IO) { InstalledApps.redetect(context.applicationContext, prefs) }
                    toast(context, context.resources.getQuantityString(R.plurals.settings_redetect_result, added.size, added.size))
                }
            }) { Text(stringResource(R.string.settings_exclusions_redetect)) }
        }
        if (excluded.isEmpty()) {
            Text(stringResource(R.string.settings_exclusions_empty), style = MaterialTheme.typography.bodySmall)
        }
        val labelled = remember(excluded) {
            excluded.map { AppEntry(it, InstalledApps.label(context, it)) }.sortedBy { it.label.lowercase() }
        }
        labelled.forEachIndexed { index, app ->
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                TextButton(onClick = { prefs.removeExcluded(app.packageName) }) {
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
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in excluded
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(app.packageName, !checked) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = { onToggle(app.packageName, it) })
                                Column {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
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

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
