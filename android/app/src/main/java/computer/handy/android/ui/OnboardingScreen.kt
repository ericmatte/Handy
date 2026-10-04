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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.transcription.ModelCatalog
import computer.handy.android.transcription.ModelState
import computer.handy.android.transcription.SpeechModel

/**
 * First-run walkthrough. Order: the mode first (it decides what to enable later), then the
 * model, whose download is the slowest part and runs in the background while the user grants
 * the microphone and turns on the keyboard or the accessibility service.
 */
private enum class Step { WELCOME, MODE, MODEL, MICROPHONE, ENABLE, DONE }

private const val STEP_MS = 280

@Composable
fun OnboardingScreen(app: HandyApp, onFinish: () -> Unit) {
    val prefs = app.prefs
    var step by rememberSaveable { mutableStateOf(Step.WELCOME) }
    // The model picked here, so the download banner follows it even before it is selectable.
    var chosenModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var floating by rememberPref(prefs) { prefs.floatingButtonEnabled }

    fun next() {
        step = Step.entries[step.ordinal + 1]
    }
    fun finish() {
        prefs.onboardingDone = true
        onFinish()
    }
    BackHandler(enabled = step != Step.WELCOME) { step = Step.entries[step.ordinal - 1] }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
        ) {
            if (step != Step.WELCOME) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { step = Step.entries[step.ordinal - 1] }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                    LinearProgressIndicator(
                        progress = { step.ordinal.toFloat() / Step.DONE.ordinal },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    if (step != Step.DONE) {
                        TextButton(onClick = ::finish) { Text(stringResource(R.string.onb_skip)) }
                    }
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(STEP_MS)) { width -> direction * width / 3 } + fadeIn(tween(STEP_MS)))
                        .togetherWith(
                            slideOutHorizontally(tween(STEP_MS)) { width -> -direction * width / 3 } + fadeOut(tween(STEP_MS / 2)),
                        )
                },
                modifier = Modifier.weight(1f),
                label = "onboarding step",
            ) { shown ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (shown) {
                        Step.WELCOME -> WelcomeStep(::next)
                        Step.MODE -> ModeStep(floating) { chosen ->
                            prefs.floatingButtonEnabled = chosen
                            floating = chosen
                            next()
                        }
                        Step.MODEL -> ModelStep(app) { model ->
                            if (model != null) {
                                prefs.selectedModelId = model.id
                                chosenModelId = model.id
                                if (app.models.states.value[model.id] != ModelState.Ready) app.models.download(model)
                            }
                            next()
                        }
                        Step.MICROPHONE -> MicrophoneStep(::next)
                        Step.ENABLE -> if (floating) AccessibilityStep(::next) else KeyboardStep(::next)
                        Step.DONE -> DoneStep(floating, ::finish)
                    }
                }
            }
            if (step.ordinal > Step.MODEL.ordinal) {
                DownloadBanner(app, chosenModelId)
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String?) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    body?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(
            Modifier
                .size(112.dp)
                .background(Color(0xFFF7A8CB), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_handy_button),
                contentDescription = null,
                tint = Color(0xFF2A1A22),
                modifier = Modifier.size(64.dp),
            )
        }
        Image(
            painterResource(R.drawable.handy_text_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.height(56.dp),
        )
        Text(
            stringResource(R.string.onb_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.onb_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_start)) }
    }
}

/** A large tappable option; [badge] marks the recommended one. */
@Composable
private fun OptionCard(
    title: String,
    body: String,
    selected: Boolean,
    badge: String? = null,
    footer: String? = null,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            footer?.let { Hint(it) }
        }
    }
}

@Composable
private fun ModeStep(floating: Boolean, onChoose: (floating: Boolean) -> Unit) {
    StepTitle(stringResource(R.string.onb_mode_title), stringResource(R.string.onb_mode_body))
    OptionCard(
        title = stringResource(R.string.settings_mode_keyboard),
        body = stringResource(R.string.onb_mode_keyboard_body),
        selected = !floating,
        badge = stringResource(R.string.onb_recommended),
    ) { onChoose(false) }
    OptionCard(
        title = stringResource(R.string.settings_mode_floating),
        body = stringResource(R.string.onb_mode_floating_body),
        selected = floating,
    ) { onChoose(true) }
}

@Composable
private fun ModelStep(app: HandyApp, onChoose: (SpeechModel?) -> Unit) {
    val states by app.models.states.collectAsState()
    StepTitle(stringResource(R.string.onb_model_title), stringResource(R.string.onb_model_body))
    val installed = ModelCatalog.ALL.firstOrNull { states[it.id] == ModelState.Ready }
    if (installed != null) {
        Button(onClick = { onChoose(installed) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onb_model_keep, installed.name))
        }
    }
    ModelCatalog.ALL.forEach { model ->
        val state = states[model.id] ?: ModelState.Missing
        OptionCard(
            title = model.name,
            body = model.description,
            selected = false,
            badge = if (model == ModelCatalog.DEFAULT) stringResource(R.string.onb_recommended) else null,
            footer = if (state == ModelState.Ready) {
                stringResource(R.string.onb_model_installed)
            } else {
                stringResource(R.string.onb_model_size, (model.downloadBytes / 1_000_000).toInt())
            },
        ) { onChoose(model) }
    }
    TextButton(onClick = { onChoose(null) }) { Text(stringResource(R.string.onb_later)) }
}

