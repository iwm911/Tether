package app.tether.remote

import app.tether.core.AskAnswer
import app.tether.core.AuthMethod
import app.tether.core.Connection
import app.tether.core.ConnectionRepository
import app.tether.core.ExecResult
import app.tether.core.FollowEvent
import app.tether.core.ImageAttachment
import app.tether.core.LinkState
import app.tether.core.NewSessionRequest
import app.tether.core.ProbeResult
import app.tether.core.SessionDecision
import app.tether.core.SessionErrorCodes
import app.tether.core.SessionKey
import app.tether.core.SessionState
import app.tether.core.SshManager
import app.tether.core.TestProgress
import app.tether.core.WatchMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** The one-session helper commands as [HelperClaudeRemote] runs them: argv, stdin JSON, parsing, error codes. */
class HelperSessionRemoteTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val helperBytes = "HELPER_VERSION = \"2.0.0\"\n".toByteArray()
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(helperBytes).joinToString("") { "%02x".format(it) }
    private val ssh = FakeSsh(sha)
    private val remote = HelperClaudeRemote(ssh, Conns(), scope) { helperBytes }

    @After
    fun tearDown() = scope.cancel()

    private fun fixture(name: String): String =
        (javaClass.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")).readText()

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject

    private val sessionJson = """{"sessionId":"7b9f8c4c-6ac7-4d5a-9111-003abd24390e","cwd":"/p","state":"working","process":"live","heldBy":"daemon","updatedAt":5}"""

    @Test
    fun sessionsCommand() = runBlocking {
        ssh.reply = { _, _ -> ExecResult(0, fixture("session/sessions.json").replace("\n", "") + "\n", "") }
        val list = remote.sessions(CONN, cwd = "/home/user/proj", limit = 20, before = 1790875000000)
        assertEquals(7, list.size)
        assertTrue(list.all { it.connectionId == CONN })
        assertEquals(
            "sessions --cwd /home/user/proj --limit 20 --before 1790875000000",
            ssh.lastArgs(),
        )
    }

    @Test
    fun writeCommandsSendTheContractBodies() = runBlocking {
        ssh.reply = { args, _ ->
            when (args.substringBefore(' ')) {
                "send" -> ExecResult(0, """{"ok":true,"woke":true}""" + "\n", "")
                "key", "stop", "rm" -> ExecResult(0, """{"ok":true}""" + "\n", "")
                else -> ExecResult(0, sessionJson + "\n", "")
            }
        }
        val s = remote.newSession(CONN, NewSessionRequest("/p", "hi", "haiku", "acceptEdits", listOf("/h/a.png")))
        assertEquals("new", ssh.lastArgs())
        assertEquals(json("""{"cwd":"/p","prompt":"hi","model":"haiku","permissionMode":"acceptEdits","images":["/h/a.png"]}"""), json(ssh.lastStdin()))
        assertEquals(SessionState.WORKING, s.state)
        assertEquals(CONN, s.connectionId)

        assertTrue(remote.send(CONN, "sid1", "go on", listOf("/h/b.png")))
        assertEquals("send sid1", ssh.lastArgs())
        assertEquals(json("""{"text":"go on","images":["/h/b.png"]}"""), json(ssh.lastStdin()))

        remote.key(CONN, "sid1", listOf(SessionKey.ShiftTab, SessionKey.Text("/model opus")))
        assertEquals("key sid1", ssh.lastArgs())
        assertEquals(json("""{"keys":["shift-tab",{"text":"/model opus"}]}"""), json(ssh.lastStdin()))

        remote.answer(CONN, "sid1", SessionDecision.ALLOW_ALWAYS)
        assertEquals("answer sid1", ssh.lastArgs())
        assertEquals(json("""{"decision":"allow_always"}"""), json(ssh.lastStdin()))

        remote.ask(CONN, "sid1", listOf(AskAnswer(listOf(0))))
        assertEquals("ask sid1", ssh.lastArgs())
        assertEquals(json("""{"answers":[{"choices":[0],"other":null}]}"""), json(ssh.lastStdin()))

        remote.interrupt(CONN, "sid1")
        assertEquals("interrupt sid1", ssh.lastArgs())
        remote.stop(CONN, "sid1")
        assertEquals("stop sid1", ssh.lastArgs())
        remote.rm(CONN, "sid1")
        assertEquals("rm sid1", ssh.lastArgs())
    }

    @Test
    fun writeMayWrapTheSession() = runBlocking {
        ssh.reply = { _, _ -> ExecResult(0, """{"session":$sessionJson}""" + "\n", "") }
        assertEquals("7b9f8c4c-6ac7-4d5a-9111-003abd24390e", remote.interrupt(CONN, "x").sessionId)
    }

    @Test
    fun errorsCarryTheHelperCode() = runBlocking {
        ssh.reply = { _, _ -> ExecResult(1, """{"error":"A terminal on this machine has this session open.","code":"EHELD"}""" + "\n", "") }
        try {
            remote.send(CONN, "sid1", "hi")
            fail("expected EHELD")
        } catch (e: RemoteException) {
            assertEquals(SessionErrorCodes.EHELD, e.code)
            assertEquals("A terminal on this machine has this session open.", e.message)
        }
        ssh.reply = { _, _ -> ExecResult(1, """{"error":"untrusted","code":"EUNTRUSTED"}""" + "\n", "") }
        try {
            remote.newSession(CONN, NewSessionRequest("/q", "hi"))
            fail("expected EUNTRUSTED")
        } catch (e: RemoteException) {
            assertEquals(SessionErrorCodes.EUNTRUSTED, e.code)
        }
        // an old helper that still prints a bare list for `sessions` is "unreadable", not an empty machine
        ssh.reply = { _, _ -> ExecResult(0, "[]\n", "") }
        try {
            remote.sessions(CONN)
            fail("expected an unreadable list")
        } catch (e: RemoteException) {
            assertFalse(e.message.isNullOrBlank())
        }
    }

    @Test
    fun daemonStatus() = runBlocking {
        ssh.reply = { _, _ -> ExecResult(0, """{"running":true,"proto":1,"version":"2.1.287","auth":"ok","pid":7}""" + "\n", "") }
        val d = remote.daemonStatus(CONN)
        assertTrue(d.running)
        assertEquals("daemon-status", ssh.lastArgs())
    }

    @Test
    fun watchAndFollowStreams() = runBlocking {
        ssh.lines = fixture("session/watch.jsonl").lines().filter { it.isNotBlank() }
        val msgs = remote.watchSessions(CONN).take(5).toList()
        assertTrue(ssh.lastStream.endsWith("tether_helper.py watch"))
        val snap = msgs[0] as WatchMessage.Snapshot
        assertTrue(snap.sessions.all { it.connectionId == CONN })
        assertTrue((msgs[2] as WatchMessage.Changed).changed.all { it.connectionId == CONN })

        ssh.lines = fixture("session/follow_extras.jsonl").lines().filter { it.isNotBlank() }
        val evs = remote.follow(CONN, "sid1", agentId = "a1", fromOffset = 77).take(15).toList()
        assertTrue(ssh.lastStream.endsWith("tether_helper.py follow sid1 --agent a1 --from 77"))
        assertEquals(15, evs.size) // the unknown event at the end is skipped, nothing else
        val st = evs.filterIsInstance<FollowEvent.State>().single()
        assertEquals(CONN, st.session.connectionId)
    }

    @Test
    fun followErrorLineThrows() = runBlocking {
        ssh.lines = listOf("""{"error":"no such session","code":"ENOSESSION"}""")
        try {
            remote.follow(CONN, "nope").toList()
            fail("expected ENOSESSION")
        } catch (e: RemoteException) {
            assertEquals(SessionErrorCodes.ENOSESSION, e.code)
        }
        // the machine closing the stream is a (retryable) I/O failure, not a silent end
        ssh.lines = listOf("""{"e":"caughtUp","offset":1}""")
        ssh.endAfterLines = true
        try {
            remote.follow(CONN, "sid").toList()
            fail("expected the stream end to throw")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("closed"))
        }
    }

    @Test
    fun anOldHelperIsNeverAskedSessionCommands() = runBlocking {
        val oldBytes = "HELPER_VERSION = \"1.10.0\"\n".toByteArray()
        val oldSha = java.security.MessageDigest.getInstance("SHA-256").digest(oldBytes).joinToString("") { "%02x".format(it) }
        val oldSsh = FakeSsh(oldSha)
        val old = HelperClaudeRemote(oldSsh, Conns(), scope) { oldBytes }
        assertFalse(old.speaksSessions)
        assertTrue(remote.speaksSessions)
        for (call in listOf<suspend () -> Unit>(
            { old.watchSessions(CONN).toList() },
            { old.follow(CONN, "sid").toList() },
            { old.sessions(CONN) },
            { old.send(CONN, "sid", "hi") },
            { old.stop(CONN, "sid") },
            { old.rm(CONN, "sid") },
            { old.newSession(CONN, NewSessionRequest("/p", "x")) },
        )) {
            try {
                call()
                fail("expected EPROTO")
            } catch (e: RemoteException) {
                assertEquals(SessionErrorCodes.EPROTO, e.code)
            }
        }
        assertTrue(oldSsh.execs.isEmpty())
        assertEquals("", oldSsh.lastStream)
    }

    @Test
    fun uploadImageGoesUnderTetherUploads() = runBlocking {
        ssh.reply = { _, _ -> ExecResult(0, "{}\n", "") }
        val p = remote.uploadImage(CONN, ImageAttachment(byteArrayOf(1, 2), "image/png", "shot.png"))
        assertTrue(p, p.matches(Regex("/home/u/\\.tether/uploads/[0-9a-f-]{36}\\.png")))
        assertEquals(p, ssh.uploads.last().first)
        assertEquals("600".toInt(8), ssh.uploads.last().second)
    }

    // ───────────── fakes ─────────────

    private class FakeSsh(private val sha: String) : SshManager {
        val execs = ConcurrentLinkedQueue<Pair<String, String?>>()
        @Volatile var reply: (args: String, stdin: String?) -> ExecResult = { _, _ -> ExecResult(0, "{}\n", "") }
        @Volatile var lines: List<String> = emptyList()
        @Volatile var lastStream: String = ""
        @Volatile var endAfterLines = false
        val uploads = ConcurrentLinkedQueue<Pair<String, Int>>()

        /** Helper argv of an exec'd command line (after the helper path), unquoted for simple args. */
        private fun argsOf(command: String) = command.substringAfter("tether_helper.py").trim()

        fun lastArgs(): String = execs.filter { !it.first.startsWith("probe") }.last().first
        fun lastStdin(): String = execs.filter { !it.first.startsWith("probe") }.last().second ?: ""

        override val states: StateFlow<Map<String, LinkState>> = MutableStateFlow(emptyMap())
        override val linkEpochs = MutableStateFlow<Map<String, Long>>(emptyMap())
        override suspend fun revalidate() {}
        override suspend fun exec(connectionId: String, command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult {
            if (command.contains("printf 'HOME=%s")) return ExecResult(0, "HOME=/home/u\nSHA=$sha\n", "")
            val args = argsOf(command)
            if (args.startsWith("probe")) return ExecResult(0, "{}\n", "")
            val body = stdin?.toString(Charsets.UTF_8)
            execs.add(args to body)
            return reply(args, body)
        }
        override fun streamLines(connectionId: String, command: String): Flow<String> {
            lastStream = command
            val snapshot = lines
            // Like a live stream: the lines, then it stays open until the reader goes.
            return kotlinx.coroutines.flow.flow {
                snapshot.forEach { emit(it) }
                if (!endAfterLines) kotlinx.coroutines.awaitCancellation()
            }
        }
        override suspend fun upload(connectionId: String, remotePath: String, bytes: ByteArray, mode: Int) {
            uploads.add(remotePath to mode)
        }
        override suspend fun download(connectionId: String, remotePath: String, dest: java.io.File, onProgress: (Long, Long) -> Unit) {}
        override suspend fun test(connection: Connection, password: String?, onProgress: (TestProgress) -> Unit) =
            Result.failure<ProbeResult>(UnsupportedOperationException())
        override suspend fun installPublicKey(connection: Connection, password: String?, publicKey: String) =
            Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun disconnect(connectionId: String) = Unit
        override suspend fun disconnectAll() = Unit
    }

    private class Conns : ConnectionRepository {
        private val c = Connection(id = CONN, name = "Workstation", host = "h", username = "u", auth = AuthMethod.Password, createdAt = 0L)
        override val connections: StateFlow<List<Connection>> = MutableStateFlow(listOf(c))
        override fun get(id: String): Connection? = if (id == CONN) c else null
        override suspend fun upsert(connection: Connection, password: String?) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun markConnected(id: String, hostname: String?, claudeVersion: String?) = Unit
        override fun newId(): String = "x"
    }

    private companion object {
        const val CONN = "conn-1"
    }
}
