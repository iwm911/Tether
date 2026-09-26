package app.tether.remote

import app.tether.core.AgentEvent
import app.tether.core.AppSettings
import app.tether.core.AuthMethod
import app.tether.core.ChatItem
import app.tether.core.Connection
import app.tether.core.ConnectionRepository
import app.tether.core.ConversationState
import app.tether.core.ExecResult
import app.tether.core.LinkState
import app.tether.core.NoticeKind
import app.tether.core.PermissionDecision
import app.tether.core.ProbeResult
import app.tether.core.RunStatus
import app.tether.core.SettingsRepository
import app.tether.core.SshManager
import app.tether.core.StartRunRequest
import app.tether.core.TestProgress
import app.tether.core.ToolStatus
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * End-to-end run of the real helper + [HelperClaudeRemote] + [DefaultAgentHub] against THIS machine,
 * with an [SshManager] that runs commands locally. Spends a few cents of Haiku.
 *
 *   TETHER_E2E=1 ./gradlew :app:testDebugUnitTest --tests app.tether.remote.LocalE2ETest
 *
 * Needs python3 + claude on PATH. Runs in ~/tether-exp/e2e and deletes its run afterwards.
 */
class LocalE2ETest {

    private class LocalSsh : SshManager {
        override suspend fun download(connectionId: String, remotePath: String, dest: java.io.File, onProgress: (Long, Long) -> Unit) {}

        override val linkEpochs = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
        override suspend fun revalidate() {}

        override val states: StateFlow<Map<String, LinkState>> = MutableStateFlow(mapOf(CONN to LinkState.Connected(since = 0L)))

