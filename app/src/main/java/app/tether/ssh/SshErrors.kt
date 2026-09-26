package app.tether.ssh

import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** An SSH failure whose [message] is a finished, human sentence safe to show as-is. */
class SshFailure(message: String, cause: Throwable? = null) : IOException(message, cause)

internal object SshErrors {
    const val UNREACHABLE = "Couldn't reach host — check the address and network"
    const val AUTH_FAILED = "Authentication failed"
    const val TIMED_OUT = "Connection timed out"
    const val HOST_KEY_REJECTED = "Host key rejected"
    const val CONNECTION_LOST = "Connection lost"
    const val TOO_MANY_SESSIONS = "The machine refused another session — too many are open"

    /** Maps any throwable from sshj / the socket layer to one short sentence. */
    fun humanize(t: Throwable, hostKeyRejected: Boolean = false): String {
        if (hostKeyRejected) return HOST_KEY_REJECTED
        val chain = generateSequence(t) { it.cause.takeIf { c -> c !== it } }.take(12).toList()
        chain.firstOrNull { it is SshFailure }?.let { return it.message ?: CONNECTION_LOST }
        return when {
            chain.any { it is UserAuthException } -> AUTH_FAILED
            chain.any { it is SSHException && it.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE } -> HOST_KEY_REJECTED
            chain.any { it is UnknownHostException } -> UNREACHABLE
            chain.any { it is SocketTimeoutException || it is TimeoutException } -> TIMED_OUT
            chain.any { it is ConnectException || it is NoRouteToHostException || it is PortUnreachableException } -> {
                val msg = chain.firstNotNullOfOrNull { it.message }?.lowercase().orEmpty()
                if ("timed out" in msg || "timeout" in msg) TIMED_OUT else UNREACHABLE
            }
            chain.any { it is OpenFailException } -> TOO_MANY_SESSIONS
            chain.any { it is InterruptedIOException } -> TIMED_OUT
            chain.any { it is EOFException || it is SocketException } -> CONNECTION_LOST
            chain.any { (it.message ?: "").contains("timeout", ignoreCase = true) || (it.message ?: "").contains("timed out", ignoreCase = true) } -> TIMED_OUT
            chain.any { it is SSHException && it.disconnectReason == DisconnectReason.CONNECTION_LOST } -> CONNECTION_LOST
            else -> t.message?.takeIf { it.isNotBlank() }?.let { tidy(it) } ?: "Connection failed"
        }
    }

    private fun tidy(raw: String): String {
        val s = raw.substringAfterLast("Exception: ").trim().take(160)
        return s.replaceFirstChar { it.uppercase() }
    }
}
