package app.tether.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import app.tether.core.SecretStore
import java.security.InvalidKeyException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (passwords, private keys, passphrases) encrypted at rest with an AES-256-GCM key
 * that never leaves the Android Keystore. Each value is stored as `base64(iv | ciphertext+tag)` in a
 * private SharedPreferences file, written synchronously (`commit`) so it survives process death.
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val lock = Any()

    @Volatile private var cachedKey: SecretKey? = null

    override fun put(key: String, value: String) {
        synchronized(lock) {
            val cipher = initCipher(Cipher.ENCRYPT_MODE, null)
            val iv = cipher.iv
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, blob, 0, iv.size)
            System.arraycopy(ct, 0, blob, iv.size, ct.size)
            val ok = prefs.edit().putString(key, Base64.getEncoder().encodeToString(blob)).commit()
            if (!ok) throw IllegalStateException("Couldn't save secret to disk")
        }
    }

    override fun get(key: String): String? {
        synchronized(lock) {
            val stored = prefs.getString(key, null) ?: return null
            return try {
                val blob = Base64.getDecoder().decode(stored)
                if (blob.size <= IV_BYTES) return null
                val cipher = initCipher(Cipher.DECRYPT_MODE, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
                String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
            } catch (e: Exception) {
                // Key invalidated (e.g. keystore reset) or corrupted entry: the value is unrecoverable.
                Log.w(TAG, "Dropping unreadable secret '$key'", e)
                prefs.edit().remove(key).commit()
                null
            }
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            prefs.edit().remove(key).commit()
        }
    }

    /**
     * JCA defers provider selection to init() and skips providers that reject the key, so the
     * keystore provider normally wins even with BouncyCastle installed at position 1. Some OEM
     * builds are less forgiving, so fall back to asking the keystore's cipher provider directly.
     */
    private fun initCipher(mode: Int, spec: GCMParameterSpec?): Cipher {
        val key = secretKey()
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { if (spec == null) init(mode, key) else init(mode, key, spec) }
        } catch (e: InvalidKeyException) {
            Cipher.getInstance(TRANSFORMATION, KEYSTORE_CIPHER_PROVIDER).apply { if (spec == null) init(mode, key) else init(mode, key, spec) }
        }
    }

    private fun secretKey(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = try {
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: Exception) {
            Log.w(TAG, "Keystore entry unreadable, regenerating", e)
            runCatching { ks.deleteEntry(ALIAS) }
            null
        }
        val key = existing ?: generateKey()
        cachedKey = key
        return key
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "TetherSecrets"
        const val PREFS = "tether_secrets"
        const val ALIAS = "tether_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_CIPHER_PROVIDER = "AndroidKeyStoreBCWorkaround"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
