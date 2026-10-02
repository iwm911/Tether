package app.tether.remote

import android.content.Context
import app.tether.core.ClaudeRemote
import app.tether.core.ConnectionRepository
import app.tether.core.DirListing
import app.tether.core.ExecResult
import app.tether.core.ProbeResult
import app.tether.core.SlashCommand
import app.tether.core.ProjectSummary
import app.tether.core.SshManager
import app.tether.core.DaemonStatus
import app.tether.core.FollowEvent
import app.tether.core.NewSessionRequest
import app.tether.core.Session
import app.tether.core.SessionDecision
import app.tether.core.SessionKey
import app.tether.core.SessionRemote
import app.tether.core.WatchMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [ClaudeRemote] over SSH, driving `tether_helper.py` (bundled in assets, installed on demand at
 * `~/.tether/bin/tether_helper.py`). Every call is one exec channel; watch/follow are long-lived
 * streams. The helper is (re)installed once per app session per machine when missing or outdated.
 */
class HelperClaudeRemote internal constructor(
    private val ssh: SshManager,
    private val connections: ConnectionRepository,
    private val scope: CoroutineScope,
    helperSource: () -> ByteArray,
) : ClaudeRemote, SessionRemote {

    constructor(context: Context, ssh: SshManager, connections: ConnectionRepository, scope: CoroutineScope) :
        this(ssh, connections, scope, { context.applicationContext.assets.open(ASSET_NAME).use { it.readBytes() } })

    private val json = RemoteJson.json

    /** connectionId → remote $HOME, once the helper there is verified current. */
    private val ready = ConcurrentHashMap<String, String>()
    private val installLocks = ConcurrentHashMap<String, Mutex>()

    private val helperBytes: ByteArray by lazy(helperSource)
    private val helperVersion: String by lazy {
        VERSION_RE.find(String(helperBytes, Charsets.UTF_8))?.groupValues?.get(1) ?: "0"
    }
    private val helperSha256: String by lazy {
        java.security.MessageDigest.getInstance("SHA-256").digest(helperBytes).joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════════════════════ helper install ═══════════════════════════════════════

    /** Makes sure the current helper is installed; returns the remote home directory. */
    private suspend fun ensureHelper(connectionId: String): String {
        ready[connectionId]?.let { return it }
        val lock = installLocks.getOrPut(connectionId) { Mutex() }
        return lock.withLock {
            ready[connectionId]?.let { return@withLock it }
            val check = ssh.exec(connectionId, CHECK_SCRIPT, timeoutMs = 30_000)
            val lines = check.stdout.lines()
            val home = lines.firstOrNull { it.startsWith("HOME=") }?.removePrefix("HOME=")?.trim()
                ?.takeIf { it.startsWith("/") }
                ?: throw RemoteException("Could not read the home directory on this machine.")
            if (lines.any { it.trim() == "NOPY" }) {
                throw RemoteException("Python 3 is needed on this machine for Tether's helper. Install python3 and try again.")
            }
            // Compare content, not the version string: a helper someone else modified (but kept the
            // version of) must be replaced before it's ever run again.
            val installed = lines.firstOrNull { it.startsWith("SHA=") }?.removePrefix("SHA=")?.trim()
            if (installed != helperSha256) {
                ssh.upload(connectionId, "$home/$HELPER_REL", helperBytes, "755".toInt(8))
                val verify = ssh.exec(connectionId, helperCommand(home, listOf("version")), timeoutMs = 30_000)
                val v = lastJsonLine(verify.stdout)?.let { RemoteJson.parseObject(it)?.str("version") }
                if (v != helperVersion) {
                    throw RemoteException(
                        "Tether's helper could not start on this machine." +
                            (verify.stderr.trim().takeIf { it.isNotEmpty() }?.let { " ${it.lines().last()}" } ?: ""),
                    )
                }
            }
            ready[connectionId] = home
            // Learn the machine's Claude plan once per app session (cost display depends on it).
            scope.launch {
                try { probe(connectionId) } catch (e: CancellationException) { throw e } catch (_: Throwable) {}
            }
            home
        }
    }

    private fun helperCommand(home: String, args: List<String>): String {
        val path = shellQuote("$home/$HELPER_REL")
        val quoted = args.joinToString(" ") { shellQuote(it) }
        // `exec` so the helper itself owns the channel: it notices the reader going away and exits.
        return "PY=\$(command -v python3 2>/dev/null || echo /usr/bin/python3); exec \"\$PY\" $path $quoted"
    }

    private fun claudeArgs(connectionId: String): List<String> {
        val p = connections.get(connectionId)?.claudePath?.trim()
        return if (p.isNullOrEmpty()) emptyList() else listOf("--claude", p)
    }

    /**
     * Runs a helper command and returns its stdout. Throws [RemoteException] with the helper's own
     * sentence on failure; reinstalls and retries once if the helper vanished from the machine.
     */
    private suspend fun helper(
        connectionId: String,
        args: List<String>,
        stdin: ByteArray? = null,
        timeoutMs: Long = 30_000,
    ): String {
        var attempt = 0
        while (true) {
            val home = ensureHelper(connectionId)
            val res: ExecResult = ssh.exec(connectionId, helperCommand(home, args), stdin, timeoutMs)
            if (res.ok) return res.stdout
            val errObj = lastJsonLine(res.stdout)?.let { RemoteJson.parseObject(it) }
            val err = errObj?.str("error")
            if (err != null) throw RemoteException(err, code = errObj.str("code"))
            val missing = res.stderr.contains("No such file") || res.stderr.contains("can't open file")
            if (missing && attempt == 0) {
                ready.remove(connectionId)
                attempt++
                continue
            }
            val detail = res.stderr.trim().lines().lastOrNull { it.isNotBlank() }
            throw RemoteException(
                if (detail != null) "The helper failed on this machine: $detail"
                else "The helper failed on this machine (exit ${res.exitCode}).",
            )
        }
    }

    private fun lastJsonLine(out: String): String? =
        out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") || it.startsWith("[") }

    private inline fun <T> decode(out: String, what: String, block: (String) -> T): T {
        val line = lastJsonLine(out) ?: throw RemoteException("The machine sent no $what.")
        return try {
            block(line)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw RemoteException("The machine sent an unreadable $what.", e)
        }
    }

    // ═══════════════════════════════════════ ClaudeRemote ═══════════════════════════════════════

    override suspend fun probe(connectionId: String): ProbeResult {
        val result = try {
            val out = helper(connectionId, claudeArgs(connectionId) + "probe", timeoutMs = 45_000)
            decode(out, "machine description") { line ->
                val o = RemoteJson.parseObject(line) ?: throw IOException("not an object")
                ProbeResult(
                    hostname = o.str("hostname") ?: "unknown",
                    os = o.str("os") ?: "unknown",
                    home = o.str("home") ?: "",
                    claudePath = o.str("claudePath"),
                    claudeVersion = o.str("claudeVersion"),
                    pythonVersion = o.str("pythonVersion"),
                    helperReady = o.bool("helperReady") ?: true,
                    problem = o.str("problem"),
                    plan = o.str("plan"),
                )
            }
        } catch (e: RemoteException) {
            // No python / helper broken: still describe the machine with plain shell.
            val basic = ssh.exec(connectionId, "uname -n; uname -s; printf '%s\\n' \"\$HOME\"", timeoutMs = 20_000)
            val l = basic.stdout.lines()
            ProbeResult(
                hostname = l.getOrNull(0)?.trim().orEmpty().ifEmpty { "unknown" },
                os = l.getOrNull(1)?.trim().orEmpty().ifEmpty { "unknown" },
                home = l.getOrNull(2)?.trim().orEmpty(),
                claudePath = null,
                claudeVersion = null,
                pythonVersion = null,
                helperReady = false,
                problem = e.message,
            )
        }
        if (result.helperReady) {
            try {
                connections.markConnected(connectionId, result.hostname, result.claudeVersion)
                connections.get(connectionId)?.let { c ->
                    if (c.lastPlan != result.plan) connections.upsert(c.copy(lastPlan = result.plan))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // cosmetic cache only
            }
        }
        return result
    }

    override suspend fun listProjects(connectionId: String): List<ProjectSummary> {
        val out = helper(connectionId, listOf("projects"), timeoutMs = 60_000)
        return decode(out, "project list") { json.decodeFromString(ListSerializer(ProjectSummary.serializer()), it) }
    }

    override suspend fun listDir(connectionId: String, path: String?): DirListing {
        val args = if (path.isNullOrBlank()) listOf("ls") else listOf("ls", path)
        val out = helper(connectionId, args, timeoutMs = 30_000)
        return decode(out, "folder listing") { json.decodeFromString(DirListing.serializer(), it) }
    }

    override suspend fun listCommands(connectionId: String, cwd: String): List<SlashCommand> {
        val out = helper(connectionId, claudeArgs(connectionId) + listOf("commands", "--cwd", cwd), timeoutMs = 45_000)
        return decode(out, "command list") { parseSlashCommands(RemoteJson.parseObject(it)?.arr("commands")) }
    }

    // ═══════════════════════════════════════ SessionRemote (one-session model) ═══════════════════════════════════════

    private fun body(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun sessionOf(out: String, what: String): Session = decode(out, what) { line ->
        val el = RemoteJson.parseElement(line) ?: throw IOException("not JSON")
        // A write may answer with the Session itself or wrap it as {"session": …}.
        val target = (el as? JsonObject)?.get("session")?.takeIf { it is JsonObject } ?: el
        SessionProtocol.decodeSession(target) ?: throw IOException("not a session")
    }

    override suspend fun daemonStatus(connectionId: String): DaemonStatus {
        val out = helper(connectionId, claudeArgs(connectionId) + "daemon-status", timeoutMs = 30_000)
        return decode(out, "daemon status") { SessionProtocol.parseDaemonStatus(it) ?: throw IOException("not a status") }
    }

    override suspend fun sessions(connectionId: String, cwd: String?, limit: Int?, before: Long?): List<Session> {
        val out = sessionHelper(connectionId, SessionProtocol.sessionsArgs(cwd, limit, before))
        return decode(out, "session list") { line ->
            val o = RemoteJson.parseObject(line) ?: throw IOException("not an object")
            if (o["sessions"] !is kotlinx.serialization.json.JsonArray) throw IOException("no sessions")
            SessionProtocol.parseSessionList(line).map { it.copy(connectionId = connectionId) }
        }
    }

    /**
     * The bundled helper speaks the one-session protocol (HELPER_VERSION 2.x). A 1.x helper has
     * commands of the same names (`watch`, `follow`, `send`) with the old meaning: never run them.
     */
    internal val speaksSessions: Boolean
        get() = helperVersion.substringBefore('.').toIntOrNull()?.let { it >= SESSION_PROTOCOL_MAJOR } == true

    private fun requireSessions() {
        if (!speaksSessions) {
            throw RemoteException("Tether's helper $helperVersion does not support sessions yet.", code = app.tether.core.SessionErrorCodes.EPROTO)
        }
    }

    /** A one-session helper command (with `--claude` when the machine has a custom path). */
    private suspend fun sessionHelper(connectionId: String, args: List<String>, stdin: String? = null, timeoutMs: Long = 60_000): String {
        requireSessions()
        return helper(connectionId, claudeArgs(connectionId) + args, stdin?.let(::body), timeoutMs)
    }

    override fun watchSessions(connectionId: String): Flow<WatchMessage> = channelFlow {
        requireSessions()
        val home = ensureHelper(connectionId)
        val lastLine = AtomicLong(System.currentTimeMillis())
        val watchdog = launch {
            while (true) {
                delay(5_000)
                if (System.currentTimeMillis() - lastLine.get() > WATCH_STALL_MS) {
                    throw IOException("Lost contact with the machine (no heartbeat).")
                }
            }
        }
        ssh.streamLines(connectionId, helperCommand(home, claudeArgs(connectionId) + "watch")).collect { raw ->
            lastLine.set(System.currentTimeMillis())
            when (val m = SessionProtocol.parseWatchLine(raw)) {
                is WatchMessage.Snapshot -> send(WatchMessage.Snapshot(m.sessions.map { it.copy(connectionId = connectionId) }))
                is WatchMessage.Changed -> send(m.copy(changed = m.changed.map { it.copy(connectionId = connectionId) }))
                is WatchMessage.Heartbeat -> send(m)
                null -> Unit
            }
        }
        watchdog.cancel()
        throw IOException("The machine closed the session stream.")
    }

    override fun follow(connectionId: String, sessionId: String, agentId: String?, fromOffset: Long): Flow<FollowEvent> = flow {
        requireSessions()
        val home = ensureHelper(connectionId)
        val args = claudeArgs(connectionId) + SessionProtocol.followArgs(sessionId, agentId, fromOffset)
        ssh.streamLines(connectionId, helperCommand(home, args)).collect { raw ->
            when (val e = SessionProtocol.parseFollowLine(raw)) {
                is FollowEvent.State -> emit(FollowEvent.State(e.session.copy(connectionId = connectionId)))
                null -> Unit
                else -> emit(e)
            }
        }
        throw IOException("The machine closed the conversation stream.")
    }

    override suspend fun newSession(connectionId: String, request: NewSessionRequest): Session {
        val out = sessionHelper(connectionId, listOf("new"), SessionProtocol.newBody(request), timeoutMs = 120_000)
        return sessionOf(out, "new session").copy(connectionId = connectionId)
    }

    override suspend fun send(connectionId: String, sessionId: String, text: String, images: List<String>): Boolean {
        val out = sessionHelper(connectionId, listOf("send", sessionId), SessionProtocol.sendBody(text, images), timeoutMs = 120_000)
        val o = lastJsonLine(out)?.let { RemoteJson.parseObject(it) }
        if (o?.bool("ok") != true) throw RemoteException("The message did not reach the session.")
        return o.bool("woke") ?: false
    }

    override suspend fun key(connectionId: String, sessionId: String, keys: List<SessionKey>) {
        if (keys.isEmpty()) return
        val out = sessionHelper(connectionId, listOf("key", sessionId), SessionProtocol.keyBody(keys))
        val o = lastJsonLine(out)?.let { RemoteJson.parseObject(it) }
        if (o?.bool("ok") != true) throw RemoteException("The keys did not reach the session.")
    }

    override suspend fun setMode(connectionId: String, sessionId: String, mode: String?): String? {
        val out = sessionHelper(connectionId, listOf("key", sessionId), SessionProtocol.modeBody(mode), timeoutMs = 60_000)
        val o = lastJsonLine(out)?.let { RemoteJson.parseObject(it) }
        if (o?.bool("ok") != true) throw RemoteException("The keys did not reach the session.")
        return o.str("permissionMode")
    }

    override suspend fun answer(connectionId: String, sessionId: String, decision: SessionDecision, message: String?, toolUseId: String?): Session {
        val out = sessionHelper(connectionId, listOf("answer", sessionId), SessionProtocol.answerBody(decision, message, toolUseId))
        return sessionOf(out, "session").copy(connectionId = connectionId)
    }

    override suspend fun ask(connectionId: String, sessionId: String, answers: List<app.tether.core.AskAnswer>): Session {
        val out = sessionHelper(connectionId, listOf("ask", sessionId), SessionProtocol.askBody(answers), timeoutMs = 120_000)
        return sessionOf(out, "session").copy(connectionId = connectionId)
    }

    override suspend fun interrupt(connectionId: String, sessionId: String): Session {
        val out = sessionHelper(connectionId, listOf("interrupt", sessionId))
        return sessionOf(out, "session").copy(connectionId = connectionId)
    }

    override suspend fun stop(connectionId: String, sessionId: String) {
        sessionHelper(connectionId, listOf("stop", sessionId))
    }

    override suspend fun rm(connectionId: String, sessionId: String) {
        sessionHelper(connectionId, listOf("rm", sessionId))
    }

    override suspend fun uploadImage(connectionId: String, image: app.tether.core.ImageAttachment): String {
        val home = ensureHelper(connectionId)
        val ext = when (image.mimeType.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> image.name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "img"
        }
        val path = "$home/$UPLOADS_REL/${java.util.UUID.randomUUID()}.$ext"
        ssh.upload(connectionId, path, image.bytes, "600".toInt(8))
        return path
    }

    /** Forgets verified-helper state (e.g. after the user edits the machine). */
    fun invalidate(connectionId: String) {
        ready.remove(connectionId)
    }

    init {
        // Machines that are edited or removed must re-verify their helper next time.
        scope.launch {
            var known = emptyMap<String, Pair<String, Int>>()
            connections.connections.collect { list ->
                val now = list.associate { it.id to (it.host to it.port) }
                for ((id, hp) in known) if (now[id] != hp) ready.remove(id)
                known = now
            }
        }
    }

    private companion object {
        const val ASSET_NAME = "tether_helper.py"
        const val HELPER_REL = ".tether/bin/tether_helper.py"
        /** Images the phone attaches, pasted into the session by path (like a file dropped on the terminal). */
        const val UPLOADS_REL = ".tether/uploads"
        /** First HELPER_VERSION major that speaks the one-session protocol. */
        const val SESSION_PROTOCOL_MAJOR = 2
        const val WATCH_STALL_MS = 40_000L
        val VERSION_RE = Regex("""HELPER_VERSION\s*=\s*"([^"]+)"""")
        /** Home dir, owner-only ~/.tether, and the installed helper's SHA-256, without running it. */
        val CHECK_SCRIPT = """
            umask 077
            printf 'HOME=%s\n' "${'$'}HOME"
            mkdir -p "${'$'}HOME/.tether/bin" && chmod 700 "${'$'}HOME/.tether" "${'$'}HOME/.tether/bin"
            PY=${'$'}(command -v python3 2>/dev/null || echo /usr/bin/python3)
            if [ -x "${'$'}PY" ]; then "${'$'}PY" -c 'import hashlib, sys
            try: print("SHA=" + hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
            except OSError: print("NOHELPER")' "${'$'}HOME/$HELPER_REL"; else echo NOPY; fi
        """.trimIndent()
    }
}
