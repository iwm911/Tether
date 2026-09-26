package app.tether.data

import android.content.Context
import app.tether.core.KnownHost
import app.tether.core.KnownHostsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Trusted host keys (trust-on-first-use), one per host:port, in `filesDir/known_hosts.json`.
 * Called from sshj's transport thread during key exchange, hence synchronous and thread-safe.
 */
class FileKnownHostsStore(context: Context) : KnownHostsStore {

    private val file = File(context.applicationContext.filesDir, "known_hosts.json")
    private val serializer = ListSerializer(KnownHost.serializer())
    private val lock = Any()
    private val state = MutableStateFlow(JsonFiles.read(file, serializer, emptyList()).sortedBy { it.host })

    override val all: StateFlow<List<KnownHost>> = state.asStateFlow()

    override fun get(host: String, port: Int): KnownHost? {
        val h = normalize(host)
        return state.value.firstOrNull { normalize(it.host) == h && it.port == port }
    }

    override fun put(entry: KnownHost) {
        synchronized(lock) {
            val h = normalize(entry.host)
            val next = (state.value.filterNot { normalize(it.host) == h && it.port == entry.port } + entry.copy(host = h))
                .sortedWith(compareBy({ it.host }, { it.port }))
            JsonFiles.write(file, serializer, next)
            state.value = next
        }
    }

    override fun remove(host: String, port: Int) {
        synchronized(lock) {
            val h = normalize(host)
            val next = state.value.filterNot { normalize(it.host) == h && it.port == port }
            if (next.size != state.value.size) {
                JsonFiles.write(file, serializer, next)
                state.value = next
            }
        }
    }

    private fun normalize(host: String) = host.trim().trimStart('[').trimEnd(']').lowercase()
}
