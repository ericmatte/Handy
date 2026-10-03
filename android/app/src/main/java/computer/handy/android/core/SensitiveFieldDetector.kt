package computer.handy.android.core

import java.text.Normalizer

/**
 * Platform-independent snapshot of an editable node, so detection logic can be unit-tested
 * without Robolectric. Built from an `AccessibilityNodeInfo` in the service layer.
 */
data class FieldInfo(
    val isEditable: Boolean,
    val isPassword: Boolean = false,
    /** Raw `android.text.InputType` bits, 0 when unknown. */
    val inputType: Int = 0,
    val hint: String? = null,
    val viewIdResourceName: String? = null,
    val contentDescription: String? = null,
)

/**
 * Decides whether the Handy button may be shown for a field. Errs on the side of hiding:
 * a false positive costs one missing button, a false negative could leak a secret into a
 * transcript or clipboard.
 */
object SensitiveFieldDetector {

    // Mirrors android.text.InputType so this file stays free of Android imports.
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    /** Matched against whole tokens, so "pin" does not match "shipping". */
    private val SENSITIVE_TOKENS = setOf(
        "password", "passwd", "pwd", "passcode", "passphrase", "pass",
        "motdepasse", "mdp",
        "pin", "nip",
        "otp", "totp", "2fa", "mfa",
        "code", "cvv", "cvc", "cvv2", "csc",
        "carte", "card", "ccnumber", "cardnumber",
        "secret",
    )

    /** Matched as substrings of the normalized text (covers multi-word and glued forms). */
    private val SENSITIVE_PHRASES = listOf(
        "password", "passcode", "mot de passe", "motdepasse", "one time", "verification code",
        "code de verification", "security code", "card number", "numero de carte",
    )

    fun isSensitive(field: FieldInfo): Boolean {
        if (field.isPassword) return true
        if (isPasswordInputType(field.inputType)) return true
        return listOf(field.hint, field.viewIdResourceName, field.contentDescription)
            .any { it != null && containsSensitiveKeyword(it) }
    }

    /** True when the button can be offered for this field. */
    fun isEligible(field: FieldInfo): Boolean = field.isEditable && !isSensitive(field)

    fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return when (cls) {
            TYPE_CLASS_TEXT -> variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD
            TYPE_CLASS_NUMBER -> variation == TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    fun containsSensitiveKeyword(raw: String): Boolean {
        // View ids look like "com.example:id/login_password": only the part after "id/" matters.
        val text = raw.substringAfter(":id/")
        val normalized = normalize(text)
        if (SENSITIVE_PHRASES.any { normalized.contains(it) }) return true
        return tokenize(text).any { it in SENSITIVE_TOKENS }
    }

    /** Lowercase, strip accents, collapse separators to single spaces. */
    internal fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    /** Splits camelCase, snake_case, kebab-case and spaces into lowercase tokens. */
    internal fun tokenize(text: String): List<String> {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        val camelSplit = noAccents
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        return camelSplit.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
    }
}
