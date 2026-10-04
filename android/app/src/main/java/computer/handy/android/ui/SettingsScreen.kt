package computer.handy.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.transcription.ModelState

enum class Screen { HOME, MODELS, TRANSCRIPTION, POST_PROCESS, BUTTON, EXCLUSIONS, HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: HandyApp, onReplayOnboarding: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val floating by rememberPref(app.prefs) { app.prefs.floatingButtonEnabled }
                    if (screen == Screen.HOME) {
                        Image(
                            painterResource(R.drawable.handy_text_logo),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier.height(36.dp),
                        )
                    } else {
                        Text(stringResource(screen.titleRes(floating)))
                    }
                },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        IconButton(onClick = { screen = Screen.HOME }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        // Home keeps its scroll position across visits; each sub-screen opens at the top.
        val homeListState = rememberLazyListState()
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val opening = targetState != Screen.HOME
                val direction = if (opening) 1 else -1
                (slideInHorizontally(tween(TRANSITION_MS)) { width -> direction * width / 4 } + fadeIn(tween(TRANSITION_MS)))
                    .togetherWith(
                        slideOutHorizontally(tween(TRANSITION_MS)) { width -> -direction * width / 4 } + fadeOut(tween(TRANSITION_MS / 2)),
                    )
            },
            label = "settings screen",
        ) { shown ->
            LazyColumn(
                state = if (shown == Screen.HOME) homeListState else rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (shown) {
                    Screen.HOME -> homeItems(app, onReplayOnboarding) { screen = it }
                    Screen.MODELS -> modelsItems(app)
                    Screen.TRANSCRIPTION -> transcriptionItems(app)
                    Screen.POST_PROCESS -> postProcessItems(app)
                    Screen.BUTTON -> buttonItems(app)
                    Screen.EXCLUSIONS -> exclusionsItems(app)
                    Screen.HISTORY -> historyItems(app)
                }
            }
        }
    }
}

private const val TRANSITION_MS = 260

private fun Screen.titleRes(floating: Boolean): Int = when (this) {
    Screen.HOME -> R.string.app_name
    Screen.MODELS -> R.string.screen_models
    Screen.TRANSCRIPTION -> R.string.screen_transcription
    Screen.POST_PROCESS -> R.string.screen_post_process
    Screen.BUTTON -> if (floating) R.string.screen_button else R.string.screen_behaviour
    Screen.EXCLUSIONS -> R.string.screen_exclusions
    Screen.HISTORY -> R.string.screen_history
}

private fun LazyListScope.homeItems(app: HandyApp, onReplayOnboarding: () -> Unit, navigate: (Screen) -> Unit) {
    item { StatusCards(app) }
    item {
        Section(stringResource(R.string.settings_title)) {
            val prefs = app.prefs
            val states by app.models.states.collectAsState()
            val modelId by rememberPref(prefs) { prefs.selectedModelId }
            val model = app.selectedModel()
            val installed = states[modelId] == ModelState.Ready
            NavRow(
                stringResource(R.string.screen_models),
                if (installed) model.name else stringResource(R.string.home_model_missing, model.name),
            ) { navigate(Screen.MODELS) }
            HorizontalDivider()
            val language by rememberPref(prefs) { prefs.selectedLanguage }
            NavRow(stringResource(R.string.screen_transcription), languageName(language)) { navigate(Screen.TRANSCRIPTION) }
            HorizontalDivider()
            val pp by rememberPref(prefs) { prefs.postProcessEnabled }
            NavRow(
                stringResource(R.string.screen_post_process),
                stringResource(if (pp) R.string.state_on else R.string.state_off),
            ) { navigate(Screen.POST_PROCESS) }
            val floating by rememberPref(prefs) { prefs.floatingButtonEnabled }
            HorizontalDivider()
            NavRow(stringResource(Screen.BUTTON.titleRes(floating)), null) { navigate(Screen.BUTTON) }
            // Excluded apps only apply to the floating button.
            if (floating) {
                HorizontalDivider()
                val excluded by rememberPref(prefs) { prefs.excludedPackages }
                NavRow(
                    stringResource(R.string.screen_exclusions),
                    LocalContext.current.resources.getQuantityString(R.plurals.home_excluded_count, excluded.size, excluded.size),
                ) { navigate(Screen.EXCLUSIONS) }
            }
            HorizontalDivider()
            val history by app.history.entries.collectAsState()
            NavRow(
                stringResource(R.string.screen_history),
                LocalContext.current.resources.getQuantityString(R.plurals.home_history_count, history.size, history.size),
            ) { navigate(Screen.HISTORY) }
        }
    }
    item {
        val context = LocalContext.current
        Button(
            onClick = { context.startActivity(Intent(context, TestActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_test_button)) }
    }
    item {
        TextButton(onClick = onReplayOnboarding, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_replay_onboarding))
        }
    }
}

