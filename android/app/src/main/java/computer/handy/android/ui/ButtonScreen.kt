package computer.handy.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import computer.handy.android.HandyApp
import computer.handy.android.R
import computer.handy.android.audio.FeedbackSounds
import computer.handy.android.core.SoundTheme
import computer.handy.android.overlay.ButtonState
import computer.handy.android.overlay.HandyButtonView
import computer.handy.android.settings.HandyPrefs
import kotlin.math.roundToInt

fun LazyListScope.buttonItems(app: HandyApp) {
    // The button's look only matters in floating-button mode.
    if (app.prefs.floatingButtonEnabled) item { AppearanceSection(app.prefs) }
    item { RecordingSection(app.prefs) }
    item { SoundsSection(app.prefs) }
    item { OutputSection(app.prefs) }
}

@Composable
private fun AppearanceSection(prefs: HandyPrefs) {
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
        Hint(stringResource(R.string.settings_preview_hint))
    }
}

@Composable
private fun RecordingSection(prefs: HandyPrefs) {
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
        Hint(stringResource(R.string.settings_silence_explanation))
    }
}

@Composable
private fun SoundsSection(prefs: HandyPrefs) {
    val context = LocalContext.current
    var enabled by rememberPref(prefs) { prefs.audioFeedback }
    var theme by rememberPref(prefs) { prefs.soundTheme }
    var volume by remember { mutableFloatStateOf(prefs.audioFeedbackVolume) }
    var picking by remember { mutableStateOf(false) }
    val sounds = remember { FeedbackSounds(context) }
    DisposableEffect(Unit) { onDispose { sounds.release() } }

    Section(stringResource(R.string.settings_sounds_title)) {
        SwitchRow(
            title = stringResource(R.string.settings_sounds),
            subtitle = stringResource(R.string.settings_sounds_hint),
            checked = enabled,
            onCheckedChange = {
                enabled = it
                prefs.audioFeedback = it
            },
        )
        if (enabled) {
            NavRow(stringResource(R.string.settings_sound_theme), soundThemeLabel(theme)) { picking = true }
            Text(stringResource(R.string.settings_sound_volume, (volume * 100).roundToInt()))
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { prefs.audioFeedbackVolume = volume },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { sounds.playStart(theme, volume) }) { Text(stringResource(R.string.settings_sound_test_start)) }
                TextButton(onClick = { sounds.playStop(theme, volume) }) { Text(stringResource(R.string.settings_sound_test_stop)) }
            }
        }
    }
    if (picking) {
        ChoiceDialog(
            title = stringResource(R.string.settings_sound_theme),
            options = SoundTheme.entries,
            selected = theme,
            label = { soundThemeLabel(it) },
            onSelect = {
                theme = it
                prefs.soundTheme = it
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun soundThemeLabel(theme: SoundTheme) = stringResource(
    if (theme == SoundTheme.MARIMBA) R.string.sound_marimba else R.string.sound_pop,
)

@Composable
private fun OutputSection(prefs: HandyPrefs) {
    var trailing by rememberPref(prefs) { prefs.appendTrailingSpace }
    var submit by rememberPref(prefs) { prefs.autoSubmit }
    Section(stringResource(R.string.settings_output_title)) {
        SwitchRow(
            title = stringResource(R.string.settings_trailing_space),
            subtitle = stringResource(R.string.settings_trailing_space_hint),
            checked = trailing,
            onCheckedChange = {
                trailing = it
                prefs.appendTrailingSpace = it
            },
        )
        HorizontalDivider()
        SwitchRow(
            title = stringResource(R.string.settings_auto_submit),
            subtitle = stringResource(R.string.settings_auto_submit_hint),
            checked = submit,
            onCheckedChange = {
                submit = it
                prefs.autoSubmit = it
            },
        )
    }
}
