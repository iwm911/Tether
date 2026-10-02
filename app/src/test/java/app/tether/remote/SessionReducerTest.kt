package app.tether.remote

import app.tether.core.ChatItem
import app.tether.core.DialogKind
import app.tether.core.FollowEvent
import app.tether.core.Holder
import app.tether.core.LinkState
import app.tether.core.PeerDirection
import app.tether.core.PermissionState
import app.tether.core.RunStatus
import app.tether.core.Session
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.SessionTaskStatus
import app.tether.core.SubagentStatus
import app.tether.core.TodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every behaviour of the follow-event reducer (draft → final, status, state, peers, subagents, tasks, todos…). */
class SessionReducerTest {

    private val sid = "7b9f8c4c-6ac7-4d5a-9111-003abd24390e"
    private val ref = SessionRef("conn", sid)
    private var now = 1_000_000L
    private fun reducer(agentId: String? = null) = SessionReducer(ref, agentId) { now }

    private fun fixture(name: String): String =
        (javaClass.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")).readText()

    private fun events(name: String): List<FollowEvent> =
        fixture(name).lines().filter { it.isNotBlank() }.mapNotNull { SessionProtocol.parseFollowLine(it) }

    private fun SessionReducer.feed(evs: List<FollowEvent>) = evs.forEach { accept(it) }

    private fun session(state: SessionState, updatedAt: Long, pending: SessionPending? = null, heldBy: Holder = Holder.DAEMON) =
        Session(sessionId = sid, cwd = "/p", name = "probe", state = state, pending = pending, heldBy = heldBy, updatedAt = updatedAt, connectionId = "conn")

    private fun line(json: String) = SessionProtocol.parseFollowLine("""{"e":"line","line":$json}""")!!

    private fun assistant(uuid: String, text: String, parent: String? = null) = line(
        """{"type":"assistant","isSidechain":false,"parent_tool_use_id":${parent?.let { "\"$it\"" } ?: "null"},"message":{"id":"m-$uuid","role":"assistant","content":[{"type":"text","text":"$text"}]},"uuid":"$uuid","timestamp":"2026-10-01T17:16:06.000Z"}""",
    )

    private fun user(uuid: String, text: String) = line(
        """{"type":"user","isSidechain":false,"message":{"role":"user","content":"$text"},"uuid":"$uuid","timestamp":"2026-10-01T17:16:00.000Z"}""",
    )

    private fun FollowEvent.at(offset: Long) = (this as FollowEvent.Line).copy(offset = offset)

    private fun texts(r: SessionReducer) = r.snapshot().items.mapNotNull { (it as? ChatItem.AssistantText)?.let { a -> a.text to a.streaming } }

    // ───────────── draft → final ─────────────

    @Test
    fun wholeTurnFromFixture() {
        val evs = events("session/follow_turn.jsonl")
        val r = reducer()
        // history up to caughtUp
        r.feed(evs.take(3))
        var s = r.snapshot()
        assertTrue(s.loadingHistory)
        assertEquals(listOf("Reply with exactly: PONG-ONE."), s.items.filterIsInstance<ChatItem.User>().map { it.text })
        r.accept(evs[3])
        s = r.snapshot()
        assertFalse(s.loadingHistory)
        assertEquals(4096L, r.offset)
        assertEquals(RunStatus.IDLE, s.status)
        assertEquals("probe", s.title)
        assertEquals(sid, s.sessionId)
        assertEquals("/home/user/tether-exp/live", s.cwd)
        assertEquals("haiku", s.model)
        assertEquals("acceptEdits", s.permissionMode)

        // live: working, prompt, status, two drafts
        r.feed(evs.subList(4, 9))
        s = r.snapshot()
        assertEquals(RunStatus.WORKING, s.status)
        val last = s.items.last() as ChatItem.AssistantText
        assertEquals("Hello wor", last.text)
        assertTrue(last.streaming)
        assertEquals("Hello wor", s.live!!.draft)
        assertEquals("Improvising…", s.live!!.status!!.verb)
        assertEquals(now - 3_000, s.workingSince)

        // the final line replaces the draft; a stale screen draft of the same text is ignored
        r.feed(evs.subList(9, 12))
        s = r.snapshot()
        assertEquals(listOf("PONG-ONE" to false, "Hello **world**!" to false), texts(r))
        assertNull(s.live!!.draft)
        assertEquals(240L, s.live!!.status!!.tokens)

        // clear + status off + idle
        r.feed(evs.subList(12, evs.size))
        s = r.snapshot()
        assertEquals(RunStatus.IDLE, s.status)
        assertNull(s.live!!.status)
        assertNull(s.workingSince)
        assertEquals(listOf("PONG-ONE" to false, "Hello **world**!" to false), texts(r))
        assertEquals(4, s.items.size) // user, assistant, user, assistant — no leftover draft
    }

    @Test
    fun draftReplacesItselfAndDraftClearRemovesIt() {
        val r = reducer()
        r.accept(FollowEvent.Draft("One"))
        val k1 = r.snapshot().items.last().key
        r.accept(FollowEvent.Draft("One two"))
        assertEquals(1, r.snapshot().items.size)
        assertEquals(k1, r.snapshot().items.last().key) // same row grows (stable key)
        assertEquals("One two", (r.snapshot().items.last() as ChatItem.AssistantText).text)
        r.accept(FollowEvent.DraftClear)
        assertTrue(r.snapshot().items.isEmpty())
        r.accept(FollowEvent.Draft("Next"))
        assertFalse(k1 == r.snapshot().items.last().key) // a new draft is a new row
        r.accept(FollowEvent.Draft("   "))
        assertTrue(r.snapshot().items.isEmpty()) // a blank draft clears
    }

    @Test
    fun staleDraftIgnoredUntilTheNextPrompt() {
        val r = reducer()
        r.accept(FollowEvent.Draft("Hel"))
        r.accept(assistant("a1", "Hello **world**"))
        r.accept(FollowEvent.Draft("Hello world")) // still on screen after it landed
        assertNull(r.snapshot().live!!.draft)
        r.accept(FollowEvent.Draft("Something else entirely")) // new text in the same turn shows
        assertEquals("Something else entirely", r.snapshot().live!!.draft)
        r.accept(FollowEvent.DraftClear)
        r.accept(user("u2", "again"))
        r.accept(FollowEvent.Draft("Hello world")) // same words in a new turn are a real draft
        assertEquals("Hello world", r.snapshot().live!!.draft)
    }

    @Test
    fun aNewReplyOpeningLikeTheLastOneStillStreams() {
        // Review round 2: the guard took any substring of the landed text as stale, so "Let me…" never streamed.
        val r = reducer()
        r.accept(assistant("a1", "Done with the build. Let me check the tests."))
        r.accept(FollowEvent.Draft("Let me check the tests.")) // the landed message's end, its top scrolled off: stale
        assertNull(r.snapshot().live!!.draft)
        r.accept(FollowEvent.Draft("Let me"))
        assertEquals("Let me", r.snapshot().live!!.draft)
        r.accept(FollowEvent.Draft("Done"))
        assertEquals("Done", r.snapshot().live!!.draft)
    }

    @Test
    fun aToolCallSinceTheTextLandedEndsTheGuard() {
        val r = reducer()
        r.accept(assistant("a1", "Done."))
        r.accept(FollowEvent.Draft("Done."))
        assertNull(r.snapshot().live!!.draft)
        r.accept(line("""{"type":"assistant","isSidechain":false,"message":{"id":"m-t","role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}]},"uuid":"t1","timestamp":"2026-10-01T17:16:07.000Z"}"""))
        r.accept(line("""{"type":"user","isSidechain":false,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"x"}]},"uuid":"r1","timestamp":"2026-10-01T17:16:08.000Z"}"""))
        r.accept(FollowEvent.Draft("Done."))
        assertEquals("Done.", r.snapshot().live!!.draft)
    }

    @Test
    fun theFollowStateWinsOverAWatchCopyOfTheSameMoment() {
        // Review round 2: a dialog's checkbox (screen-only, no mtime change) jumped back to the watch's copy.
        val r = reducer()
        val checked = SessionPending.Dialog(DialogKind.MCP_SERVERS, "2 new MCP servers found", options = listOf(app.tether.core.DialogOption("a", checked = true)))
        val unchecked = checked.copy(options = listOf(app.tether.core.DialogOption("a", checked = false)))
        assertTrue(r.applySession(session(SessionState.NEEDS_YOU, 10, checked), fromWatch = true))
        r.accept(FollowEvent.State(session(SessionState.NEEDS_YOU, 10, unchecked)))
        assertEquals(unchecked, r.snapshot().live!!.pending)
        assertFalse(r.applySession(session(SessionState.NEEDS_YOU, 10, checked), fromWatch = true))
        assertEquals(unchecked, r.snapshot().live!!.pending)
        // a strictly newer watch copy still wins (the follow may have dropped)
        assertTrue(r.applySession(session(SessionState.WORKING, 11), fromWatch = true))
        assertEquals(RunStatus.WORKING, r.snapshot().status)
    }

    @Test
    fun subagentAssistantLineDoesNotClearTheMainDraft() {
        val r = reducer()
        r.accept(FollowEvent.Draft("Working on it"))
        r.accept(assistant("sub1", "inner", parent = "toolu_X"))
        assertEquals("Working on it", r.snapshot().live!!.draft)
    }

    // ───────────── status / state ─────────────

    @Test
    fun statusDrivesTheWorkingIndicatorAndKeepsItsStart() {
        val r = reducer()
        r.accept(FollowEvent.State(session(SessionState.IDLE, 1)))
        r.accept(FollowEvent.CaughtUp(0))
        assertEquals(RunStatus.IDLE, r.snapshot().status)
        r.accept(FollowEvent.Status("Thinking…", 2, 10))
        val since = r.snapshot().live!!.status!!.since
        assertEquals(RunStatus.WORKING, r.snapshot().status) // spinner up before state.json says so
        assertEquals(now - 2_000, since)
        now += 1_000
        r.accept(FollowEvent.Status("Thinking…", 3, 20))
        assertEquals(since, r.snapshot().live!!.status!!.since) // steady across ticks
        r.accept(FollowEvent.Status(null, null, null))
        assertEquals(RunStatus.IDLE, r.snapshot().status)
    }

    @Test
    fun stateMapping() {
        val r = reducer()
        assertEquals(RunStatus.STARTING, r.snapshot().status)
        r.accept(FollowEvent.State(session(SessionState.WORKING, 1)))
        assertEquals(RunStatus.WORKING, r.snapshot().status)
        r.accept(FollowEvent.State(session(SessionState.DONE, 2)))
        assertEquals(RunStatus.IDLE, r.snapshot().status)
        r.accept(FollowEvent.State(session(SessionState.FAILED, 3)))
        assertEquals(RunStatus.FAILED, r.snapshot().status)
        // an older description never overwrites a newer one
        assertFalse(r.applySession(session(SessionState.WORKING, 2)))
        assertEquals(RunStatus.FAILED, r.snapshot().status)
        assertTrue(r.applySession(session(SessionState.IDLE, 4)))
        assertEquals(RunStatus.IDLE, r.snapshot().status)
        assertEquals("conn", r.currentSession!!.connectionId)
    }

    @Test
    fun permissionAndQuestionBecomePendingPermissions() {
        val r = reducer()
        val perm = SessionPending.Permission("toolu_P", "Bash", "rm -rf build", """{"command":"rm -rf build"}""")
        r.accept(FollowEvent.State(session(SessionState.NEEDS_YOU, 1, perm)))
        var s = r.snapshot()
        assertEquals(RunStatus.AWAITING_PERMISSION, s.status)
        val p = s.pendingPermissions.single()
        assertEquals("toolu_P", p.requestId)
        assertEquals("toolu_P", p.toolUseId)
        assertEquals("Bash", p.toolName)
        assertEquals("""{"command":"rm -rf build"}""", p.inputJson)
        assertEquals("rm -rf build", p.description)
        assertEquals(PermissionState.PENDING, p.state)
        assertEquals(perm, s.live!!.pending)

        val q = SessionPending.Question("toolu_Q", "AskUserQuestion", "Which DB?", """{"questions":[]}""")
        r.accept(FollowEvent.State(session(SessionState.NEEDS_YOU, 2, q)))
        s = r.snapshot()
        assertEquals("AskUserQuestion", s.pendingPermissions.single().toolName)

        r.accept(FollowEvent.State(session(SessionState.WORKING, 3)))
        assertTrue(r.snapshot().pendingPermissions.isEmpty())
        assertNull(r.snapshot().live!!.pending)
    }

    @Test
    fun dialogIsPendingButNotAPermission() {
        val r = reducer()
        val d = SessionPending.Dialog(DialogKind.MCP_SERVERS, "2 new MCP servers found", "Select", keys = listOf("space", "enter", "esc"))
        r.accept(FollowEvent.State(session(SessionState.NEEDS_YOU, 1, d)))
        val s = r.snapshot()
        assertEquals(RunStatus.AWAITING_PERMISSION, s.status)
        assertTrue(s.pendingPermissions.isEmpty())
        assertEquals(d, s.live!!.pending)
    }

    @Test
    fun heldByTerminal() {
        val r = reducer()
        r.accept(FollowEvent.State(session(SessionState.WORKING, 1, heldBy = Holder.TERMINAL).copy(terminalPid = 4242)))
        val live = r.snapshot().live!!
        assertTrue(live.heldByTerminal)
        assertEquals(4242, live.terminalPid)
        r.accept(FollowEvent.State(session(SessionState.IDLE, 2, heldBy = Holder.DAEMON)))
        assertFalse(r.snapshot().live!!.heldByTerminal)
    }

    // ───────────── peers, subagents, tasks, todos ─────────────

    @Test
    fun extrasFromFixture() {
        val r = reducer()
        r.feed(events("session/follow_extras.jsonl"))
        val s = r.snapshot()
        val live = s.live!!

        // peers: the incoming one once (duplicate event, its user line and queue line not shown as chat),
        // the outgoing one from the SendMessage call
        assertEquals(2, live.peers.size)
        val inbound = live.peers.single { it.dir == PeerDirection.IN }
        assertEquals("builder", inbound.peerName)
        assertEquals("tests are green", inbound.text)
        val out = live.peers.single { it.dir == PeerDirection.OUT }
        assertEquals("builder", out.peer)
        assertEquals("thanks, merging", out.text)
        assertEquals("toolu_SEND", out.toolUseId)
        assertTrue(s.items.none { it is ChatItem.User })
        assertTrue(s.items.any { it is ChatItem.ToolCall && it.name == "SendMessage" })

        // subagents: merged by id, status follows, earlier fields kept
        assertEquals(2, live.subagents.size)
        val a = live.subagents.first { it.agentId == "a1b2c3" }
        assertEquals(SubagentStatus.DONE, a.status)
        assertEquals("Explore", a.agentType)
        assertEquals("Find the config loader", a.description)
        assertTrue(live.subagents.first { it.agentId == "d4e5f6" }.background)

        // tasks: merged by id; only running ones are background work
        assertEquals(3, live.tasks.size)
        val m9 = live.tasks.first { it.taskId == "m9" }
        assertEquals(SessionTaskStatus.KILLED, m9.status)
        assertEquals("watch logs", m9.summary)
        assertEquals(listOf("b7x1"), s.backgroundTasks.map { it.id })
        assertEquals("local_bash", s.backgroundTasks.single().type)
        assertEquals("npm run dev", s.backgroundTasks.single().description)

        // todos
        assertEquals(listOf("Write tests", "Fix bug", "Ship"), s.todos.map { it.content })
        assertEquals(listOf(TodoStatus.COMPLETED, TodoStatus.IN_PROGRESS, TodoStatus.PENDING), s.todos.map { it.status })
        assertEquals(3, live.todoLists["L1"]!!.size)

        // state with a pending permission and a queue
        assertEquals(RunStatus.AWAITING_PERMISSION, s.status)
        assertEquals(1, s.queuedCount)
        assertEquals(9000L, live.offset)
        assertTrue(live.caughtUp)
    }

    @Test
    fun todoListsLatestWinsAndEmptyListClears() {
        val r = reducer()
        r.accept(FollowEvent.Todos("A", listOf(app.tether.core.SessionTodo("1", "first", "pending"))))
        r.accept(FollowEvent.Todos("B", listOf(app.tether.core.SessionTodo("1", "second", "completed"))))
        assertEquals(listOf("second"), r.snapshot().todos.map { it.content })
        r.accept(FollowEvent.Todos("B", emptyList()))
        assertTrue(r.snapshot().todos.isEmpty())
        assertEquals(2, r.snapshot().live!!.todoLists.size)
    }

    // ───────────── reconnect ─────────────

    @Test
    fun replayAfterReconnectIsDeduplicatedAndDraftIsDropped() {
        val evs = events("session/follow_turn.jsonl")
        val r = reducer()
        r.feed(evs.take(4))
        r.feed(evs.subList(4, 9)) // live: draft + status up
        assertNotNull(r.snapshot().live!!.draft)
        r.onDisconnected()
        var s = r.snapshot()
        assertNull(s.live!!.draft)
        assertNull(s.live!!.status)
        assertEquals(4096L, r.offset) // resume point
        // the helper resends from the offset: the prompt line comes again, then the rest
        r.feed(evs.subList(4, evs.size))
        s = r.snapshot()
        assertEquals(2, s.items.filterIsInstance<ChatItem.User>().size)
        assertEquals(2, s.items.filterIsInstance<ChatItem.AssistantText>().size)
    }

    @Test
    fun lineOffsetsAdvanceTheResumePoint() {
        val r = reducer()
        r.accept(FollowEvent.CaughtUp(100))
        r.accept(FollowEvent.Line("""{"type":"system","uuid":"s1"}""", "s1", 250))
        assertEquals(250L, r.offset)
        // caughtUp is the helper's real position: lower only when the transcript was replaced and it started over.
        r.accept(FollowEvent.CaughtUp(200))
        assertEquals(200L, r.offset)
    }

    @Test
    fun aReplacedTranscriptIsRebuiltFromScratchAndResumesFromItsRealOffset() {
        val r = reducer()
        r.accept(user("u1", "old prompt").at(400))
        r.accept(assistant("a1", "old answer").at(900))
        r.accept(FollowEvent.CaughtUp(900))
        assertEquals(900L, r.beginFollow())
        // The file was replaced (smaller): the helper replays it from 0, then reports where it really is.
        r.accept(user("u2", "new prompt").at(120))
        r.accept(FollowEvent.CaughtUp(120))
        assertEquals(120L, r.offset)
        assertEquals(listOf("new prompt"), r.snapshot().items.filterIsInstance<ChatItem.User>().map { it.text })
        assertTrue(r.snapshot().items.filterIsInstance<ChatItem.AssistantText>().isEmpty())
        // The next follow resumes at 120: lines past it append normally.
        assertEquals(120L, r.beginFollow())
        r.accept(assistant("a2", "new answer").at(300))
        assertEquals(listOf("new answer"), texts(r).map { it.first })
        assertEquals(300L, r.offset)
    }

    @Test
    fun aNewFollowDropsTheLastVisitsDraftAndSpinner() {
        // Reopened from the cache after the turn ended while nobody watched: the helper's new follow only sends
        // what is on screen now (nothing), so the old draft and spinner must not survive.
        val r = reducer()
        r.accept(FollowEvent.CaughtUp(10))
        r.applySession(session(SessionState.IDLE, 5))
        r.accept(FollowEvent.Status("Improvising…", 3, null))
        r.accept(FollowEvent.Draft("half a rep"))
        assertEquals(RunStatus.WORKING, r.snapshot().status)
        r.beginFollow()
        val s = r.snapshot()
        assertNull(s.live!!.draft)
        assertNull(s.live!!.status)
        assertEquals(RunStatus.IDLE, s.status)
        assertNull(s.workingSince)
    }

    // ───────────── transcript reducer reuse ─────────────

    @Test
    fun lineEventsRenderExactlyLikeTheTranscriptReducer() {
        val raw = fixture("transcript_session.jsonl").lines().filter { it.isNotBlank() }
        val direct = TranscriptReducer { now }.also { sr -> raw.forEach { sr.acceptTranscript(it) } }.snapshot()
        val r = reducer()
        for (l in raw) SessionProtocol.parseFollowLine("""{"e":"line","line":$l}""")?.let { r.accept(it) }
        r.accept(FollowEvent.CaughtUp(1))
        val via = r.snapshot()
        assertEquals(direct.items, via.items)
        assertEquals(direct.todos, via.todos)
        assertTrue(via.items.isNotEmpty())
    }

    @Test
    fun subagentViewShowsSidechainLines() {
        val r = reducer(agentId = "a1b2c3")
        r.accept(line("""{"type":"user","isSidechain":true,"message":{"role":"user","content":"Find the config loader"},"uuid":"s-u","timestamp":"2026-10-01T17:16:00.000Z"}"""))
        r.accept(line("""{"type":"assistant","isSidechain":true,"message":{"id":"m","role":"assistant","content":[{"type":"text","text":"It is in Config.kt"}]},"uuid":"s-a","timestamp":"2026-10-01T17:16:01.000Z"}"""))
        r.accept(FollowEvent.Subagent(app.tether.core.SubagentInfo("a1b2c3", status = SubagentStatus.RUNNING)))
        var s = r.snapshot()
        assertEquals(2, s.items.size)
        assertEquals(RunStatus.WORKING, s.status)
        assertEquals("a1b2c3", s.live!!.agentId)
        r.accept(FollowEvent.Subagent(app.tether.core.SubagentInfo("a1b2c3", status = SubagentStatus.DONE)))
        s = r.snapshot()
        assertEquals(RunStatus.IDLE, s.status)

        // the main view does not show sidechain lines
        val main = reducer()
        main.accept(line("""{"type":"assistant","isSidechain":true,"message":{"id":"m","role":"assistant","content":[{"type":"text","text":"x"}]},"uuid":"s-a2"}"""))
        assertTrue(main.snapshot().items.isEmpty())
    }

    @Test
    fun optimisticUserReconciledByItsLine() {
        val r = reducer()
        val key = r.addOptimisticUser("Say hi", 0, queued = true)
        assertEquals(1, r.snapshot().queuedCount)
        assertTrue((r.snapshot().items.single() as ChatItem.User).queued)
        r.accept(user("u9", "Say hi"))
        val users = r.snapshot().items.filterIsInstance<ChatItem.User>()
        assertEquals(1, users.size)
        assertFalse(users.single().queued)
        assertEquals(key, users.single().key) // same row, no flicker
        assertEquals("u9", users.single().uuid)
        val k2 = r.addOptimisticUser("will fail", 0, queued = false)
        r.removeOptimistic(k2)
        assertEquals(1, r.snapshot().items.filterIsInstance<ChatItem.User>().size)
    }

    @Test
    fun linkAndErrorPassThrough() {
        val r = reducer()
        val s = r.snapshot(LinkState.Connected(5), "boom")
        assertEquals(LinkState.Connected(5), s.link)
        assertEquals("boom", s.error)
        assertEquals(ref, s.live!!.ref)
    }
}
