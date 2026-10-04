package computer.handy.android.core

/** An installed, launchable app as shown in the picker. */
data class AppEntry(val packageName: String, val label: String)

/**
 * Decides in which apps the button never appears.
 *
 * Two layers:
 *  - [ALWAYS_EXCLUDED] + runtime packages (Handy itself, the launcher): not user-editable.
 *  - The user blacklist, pre-filled once with [suggestSensitiveApps] (banks, password managers).
 */
object ExclusionMatcher {

    /** System surfaces where a dictation button makes no sense or could leak data. */
    val ALWAYS_EXCLUDED: Set<String> = setOf(
        "com.android.systemui", // status bar, quick settings, lock screen (keyguard)
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    /**
     * Package ids verified on Google Play (October 2026), see android/README.md for sources.
     * Anything not listed here is still caught by [SENSITIVE_KEYWORDS] when its id or label matches.
     */
    val KNOWN_SENSITIVE_PACKAGES: Set<String> = setOf(
        // Canadian banks
        "com.desjardins.mobile",
        "com.td",
        "com.td.softtoken",
        "com.rbc.mobile.android",
        "com.bmo.mobile",
        "com.scotiabank.banking",
        "com.cibc.android.mobi",
        "ca.bnc.android",
        "ca.tangerine.clients.banking.app",
        "com.pcfinancial.mobile", // Simplii Financial
        "com.eqbank.eqbank",
        "ca.laurentianbank.mobileapp",
        "com.wealthsimple.trade",
        "ca.koho",
        // Password managers
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre", // F-Droid build
        "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "proton.android.pass",
        "com.callpod.android_apps.keeper",
        "com.nordpass.android.app.password.manager",
        "io.enpass.app",
        // Authenticators
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.bitwarden.authenticator",
        "com.lastpass.authenticator",
    )

    /**
     * Keywords matched against package ids and app labels. Short, ambiguous ones are matched as
     * whole tokens ("td" must not match "ltd"); longer ones as substrings ("mobilebanking").
     */
    private val TOKEN_KEYWORDS = setOf(
        "td", "rbc", "bmo", "cibc", "bnc", "scotia", "scotiabank", "desjardins", "tangerine",
        "simplii", "koho", "wealthsimple", "eqbank", "keeper", "authy",
    )
    private val SUBSTRING_KEYWORDS = listOf(
        "bank", "banque", "banking", "desjardins", "scotia", "banque nationale",
        "national bank", "credit union", "caisse",
        "bitwarden", "1password", "onepassword", "keepass", "lastpass", "dashlane",
        "nordpass", "enpass", "protonpass", "proton pass", "roboform", "password",
        "authenticator", "wallet",
    )

    val SENSITIVE_KEYWORDS: Set<String> = TOKEN_KEYWORDS + SUBSTRING_KEYWORDS

    /**
     * @param packageName package of the focused field's window
     * @param ownPackage Handy's own package
     * @param launcherPackages current home apps, resolved at runtime
     * @param userBlacklist the editable list from settings
     */
    fun isExcluded(
        packageName: String?,
        ownPackage: String,
        launcherPackages: Set<String>,
        userBlacklist: Set<String>,
    ): Boolean {
        if (packageName.isNullOrEmpty()) return true
        if (packageName == ownPackage) return true
        if (packageName in ALWAYS_EXCLUDED) return true
        if (packageName in launcherPackages) return true
        return packageName in userBlacklist
    }

    /** True if an app looks like a bank, password manager or authenticator. */
    fun looksSensitive(app: AppEntry): Boolean {
        if (app.packageName in KNOWN_SENSITIVE_PACKAGES) return true
        return matchesKeywords(app.packageName) || matchesKeywords(app.label)
    }

    /** Apps among [installed] that should pre-fill the blacklist. */
    fun suggestSensitiveApps(installed: Collection<AppEntry>): Set<String> =
        installed.filter(::looksSensitive).mapTo(sortedSetOf()) { it.packageName }

    internal fun matchesKeywords(raw: String): Boolean {
        val normalized = SensitiveFieldDetector.normalize(raw)
        if (normalized.isEmpty()) return false
        if (SUBSTRING_KEYWORDS.any { normalized.contains(it) }) return true
        val tokens = SensitiveFieldDetector.tokenize(raw)
        return tokens.any { it in TOKEN_KEYWORDS }
    }
}