@Composable
private fun StatusCards(app: HandyApp) {
    val context = LocalContext.current
    val prefs = app.prefs
    var serviceEnabled by remember { mutableStateOf(SystemState.isServiceEnabled(context)) }
    var imeEnabled by remember { mutableStateOf(SystemState.isImeEnabled(context)) }
    var micGranted by remember { mutableStateOf(SystemState.hasMicPermission(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        serviceEnabled = SystemState.isServiceEnabled(context)
        imeEnabled = SystemState.isImeEnabled(context)
        micGranted = SystemState.hasMicPermission(context)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micGranted = it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var floating by rememberPref(prefs) { prefs.floatingButtonEnabled }
    var autoStart by rememberPref(prefs) { prefs.imeAutoStart }
    var returnToPrevious by rememberPref(prefs) { prefs.imeReturnToPrevious }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // One way to dictate at a time: the other mode's settings and permissions are hidden.
        Section(stringResource(R.string.settings_mode_title)) {
            RadioRow(
                stringResource(R.string.settings_mode_keyboard),
                stringResource(R.string.settings_mode_keyboard_hint),
                selected = !floating,
            ) { prefs.floatingButtonEnabled = false; floating = false }
            RadioRow(
                stringResource(R.string.settings_mode_floating),
                stringResource(R.string.settings_mode_floating_hint),
                selected = floating,
            ) { prefs.floatingButtonEnabled = true; floating = true }
        }
        if (!floating) {
            Section(stringResource(R.string.settings_ime_title)) {
                StatusLine(
                    imeEnabled,
                    stringResource(R.string.settings_ime_enabled),
                    stringResource(R.string.settings_ime_disabled),
                )
                Hint(stringResource(R.string.settings_ime_explanation))
                if (imeEnabled) {
                    OutlinedButton(onClick = { SystemState.showKeyboardPicker(context) }) {
                        Text(stringResource(R.string.settings_ime_try))
                    }
                } else {
                    Button(onClick = { context.startActivity(SystemState.inputMethodSettingsIntent()) }) {
                        Text(stringResource(R.string.settings_ime_enable))
                    }
                }
                SwitchRow(
                    stringResource(R.string.settings_ime_auto_start),
                    autoStart,
                    { prefs.imeAutoStart = it; autoStart = it },
                    subtitle = stringResource(R.string.settings_ime_auto_start_hint),
                )
                SwitchRow(
                    stringResource(R.string.settings_ime_return),
                    returnToPrevious,
                    { prefs.imeReturnToPrevious = it; returnToPrevious = it },
                    subtitle = stringResource(R.string.settings_ime_return_hint),
                )
            }
        } else {
            Section(stringResource(R.string.settings_service_title)) {
                StatusLine(
                    serviceEnabled,
                    stringResource(R.string.settings_service_enabled),
                    stringResource(R.string.settings_service_disabled),
                )
                Hint(stringResource(R.string.settings_service_explanation))
                OutlinedButton(onClick = { context.startActivity(SystemState.accessibilitySettingsIntent()) }) {
                    Text(stringResource(R.string.settings_open_accessibility))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !serviceEnabled) {
                Section(stringResource(R.string.settings_restricted_title)) {
                    Hint(stringResource(R.string.settings_restricted_body))
                    OutlinedButton(onClick = { context.startActivity(SystemState.appDetailsIntent(context)) }) {
                        Text(stringResource(R.string.settings_open_app_info))
                    }
                }
            }
        }
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
                Hint(stringResource(R.string.settings_notifications_explanation))
                TextButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(stringResource(R.string.settings_grant_notifications))
                }
            }
        }
        val states by app.models.states.collectAsState()
        if (states.values.none { it == ModelState.Ready }) {
            Section(stringResource(R.string.home_first_model_title)) {
                Hint(stringResource(R.string.home_first_model_body))
            }
        }
    }
}
