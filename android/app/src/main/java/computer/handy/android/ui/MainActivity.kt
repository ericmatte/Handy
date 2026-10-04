package computer.handy.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import computer.handy.android.HandyApp
import computer.handy.android.settings.InstalledApps
import kotlin.concurrent.thread

/** Settings screen; also the accessibility service's `settingsActivity`. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HandyApp
        val prefs = app.prefs
        if (!prefs.defaultsSeeded) {
            thread(name = "handy-seed-exclusions") { InstalledApps.seedDefaultExclusions(app, prefs) }
        }
        // Existing installs that already have a model skip the walkthrough.
        if (!prefs.onboardingDone && app.models.installedModels().isNotEmpty()) prefs.onboardingDone = true
        setContent {
            HandyTheme {
                var onboarding by rememberSaveable { mutableStateOf(!prefs.onboardingDone) }
                if (onboarding) {
                    OnboardingScreen(app) { onboarding = false }
                } else {
                    SettingsScreen(app, onReplayOnboarding = { onboarding = true })
                }
            }
        }
    }
}
