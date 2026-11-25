package app.tether.remote

import app.tether.core.FileTooBig
import app.tether.core.RemoteFiles
import app.tether.core.SshManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** [RemoteFiles] over the machine's SFTP link; copies live in [dir] (the app's cache), one per path. */
class SshRemoteFiles(private val ssh: SshManager, private val connectionId: String, private val dir: File) : RemoteFiles {
    private val locks = ConcurrentHashMap<String, Mutex>()

    override suspend fun fetch(path: String, maxBytes: Long, fresh: Boolean): File {
        val name = cacheName(connectionId, path)
        val dest = File(dir, name)
        return locks.getOrPut(name) { Mutex() }.withLock {
            if (!fresh && dest.isFile) return@withLock dest
            dir.mkdirs()
            try {
                ssh.download(connectionId, path, dest) { _, total -> if (total > maxBytes) throw FileTooBig(total, maxBytes) }
            } catch (e: Throwable) {
                File(dir, "$name.part").delete()
                throw e
            }
            dest
        }
    }

    companion object {
        internal fun cacheName(connectionId: String, path: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$connectionId\n$path".toByteArray())
            val hex = digest.joinToString("") { "%02x".format(it) }.take(32)
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
            return if (ext.isEmpty()) hex else "$hex.$ext"
        }
    }
}
