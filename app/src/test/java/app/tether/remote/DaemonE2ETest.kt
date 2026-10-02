package app.tether.remote

import app.tether.core.AuthMethod
import app.tether.core.ChatItem
import app.tether.core.Connection
import app.tether.core.ConnectionRepository
import app.tether.core.ConversationState
import app.tether.core.ExecResult
import app.tether.core.FollowEvent
import app.tether.core.LinkState
import app.tether.core.NewSessionRequest
import app.tether.core.NewSessionResult
import app.tether.core.ProbeResult
import app.tether.core.Session
import app.tether.core.SessionKey
import app.tether.core.SessionProcess
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.SshManager
import app.tether.core.TestProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Daemon end-to-end of the one-session model: the real helper + [HelperClaudeRemote] +
 * [DefaultSessionHub] against THIS machine's Claude Code daemon, with an [SshManager] that runs
 * commands locally. One throwaway haiku session in ~/tether-exp/live, always removed.
 *
 *   TETHER_E2E=1 ./gradlew :app:testDebugUnitTest --tests app.tether.remote.DaemonE2ETest
 *
 * The helper is installed into app/build/e2e-home/.tether/bin (the check script runs with HOME
 * pointed there); everything else runs with the real HOME so the helper sees the real ~/.claude.
 */
class DaemonE2ETest {

    private class LocalSsh(private val installHome: File) : SshManager {
        override suspend fun download(connectionId: String, remotePath: String, dest: File, onProgress: (Long, Long) -> Unit) {}
        override val linkEpochs = MutableStateFlow<Map<String, Long>>(emptyMap())
        override suspend fun revalidate() {}
        override val states: StateFlow<Map<String, LinkState>> = MutableStateFlow(mapOf(CONN to LinkState.Connected(since = 0L)))

        private fun process(command: String): ProcessBuilder {
            val pb = ProcessBuilder("bash", "-c", command)
            // Only the helper-install check sees the scratch HOME: the helper lands in the build dir,
            // never in the real ~/.tether/bin.
            if (command.contains("printf 'HOME=%s")) pb.environment()["HOME"] = installHome.path
            return pb
        }

        override suspend fun exec(connectionId: String, command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult =
            withContext(Dispatchers.IO) {
                val p = process(command).start()
                p.outputStream.use { o -> if (stdin != null) o.write(stdin) }
                val err = async { p.errorStream.readBytes().toString(Charsets.UTF_8) }
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly()
                ExecResult(p.exitValue(), out, err.await())
            }

        override fun streamLines(connectionId: String, command: String): Flow<String> = callbackFlow {
            val p = process(command).start()
            p.outputStream.close()
            val reader = thread(isDaemon = true) {
                try {
                    p.inputStream.bufferedReader().forEachLine { trySend(it) }
                    close()
                } catch (e: Exception) {
                    close(e)
                }
            }
            awaitClose {
                p.destroy()
                reader.interrupt()
            }
        }

        override suspend fun upload(connectionId: String, remotePath: String, bytes: ByteArray, mode: Int) {
            withContext(Dispatchers.IO) {
                val f = File(remotePath)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                f.setExecutable(true)
            }
        }

        override suspend fun test(connection: Connection, password: String?, onProgress: (TestProgress) -> Unit): Result<ProbeResult> =
            Result.failure(UnsupportedOperationException("not used by this test"))

        override suspend fun installPublicKey(connection: Connection, password: String?, publicKey: String): Result<Unit> =
            Result.failure(UnsupportedOperationException("not used by this test"))

        override suspend fun disconnect(connectionId: String) = Unit
        override suspend fun disconnectAll() = Unit
    }

    private class Conns : ConnectionRepository {
        private val c = Connection(id = CONN, name = "Local", host = "localhost", username = "me", auth = AuthMethod.Password, createdAt = 0L)
        override val connections: StateFlow<List<Connection>> = MutableStateFlow(listOf(c))
        override fun get(id: String): Connection? = if (id == CONN) c else null
        override suspend fun upsert(connection: Connection, password: String?) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun markConnected(id: String, hostname: String?, claudeVersion: String?) = Unit
        override fun newId(): String = "x"
    }

