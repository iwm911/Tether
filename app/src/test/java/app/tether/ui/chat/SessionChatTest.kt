package app.tether.ui.chat

import app.tether.core.ASK_USER_QUESTION
import app.tether.core.AskAnswer
import app.tether.core.ChatItem
import app.tether.core.ConversationState
import app.tether.core.DialogKind
import app.tether.core.DialogOption
import app.tether.core.FollowEvent
import app.tether.core.LinkState
import app.tether.core.PeerDirection
import app.tether.core.PeerMessage
import app.tether.core.PermissionDecision
import app.tether.core.PermissionMode
import app.tether.core.PermissionState
import app.tether.core.RunStatus
import app.tether.core.Session
import app.tether.core.SessionDecision
import app.tether.core.SessionKey
import app.tether.core.SessionLive
import app.tether.core.SessionPending
import app.tether.core.SessionProcess
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.ToolStatus
import app.tether.remote.SessionProtocol
import app.tether.remote.SessionReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation logic of the session screen (items with peers, answers, mode keys, dialog keys, labels). */
class SessionChatTest {

    private val sid = "7b9f8c4c-6ac7-4d5a-9111-003abd24390e"
    private val ref = SessionRef("conn", sid)

    private fun fixture(name: String): List<FollowEvent> =
        (javaClass.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")).readText()
            .lines().filter { it.isNotBlank() }.mapNotNull { SessionProtocol.parseFollowLine(it) }

    private fun reduce(events: List<FollowEvent>): ConversationState {
        val r = SessionReducer(ref) { 1_000_000L }
        events.forEach { r.accept(it) }
        return r.snapshot()
    }

    // ───────────── items ─────────────

    @Test
    fun draftStreamsAsTheLastRowThenTheFinalMessageReplacesIt() {
        val evs = fixture("session/follow_turn.jsonl")
        val cut = evs.indexOfFirst { it is FollowEvent.Draft && it.text == "Hello wor" }
        val mid = sessionChatItems(reduce(evs.subList(0, cut + 1)), showThinking = true)
        val last = mid.last() as ChatItem.AssistantText
        assertTrue("draft is streaming", last.streaming)
        assertEquals("Hello wor", last.text)

        val done = sessionChatItems(reduce(evs), showThinking = true)
        val texts = done.filterIsInstance<ChatItem.AssistantText>()
        assertTrue("no streaming row left", texts.none { it.streaming })
        assertEquals(listOf("PONG-ONE", "Hello **world**!"), texts.map { it.text.trim() })
        assertEquals("one key per row", done.size, done.map { it.key }.toSet().size)
    }

    @Test
    fun peersRenderAsTheirOwnRowsInAndOut() {
        val conv = reduce(fixture("session/follow_extras.jsonl"))
        val items = sessionChatItems(conv, showThinking = true)
        val peers = items.filterIsInstance<ChatItem.Peer>()
        assertEquals(2, peers.size)
        val incoming = peers.single { it.incoming }
        assertEquals("builder", incoming.peerName)
        assertEquals("tests are green", incoming.text)
        val out = peers.single { !it.incoming }
        assertEquals("thanks, merging", out.text)
        assertEquals("builder", out.peer)
        // The SendMessage tool row is replaced, not duplicated; the incoming user line is not a bubble.
        assertTrue(items.none { it is ChatItem.ToolCall && it.name == "SendMessage" })
        assertTrue(items.none { it is ChatItem.User && it.text.contains("cross-session-message") })
        assertTrue("incoming before the reply to it", items.indexOf(incoming) < items.indexOf(out))
        assertEquals("From builder", app.tether.ui.chat.render.peerLabel(incoming))
        assertEquals("To builder", app.tether.ui.chat.render.peerLabel(out))
    }

    @Test
    fun incomingPeersAreSlottedByTime() {
        val items = listOf(
            ChatItem.User("u1", "first", timestamp = 100),
            ChatItem.AssistantText("a1", "reply"),
            ChatItem.User("u2", "second", timestamp = 300),
            ChatItem.AssistantText("a2", "reply 2"),
        )
        fun peer(at: Long?, text: String) = PeerMessage(PeerDirection.IN, "uds:/x.sock", "bob", null, text, at)
        val out = mergePeers(items, listOf(peer(null, "late"), peer(200, "middle"), peer(50, "early"), peer(200, "middle")))
        assertEquals(
            listOf("early", "first", "reply", "middle", "second", "reply 2", "late"),
            out.map { (it as? ChatItem.Peer)?.text ?: (it as? ChatItem.User)?.text ?: (it as ChatItem.AssistantText).text },
        )
        // Feeding the result back in adds nothing (stable keys).
        assertEquals(out, mergePeers(out, listOf(peer(200, "middle"))))
    }

    @Test
    fun sendMessageWithoutTextStaysAToolRow() {
        val t = ChatItem.ToolCall("t", "toolu_1", "SendMessage", "{\"to\":\"x\"}", ToolStatus.RUNNING)
        assertEquals(t, outgoingPeerOrSelf(t))
    }

    // ───────────── answers ─────────────

    private fun perm(tool: String) = ChatItem.Permission("p", "toolu_P", tool, "toolu_P", "{}", null, null, emptyList(), PermissionState.PENDING)

    @Test
    fun permissionPanelGetsDontAskAgainButQuestionsDoNot() {
        val out = withSessionSuggestions(listOf(perm("Bash"), perm(ASK_USER_QUESTION)))
        assertEquals(ALWAYS_ALLOW_SENTINEL, out[0].suggestions.single().rawJson)
        assertTrue(out[1].suggestions.isEmpty())
    }

    @Test
    fun decisionsMapToTheProtocol() {
        assertEquals(SessionReply.Answer(SessionDecision.ALLOW, null), sessionReplyFor("Bash", PermissionDecision.Allow()))
        assertEquals(
            SessionReply.Answer(SessionDecision.ALLOW_ALWAYS, null),
            sessionReplyFor("Bash", PermissionDecision.Allow(alwaysAllow = listOf(ALWAYS_ALLOW_SENTINEL))),
        )
        assertEquals(SessionReply.Answer(SessionDecision.DENY, null), sessionReplyFor("Bash", PermissionDecision.Deny()))
        assertEquals(SessionReply.Answer(SessionDecision.DENY, "use staging"), sessionReplyFor("Bash", PermissionDecision.Deny("use staging")))
        val answers = listOf(AskAnswer(listOf(1)), AskAnswer(emptyList(), "other"))
        assertEquals(SessionReply.Ask(answers), sessionReplyFor(ASK_USER_QUESTION, PermissionDecision.Answer(answers)))
        assertEquals(SessionReply.Dismiss, sessionReplyFor(ASK_USER_QUESTION, PermissionDecision.Deny("The user chose not to answer.")))
    }

    // ───────────── mode / model ─────────────

    @Test
    fun modeKeysFollowTheShiftTabCycle() {
        assertEquals(1, modeKeyPresses(null, PermissionMode.ACCEPT_EDITS))
        assertEquals(2, modeKeyPresses("default", PermissionMode.PLAN))
        assertEquals(0, modeKeyPresses("plan", PermissionMode.PLAN))
        assertEquals(2, modeKeyPresses("plan", PermissionMode.DEFAULT)) // plan → auto → default
        assertNull(modeKeyPresses("default", PermissionMode.BYPASS))
        assertEquals(PermissionMode.ACCEPT_EDITS, nextMode(null))
        assertEquals(PermissionMode.DEFAULT, nextMode("auto"))
        assertEquals("/model haiku", modelCommand(" haiku "))
    }

    // ───────────── dialogs ─────────────

    private val mcp = SessionPending.Dialog(
        dialog = DialogKind.MCP_SERVERS,
        title = "",
        options = listOf(DialogOption("github", checked = true), DialogOption("postgres", checked = false)),
        keys = listOf("up", "down", "space", "enter", "esc"),
    )

    @Test
    fun dialogKeysAndLabels() {
        assertEquals(SessionKey.Named("down"), dialogKey("down"))
        assertEquals(SessionKey.Named("3"), dialogKey("3"))
        assertEquals(SessionKey.Text("y"), dialogKey("y"))
        assertEquals("↑", keyLabel("up"))
        assertEquals("Esc", keyLabel("esc"))
        assertEquals("New MCP servers found in this project", dialogTitle(mcp))
        assertEquals("Trust?", dialogTitle(mcp.copy(dialog = DialogKind.TRUST, title = " Trust? ")))
        // No per-option key: walk the cursor (top, then down) and Space / Enter.
        assertEquals(listOf(SessionKey.Up, SessionKey.Up, SessionKey.Down, SessionKey.Space), toggleKeys(mcp, 1))
        assertEquals(listOf(SessionKey.Down, SessionKey.Enter), optionKeys(mcp, 1))
        // The helper's own key wins.
        val keyed = mcp.copy(options = listOf(DialogOption("Yes", key = "1"), DialogOption("No", key = "esc")))
        assertEquals(listOf(SessionKey.Named("1")), optionKeys(keyed, 0))
        assertEquals(listOf(SessionKey.Esc), toggleKeys(keyed, 1))
    }

    @Test
    fun statusVerbAndHeldText() {
        assertEquals("Improvising", statusVerb("Improvising…"))
        assertEquals("Thinking", statusVerb("Thinking..."))
        assertEquals("Working", statusVerb("…"))
        assertEquals("Open in a terminal on box · type /bg there to continue here", heldByTerminalText("box"))
    }

    // ───────────── header status ─────────────

    private fun ui(session: Session?, status: RunStatus = RunStatus.IDLE, agentId: String? = null, waking: Boolean = false, link: LinkState = LinkState.Idle) =
        SessionChatUiState(
            ref = ref,
            agentId = agentId,
            conversation = ConversationState(status = status, link = link, loadingHistory = false, live = SessionLive(ref = ref, session = session, heldByTerminal = session?.heldByTerminal == true)),
            items = emptyList(),
            machineName = "box",
            machineAccent = 0,
            showThinking = true,
            compactTools = true,
            waking = waking,
            permissionMode = null,
            model = null,
        )

    @Test
    fun statusLabels() {
        val s = Session(sessionId = sid, state = SessionState.IDLE, process = SessionProcess.LIVE)
        assertEquals("Idle", sessionStatusLabel(ui(s)))
        assertEquals("Stopped · a reply wakes it", sessionStatusLabel(ui(s.copy(process = SessionProcess.RETIRED))))
        assertEquals("Working", sessionStatusLabel(ui(s.copy(state = SessionState.WORKING))))
        assertEquals("Needs you", sessionStatusLabel(ui(s.copy(state = SessionState.NEEDS_YOU))))
        assertEquals("In a terminal", sessionStatusLabel(ui(s.copy(heldBy = app.tether.core.Holder.TERMINAL))))
        assertEquals("Waking…", sessionStatusLabel(ui(s.copy(process = SessionProcess.RETIRED), waking = true)))
        assertEquals("Subagent", sessionStatusLabel(ui(s, agentId = "a1")))
        assertEquals("Offline", sessionStatusLabel(ui(s, link = LinkState.Failed("down", 0))))
    }
}
