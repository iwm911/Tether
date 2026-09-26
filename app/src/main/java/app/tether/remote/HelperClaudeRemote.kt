package app.tether.remote

import android.content.Context
import app.tether.core.ClaudeRemote
import app.tether.core.ConnectionRepository
import app.tether.core.DirListing
import app.tether.core.ExecResult
import app.tether.core.NativeStartResult
import app.tether.core.NativeTimelineEntry
import app.tether.core.isNative
import app.tether.core.nativeId
import app.tether.core.nativeRunRef
import app.tether.core.ProbeResult
import app.tether.core.SlashCommand
import app.tether.core.ProjectSummary
import app.tether.core.RunInfo
import app.tether.core.RunRef
import app.tether.core.SessionSummary
import app.tether.core.SshManager
import app.tether.core.StartRunRequest
import app.tether.core.TailLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * [ClaudeRemote] over SSH, driving `tether_helper.py` (bundled in assets, installed on demand at
 * `~/.tether/bin/tether_helper.py`). Every call is one exec channel; tail/watch are long-lived
 * streams. The helper is (re)installed once per app session per machine when missing or outdated.
 */
class HelperClaudeRemote internal constructor(
    private val ssh: SshManager,
    private val connections: ConnectionRepository,
    private val scope: CoroutineScope,
    helperSource: () -> ByteArray,
) : ClaudeRemote, RunHistorySource {

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
            val err = lastJsonLine(res.stdout)?.let { RemoteJson.parseObject(it)?.str("error") }
            if (err != null) throw RemoteException(err)
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

    override suspend fun listSessions(connectionId: String, cwd: String?, limit: Int): List<SessionSummary> {
        val args = buildList {
            add("sessions")
            add("--limit"); add(limit.coerceIn(1, 1000).toString())
            if (!cwd.isNullOrBlank()) { add("--cwd"); add(cwd) }
        }
        val out = helper(connectionId, args, timeoutMs = 60_000)
        return decode(out, "session list") { json.decodeFromString(ListSerializer(SessionSummary.serializer()), it) }
    }

    override suspend fun loadTranscript(connectionId: String, sessionId: String): List<String> {
        val out = helper(connectionId, listOf("transcript", sessionId), timeoutMs = 120_000)
        return withContext(Dispatchers.Default) { out.lines().filter { it.isNotBlank() } }
    }

    override suspend fun loadRunHistory(ref: RunRef): List<String> {
        val out = helper(ref.connectionId, listOf("history", ref.runId), timeoutMs = 120_000)
        return withContext(Dispatchers.Default) { out.lines().filter { it.isNotBlank() } }
    }

    override suspend fun listRuns(connectionId: String): List<RunInfo> {
        val out = helper(connectionId, listOf("runs"), timeoutMs = 45_000)
        val runs = decode(out, "agent list") { json.decodeFromString(ListSerializer(RunInfo.serializer()), it) }
        // Native agents are best-effort: a machine without `claude agents` still lists its Tether runs.
        val native = try {
            listNative(connectionId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return runs + native
    }

    // ═══════════════════════════════════════ native background agents ═══════════════════════════════════════

    private fun nativeIdOf(ref: RunRef): String = ref.nativeId ?: throw RemoteException("Not a background agent.")

    suspend fun listNative(connectionId: String): List<RunInfo> {
        val out = helper(connectionId, claudeArgs(connectionId) + "native-list", timeoutMs = 45_000)
        return decode(out, "background agent list") { NativeAgents.parseList(it).map(NativeAgents::toRunInfo) }
    }

    override suspend fun startNative(connectionId: String, request: StartRunRequest, trust: Boolean): NativeStartResult {
        val body = buildJsonObject {
            put("cwd", request.cwd)
            put("prompt", request.prompt.orEmpty())
            request.model?.let { put("model", it) }
            request.permissionMode?.let { put("permissionMode", it) }
            put("trust", trust)
        }.toString().toByteArray(Charsets.UTF_8)
        val out = helper(connectionId, claudeArgs(connectionId) + "native-start", stdin = body, timeoutMs = 120_000)
        val line = lastJsonLine(out) ?: throw RemoteException("The machine sent no answer.")
        val o = RemoteJson.parseObject(line)
        if (o?.str("error") == "untrusted") return NativeStartResult.Untrusted(o.str("cwd") ?: request.cwd)
        o?.str("error")?.let { throw RemoteException(it) }
        val dto = decode(out, "background agent") { NativeAgents.parseOne(it) }
        return NativeStartResult.Started(nativeRunRef(connectionId, dto.id))
    }

    override suspend fun replyNative(ref: RunRef, message: String): RunInfo {
        val body = buildJsonObject { put("message", message) }.toString().toByteArray(Charsets.UTF_8)
        val out = helper(ref.connectionId, claudeArgs(ref.connectionId) + listOf("native-send", nativeIdOf(ref)), stdin = body, timeoutMs = 120_000)
        return decode(out, "background agent") { NativeAgents.toRunInfo(NativeAgents.parseOne(it)) }
    }

    override suspend fun answerNative(ref: RunRef, allow: Boolean): RunInfo {
        val body = buildJsonObject { put("decision", if (allow) "allow" else "deny") }.toString().toByteArray(Charsets.UTF_8)
        val out = helper(ref.connectionId, claudeArgs(ref.connectionId) + listOf("native-answer", nativeIdOf(ref)), stdin = body, timeoutMs = 60_000)
        return decode(out, "background agent") { NativeAgents.toRunInfo(NativeAgents.parseOne(it)) }
    }

    override suspend fun rewindFiles(connectionId: String, sessionId: String, messageId: String, cwd: String, dryRun: Boolean, runId: String?): app.tether.core.RewindResult {
        val body = buildJsonObject {
            put("sessionId", sessionId); put("messageId", messageId); put("cwd", cwd); put("dryRun", dryRun)
            runId?.let { put("runId", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        val out = helper(connectionId, claudeArgs(connectionId) + "rewind", stdin = body, timeoutMs = 90_000)
        return decode(out, "rewind result") { line ->
            val o = RemoteJson.parseObject(line) ?: throw IOException("not an object")
            app.tether.core.RewindResult(
                canRewind = o.bool("canRewind") ?: false,
                filesChanged = (o["filesChanged"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
                insertions = o.int("insertions") ?: 0,
                deletions = o.int("deletions") ?: 0,
                error = o.str("error"),
            )
        }
    }

    override suspend fun nativeQuestion(ref: RunRef): String {
        val out = helper(ref.connectionId, claudeArgs(ref.connectionId) + listOf("native-question", nativeIdOf(ref)), timeoutMs = 60_000)
        return decode(out, "question") { RemoteJson.parseObject(it)?.str("inputJson") ?: throw IOException("no question") }
    }

    override suspend fun askNative(ref: RunRef, answers: List<app.tether.core.AskAnswer>): RunInfo {
        val body = buildJsonObject {
            put("answers", kotlinx.serialization.json.buildJsonArray {
                for (a in answers) add(buildJsonObject {
                    put("choices", kotlinx.serialization.json.JsonArray(a.choices.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    a.other?.trim()?.takeIf { it.isNotEmpty() }?.let { put("other", it) }
                })
            })
        }.toString().toByteArray(Charsets.UTF_8)
        val out = helper(ref.connectionId, claudeArgs(ref.connectionId) + listOf("native-ask", nativeIdOf(ref)), stdin = body, timeoutMs = 120_000)
        return decode(out, "background agent") { NativeAgents.toRunInfo(NativeAgents.parseOne(it)) }
    }

    override suspend fun interruptNative(ref: RunRef): RunInfo {
        val out = helper(ref.connectionId, claudeArgs(ref.connectionId) + listOf("native-interrupt", nativeIdOf(ref)), timeoutMs = 60_000)
        return decode(out, "background agent") { NativeAgents.toRunInfo(NativeAgents.parseOne(it)) }
    }

    override suspend fun nativeTimeline(ref: RunRef): List<NativeTimelineEntry> {
        val out = helper(ref.connectionId, listOf("native-timeline", nativeIdOf(ref)), timeoutMs = 30_000)
        return decode(out, "activity timeline") { NativeAgents.parseTimeline(it) }
    }

    override suspend fun nativeLogs(ref: RunRef): String {
        val out = helper(ref.connectionId, claudeArgs(ref.connectionId) + listOf("native-logs", nativeIdOf(ref)), timeoutMs = 45_000)
        return decode(out, "terminal output") { RemoteJson.parseObject(it)?.str("text") ?: "" }
    }

    override fun followNative(ref: RunRef): Flow<String> = flow {
        val home = ensureHelper(ref.connectionId)
        ssh.streamLines(ref.connectionId, helperCommand(home, listOf("native-follow", nativeIdOf(ref)))).collect { line ->
            if (line.startsWith("{\"error\"")) {
                RemoteJson.parseObject(line)?.str("error")?.let { throw RemoteException(it) }
            }
            emit(line)
        }
    }

    override suspend fun startRun(connectionId: String, request: StartRunRequest): RunInfo {
        val body = json.encodeToString(StartRunRequest.serializer(), request).toByteArray(Charsets.UTF_8)
        val out = helper(connectionId, claudeArgs(connectionId) + "start", stdin = body, timeoutMs = 60_000)
        return decode(out, "agent description") { json.decodeFromString(RunInfo.serializer(), it) }
    }

    override suspend fun writeInput(ref: RunRef, jsonLines: List<String>) {
        if (jsonLines.isEmpty()) return
        val payload = (jsonLines.joinToString("\n") { it.replace("\n", "") } + "\n").toByteArray(Charsets.UTF_8)
        val out = helper(ref.connectionId, listOf("send", ref.runId), stdin = payload, timeoutMs = 30_000)
        val o = lastJsonLine(out)?.let { RemoteJson.parseObject(it) }
        if (o?.bool("ok") != true) throw RemoteException("The message did not reach the agent.")
    }

    override suspend fun readInput(ref: RunRef): List<String> {
        val out = helper(ref.connectionId, listOf("input", ref.runId), timeoutMs = 60_000)
        return out.lines().filter { it.isNotBlank() }
    }

    /**
     * Follows out.jsonl from [fromOffset]. Uses the helper's `follow` (byte-exact, complete lines only,
     * exits the moment the SSH channel closes) — a bare `tail -F` would linger on the remote forever
     * once the run goes quiet. Equivalent to `tail -c +<offset+1> -F out.jsonl`.
     */
    override fun tail(ref: RunRef, fromOffset: Long): Flow<TailLine> = flow {
        val home = ensureHelper(ref.connectionId)
        val start = fromOffset.coerceAtLeast(0)
        var offset = start
        ssh.streamLines(ref.connectionId, helperCommand(home, listOf("follow", ref.runId, start.toString()))).collect { line ->
            offset += utf8Length(line) + 1
            emit(TailLine(line, offset))
        }
    }

    override fun watch(connectionId: String): Flow<List<RunInfo>> = channelFlow {
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
        val serializer = ListSerializer(RunInfo.serializer())
        // The helper prints the Tether runs (`[…]`) and the native agents (`{"native":[…]}`) as
        // separate lines; downstream sees one merged list. Right after (re)connecting, runs are held
        // back briefly for the native line so native agents do not blink out of the dashboard.
        var runs: List<RunInfo>? = null
        var native: List<RunInfo>? = null
        var hold: kotlinx.coroutines.Job? = null
        ssh.streamLines(connectionId, helperCommand(home, claudeArgs(connectionId) + "watch"))
            .collect { raw ->
                lastLine.set(System.currentTimeMillis())
                val line = raw.trim()
                if (line.startsWith("[")) {
                    val parsed = try {
                        json.decodeFromString(serializer, line)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (parsed != null) {
                        runs = parsed
                        if (native != null) {
                            send(parsed + native!!)
                        } else if (hold == null) {
                            hold = launch {
                                delay(NATIVE_HOLD_MS)
                                if (native == null) send(runs.orEmpty())
                            }
                        }
                    }
                } else if (line.startsWith("{\"native\"")) {
                    val parsed = try {
                        NativeAgents.parseWatchLine(line)?.map(NativeAgents::toRunInfo)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (parsed != null) {
                        native = parsed
                        hold?.cancel()
                        runs?.let { send(it + parsed) }
                    }
                } else if (line.startsWith("{")) {
                    val o: JsonObject? = RemoteJson.parseObject(line)
                    o?.str("error")?.let { throw RemoteException(it) }
                }
            }
        hold?.cancel()
        watchdog.cancel()
        throw IOException("The machine closed the status stream.")
    }

    override suspend fun stopRun(ref: RunRef) {
        if (ref.isNative) {
            helper(ref.connectionId, claudeArgs(ref.connectionId) + listOf("native-stop", nativeIdOf(ref)), timeoutMs = 45_000)
        } else {
            helper(ref.connectionId, listOf("stop", ref.runId), timeoutMs = 30_000)
        }
    }

    override suspend fun deleteRun(ref: RunRef) {
        if (ref.isNative) {
            helper(ref.connectionId, claudeArgs(ref.connectionId) + listOf("native-rm", nativeIdOf(ref)), timeoutMs = 60_000)
        } else {
            helper(ref.connectionId, listOf("delete", ref.runId), timeoutMs = 30_000)
        }
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
        const val WATCH_STALL_MS = 40_000L
        const val NATIVE_HOLD_MS = 2_500L
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