    /** `claude agents --json --all` entries (id = short, sessionId). */
    private fun claudeAgents(): List<JsonObject> {
        val p = ProcessBuilder("claude", "agents", "--json", "--all").redirectError(File("/dev/null")).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(60, TimeUnit.SECONDS)
        val el = runCatching { Json.parseToJsonElement(out.ifBlank { "[]" }) }.getOrNull()
        return (el as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Last-resort cleanup straight through the helper (also when the hub is broken). */
    private fun helperRm(helper: File, sessionId: String): String {
        val p = ProcessBuilder("python3", helper.path, "rm", sessionId).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor(60, TimeUnit.SECONDS)
        return out.trim()
    }

    /** Deletes a throwaway session's transcript (and subagent dir) and its entry in the helper's removed list. */
    private fun forgetTranscript(home: String, sessionId: String) {
        File(home, ".claude/projects").listFiles()?.forEach { dir ->
            File(dir, "$sessionId.jsonl").delete()
            File(dir, sessionId).takeIf { it.isDirectory }?.deleteRecursively()
        }
        val removed = File(home, ".tether/removed_sessions.json")
        val o = runCatching { Json.parseToJsonElement(removed.readText()) as? JsonObject }.getOrNull() ?: return
        if (sessionId in o) removed.writeText(JsonObject(o - sessionId).toString())
    }

    @Test
    fun sessionLifecycleOnTheDaemon() = runBlocking {
        assumeTrue("set TETHER_E2E=1 to run", System.getenv("TETHER_E2E") == "1")
        val helperFile = File(System.getProperty("tether.helper") ?: "src/main/assets/tether_helper.py").absoluteFile
        assumeTrue("helper script not found at $helperFile", helperFile.isFile)
        val home: String = System.getProperty("user.home")!!
        val work = File(home, "tether-exp/live").apply { mkdirs() }
        val installHome = File("build/e2e-home").absoluteFile.apply { mkdirs() }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ssh = LocalSsh(installHome)
        val remote = HelperClaudeRemote(ssh, Conns(), scope) { helperFile.readBytes() }
        val hub = DefaultSessionHub(remote, ssh, Conns(), scope)
        var sessionId: String? = null // set while the session exists on the daemon
        var created: String? = null
        val log = StringBuilder()
        fun note(s: String) { println("E2E ▸ $s"); log.appendLine(s) }
        try {
            // ── read-only surface ──
            val status = remote.daemonStatus(CONN)
            assertTrue("daemon running: $status", status.running)
            assertEquals(1, status.proto)
            note("daemon $status")
            assertTrue("installed into the scratch home", File(installHome, ".tether/bin/tether_helper.py").isFile)

            // Every state the watch list reports for our session, in order (deduplicated).
            val watched = Collections.synchronizedList(ArrayList<Session>())
            val trail = Collections.synchronizedList(ArrayList<String>())
            scope.launch {
                hub.sessions.collect { list ->
                    val sid = sessionId ?: return@collect
                    val s = list.firstOrNull { it.sessionId == sid } ?: return@collect
                    watched.add(s)
                    val tag = "${s.state.name}/${s.process.name}"
                    if (trail.lastOrNull() != tag) trail.add(tag)
                }
            }
            withTimeout(60_000) { while (hub.sessions.value.isEmpty() && hub.machineErrors.value.isEmpty()) delay(200) }
            assertTrue("watch errors: ${hub.machineErrors.value}", hub.machineErrors.value.isEmpty())
            note("watch snapshot: ${hub.sessions.value.size} sessions")

            // ── new (haiku) ──
            val req = NewSessionRequest(
                cwd = work.path, model = "haiku", permissionMode = "default",
                prompt = "Without using any tools, write a numbered list of 40 short facts about the ocean, one sentence each. Then on its own line write: END-ONE",
            )
            val started = when (val r = hub.new(CONN, req)) {
                is NewSessionResult.Started -> r.session
                is NewSessionResult.Untrusted -> (hub.new(CONN, req.copy(trust = true)) as NewSessionResult.Started).session
            }
            sessionId = started.sessionId
            created = started.sessionId
            val ref = SessionRef(CONN, started.sessionId)
            note("new → ${started.short} state=${started.state} process=${started.process} model=${started.model}")
            assertEquals(started.sessionId.take(8), started.short)
            assertEquals(SessionProcess.LIVE, started.process)
            assertEquals(work.path, started.cwd)

            // The raw follow stream too, to see the helper's drafts before the hub conflates frames.
            val rawDrafts = Collections.synchronizedList(ArrayList<Pair<Long, Int>>())
            val rawJob = scope.launch {
                remote.follow(CONN, started.sessionId).collect { e ->
                    if (e is FollowEvent.Draft) rawDrafts.add(System.currentTimeMillis() to e.text.length)
                }
            }
            val latest = MutableStateFlow(ConversationState())
            val drafts = Collections.synchronizedList(ArrayList<String>())
            val convJob = scope.launch {
                hub.open(ref).collect { c ->
                    latest.value = c
                    c.live?.draft?.let { d -> if (drafts.lastOrNull() != d) drafts.add(d) }
                }
            }

            suspend fun await(what: String, timeoutMs: Long = 120_000, cond: (ConversationState) -> Boolean): ConversationState =
                try {
                    withTimeout(timeoutMs) {
                        while (!cond(latest.value)) delay(50)
                        latest.value
                    }
                } catch (e: Exception) {
                    val l = latest.value
                    throw AssertionError(
                        "timed out waiting for $what; status=${l.status} draft=${l.live?.draft?.take(80)} " +
                            "session=${l.live?.session?.state} items=${l.items.map { it.javaClass.simpleName }} trail=$trail",
                        e,
                    )
                }

            suspend fun awaitSession(what: String, timeoutMs: Long = 60_000, cond: (Session) -> Boolean): Session =
                try {
                    withTimeout(timeoutMs) {
                        while (true) {
                            hub.session(ref)?.takeIf(cond)?.let { return@withTimeout it }
                            delay(100)
                        }
                        @Suppress("UNREACHABLE_CODE") error("unreachable")
                    }
                } catch (e: Exception) {
                    throw AssertionError("timed out waiting for $what; session=${hub.session(ref)} trail=$trail", e)
                }

            fun finalTexts(c: ConversationState) = c.items.filterIsInstance<ChatItem.AssistantText>().filter { !it.streaming }.map { it.text }

            // ── a streaming draft, then the final message replaces it ──
            val streaming = await("a streaming draft") { c ->
                c.live?.draft != null && (c.items.lastOrNull() as? ChatItem.AssistantText)?.streaming == true
            }
            note("draft: ${streaming.live?.draft?.length} chars; status=${streaming.live?.status}")
            val done1 = await("the final message") { c ->
                finalTexts(c).any { it.contains("END-ONE") } && c.live?.draft == null && c.live?.session?.state == SessionState.IDLE
            }
            rawJob.cancel()
            assertTrue("drafts seen: ${drafts.size}", drafts.isNotEmpty())
            val growing = rawDrafts.map { it.second }.distinct()
            assertTrue("the helper's draft grows while the reply streams: $rawDrafts", growing.size >= 2 && growing.last() > growing.first())
            note("helper drafts (ms after first, chars): ${rawDrafts.map { (it.first - rawDrafts.first().first) to it.second }}")
            assertFalse("no streaming row left", done1.items.any { it is ChatItem.AssistantText && it.streaming })
            assertEquals(1, done1.items.count { it is ChatItem.User })
            assertTrue(done1.live!!.caughtUp)
            note("final: ${drafts.size} drafts, last draft ${drafts.last().length} chars, final ${finalTexts(done1).last().length} chars")
            awaitSession("idle on the watch list") { it.state == SessionState.IDLE && it.process == SessionProcess.LIVE }

            // ── stop → retired ──
            hub.stop(ref)
            val retired = awaitSession("retired") { it.process == SessionProcess.RETIRED }
            note("stop → state=${retired.state} process=${retired.process}")
            assertEquals(SessionState.DONE, retired.state)

            // ── send wakes the SAME session ──
            val woke = hub.send(ref, "Reply with just the word TWO.")
            assertTrue("send reported a wake", woke)
            val done2 = await("the second answer") { c ->
                finalTexts(c).any { it.trim().trimEnd('.') == "TWO" } && c.live?.session?.state == SessionState.IDLE &&
                    c.live?.session?.process == SessionProcess.LIVE
            }
            assertEquals(started.sessionId, done2.sessionId)
            assertEquals(2, done2.items.count { it is ChatItem.User })
            val mine = hub.sessions.value.filter { it.short == started.short }
            assertEquals("one session under that short: $mine", 1, mine.size)
            assertEquals(started.sessionId, mine.single().sessionId)
            val agents = claudeAgents().filter { it.s("id") == started.short || it.s("sessionId") == started.sessionId }
            assertEquals("claude agents lists it once: $agents", 1, agents.size)
            note("wake → same id ${started.short}; claude agents: ${agents.map { it.s("id") to it.s("status") }}")

            // ── shift-tab changes the permission mode ──
            val modeBefore = hub.session(ref)?.permissionMode ?: done2.permissionMode
            note("mode before: $modeBefore")
            hub.key(ref, listOf(SessionKey.ShiftTab))
            val changed = awaitSession("a new permission mode", 30_000) { it.permissionMode != null && it.permissionMode != modeBefore }
            assertNotEquals(modeBefore, changed.permissionMode)
            await("conversation shows the new mode", 30_000) { it.permissionMode == changed.permissionMode }
            note("shift-tab → mode ${changed.permissionMode}")

            // ── rm ──
            convJob.cancel()
            hub.remove(ref)
            withTimeout(30_000) { while (hub.sessions.value.any { it.sessionId == started.sessionId }) delay(200) }
            assertTrue("gone from sessions", remote.sessions(CONN, cwd = work.path, limit = 50).none { it.sessionId == started.sessionId })
            assertTrue("gone from claude agents", claudeAgents().none { it.s("id") == started.short || it.s("sessionId") == started.sessionId })
            sessionId = null
            note("rm → gone")

            // ── the watch list's transitions ──
            note("watch trail: $trail")
            val iWorking = trail.indexOfFirst { it.startsWith("WORKING/LIVE") }
            val iIdle = trail.indexOfFirst { it == "IDLE/LIVE" }
            val iDone = trail.indexOfFirst { it == "DONE/RETIRED" }
            val iIdle2 = trail.indexOfLast { it == "IDLE/LIVE" }
            assertTrue("working before idle: $trail", iWorking in 0 until iIdle)
            assertTrue("idle before retired: $trail", iDone > iIdle)
            assertTrue("live again after the wake: $trail", iIdle2 > iDone)
            assertTrue(watched.all { it.connectionId == CONN })
            println("DAEMON E2E OK\n$log")
        } finally {
            scope.cancel()
            created?.let { sid ->
                if (sessionId != null) println("E2E cleanup: rm $sid → ${helperRm(helperFile, sid)}")
                forgetTranscript(home, sid)
                val left = claudeAgents().filter { it.s("sessionId") == sid || it.s("id") == sid.take(8) }
                assertTrue("throwaway session still listed: $left", left.isEmpty())
            }
        }
    }

    private companion object {
        const val CONN = "local"
    }
}
