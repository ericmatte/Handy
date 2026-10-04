package computer.handy.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveFieldDetectorTest {

    // android.text.InputType values, spelled out so the test documents what is being covered.
    private val text = 0x1
    private val number = 0x2
    private val textPassword = text or 0x80
    private val textVisiblePassword = text or 0x90
    private val textWebPassword = text or 0xe0
    private val numberPassword = number or 0x10
    private val textEmail = text or 0x20
    private val textMultiLine = text or 0x20000

    private fun field(
        inputType: Int = text,
        isPassword: Boolean = false,
        hint: String? = null,
        id: String? = null,
        description: String? = null,
        editable: Boolean = true,
    ) = FieldInfo(editable, isPassword, inputType, hint, id, description)

    @Test
    fun `plain text fields are eligible`() {
        assertTrue(SensitiveFieldDetector.isEligible(field()))
        assertTrue(SensitiveFieldDetector.isEligible(field(inputType = textMultiLine, hint = "Message")))
        assertTrue(SensitiveFieldDetector.isEligible(field(inputType = textEmail, hint = "To")))
        assertTrue(SensitiveFieldDetector.isEligible(field(hint = "Écrire un message", id = "com.google.android.apps.messaging:id/compose_message_text")))
    }

    @Test
    fun `non editable nodes are not eligible`() {
        assertFalse(SensitiveFieldDetector.isEligible(field(editable = false)))
    }

    @Test
    fun `isPassword flag hides the button`() {
        assertTrue(SensitiveFieldDetector.isSensitive(field(isPassword = true)))
    }

    @Test
    fun `password input types are sensitive`() {
        listOf(textPassword, textVisiblePassword, textWebPassword, numberPassword).forEach {
            assertTrue("inputType=0x${it.toString(16)}", SensitiveFieldDetector.isSensitive(field(inputType = it)))
        }
    }

    @Test
    fun `password variation bits under another class are not mistaken`() {
        // 0x80 under TYPE_CLASS_NUMBER is not a password variation.
        assertFalse(SensitiveFieldDetector.isPasswordInputType(number or 0x80))
        assertFalse(SensitiveFieldDetector.isPasswordInputType(textEmail))
        assertFalse(SensitiveFieldDetector.isPasswordInputType(0))
    }

    @Test
    fun `hint keywords in English and French are sensitive`() {
        listOf(
            "Password", "Enter your password", "Mot de passe", "Mot de passe oublié ?",
            "PIN", "NIP", "Enter OTP", "Verification code", "Code de vérification",
            "CVV", "Numéro de carte", "Card number", "Passcode",
        ).forEach { assertTrue(it, SensitiveFieldDetector.isSensitive(field(hint = it))) }
    }

    @Test
    fun `view id keywords are sensitive`() {
        listOf(
            "com.bank:id/password",
            "com.app:id/login_pwd",
            "com.app:id/pinEntry",
            "com.app:id/otp_input",
            "com.app:id/cvvField",
            "com.app:id/smsCode",
            "com.app:id/carte_numero",
            "com.app:id/et_passcode",
        ).forEach { assertTrue(it, SensitiveFieldDetector.isSensitive(field(id = it))) }
    }

    @Test
    fun `keywords inside unrelated words do not trigger`() {
        listOf(
            "Shipping address" to null,
            "Typing…" to "com.app:id/typing_indicator",
            "Spinner" to "com.app:id/spinner",
            "Search" to "com.app:id/search_src_text",
            "Opinion" to "com.app:id/comment",
            "Encoder" to null,
        ).forEach { (hint, id) ->
            assertFalse("$hint / $id", SensitiveFieldDetector.isSensitive(field(hint = hint, id = id)))
        }
    }

    @Test
    fun `content description is checked too`() {
        assertTrue(SensitiveFieldDetector.isSensitive(field(description = "One-time code")))
    }

    @Test
    fun `tokenizer splits camelCase snake_case and accents`() {
        assertEquals(listOf("login", "password", "field"), SensitiveFieldDetector.tokenize("loginPasswordField"))
        assertEquals(listOf("numero", "de", "carte"), SensitiveFieldDetector.tokenize("numéro_de-carte"))
        assertEquals(listOf("otp", "code"), SensitiveFieldDetector.tokenize("OTPCode"))
    }
}
