package app.tether.data

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import app.tether.core.SecretStore
import java.security.InvalidKeyException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (passwords, private keys, passphrases) encrypted at rest with an AES-256-GCM key
 * that never leaves the Android Keystore. Each value is stored as `base64(iv | ciphertext+tag)` in a
 * private SharedPreferences file, written synchronously (`commit`) so it survives process death.
 *
 * With [requireUnlock] on (Android 9+), secrets live under a second Keystore key created with
 * `setUnlockedDeviceRequired`: they can't be decrypted at all while the phone is locked, so even
 * code running as Tether can't read them then. Connections already open stay up; new ones wait
 * until the phone is unlocked.
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val lock = Any()

    private val cachedKeys = HashMap<String, SecretKey>()

    @Volatile private var lastReadBlockedByLock = false

    override val requireUnlockSupported: Boolean get() = Build.VERSION.SDK_INT >= 28

    override val requireUnlock: Boolean get() = prefs.getBoolean(MODE_KEY, false)

    override fun put(key: String, value: String) {
        require(key != MODE_KEY)
        synchronized(lock) {
            val ok = prefs.edit().putString(key, encrypt(value, requireUnlock)).commit()
            if (!ok) throw IllegalStateException("Couldn't save secret to disk")
        }
    }

    override fun get(key: String): String? {
        synchronized(lock) {
            val stored = prefs.getString(key, null) ?: return null
            return try {
                decrypt(stored, requireUnlock).also { lastReadBlockedByLock = false }
            } catch (e: AEADBadTagException) {
                drop(key, e) // encrypted with a key that no longer exists, or tampered with
            } catch (e: KeyPermanentlyInvalidatedException) {
                drop(key, e)
            } catch (e: IllegalArgumentException) {
                drop(key, e) // not valid base64: corrupted on disk
            } catch (e: Exception) {
                // Keystore hiccups happen (daemon restart, right after an OTA), and with requireUnlock the
                // key refuses to work while the phone is locked. Never destroy a secret over either:
                // report it as unavailable this time and try again on the next read.
                lastReadBlockedByLock = requireUnlock && deviceLocked()
                Log.w(TAG, "Secret '$key' temporarily unreadable" + if (lastReadBlockedByLock) " (phone locked)" else "", e)
                null
            }
        }
    }

    override fun readBlockedByDeviceLock(): Boolean = lastReadBlockedByLock

    override fun remove(key: String) {
        synchronized(lock) {
            prefs.edit().remove(key).commit()
        }
    }

    /**
     * Re-encrypts every secret under the key for [on]. All-or-nothing: if any secret can't be read
     * right now, nothing changes and this throws. Must be called while the phone is unlocked.
     */
    override fun setRequireUnlock(on: Boolean) {
        synchronized(lock) {
            if (on == requireUnlock) return
            check(!on || requireUnlockSupported) { "Needs Android 9 or newer" }
            val from = requireUnlock
            val plain = prefs.all.filterKeys { it != MODE_KEY }.mapValues { (_, v) -> decrypt(v as String, from) }
            val edit = prefs.edit()
            plain.forEach { (k, v) -> edit.putString(k, encrypt(v, on)) }
            edit.putBoolean(MODE_KEY, on)
            if (!edit.commit()) throw IllegalStateException("Couldn't save secrets to disk")
            // The old key no longer protects anything.
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(aliasFor(from))
                cachedKeys.remove(aliasFor(from))
            }
        }
    }

    /** Only for values that can never be decrypted again. */
    private fun drop(key: String, e: Exception): String? {
        Log.w(TAG, "Dropping unrecoverable secret '$key'", e)
        prefs.edit().remove(key).commit()
        return null
    }

    private fun encrypt(value: String, strict: Boolean): String {
        val cipher = initCipher(Cipher.ENCRYPT_MODE, null, strict)
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(ct, 0, blob, iv.size, ct.size)
        return Base64.getEncoder().encodeToString(blob)
    }

    private fun decrypt(stored: String, strict: Boolean): String {
        val blob = Base64.getDecoder().decode(stored)
        if (blob.size <= IV_BYTES) throw IllegalArgumentException("Secret too short")
        val cipher = initCipher(Cipher.DECRYPT_MODE, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES), strict)
        return String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun deviceLocked(): Boolean = app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    /**
     * JCA defers provider selection to init() and skips providers that reject the key, so the
     * keystore provider normally wins even with BouncyCastle installed at position 1. Some OEM
     * builds are less forgiving, so fall back to asking the keystore's cipher provider directly.
     */
    private fun initCipher(mode: Int, spec: GCMParameterSpec?, strict: Boolean): Cipher {
        val key = secretKey(strict)
        return try {
            Cipher.getInstance(TRANSFORMATION).apply { if (spec == null) init(mode, key) else init(mode, key, spec) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw e
        } catch (e: InvalidKeyException) {
            Cipher.getInstance(TRANSFORMATION, KEYSTORE_CIPHER_PROVIDER).apply { if (spec == null) init(mode, key) else init(mode, key, spec) }
        }
    }

    private fun aliasFor(strict: Boolean) = if (strict) ALIAS_UNLOCKED else ALIAS

    private fun secretKey(strict: Boolean): SecretKey {
        val alias = aliasFor(strict)
        cachedKeys[alias]?.let { return it }
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        // Generate only when there is no key at all. A key that exists but can't be read right now
        // is left alone (the exception propagates): deleting it would orphan every stored secret.
        val key = if (!ks.containsAlias(alias)) generateKey(alias, strict)
        else (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Keystore entry '$alias' isn't a secret key")
        cachedKeys[alias] = key
        return key
    }

    private fun generateKey(alias: String, strict: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strict && Build.VERSION.SDK_INT >= 28) setUnlockedDeviceRequired(true) }
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "TetherSecrets"
        const val PREFS = "tether_secrets"
        const val MODE_KEY = "__require_unlock"
        const val ALIAS = "tether_secrets"
        const val ALIAS_UNLOCKED = "tether_secrets_unlocked"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_CIPHER_PROVIDER = "AndroidKeyStoreBCWorkaround"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
