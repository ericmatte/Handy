package computer.handy.android.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Anthropic API key encrypted with an AES-GCM key that never leaves the Android
 * Keystore. The ciphertext lives in app-private preferences (backups are disabled).
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var apiKey: String
        get() = decrypt(prefs.getString(KEY_API_KEY, null)) ?: ""
        set(value) {
            val trimmed = value.trim()
            prefs.edit().apply {
                if (trimmed.isEmpty()) remove(KEY_API_KEY) else putString(KEY_API_KEY, encrypt(trimmed))
            }.apply()
        }

    val hasApiKey: Boolean get() = apiKey.isNotEmpty()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // Keystore key lost (e.g. device restore): the user has to enter the key again.
            Log.w("HandySecrets", "Could not decrypt the stored API key", e)
            null
        }
    }

    companion object {
        private const val FILE = "handy_secrets"
        private const val KEY_API_KEY = "anthropic_api_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "handy_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}
