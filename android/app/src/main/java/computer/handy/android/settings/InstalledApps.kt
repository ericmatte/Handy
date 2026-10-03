package computer.handy.android.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import computer.handy.android.core.AppEntry
import computer.handy.android.core.ExclusionMatcher

/**
 * Package queries. Visibility is granted by the <queries> block in the manifest.
 * The int-flag overloads are deprecated on API 33+ but are the only ones on minSdk 29.
 */
@Suppress("DEPRECATION")
object InstalledApps {

    /** Launchable apps, sorted by label, without Handy itself. */
    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Every installed home app (not only the default one), always excluded. */
    fun launchers(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .queryIntentActivities(intent, 0)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0)
            .loadLabel(pm).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /** Adds detected banks/password managers to the blacklist, once. Call off the main thread. */
    fun seedDefaultExclusions(context: Context, prefs: HandyPrefs) {
        if (prefs.defaultsSeeded) return
        val suggested = ExclusionMatcher.suggestSensitiveApps(launchable(context))
        prefs.excludedPackages = prefs.excludedPackages + suggested
        prefs.defaultsSeeded = true
    }

    /** Re-runs detection (e.g. after installing a new bank app). Returns the added packages. */
    fun redetect(context: Context, prefs: HandyPrefs): Set<String> {
        val suggested = ExclusionMatcher.suggestSensitiveApps(launchable(context))
        val added = suggested - prefs.excludedPackages
        prefs.excludedPackages = prefs.excludedPackages + suggested
        return added
    }
}
