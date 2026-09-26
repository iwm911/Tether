package app.tether.data

import android.content.Context
import android.os.Build
import app.tether.core.KeyRepository
import app.tether.core.SecretKeys
import app.tether.core.SecretStore
import app.tether.core.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * SSH keys. Metadata (name, public line, fingerprint) in `filesDir/keys.json`; the private half —
 * always stored as text sshj can load (OpenSSH v1 / PEM / PuTTY) — and any passphrase live
 * encrypted in the [SecretStore].
 */
class SecureKeyRepository(context: Context, private val secrets: SecretStore) : KeyRepository {

    private val file = File(context.applicationContext.filesDir, "keys.json")
    private val serializer = ListSerializer(SshKey.serializer())
    private val lock = Any()
    private val state = MutableStateFlow(JsonFiles.read(file, serializer, emptyList()))

    override val keys: StateFlow<List<SshKey>> = state.asStateFlow()

    override fun get(id: String): SshKey? = state.value.firstOrNull { it.id == id }

    override suspend fun generateEd25519(name: String): SshKey = withContext(Dispatchers.Default) {
        val generated = SshKeyCodec.generateEd25519(deviceComment())
        val key = SshKey(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Tether key" },
            algorithm = "ed25519",
            publicKey = generated.publicKeyLine,
            fingerprint = generated.fingerprint,
            createdAt = System.currentTimeMillis(),
            imported = false,
            hasPassphrase = false,
        )
        withContext(Dispatchers.IO) { store(key, generated.privateKeyPem, null) }
        key
    }

    override suspend fun import(name: String, privateKeyText: String, passphrase: String?): SshKey =
        withContext(Dispatchers.IO) { importInternal(UUID.randomUUID().toString(), name, privateKeyText, passphrase) }

    /**
     * Imports keeping a caller-chosen [id] (debug seeder: connections in seed.json reference keys by
     * id). Blocking; call off the main thread where possible.
     */
    internal fun importWithId(id: String, name: String, privateKeyText: String, passphrase: String?): SshKey =
        importInternal(id, name, privateKeyText, passphrase)

    private fun importInternal(id: String, name: String, privateKeyText: String, passphrase: String?): SshKey {
        val displayName = name.trim().ifEmpty { "Imported key" }
        val parsed = SshKeyCodec.parsePrivateKey(privateKeyText, passphrase, commentFor(displayName))
        state.value.firstOrNull { it.fingerprint == parsed.fingerprint && it.id != id }?.let { existing ->
            throw IllegalArgumentException("You already have this key as “${existing.name}”")
        }
        val key = SshKey(
            id = id,
            name = displayName,
            algorithm = parsed.algorithm,
            publicKey = parsed.publicKeyLine,
            fingerprint = parsed.fingerprint,
            createdAt = System.currentTimeMillis(),
            imported = true,
            hasPassphrase = parsed.encrypted,
        )
        store(key, parsed.normalizedText, passphrase?.takeIf { parsed.encrypted && it.isNotEmpty() })
        return key
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val next = state.value.filterNot { it.id == id }
            JsonFiles.write(file, serializer, next)
            state.value = next
        }
        secrets.remove(SecretKeys.privateKey(id))
        secrets.remove(SecretKeys.keyPassphrase(id))
    }

    override fun privateKey(id: String): String? = secrets.get(SecretKeys.privateKey(id))

    override fun passphrase(id: String): String? = secrets.get(SecretKeys.keyPassphrase(id))

    private fun store(key: SshKey, privateText: String, passphrase: String?) {
        // Secrets first: metadata without its private half would be a broken key in the UI.
        secrets.put(SecretKeys.privateKey(key.id), privateText)
        if (passphrase != null) secrets.put(SecretKeys.keyPassphrase(key.id), passphrase)
        else secrets.remove(SecretKeys.keyPassphrase(key.id))
        synchronized(lock) {
            val list = state.value
            val idx = list.indexOfFirst { it.id == key.id }
            val next = if (idx >= 0) list.toMutableList().also { it[idx] = key } else list + key
            JsonFiles.write(file, serializer, next)
            state.value = next
        }
    }

    private fun commentFor(name: String): String =
        name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._@-]+"), "-").trim('-').ifEmpty { "tether" }

    private fun deviceComment(): String {
        val model = (Build.MODEL ?: "android").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "android" }
        return "tether@$model"
    }
}
