package app.tether.data

import android.content.Context
import app.tether.core.AuthMethod
import app.tether.core.Connection
import app.tether.core.ConnectionRepository
import app.tether.core.SecretKeys
import app.tether.core.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * Machines, stored as JSON in `filesDir/connections.json`. Loaded synchronously at construction so
 * the very first frame knows whether any machine exists. Passwords never touch the JSON: they live
 * in the [SecretStore] under [SecretKeys.password].
 */
class FileConnectionRepository(context: Context, private val secrets: SecretStore) : ConnectionRepository {

    private val file = File(context.applicationContext.filesDir, "connections.json")
    private val serializer = ListSerializer(Connection.serializer())
    private val mutex = Mutex()
    private val state = MutableStateFlow(JsonFiles.read(file, serializer, emptyList()))

    override val connections: StateFlow<List<Connection>> = state.asStateFlow()

    override fun get(id: String): Connection? = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(connection: Connection, password: String?) {
        val clean = connection.copy(
            name = connection.name.trim().ifEmpty { connection.host.trim() },
            host = connection.host.trim(),
            username = connection.username.trim(),
            claudePath = connection.claudePath?.trim()?.takeIf { it.isNotEmpty() },
            defaultCwd = connection.defaultCwd?.trim()?.takeIf { it.isNotEmpty() },
        )
        withContext(Dispatchers.IO) {
            if (password != null) {
                secrets.put(SecretKeys.password(clean.id), password)
            } else if (clean.auth is AuthMethod.Key) {
                // Switched to key auth: the password is no longer needed, don't keep it around.
                secrets.remove(SecretKeys.password(clean.id))
            }
        }
        mutate { list ->
            val idx = list.indexOfFirst { it.id == clean.id }
            if (idx >= 0) list.toMutableList().also { it[idx] = clean } else list + clean
        }
    }

    override suspend fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
        withContext(Dispatchers.IO) { secrets.remove(SecretKeys.password(id)) }
    }

    override suspend fun markConnected(id: String, hostname: String?, claudeVersion: String?) {
        mutate { list ->
            list.map {
                if (it.id != id) it
                else it.copy(
                    lastConnectedAt = System.currentTimeMillis(),
                    lastHostname = hostname ?: it.lastHostname,
                    lastClaudeVersion = claudeVersion ?: it.lastClaudeVersion,
                )
            }
        }
    }

    override fun newId(): String = UUID.randomUUID().toString()

    /** Used by the debug seeder: insert/replace without touching secrets. */
    internal fun upsertBlocking(connection: Connection) {
        synchronized(this) {
            val list = state.value
            val idx = list.indexOfFirst { it.id == connection.id }
            val next = if (idx >= 0) list.toMutableList().also { it[idx] = connection } else list + connection
            JsonFiles.write(file, serializer, next)
            state.value = next
        }
    }

    private suspend fun mutate(transform: (List<Connection>) -> List<Connection>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                synchronized(this@FileConnectionRepository) {
                    val next = transform(state.value)
                    if (next != state.value) {
                        JsonFiles.write(file, serializer, next)
                        state.value = next
                    }
                }
            }
        }
    }
}
