package app.tether.ssh

import android.content.Context
import android.util.Log
import app.tether.core.AuthMethod
import app.tether.core.Connection
import app.tether.core.ConnectionRepository
import app.tether.core.ExecResult
import app.tether.core.HostKeyPrompt
import app.tether.core.HostKeyPromptBus
import app.tether.core.KeyRepository
import app.tether.core.KnownHost
import app.tether.core.KnownHostsStore
import app.tether.core.LinkState
import app.tether.core.ProbeResult
import app.tether.core.SecretKeys
import app.tether.core.SecretStore
import app.tether.core.SshManager
import app.tether.core.TestProgress
import app.tether.core.TestStep
import app.tether.data.SshKeyCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.xfer.InMemorySourceFile
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.PublicKey
import java.security.Security
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [SshManager] on sshj. One pooled, keep-alive'd [SSHClient] per machine, created lazily under a
 * per-machine mutex and replaced transparently when it dies. Host keys use trust-on-first-use via
 * [HostKeyPromptBus]. All blocking I/O runs on [Dispatchers.IO]; blocking reads are interruptible
 * so cancelling a caller really stops waiting.
 */
class SshjManager(
    context: Context,
    private val connections: ConnectionRepository,
    private val keys: KeyRepository,
    private val secrets: SecretStore,
    private val knownHosts: KnownHostsStore,
    private val hostKeyPrompts: HostKeyPromptBus,
    private val scope: CoroutineScope,
) : SshManager {

    /** What a pooled client was authenticated for; if the saved machine changes, reconnect. */
    private data class Endpoint(val host: String, val port: Int, val username: String, val auth: AuthMethod)

    private class Pooled(val client: SSHClient, val endpoint: Endpoint, val since: Long)

    private val pool = ConcurrentHashMap<String, Pooled>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val hostLocks = ConcurrentHashMap<String, Any>()
    private val stateFlow = MutableStateFlow<Map<String, LinkState>>(emptyMap())

    override val states: StateFlow<Map<String, LinkState>> = stateFlow.asStateFlow()

    private val epochFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val linkEpochs: StateFlow<Map<String, Long>> = epochFlow.asStateFlow()

    private val config: DefaultConfig by lazy { sshConfig() }

    init {
        // Machines deleted from the repository drop their pooled connection.
        scope.launch {
            connections.connections.collect { list ->
                val ids = list.mapTo(HashSet()) { it.id }
                pool.keys.filter { it !in ids }.forEach { id -> runCatching { disconnect(id) } }
            }
        }
        // Refresh the latency figure of live links; doubles as an extra liveness probe.
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(LATENCY_REFRESH_MS)
                for ((id, p) in pool.entries.toList()) {
                    if (!alive(p.client)) continue
                    val latency = measureLatency(p.client) ?: continue
                    if (pool[id] === p) setState(id, LinkState.Connected(p.since, latency))
                }
            }
        }
    }

    // ───────────────────────────── public API ─────────────────────────────

    override suspend fun exec(connectionId: String, command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult {
        val (client, session) = openSession(connectionId)
        return try {
            runCommand(session, command, stdin, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw failureAfterDrop(connectionId, client, e)
        } finally {
            closeQuietly(session)
        }
    }

    override fun streamLines(connectionId: String, command: String): Flow<String> = flow {
        val (client, session) = openSession(connectionId)
        try {
            coroutineScope {
                val cmd = io { session.exec(command) }
                // stderr shares the channel window: it must be drained or stdout would stall.
                val errDrain = launch(Dispatchers.IO) { runCatching { io { discard(cmd.errorStream) } } }
                val reader = cmd.inputStream.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line = io { reader.readLine() } ?: break
                    emit(line)
                }
                errDrain.cancel()
            }
            if (!alive(client)) throw SshFailure(SshErrors.CONNECTION_LOST)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw failureAfterDrop(connectionId, client, e)
        } finally {
            closeQuietly(session)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun upload(connectionId: String, remotePath: String, bytes: ByteArray, mode: Int) {
        withContext(Dispatchers.IO) {
            val client = pooledClient(connectionId)
            try {
                io { sftpPut(client, remotePath, bytes, mode) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (alive(client)) throw SshFailure("Couldn't write $remotePath: ${e.message ?: "SFTP error"}", e)
                // The link died underneath us: reconnect once and retry the (idempotent) write.
                invalidate(connectionId, client)
                val fresh = pooledClient(connectionId)
                try {
                    io { sftpPut(fresh, remotePath, bytes, mode) }
                } catch (e2: IOException) {
                    throw failureAfterDrop(connectionId, fresh, e2)
                }
            }
        }
    }

    override suspend fun download(connectionId: String, remotePath: String, dest: java.io.File, onProgress: (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            var client = pooledClient(connectionId)
            try {
                io { sftpGet(client, remotePath, dest, onProgress) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (alive(client)) throw SshFailure("Couldn't read $remotePath: ${e.message ?: "SFTP error"}", e)
                invalidate(connectionId, client)
                client = pooledClient(connectionId)
                try {
                    io { sftpGet(client, remotePath, dest, onProgress) }
                } catch (e2: IOException) {
                    throw failureAfterDrop(connectionId, client, e2)
                }
            }
        }
    }

    private fun sftpGet(client: SSHClient, remotePath: String, dest: java.io.File, onProgress: (Long, Long) -> Unit) {
        client.newSFTPClient().use { sftp ->
            val home = sftp.canonicalize(".").trimEnd('/')
            val path = if (remotePath.startsWith("~/")) "$home/${remotePath.removePrefix("~/")}" else remotePath
            sftp.open(path).use { rf ->
                val total = rf.length()
                val tmp = java.io.File(dest.parentFile, dest.name + ".part")
                rf.RemoteFileInputStream(0).use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport >= 128 * 1024 || done == total) {
                                lastReport = done
                                onProgress(done, total)
                            }
                        }
                    }
                }
                if (!tmp.renameTo(dest)) {
                    dest.delete()
                    if (!tmp.renameTo(dest)) throw IOException("Couldn't save the download")
                }
            }
        }
    }

    override suspend fun test(
        connection: Connection,
        password: String?,
        onProgress: (TestProgress) -> Unit,
    ): Result<ProbeResult> = withContext(Dispatchers.IO) {
        var step = TestStep.RESOLVE
        var client: SSHClient? = null
        var verifier: TofuVerifier? = null
        fun report(s: TestStep, done: Boolean, error: String? = null, detail: String? = null) =
            runCatching { onProgress(TestProgress(s, done, error, detail)) }

        try {
            report(TestStep.RESOLVE, false, detail = "${connection.host}:${connection.port}")
            val v = TofuVerifier(connection.host, connection.port) {
                // Called from the transport thread once the server presented its key.
                if (step == TestStep.RESOLVE) {
                    step = TestStep.HOST_KEY
                    report(TestStep.RESOLVE, true, detail = "${connection.host}:${connection.port}")
                    report(TestStep.HOST_KEY, false)
                }
            }
            verifier = v
            val c = newClient(v)
            client = c
            io { c.connect(connection.host, connection.port) }
            if (step == TestStep.RESOLVE) {
                report(TestStep.RESOLVE, true)
                report(TestStep.HOST_KEY, false)
            }
            report(TestStep.HOST_KEY, true, detail = v.acceptedFingerprint?.let { "${v.acceptedKeyType ?: "Key"} · $it" })

            step = TestStep.AUTH
            report(TestStep.AUTH, false)
            io { authenticate(c, connection, password) }
            c.transport.timeoutMs = OPERATION_TIMEOUT_MS
            report(
                TestStep.AUTH, true,
                detail = when (val a = connection.auth) {
                    is AuthMethod.Key -> "Signed in as ${connection.username} with ${keys.get(a.keyId)?.name ?: "key"}"
                    AuthMethod.Password -> "Signed in as ${connection.username} with password"
                },
            )

            step = TestStep.CLAUDE
            report(TestStep.CLAUDE, false)
            val probe = probe(c, connection)
            if (probe.problem != null) {
                report(TestStep.CLAUDE, true, error = probe.problem, detail = probeHint(probe))
            } else {
                report(TestStep.CLAUDE, true, detail = "Claude Code ${probe.claudeVersion ?: ""} · ${probe.claudePath}".trim())
                step = TestStep.READY
                report(
                    TestStep.READY, true,
                    detail = "Claude Code ${probe.claudeVersion ?: ""} found on ${probe.hostname}".replace("  ", " "),
                )
            }
            if (connections.get(connection.id) != null) {
                runCatching { connections.markConnected(connection.id, probe.hostname, probe.claudeVersion) }
            }
            Result.success(probe)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val rejected = verifier?.rejected == true
            val failedStep = if (rejected) TestStep.HOST_KEY else step
            val message = SshErrors.humanize(e, rejected)
            report(failedStep, true, error = message, detail = failureHint(failedStep, connection, message))
            Result.failure(SshFailure(message, e))
        } finally {
            client?.let { c -> withContext(NonCancellable) { closeQuietly(c) } }
        }
    }

    override suspend fun installPublicKey(connection: Connection, password: String?, publicKey: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val line = publicKey.trim().lineSequence().firstOrNull()?.trim().orEmpty()
            if (!PUBLIC_KEY_LINE.matches(line)) {
                return@withContext Result.failure(IllegalArgumentException("That doesn't look like an SSH public key"))
            }
            // A one-off password (from the install dialog) means: authenticate with it this once.
            val authConnection = if (password != null) connection.copy(auth = AuthMethod.Password) else connection
            var client: SSHClient? = null
            var verifier: TofuVerifier? = null
            try {
                val v = TofuVerifier(connection.host, connection.port) {}
                verifier = v
                val c = newClient(v)
                client = c
                io { c.connect(connection.host, connection.port) }
                io { authenticate(c, authConnection, password) }
                c.transport.timeoutMs = OPERATION_TIMEOUT_MS
                val session = io { c.startSession() }
                val result = try {
                    runCommand(session, "sh -c '$INSTALL_KEY_SCRIPT'", (line + "\n").toByteArray(Charsets.UTF_8), 20_000)
                } finally {
                    closeQuietly(session)
                }
                if (!result.ok) {
                    val why = result.stderr.trim().lineSequence().lastOrNull()?.takeIf { it.isNotBlank() }
                    Result.failure(SshFailure("Couldn't update ~/.ssh/authorized_keys" + (why?.let { ": $it" } ?: "")))
                } else {
                    Result.success(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(SshFailure(SshErrors.humanize(e, verifier?.rejected == true), e))
            } finally {
                client?.let { c -> withContext(NonCancellable) { closeQuietly(c) } }
            }
        }

    override suspend fun disconnect(connectionId: String) {
        lockFor(connectionId).withLock {
            val p = pool.remove(connectionId)
            if (p != null) withContext(Dispatchers.IO + NonCancellable) { closeQuietly(p.client) }
            stateFlow.update { it - connectionId }
        }
    }

    override suspend fun disconnectAll() {
        (pool.keys.toList() + stateFlow.value.keys).distinct().forEach { disconnect(it) }
    }

    override suspend fun revalidate() = coroutineScope {
        for ((id, p) in pool.entries.toList()) {
            launch(Dispatchers.IO) {
                val healthy = alive(p.client) && probe(p.client, REVALIDATE_TIMEOUT_MS)
                if (healthy) return@launch
                // Half-open socket (app was frozen, NAT forgot us, network switched): drop it before
                // the disconnect listener can publish "Connection lost", and reconnect right away.
                if (!pool.remove(id, p)) return@launch
                setState(id, LinkState.Connecting)
                withContext(NonCancellable) { closeQuietly(p.client) }
                try {
                    pooledClient(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // pooledClient already published Failed with a human message.
                }
            }
        }
    }

    /** One keepalive round trip within [timeoutMs]; false on timeout or a dead transport. */
    private fun probe(client: SSHClient, timeoutMs: Long): Boolean = try {
        client.connection.sendGlobalRequest("keepalive@openssh.com", true, ByteArray(0)).retrieve(timeoutMs, TimeUnit.MILLISECONDS)
        true
    } catch (e: Exception) {
        // OpenSSH answers keepalive@openssh.com with a failure reply — that still proves the link is up.
        !generateSequence<Throwable>(e) { it.cause }.take(6).any { it is TimeoutException } && alive(client)
    }

    // ───────────────────────────── pooled clients ─────────────────────────────

    private fun lockFor(id: String): Mutex = locks.getOrPut(id) { Mutex() }

    private fun alive(c: SSHClient): Boolean = runCatching { c.isConnected && c.isAuthenticated }.getOrDefault(false)

    private fun endpointOf(c: Connection) = Endpoint(c.host.trim().lowercase(), c.port, c.username, c.auth)

    private suspend fun pooledClient(id: String): SSHClient {
        pool[id]?.let { p ->
            val conn = connections.get(id)
            if (alive(p.client) && conn != null && endpointOf(conn) == p.endpoint) return p.client
        }
        return lockFor(id).withLock {
            val conn = connections.get(id) ?: throw SshFailure("This machine isn't saved any more")
            val existing = pool[id]
            if (existing != null) {
                if (alive(existing.client) && existing.endpoint == endpointOf(conn)) return@withLock existing.client
                pool.remove(id, existing)
                withContext(Dispatchers.IO + NonCancellable) { closeQuietly(existing.client) }
            }
            setState(id, LinkState.Connecting)
            val verifier = TofuVerifier(conn.host, conn.port) {}
            val client = try {
                withContext(Dispatchers.IO) {
                    val c = newClient(verifier)
                    try {
                        c.connect(conn.host, conn.port)
                        authenticate(c, conn, null)
                        c.transport.timeoutMs = OPERATION_TIMEOUT_MS
                        c
                    } catch (e: Throwable) {
                        closeQuietly(c)
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                stateFlow.update { if (it[id] == LinkState.Connecting) it - id else it }
                throw e
            } catch (e: Throwable) {
                val message = SshErrors.humanize(e, verifier.rejected)
                Log.w(TAG, "Connect to ${conn.host}:${conn.port} failed: $message", e)
                setState(id, LinkState.Failed(message, System.currentTimeMillis()))
                throw SshFailure(message, e)
            }
            val since = System.currentTimeMillis()
            val pooled = Pooled(client, endpointOf(conn), since)
            client.transport.disconnectListener = DisconnectListener { _, _ ->
                if (pool.remove(id, pooled)) {
                    setState(id, LinkState.Failed(SshErrors.CONNECTION_LOST, System.currentTimeMillis()))
                }
            }
            pool[id] = pooled
            setState(id, LinkState.Connected(since, null))
            epochFlow.update { it + (id to ((it[id] ?: 0L) + 1)) }
            scope.launch(Dispatchers.IO) {
                val latency = measureLatency(client)
                if (pool[id] === pooled && latency != null) setState(id, LinkState.Connected(since, latency))
                runCatching { connections.markConnected(id, null, null) }
            }
            client
        }
    }

    /** Opens a channel, reconnecting once if the pooled link turns out to be dead. */
    private suspend fun openSession(id: String): Pair<SSHClient, Session> {
        var client = pooledClient(id)
        var reconnected = false
        var attempt = 0
        while (true) {
            try {
                val c = client
                return c to io { c.startSession() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OpenFailException) {
                // Server-side session limit (MaxSessions) — wait for a channel to free up.
                if (++attempt > 8) throw SshFailure(SshErrors.TOO_MANY_SESSIONS, e)
                delay(200L * attempt)
            } catch (e: Exception) {
                if (reconnected || alive(client)) throw failureAfterDrop(id, client, e)
                invalidate(id, client)
                reconnected = true
                client = pooledClient(id)
            }
        }
    }

    private fun invalidate(id: String, client: SSHClient) {
        pool[id]?.takeIf { it.client === client }?.let { pool.remove(id, it) }
        closeQuietly(client)
    }

    /** Converts a mid-operation failure into a human [SshFailure], dropping the link if it died. */
    private fun failureAfterDrop(id: String, client: SSHClient, e: Throwable): SshFailure {
        if (e is SshFailure) {
            if (!alive(client)) markLost(id, client)
            return e
        }
        return if (!alive(client)) {
            markLost(id, client)
            SshFailure(SshErrors.CONNECTION_LOST, e)
        } else {
            SshFailure(SshErrors.humanize(e), e)
        }
    }

    private fun markLost(id: String, client: SSHClient) {
        val p = pool[id]
        if (p != null && p.client === client && pool.remove(id, p)) {
            setState(id, LinkState.Failed(SshErrors.CONNECTION_LOST, System.currentTimeMillis()))
        }
        closeQuietly(client)
    }

    private fun setState(id: String, state: LinkState) {
        stateFlow.update { if (state == LinkState.Idle) it - id else it + (id to state) }
    }

    // ───────────────────────────── connect & auth ─────────────────────────────

    private fun newClient(verifier: TofuVerifier): SSHClient {
        val c = SSHClient(config)
        c.connectTimeout = CONNECT_TIMEOUT_MS
        // Key exchange waits while the user reads the trust dialog, so give it longer than the prompt timeout.
        c.transport.timeoutMs = KEX_TIMEOUT_MS
        c.connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_S
        (c.connection.keepAlive as? KeepAliveRunner)?.maxAliveCount = KEEPALIVE_MAX_MISSES
        c.addHostKeyVerifier(verifier)
        return c
    }

    /** Blocking. Authenticates [client] as [conn] (password falls back to keyboard-interactive). */
    private fun authenticate(client: SSHClient, conn: Connection, passwordOverride: String?) {
        when (val auth = conn.auth) {
            AuthMethod.Password -> {
                val pw = passwordOverride ?: secrets.get(SecretKeys.password(conn.id))
                    ?: throw SshFailure(
                        if (secrets.readBlockedByDeviceLock()) UNLOCK_TO_CONNECT else "No password saved for this machine"
                    )
                try {
                    client.authPassword(conn.username, pw)
                } catch (e: UserAuthException) {
                    val allowed = runCatching { client.userAuth.allowedMethods }.getOrNull().orEmpty()
                    if (client.isConnected && !client.isAuthenticated && "keyboard-interactive" in allowed) {
                        client.auth(
                            conn.username,
                            AuthKeyboardInteractive(PasswordResponseProvider(PasswordUtils.createOneOff(pw.toCharArray()))),
                        )
                    } else {
                        throw e
                    }
                }
            }
            is AuthMethod.Key -> {
                val text = keys.privateKey(auth.keyId)
                    ?: throw SshFailure(
                        if (secrets.readBlockedByDeviceLock()) UNLOCK_TO_CONNECT
                        else "This machine's SSH key is missing — pick or import a key"
                    )
                val provider = try {
                    client.loadKeys(text, null as String?, passwordFinder(keys.passphrase(auth.keyId)))
                } catch (e: IOException) {
                    throw SshFailure("Couldn't unlock the SSH key", e)
                }
                client.authPublickey(conn.username, provider)
            }
        }
        if (!client.isAuthenticated) throw UserAuthException(SshErrors.AUTH_FAILED)
    }

    private fun passwordFinder(passphrase: String?): PasswordFinder = object : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray? = passphrase?.toCharArray()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }

    /** Round trip of a global request (OpenSSH answers `keepalive@openssh.com` with a failure — that's fine). */
    private fun measureLatency(client: SSHClient): Long? {
        if (!alive(client)) return null
        val t0 = System.nanoTime()
        try {
            client.connection.sendGlobalRequest("keepalive@openssh.com", true, ByteArray(0)).retrieve(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.take(6).any { it is TimeoutException }) return null
            if (!alive(client)) return null
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    /** Trust-on-first-use against [knownHosts]; unknown or changed keys ask the user. */
    private inner class TofuVerifier(
        private val host: String,
        private val port: Int,
        private val onKeyReceived: () -> Unit,
    ) : HostKeyVerifier {
        @Volatile var rejected = false
        @Volatile var acceptedFingerprint: String? = null
        @Volatile var acceptedKeyType: String? = null

        override fun verify(hostname: String?, port: Int, key: PublicKey): Boolean {
            onKeyReceived()
            val fingerprint = SshKeyCodec.fingerprint(key)
            val type = SshKeyCodec.keyTypeName(key)
            val lock = hostLocks.getOrPut("${host.lowercase()}:${this.port}") { Any() }
            synchronized(lock) {
                val known = knownHosts.get(host, this.port)
                if (known != null && known.fingerprint == fingerprint) return accept(type, fingerprint)
                val trusted = try {
                    runBlocking {
                        hostKeyPrompts.ask(
                            HostKeyPrompt(
                                id = UUID.randomUUID().toString(),
                                host = host,
                                port = this@TofuVerifier.port,
                                keyType = type,
                                fingerprint = fingerprint,
                                previousFingerprint = known?.fingerprint,
                            ),
                        )
                    }
                } catch (e: InterruptedException) {
                    false
                }
                if (!trusted) {
                    rejected = true
                    return false
                }
                knownHosts.put(KnownHost(host, this.port, type, fingerprint, System.currentTimeMillis()))
                return accept(type, fingerprint)
            }
        }

        private fun accept(type: String, fingerprint: String): Boolean {
            acceptedKeyType = type
            acceptedFingerprint = fingerprint
            return true
        }

        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> {
            val known = knownHosts.get(host, this.port) ?: return emptyList()
            // Prefer the algorithm we already trust, so a server offering several key types
            // doesn't trigger a spurious "host key changed" warning.
            return if (known.keyType == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa") else listOf(known.keyType)
        }
    }

    // ───────────────────────────── commands ─────────────────────────────

    /** Runs [command] on an open [session] and collects its output. Does not close the session. */
    private suspend fun runCommand(session: Session, command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult {
        val result = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val cmd = io { session.exec(command) }
                    val out = async { io { cmd.inputStream.readBytes() } }
                    val err = async { io { cmd.errorStream.readBytes() } }
                    io {
                        cmd.outputStream.use { os ->
                            if (stdin != null && stdin.isNotEmpty()) {
                                os.write(stdin)
                                os.flush()
                            }
                        } // close = EOF on the remote's stdin
                    }
                    val stdout = out.await()
                    val stderr = err.await()
                    runCatching { io { cmd.join(5, TimeUnit.SECONDS) } }
                    ExecResult(cmd.exitStatus ?: -1, String(stdout, Charsets.UTF_8), String(stderr, Charsets.UTF_8))
                }
            }
        }
        return result ?: throw SshFailure("The command took longer than ${(timeoutMs + 999) / 1000}s")
    }

    /**
     * Runs blocking sshj I/O on [Dispatchers.IO], interrupting the thread if the coroutine is
     * cancelled. sshj reports the interrupt as [InterruptedIOException]; translate it back into
     * the coroutine's cancellation so timeouts and cancels aren't mistaken for network failures.
     */
    private suspend fun <T> io(block: () -> T): T = try {
        runInterruptible(Dispatchers.IO, block)
    } catch (e: InterruptedIOException) {
        currentCoroutineContext().ensureActive()
        throw e
    }

    private fun discard(stream: InputStream) {
        val buf = ByteArray(4096)
        while (stream.read(buf) >= 0) Unit
    }

    private fun sftpPut(client: SSHClient, remotePath: String, bytes: ByteArray, mode: Int) {
        client.newSFTPClient().use { sftp ->
            val home = sftp.canonicalize(".").trimEnd('/')
            val path = when {
                remotePath == "~" -> home
                remotePath.startsWith("~/") -> "$home/${remotePath.removePrefix("~/")}"
                remotePath.startsWith("/") -> remotePath
                else -> "$home/$remotePath"
            }
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) sftp.mkdirs(parent)
            val tmp = "$path.tether-tmp"
            val source = object : InMemorySourceFile() {
                override fun getName(): String = tmp.substringAfterLast('/')
                override fun getLength(): Long = bytes.size.toLong()
                override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
                override fun getPermissions(): Int = mode
            }
            sftp.put(source, tmp)
            sftp.chmod(tmp, mode)
            if (sftp.statExistence(path) != null) sftp.rm(path)
            sftp.rename(tmp, path)
        }
    }

    // ───────────────────────────── probe ─────────────────────────────

    private suspend fun probe(client: SSHClient, conn: Connection): ProbeResult {
        val session = io { client.startSession() }
        val script = PROBE_SCRIPT.replace("@CLAUDE_PATH@", shellQuote(conn.claudePath?.trim().orEmpty()))
        val result = try {
            runCommand(session, "sh -s", script.toByteArray(Charsets.UTF_8), PROBE_TIMEOUT_MS)
        } finally {
            closeQuietly(session)
        }
        val values = result.stdout.lineSequence()
            .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1).trim() } }
            .toMap()
        val claudePath = values["CLAUDE"]?.takeIf { it.startsWith("/") }
        val versionLine = values["CVER"]?.takeIf { it.isNotBlank() }
        val claudeVersion = versionLine?.let { VERSION.find(it)?.value ?: it }
        val python = values["PY"]?.takeIf { it.startsWith("Python", ignoreCase = true) }
        val problem = when {
            claudePath == null || claudeVersion == null -> "Claude Code isn't installed on this machine (or not on PATH)"
            python == null -> "python3 is required on the machine"
            else -> null
        }
        return ProbeResult(
            hostname = values["HOSTNAME"]?.takeIf { it.isNotBlank() } ?: conn.host,
            os = values["OS"].orEmpty(),
            home = values["HOME"].orEmpty(),
            claudePath = claudePath,
            claudeVersion = claudeVersion,
            pythonVersion = python?.removePrefix("Python")?.trim(),
            helperReady = values["HELPER"] == "1",
            problem = problem,
        )
    }

    private fun probeHint(p: ProbeResult): String? = when {
        p.claudePath == null -> "Install it with `npm install -g @anthropic-ai/claude-code`, or set the claude path under Advanced"
        p.claudeVersion == null -> "Found ${p.claudePath}, but it didn't report a version"
        p.pythonVersion == null -> "Tether runs a small Python 3 helper on the machine — install python3"
        else -> null
    }

    private fun failureHint(step: TestStep, conn: Connection, message: String): String? = when (step) {
        TestStep.RESOLVE -> when (message) {
            SshErrors.TIMED_OUT -> "Nothing answered on ${conn.host}:${conn.port}. Is the machine on and reachable from this network?"
            SshErrors.UNREACHABLE -> "Check the host name, port ${conn.port}, and that SSH is enabled on the machine"
            else -> null
        }
        TestStep.HOST_KEY -> "The machine's identity wasn't trusted, so Tether didn't sign in"
        TestStep.AUTH -> when (conn.auth) {
            is AuthMethod.Key -> "The server didn't accept this key. Install it on the server first (uses your password once)"
            AuthMethod.Password -> "Check the username and password — some servers only allow keys"
        }
        else -> null
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    private fun closeQuietly(c: Closeable?) {
        if (c == null) return
        try {
            if (c is SSHClient) c.disconnect() else c.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "TetherSsh"
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val KEX_TIMEOUT_MS = 75_000
        private const val OPERATION_TIMEOUT_MS = 30_000
        private const val KEEPALIVE_INTERVAL_S = 10
        private const val KEEPALIVE_MAX_MISSES = 2
        private const val REVALIDATE_TIMEOUT_MS = 2_500L
        private const val PROBE_TIMEOUT_MS = 30_000L
        private const val LATENCY_REFRESH_MS = 30_000L

        private val VERSION = Regex("\\d+\\.\\d+\\.\\d+[0-9A-Za-z.+-]*")
        private val PUBLIC_KEY_LINE = Regex("^(ssh-(ed25519|rsa|dss)|ecdsa-sha2-[a-z0-9]+|sk-[A-Za-z0-9@.-]+)\\s+[A-Za-z0-9+/]+={0,3}(\\s+.*)?$")

        /** Idempotent authorized_keys append; the key arrives on stdin (never interpolated). No single quotes inside. */
        private const val INSTALL_KEY_SCRIPT =
            "umask 077; " +
                "mkdir -p \"\$HOME/.ssh\" && chmod 700 \"\$HOME/.ssh\" && " +
                "touch \"\$HOME/.ssh/authorized_keys\" && chmod 600 \"\$HOME/.ssh/authorized_keys\" && " +
                "IFS= read -r K && [ -n \"\$K\" ] && " +
                "if grep -qxF \"\$K\" \"\$HOME/.ssh/authorized_keys\"; then echo present; else " +
                "if [ -s \"\$HOME/.ssh/authorized_keys\" ] && [ -n \"\$(tail -c 1 \"\$HOME/.ssh/authorized_keys\")\" ]; then " +
                "echo >> \"\$HOME/.ssh/authorized_keys\"; fi; " +
                "printf \"%s\\n\" \"\$K\" >> \"\$HOME/.ssh/authorized_keys\" && echo added; fi"

        /** POSIX sh, fed on stdin to `sh -s` so the user's login shell (bash, zsh, fish…) never parses it. */
        private val PROBE_SCRIPT = """
            CP=@CLAUDE_PATH@
            printf 'HOSTNAME=%s\n' "${'$'}(hostname 2>/dev/null || uname -n 2>/dev/null)"
            printf 'OS=%s\n' "${'$'}(uname -sr 2>/dev/null)"
            printf 'HOME=%s\n' "${'$'}HOME"
            if command -v python3 >/dev/null 2>&1; then printf 'PY=%s\n' "${'$'}(python3 --version 2>&1 </dev/null | head -n 1)"; fi
            C=""
            if [ -n "${'$'}CP" ]; then
              case "${'$'}CP" in "~/"*) CP="${'$'}HOME/${'$'}{CP#\~/}" ;; esac
              if [ -x "${'$'}CP" ]; then C="${'$'}CP"; fi
            fi
            if [ -z "${'$'}C" ] && command -v bash >/dev/null 2>&1; then
              C=${'$'}(bash -lc 'command -v claude' </dev/null 2>/dev/null | tail -n 1)
              case "${'$'}C" in /*) [ -x "${'$'}C" ] || C="" ;; *) C="" ;; esac
            fi
            if [ -z "${'$'}C" ]; then
              C=${'$'}(command -v claude 2>/dev/null)
              case "${'$'}C" in /*) ;; *) C="" ;; esac
            fi
            for p in "${'$'}HOME/.local/bin/claude" "${'$'}HOME/.claude/local/claude" "${'$'}HOME/.npm-global/bin/claude" /usr/local/bin/claude /opt/homebrew/bin/claude "${'$'}HOME"/.nvm/versions/node/*/bin/claude "${'$'}HOME/.bun/bin/claude" "${'$'}HOME/.volta/bin/claude"; do
              if [ -z "${'$'}C" ] && [ -x "${'$'}p" ]; then C="${'$'}p"; fi
            done
            printf 'CLAUDE=%s\n' "${'$'}C"
            if [ -n "${'$'}C" ]; then printf 'CVER=%s\n' "${'$'}("${'$'}C" --version 2>/dev/null </dev/null | head -n 1)"; fi
            if [ -f "${'$'}HOME/.tether/bin/tether_helper.py" ]; then printf 'HELPER=1\n'; fi
            exit 0
        """.trimIndent() + "\n"

        /** Replaces Android's stripped-down "BC" provider with the full BouncyCastle sshj needs. */
        const val UNLOCK_TO_CONNECT = "Unlock your phone to connect — your keys stay locked while it is"

        private val MODERN_KEX = setOf(
            "curve25519-sha256", "curve25519-sha256@libssh.org",
            "ecdh-sha2-nistp256", "ecdh-sha2-nistp384", "ecdh-sha2-nistp521",
            "diffie-hellman-group-exchange-sha256",
            "diffie-hellman-group14-sha256", "diffie-hellman-group16-sha512", "diffie-hellman-group18-sha512",
            "ext-info-c",
        )
        private val MODERN_CIPHERS = setOf(
            "chacha20-poly1305@openssh.com", "aes128-gcm@openssh.com", "aes256-gcm@openssh.com",
            "aes128-ctr", "aes192-ctr", "aes256-ctr",
        )
        private val WEAK_SIGNATURES = setOf("ssh-dss", "ssh-dss-cert-v01@openssh.com", "ssh-rsa", "ssh-rsa-cert-v01@openssh.com")

        /**
         * Modern algorithms only, roughly OpenSSH 8.8+ defaults: no SHA-1 key exchange or MACs, no
         * CBC/RC4/3DES/Blowfish ciphers, no DSA or SHA-1 RSA signatures (RSA keys still work via
         * rsa-sha2-*). Keeps pace with what any maintained server offers.
         */
        internal fun sshConfig(): DefaultConfig = DefaultConfig().apply {
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
            keyExchangeFactories = keyExchangeFactories.filter { it.name in MODERN_KEX || "mlkem" in it.name || "sntrup" in it.name }
            cipherFactories = cipherFactories.filter { it.name in MODERN_CIPHERS }
            macFactories = macFactories.filter { it.name.startsWith("hmac-sha2-") }
            keyAlgorithms = keyAlgorithms.filter { it.name !in WEAK_SIGNATURES }
        }

        fun installSecurityProvider() {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}
