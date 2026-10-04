package app.tether.service

import app.tether.core.DialogKind
import app.tether.core.Holder
import app.tether.core.Session
import app.tether.core.SessionEvent
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.remote.SessionTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notification decisions keyed on session id + state (K3). */
class SessionAlertsTest {

    private fun s(id: String, state: SessionState, pending: SessionPending? = null, held: Boolean = false, waitingFor: String? = null, lastText: String? = null) =
        Session(
            sessionId = id, cwd = "/home/u/proj", name = "", intent = "Fix the build", state = state, pending = pending,
            heldBy = if (held) Holder.TERMINAL else Holder.DAEMON, waitingFor = waitingFor, lastText = lastText, connectionId = "c",
        )

    private val perm = SessionPending.Permission("toolu_1", "Bash", "npm test")
    private val ask = SessionPending.Question("toolu_2", "AskUserQuestion", "Which DB?")
    private val dialog = SessionPending.Dialog(DialogKind.MCP_SERVERS, "2 new MCP servers found", "\nSelect any you wish to enable.\n")

    // ───────────── live set / counts ─────────────

    @Test
    fun liveIsWorkingOrNeedsYouAndNotTerminalHeld() {
        val list = listOf(
            s("a", SessionState.WORKING),
            s("b", SessionState.NEEDS_YOU, perm),
            s("c", SessionState.IDLE),
            s("d", SessionState.DONE),
            s("e", SessionState.FAILED),
            s("f", SessionState.WORKING, held = true),
        )
        assertEquals(listOf("a", "b"), SessionAlerts.live(list).map { it.sessionId })
        assertEquals(1, SessionAlerts.needsYouCount(list))
        // 1 working, 1 needs you (terminal-held and finished sessions do not count)
        assertEquals(1 to 1, SessionAlerts.watchCounts(list))
        assertEquals(0 to 0, SessionAlerts.watchCounts(emptyList()))
    }

    // ───────────── texts ─────────────

    @Test
    fun titlesAndTexts() {
        assertEquals("X needs approval", SessionAlerts.needsYouTitle("X", perm))
        assertEquals("X has a question", SessionAlerts.needsYouTitle("X", ask))
        assertEquals("X has a question", SessionAlerts.needsYouTitle("X", SessionPending.Permission("t", "AskUserQuestion")))
        assertEquals("X is waiting", SessionAlerts.needsYouTitle("X", dialog))
        assertEquals("X needs you", SessionAlerts.needsYouTitle("X", null))

        assertEquals("Bash: npm test", SessionAlerts.needsYouText(perm, null))
        assertEquals("Bash", SessionAlerts.needsYouText(SessionPending.Permission("t", "Bash", ""), null))
        assertEquals("Which DB?", SessionAlerts.needsYouText(ask, null))
        assertEquals("2 new MCP servers found · Select any you wish to enable.", SessionAlerts.needsYouText(dialog, null))
        assertEquals("input needed", SessionAlerts.needsYouText(null, "input needed"))
        assertEquals("Waiting for you", SessionAlerts.needsYouText(null, null))
    }

    @Test
    fun aMessageToAnotherSessionReadsAsAMessage() {
        val send = SessionPending.Permission("toolu_3", "SendMessage", "builder", "{\"to\":\"builder\",\"message\":\"tests are green\"}")
        assertEquals("X wants to message builder", SessionAlerts.needsYouTitle("X", send))
        assertEquals("tests are green", SessionAlerts.needsYouText(send, null))
        assertEquals("Send" to "Don't send", SessionAlerts.answerLabels(send))
        assertEquals("Allow" to "Deny", SessionAlerts.answerLabels(perm))
        assertTrue(SessionAlerts.answerableInline(send))
        // A uds address shows its last part; no input at all still reads sensibly.
        val uds = SessionPending.Permission("t", "SendMessage", "", "{\"to\":\"uds:/tmp/claude/ab12.sock\",\"message\":\"hi\"}")
        assertEquals("X wants to message ab12.sock", SessionAlerts.needsYouTitle("X", uds))
        val bare = SessionPending.Permission("t", "SendMessage")
        assertEquals("X wants to message another session", SessionAlerts.needsYouTitle("X", bare))
        assertEquals("A message to another session", SessionAlerts.needsYouText(bare, null))
    }

    @Test
    fun onlyToolPermissionsAreAnswerableInline() {
        assertTrue(SessionAlerts.answerableInline(perm))
        assertFalse(SessionAlerts.answerableInline(ask))
        assertFalse(SessionAlerts.answerableInline(SessionPending.Permission("t", "AskUserQuestion")))
        assertFalse(SessionAlerts.answerableInline(dialog))
        assertFalse(SessionAlerts.answerableInline(null))
    }

    // ───────────── ids and dismissal ─────────────

    @Test
    fun oneNotificationSlotPerSession() {
        val r1 = SessionRef("c", "a")
        assertEquals(SessionAlerts.notificationId(r1), SessionAlerts.notificationId(SessionRef("c", "a")))
        assertNotEquals(SessionAlerts.notificationId(r1), SessionAlerts.notificationId(SessionRef("c2", "a")))
        assertNotEquals(SessionAlerts.notificationId(r1), SessionAlerts.notificationId(SessionRef("c", "b")))
        assertEquals("perm:toolu_1", SessionAlerts.identityOf(perm, "permission"))
        assertEquals("ask:toolu_2", SessionAlerts.identityOf(ask, null))
        assertEquals("dialog:MCP_SERVERS:2 new MCP servers found", SessionAlerts.identityOf(dialog, null))
        assertEquals("input needed", SessionAlerts.identityOf(null, "input needed"))
    }

