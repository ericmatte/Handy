package computer.handy.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            HandyTheme {
                SettingsScreen(app)
            }
        }
    }
}
