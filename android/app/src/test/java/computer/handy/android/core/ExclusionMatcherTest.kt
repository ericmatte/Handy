package computer.handy.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionMatcherTest {

    private val own = "computer.handy.android"
    private val launchers = setOf("com.google.android.apps.nexuslauncher")

    private fun excluded(pkg: String?, blacklist: Set<String> = emptySet()) =
        ExclusionMatcher.isExcluded(pkg, own, launchers, blacklist)

    @Test
    fun `regular apps are allowed`() {
        assertFalse(excluded("com.google.android.apps.messaging"))
        assertFalse(excluded("com.android.chrome"))
        assertFalse(excluded("com.google.android.gm"))
    }

    @Test
    fun `handy itself, system ui and launchers are always excluded`() {
        assertTrue(excluded(own))
        assertTrue(excluded("com.android.systemui"))
        assertTrue(excluded("com.google.android.apps.nexuslauncher"))
    }

    @Test
    fun `unknown package is excluded`() {
        assertTrue(excluded(null))
        assertTrue(excluded(""))
    }

    @Test
    fun `user blacklist is an exact package match`() {
        val blacklist = setOf("com.whatsapp")
        assertTrue(excluded("com.whatsapp", blacklist))
        assertFalse(excluded("com.whatsapp.w4b", blacklist))
    }

    @Test
    fun `known banks and password managers are suggested`() {
        listOf(
            AppEntry("com.desjardins.mobile", "Desjardins"),
            AppEntry("com.td", "TD"),
            AppEntry("com.rbc.mobile.android", "RBC Mobile"),
            AppEntry("com.bmo.mobile", "BMO"),
            AppEntry("com.scotiabank.banking", "Scotia"),
            AppEntry("com.cibc.android.mobi", "CIBC"),
            AppEntry("ca.bnc.android", "Banque Nationale"),
            AppEntry("com.x8bit.bitwarden", "Bitwarden"),
            AppEntry("com.onepassword.android", "1Password"),
            AppEntry("com.kunzisoft.keepass.free", "KeePassDX"),
            AppEntry("com.lastpass.lpandroid", "LastPass"),
        ).forEach { assertTrue(it.toString(), ExclusionMatcher.looksSensitive(it)) }
    }

    @Test
    fun `unknown banks are caught by package or label keywords`() {
        assertTrue(ExclusionMatcher.looksSensitive(AppEntry("com.example.mobilebanking", "Example")))
        assertTrue(ExclusionMatcher.looksSensitive(AppEntry("com.example.app", "Caisse Populaire")))
        assertTrue(ExclusionMatcher.looksSensitive(AppEntry("com.example.app", "Ma Banque")))
        assertTrue(ExclusionMatcher.looksSensitive(AppEntry("com.example.vault", "Password Vault")))
        assertTrue(ExclusionMatcher.looksSensitive(AppEntry("com.example.td", "Something")))
    }

    @Test
    fun `short keywords only match whole tokens`() {
        // "td" must not match "ltd", "rbc" must not match random substrings.
        assertFalse(ExclusionMatcher.looksSensitive(AppEntry("com.acmeltd.notes", "Acme Ltd Notes")))
        assertFalse(ExclusionMatcher.looksSensitive(AppEntry("com.google.android.apps.messaging", "Messages")))
        assertFalse(ExclusionMatcher.looksSensitive(AppEntry("com.android.chrome", "Chrome")))
        assertFalse(ExclusionMatcher.looksSensitive(AppEntry("com.google.android.gm", "Gmail")))
        assertFalse(ExclusionMatcher.looksSensitive(AppEntry("org.thoughtcrime.securesms", "Signal")))
    }

    @Test
    fun `suggestions keep only sensitive apps`() {
        val installed = listOf(
            AppEntry("com.td", "TD"),
            AppEntry("com.android.chrome", "Chrome"),
            AppEntry("com.x8bit.bitwarden", "Bitwarden"),
            AppEntry("com.google.android.apps.messaging", "Messages"),
        )
        assertEquals(setOf("com.td", "com.x8bit.bitwarden"), ExclusionMatcher.suggestSensitiveApps(installed))
    }
}
