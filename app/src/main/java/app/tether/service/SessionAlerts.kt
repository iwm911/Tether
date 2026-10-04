package app.tether.service

import app.tether.core.ASK_USER_QUESTION
import app.tether.core.SEND_MESSAGE
import app.tether.core.Session
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.identity
import app.tether.core.parseSendMessage

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

    /** What a session notification is about. */
    enum class Kind { NEEDS_YOU, TURN_DONE, FAILED }

    /** A session notification on screen: [identity] is the prompt's (see [identityOf]), empty for turn done / failed. */
    data class Shown(val ref: SessionRef, val kind: Kind, val identity: String = "")

    /**
     * Notification id of a session: one slot per session, so a newer prompt, a finished turn or an
     * error replaces what was shown for it instead of piling up.
     */
    fun notificationId(ref: SessionRef): Int = "sess:${ref.connectionId}/${ref.sessionId}".hashCode()

    /**
     * True while a shown notification still asks something of the user: the same prompt is open, the
     * turn is still waiting for a reply, the error still stands. A session someone took over at the
     * terminal needs nothing from the phone. When the session is unknown ([session] null) it is kept.
     */
    fun stillRelevant(shown: Shown, session: Session?): Boolean {
        if (session == null) return true
        if (session.heldByTerminal) return false
        return when (shown.kind) {
            Kind.NEEDS_YOU -> session.state == SessionState.NEEDS_YOU && identityOf(session.pending, session.waitingFor) == shown.identity
            Kind.TURN_DONE -> session.state == SessionState.IDLE || session.state == SessionState.DONE
            Kind.FAILED -> session.state == SessionState.FAILED
        }
    }

    fun identityOf(pending: SessionPending?, waitingFor: String?): String = pending?.identity ?: waitingFor ?: "needs-you"

    /** Notification title for a needs-you prompt. */
    fun needsYouTitle(title: String, pending: SessionPending?): String = when (pending) {
        is SessionPending.Question -> "$title has a question"
        is SessionPending.Permission -> when (pending.toolName) {
            ASK_USER_QUESTION -> "$title has a question"
            SEND_MESSAGE -> "$title wants to message ${parseSendMessage(pending.inputJson).recipient}"
            else -> "$title needs approval"
        }
        is SessionPending.Dialog -> "$title is waiting"
        null -> "$title needs you"
    }

    /** One-line body of a needs-you notification. */
    fun needsYouText(pending: SessionPending?, waitingFor: String?): String = when (pending) {
        is SessionPending.Permission -> when {
            pending.toolName == SEND_MESSAGE -> parseSendMessage(pending.inputJson).text ?: "A message to another session"
            pending.summary.isBlank() -> pending.toolName
            else -> "${pending.toolName}: ${pending.summary}"
        }
        is SessionPending.Question -> pending.summary.ifBlank { "Claude asked a question" }
        is SessionPending.Dialog -> listOf(pending.title, pending.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim())
            .filter { !it.isNullOrBlank() }.joinToString(" · ").ifEmpty { "A dialog is open" }
        null -> waitingFor?.takeIf { it.isNotBlank() } ?: "Waiting for you"
    }

    /** The inline Allow / Deny buttons' labels: Send / Don't send for a message to another session. */
    fun answerLabels(pending: SessionPending?): Pair<String, String> =
        if ((pending as? SessionPending.Permission)?.toolName == SEND_MESSAGE) "Send" to "Don't send" else "Allow" to "Deny"

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
