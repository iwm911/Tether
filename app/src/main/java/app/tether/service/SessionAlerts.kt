package app.tether.service

import app.tether.core.ASK_USER_QUESTION
import app.tether.core.Session
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.identity

/**
 * Pure decisions behind session notifications and the background-watch summary (no Android here,
 * so they are unit-tested on the JVM). Everything is keyed on session id + state.
 */
object SessionAlerts {

    /**
     * Sessions that keep the background watch alive: working or waiting for the user, not held by a terminal,
     * on a machine that answers (an offline machine's last state is not news).
     */
    fun live(sessions: List<Session>): List<Session> =
        sessions.filter { (it.state == SessionState.WORKING || it.state == SessionState.NEEDS_YOU) && !it.heldByTerminal && !it.offline }

    fun needsYouCount(sessions: List<Session>): Int = live(sessions).count { it.state == SessionState.NEEDS_YOU }

    /** Notification id of one needs-you prompt (stable per session + prompt). */
    fun needsYouId(ref: SessionRef, identity: String): Int = "sess-need:${ref.connectionId}/${ref.sessionId}/$identity".hashCode()

    /** Notification id for turn-done / failed of a session (one slot per session). */
    fun updateId(ref: SessionRef): Int = "sess-upd:${ref.connectionId}/${ref.sessionId}".hashCode()

    fun identityOf(pending: SessionPending?, waitingFor: String?): String = pending?.identity ?: waitingFor ?: "needs-you"

    /** Notification title for a needs-you prompt. */
    fun needsYouTitle(title: String, pending: SessionPending?): String = when (pending) {
        is SessionPending.Question -> "$title has a question"
        is SessionPending.Permission -> if (pending.toolName == ASK_USER_QUESTION) "$title has a question" else "$title needs approval"
        is SessionPending.Dialog -> "$title is waiting"
        null -> "$title needs you"
    }

    /** One-line body of a needs-you notification. */
    fun needsYouText(pending: SessionPending?, waitingFor: String?): String = when (pending) {
        is SessionPending.Permission -> if (pending.summary.isBlank()) pending.toolName else "${pending.toolName}: ${pending.summary}"
        is SessionPending.Question -> pending.summary.ifBlank { "Claude asked a question" }
        is SessionPending.Dialog -> listOf(pending.title, pending.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim())
            .filter { !it.isNullOrBlank() }.joinToString(" · ").ifEmpty { "A dialog is open" }
        null -> waitingFor?.takeIf { it.isNotBlank() } ?: "Waiting for you"
    }

    /** Allow / Deny can be answered from the notification only for a tool permission prompt. */
    fun answerableInline(pending: SessionPending?): Boolean =
        pending is SessionPending.Permission && pending.toolName != ASK_USER_QUESTION

    /**
     * True while the prompt a notification was posted for is still open. When the session is not
     * in [sessions] at all the answer is [unknownDefault] (watch not running: keep it).
     */
    fun stillWaiting(sessions: List<Session>, ref: SessionRef, identity: String, unknownDefault: Boolean = true): Boolean {
        val s = sessions.firstOrNull { it.connectionId == ref.connectionId && it.sessionId == ref.sessionId } ?: return unknownDefault
        return s.state == SessionState.NEEDS_YOU && identityOf(s.pending, s.waitingFor) == identity
    }

    /** (working, needs you) counts of the live sessions, for "2 agents working · 1 needs you". */
    fun watchCounts(sessions: List<Session>): Pair<Int, Int> {
        val live = live(sessions)
        val needs = live.count { it.state == SessionState.NEEDS_YOU }
        return (live.size - needs) to needs
    }
}