@Composable
private fun MicrophoneStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(SystemState.hasMicPermission(context)) }
    var refused by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = SystemState.hasMicPermission(context) }
    // The "Handy is listening" notification (Android 13+) is asked right after the mic.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNext() }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
        if (ok) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onNext()
            }
        }
    }

    StepTitle(stringResource(R.string.onb_mic_title), stringResource(R.string.onb_mic_body))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Hint(stringResource(R.string.onb_mic_notifications))
    if (granted) {
        StatusLine(true, stringResource(R.string.settings_mic_granted), "")
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_continue)) }
    } else {
        Button(
            onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.onb_mic_allow)) }
        if (refused) {
            Text(stringResource(R.string.onb_mic_refused), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { context.startActivity(SystemState.appDetailsIntent(context)) }) {
                Text(stringResource(R.string.settings_open_app_info))
            }
        }
    }
}

@Composable
private fun KeyboardStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SystemState.isImeEnabled(context)) }
    var openedSettings by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = SystemState.isImeEnabled(context)
        // Back from the system list with the keyboard on: nothing left to do here.
        if (enabled && openedSettings) onNext()
    }

    StepTitle(stringResource(R.string.onb_kbd_title), stringResource(R.string.onb_kbd_body))
    Hint(stringResource(R.string.onb_kbd_warning))
    if (enabled) {
        StatusLine(true, stringResource(R.string.settings_ime_enabled), "")
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_continue)) }
    } else {
        Button(
            onClick = {
                openedSettings = true
                context.startActivity(SystemState.inputMethodSettingsIntent())
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_ime_enable)) }
    }
}

@Composable
private fun AccessibilityStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(SystemState.isServiceEnabled(context)) }
    var openedSettings by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = SystemState.isServiceEnabled(context)
        if (enabled && openedSettings) onNext()
    }

    StepTitle(stringResource(R.string.onb_a11y_title), stringResource(R.string.onb_a11y_body))
    if (enabled) {
        StatusLine(true, stringResource(R.string.settings_service_enabled), "")
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_continue)) }
    } else {
        Button(
            onClick = {
                openedSettings = true
                context.startActivity(SystemState.accessibilitySettingsIntent())
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_open_accessibility)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Text(stringResource(R.string.settings_restricted_title), style = MaterialTheme.typography.titleSmall)
            Hint(stringResource(R.string.settings_restricted_body))
            OutlinedButton(onClick = { context.startActivity(SystemState.appDetailsIntent(context)) }) {
                Text(stringResource(R.string.settings_open_app_info))
            }
        }
    }
}

@Composable
private fun DoneStep(floating: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    StepTitle(
        stringResource(R.string.onb_done_title),
        stringResource(if (floating) R.string.onb_done_floating else R.string.onb_done_keyboard),
    )
    Hint(stringResource(R.string.onb_done_claude))
    OutlinedButton(
        onClick = { context.startActivity(Intent(context, TestActivity::class.java)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.onb_try)) }
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_finish)) }
}

/** Progress of the model chosen in the walkthrough, shown under the following steps. */
@Composable
private fun DownloadBanner(app: HandyApp, modelId: String?) {
    val model = ModelCatalog.byId(modelId) ?: return
    val states by app.models.states.collectAsState()
    val state = states[model.id] ?: ModelState.Missing
    val text = when (state) {
        ModelState.Missing -> return
        ModelState.Queued -> stringResource(R.string.onb_download_queued, model.name)
        is ModelState.Downloading -> {
            val percent = if (state.total > 0) (state.downloaded * 100 / state.total).toInt() else 0
            stringResource(R.string.onb_download_progress, model.name, percent)
        }
        ModelState.Extracting -> stringResource(R.string.onb_download_installing, model.name)
        ModelState.Ready -> stringResource(R.string.onb_download_ready, model.name)
        is ModelState.Failed -> stringResource(R.string.onb_download_failed, model.name)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is ModelState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            when (state) {
                is ModelState.Downloading -> {
                    val fraction = if (state.total > 0) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                ModelState.Queued, ModelState.Extracting -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is ModelState.Failed -> TextButton(onClick = { app.models.download(model) }) {
                    Text(stringResource(R.string.settings_model_retry))
                }
                else -> Unit
            }
        }
    }
}