    @Test
    fun stillWaitingOnlyWhileThatPromptIsOpen() {
        val ref = SessionRef("c", "a")
        val id = SessionAlerts.identityOf(perm, null)
        assertTrue(SessionAlerts.stillWaiting(listOf(s("a", SessionState.NEEDS_YOU, perm)), ref, id))
        assertFalse(SessionAlerts.stillWaiting(listOf(s("a", SessionState.WORKING)), ref, id))
        assertFalse(SessionAlerts.stillWaiting(listOf(s("a", SessionState.NEEDS_YOU, SessionPending.Permission("toolu_9", "Bash"))), ref, id))
        // the session is not known (no watch yet): the default decides
        assertTrue(SessionAlerts.stillWaiting(emptyList(), ref, id))
        assertFalse(SessionAlerts.stillWaiting(emptyList(), ref, id, unknownDefault = false))
    }

    @Test
    fun aNotificationGoesOnceTheUserActedOnIt() {
        val ref = SessionRef("c", "a")
        val prompt = SessionAlerts.Shown(ref, SessionAlerts.Kind.NEEDS_YOU, SessionAlerts.identityOf(perm, null))
        assertTrue(SessionAlerts.stillRelevant(prompt, s("a", SessionState.NEEDS_YOU, perm)))
        // answered (anywhere), or a newer prompt took its place
        assertFalse(SessionAlerts.stillRelevant(prompt, s("a", SessionState.WORKING)))
        assertFalse(SessionAlerts.stillRelevant(prompt, s("a", SessionState.NEEDS_YOU, SessionPending.Permission("toolu_9", "Bash"))))

        val done = SessionAlerts.Shown(ref, SessionAlerts.Kind.TURN_DONE)
        assertTrue(SessionAlerts.stillRelevant(done, s("a", SessionState.IDLE)))
        assertTrue(SessionAlerts.stillRelevant(done, s("a", SessionState.DONE)))
        // the user replied: a new turn is running, or a prompt is up (it gets its own notification)
        assertFalse(SessionAlerts.stillRelevant(done, s("a", SessionState.WORKING)))
        assertFalse(SessionAlerts.stillRelevant(done, s("a", SessionState.NEEDS_YOU, perm)))

        val failed = SessionAlerts.Shown(ref, SessionAlerts.Kind.FAILED)
        assertTrue(SessionAlerts.stillRelevant(failed, s("a", SessionState.FAILED)))
        assertFalse(SessionAlerts.stillRelevant(failed, s("a", SessionState.WORKING)))

        // someone took the session over at the terminal: nothing left for the phone
        assertFalse(SessionAlerts.stillRelevant(prompt, s("a", SessionState.NEEDS_YOU, perm, held = true)))
        assertFalse(SessionAlerts.stillRelevant(done, s("a", SessionState.IDLE, held = true)))
        // not known (yet): keep it
        assertTrue(SessionAlerts.stillRelevant(done, null))
    }

    // ───────────── transitions (hub → events) ─────────────

    @Test
    fun transitionsAnnounceEachPromptOnce() {
        val t = SessionTransitions()
        // first sight of a session that already needs you: announce
        var ev = t.diff(null, s("a", SessionState.NEEDS_YOU, perm), firstSight = true)
        assertTrue(ev.single() is SessionEvent.NeedsYou)
        assertEquals("Fix the build", ev.single().title)
        // same prompt again: nothing
        assertTrue(t.diff(s("a", SessionState.NEEDS_YOU, perm), s("a", SessionState.NEEDS_YOU, perm), false).isEmpty())
        // a different prompt while still waiting: announce
        ev = t.diff(s("a", SessionState.NEEDS_YOU, perm), s("a", SessionState.NEEDS_YOU, ask), false)
        assertEquals(ask, (ev.single() as SessionEvent.NeedsYou).pending)
        // leaving and coming back with the same prompt id re-announces
        t.diff(s("a", SessionState.NEEDS_YOU, ask), s("a", SessionState.WORKING), false)
        assertEquals(1, t.diff(s("a", SessionState.WORKING), s("a", SessionState.NEEDS_YOU, ask), false).size)
        // forgetting (session removed) re-arms too
        t.forget(SessionRef("c", "a"))
        assertEquals(1, t.diff(null, s("a", SessionState.NEEDS_YOU, ask), true).size)
    }

    @Test
    fun turnDoneAndFailed() {
        val t = SessionTransitions()
        val done = t.diff(s("a", SessionState.WORKING), s("a", SessionState.IDLE, lastText = "All green"), false).single() as SessionEvent.TurnDone
        assertEquals("All green", done.snippet)
        assertTrue(t.diff(s("a", SessionState.WORKING), s("a", SessionState.DONE), false).single() is SessionEvent.TurnDone)
        assertTrue(t.diff(s("a", SessionState.IDLE), s("a", SessionState.IDLE), false).isEmpty())
        assertTrue(t.diff(s("a", SessionState.WORKING), s("a", SessionState.FAILED), false).single() is SessionEvent.Failed)
        assertTrue(t.diff(s("a", SessionState.FAILED), s("a", SessionState.FAILED), false).isEmpty())
        // no events on first sight except needs-you, and none for terminal-held sessions
        assertTrue(t.diff(null, s("a", SessionState.FAILED), true).isEmpty())
        assertTrue(t.diff(s("b", SessionState.WORKING, held = true), s("b", SessionState.IDLE, held = true), false).isEmpty())
        assertTrue(t.diff(null, s("b", SessionState.NEEDS_YOU, perm, held = true), true).isEmpty())
    }
}