        override suspend fun exec(connectionId: String, command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult =
            withContext(Dispatchers.IO) {
                val p = ProcessBuilder("bash", "-c", command).start()
                p.outputStream.use { o -> if (stdin != null) o.write(stdin) }
                val err = async { p.errorStream.readBytes().toString(Charsets.UTF_8) }
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly()
                ExecResult(p.exitValue(), out, err.await())
            }

        override fun streamLines(connectionId: String, command: String): Flow<String> = callbackFlow {
            val p = ProcessBuilder("bash", "-c", command).start()
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

    private class Settings : SettingsRepository {
        override val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    @Test
    fun fullAgentLifecycle() = runBlocking {
        assumeTrue("set TETHER_E2E=1 to run", System.getenv("TETHER_E2E") == "1")
        val helperFile = File(System.getProperty("tether.helper") ?: "src/main/assets/tether_helper.py")
        assumeTrue("helper script not found at $helperFile", helperFile.isFile)
        val home = System.getProperty("user.home")
        val work = File(home, "tether-exp/e2e").apply { mkdirs() }
        File(work, "e2e.txt").delete()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ssh = LocalSsh()
        val remote = HelperClaudeRemote(ssh, Conns(), scope) { helperFile.readBytes() }
        val hub = DefaultAgentHub(remote, ssh, Conns(), Settings(), scope)
        try {
            // ── read-only surface ──
            val probe = remote.probe(CONN)
            assertTrue(probe.helperReady)
            assertNotNull("claude version", probe.claudeVersion)
            assertTrue(remote.listProjects(CONN).isNotEmpty())
            assertTrue(remote.listSessions(CONN, limit = 5).isNotEmpty())
            assertEquals(home, remote.listDir(CONN, null).path)

            val events = java.util.Collections.synchronizedList(ArrayList<AgentEvent>())
            scope.launch { hub.events.collect { events.add(it) } }
            scope.launch { hub.agents.collect { } } // an observer → watch streams open

            // ── start: needs a Bash permission ──
            val ref = hub.start(
                CONN,
                StartRunRequest(
                    cwd = work.path, model = "haiku", permissionMode = "default",
                    prompt = "Run this bash command: echo e2e > e2e.txt . Then reply with just DONE.",
                ),
            )
            val latest = MutableStateFlow(ConversationState())
            val convJob = scope.launch { hub.conversation(ref).collect { latest.value = it } }

            suspend fun await(what: String, timeoutMs: Long = 120_000, cond: (ConversationState) -> Boolean): ConversationState =
                try {
                    withTimeout(timeoutMs) {
                        while (!cond(latest.value)) delay(100)
                        latest.value
                    }
                } catch (e: Exception) {
                    throw AssertionError("timed out waiting for $what; last state: status=${latest.value.status} items=${latest.value.items.map { it.javaClass.simpleName }}", e)
                }

            val waiting = await("permission") { it.pendingPermissions.isNotEmpty() }
            assertEquals(RunStatus.AWAITING_PERMISSION, waiting.status)
            val perm = waiting.pendingPermissions.single()
            assertEquals("Bash", perm.toolName)
            assertTrue(waiting.items.filterIsInstance<ChatItem.ToolCall>().any { it.status == ToolStatus.AWAITING_PERMISSION })
            assertTrue(waiting.commands.isNotEmpty())
            await("dashboard pending") { hub.agents.value.any { a -> a.ref == ref && a.run.pending?.requestId == perm.requestId } }

            hub.respond(ref, perm.requestId, PermissionDecision.Allow())
            val idle = await("first turn") { s -> s.status == RunStatus.IDLE && s.items.any { it is ChatItem.TurnSummary } }
            assertTrue(File(work, "e2e.txt").isFile)
            assertEquals(ToolStatus.SUCCESS, idle.items.filterIsInstance<ChatItem.ToolCall>().first { it.name == "Bash" }.status)
            assertTrue(idle.totalCostUsd > 0)
            val sessionId = idle.sessionId
            assertNotNull(sessionId)

            // ── second message ──
            hub.send(ref, "Reply with just the word TWO.")
            val two = await("second turn") { s -> s.items.count { it is ChatItem.TurnSummary } == 2 && s.status == RunStatus.IDLE }
            assertTrue(two.items.any { it is ChatItem.AssistantText && it.text.contains("TWO") })
            val users = two.items.filterIsInstance<ChatItem.User>()
            assertEquals(2, users.size)
            assertFalse(users.any { it.queued })

            try {
                withTimeout(20_000) {
                    while (events.none { it is AgentEvent.TurnCompleted }) delay(200)
                }
            } catch (e: Exception) {
                throw AssertionError("no TurnCompleted; events=$events errors=${hub.machineErrors.value} agents=${hub.agents.value.map { it.run.status to it.run.turns }}", e)
            }
            assertTrue(events.any { it is AgentEvent.PermissionRequested && it.requestId == perm.requestId })

            // ── interrupt a long answer ──
            hub.send(ref, "Count slowly from 1 to 400, one number per line, no other text.")
            await("streaming") { s -> s.status == RunStatus.WORKING && s.items.lastOrNull().let { it is ChatItem.AssistantText && it.streaming } }
            hub.interrupt(ref)
            val stopped = await("interrupted") { s -> s.status == RunStatus.IDLE && s.items.any { it is ChatItem.Notice && it.kind == NoticeKind.INTERRUPTED } }
            assertEquals(2, stopped.items.count { it is ChatItem.TurnSummary })

            // ── mode change ──
            hub.setPermissionMode(ref, "plan")
            await("plan mode") { it.permissionMode == "plan" }

            // ── stop, remove ──
            hub.stop(ref)
            await("ended", 30_000) { it.status == RunStatus.ENDED }
            withTimeout(20_000) { while (events.none { it is AgentEvent.Ended && it.ref == ref }) delay(200) }
            convJob.cancel()
            hub.remove(ref)
            assertTrue(remote.listRuns(CONN).none { it.runId == ref.runId })

            // ── the session's transcript ──
            val t = hub.transcript(CONN, sessionId!!)
            assertEquals(RunStatus.ENDED, t.status)
            assertTrue(t.items.filterIsInstance<ChatItem.User>().any { it.text.contains("TWO") })
            println("E2E OK · cost \$${idle.totalCostUsd} · events ${events.map { it.javaClass.simpleName }}")
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val CONN = "local"
    }
}
