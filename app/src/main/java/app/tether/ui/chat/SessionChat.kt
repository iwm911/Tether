package app.tether.ui.chat

import app.tether.core.ASK_USER_QUESTION
import app.tether.core.AskAnswer
import app.tether.core.ChatItem
import app.tether.core.ConversationState
import app.tether.core.PeerDirection
import app.tether.core.PeerMessage
import app.tether.core.PermissionDecision
import app.tether.core.PermissionMode
import app.tether.core.PermissionSuggestion
import app.tether.core.SessionDecision
import app.tether.core.SessionKey
import app.tether.remote.RemoteJson
import app.tether.remote.SessionReducer
import kotlinx.serialization.json.JsonPrimitive

/*
 * ════════════════════════════ Session screen: pure presentation logic ════════════════════════════
 *
 * Everything the one-session screen decides that is not drawing lives here so it is unit-tested:
 * the rendered item list (peers as their own rows), what a permission / question answer sends,
 * how many Shift+Tabs reach a mode, and the dialog key names.
 */

/** Sentinel suggestion: "Yes, and don't ask again" → `answer allow_always`. */
internal const val ALWAYS_ALLOW_SENTINEL = "{\"tether\":\"allow_always\"}"

/**
 * The rows the session screen renders: [visibleChatItems], with each `SendMessage` tool call drawn
 * as an outgoing peer row and every incoming peer message slotted in by time.
 */
internal fun sessionChatItems(conv: ConversationState, showThinking: Boolean): List<ChatItem> {
    val base = visibleChatItems(conv.items, showThinking)
    val incoming = conv.live?.peers.orEmpty().filter { it.dir == PeerDirection.IN }
    return mergePeers(base.map(::outgoingPeerOrSelf), incoming)
}

/** A top-level `SendMessage` call becomes an outgoing peer row; anything else is unchanged. */
internal fun outgoingPeerOrSelf(item: ChatItem): ChatItem {
    if (item !is ChatItem.ToolCall || item.name != SessionReducer.SEND_MESSAGE) return item
    val input = RemoteJson.parseObject(item.inputJson) ?: return item
    fun str(k: String) = (input[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    val text = str("message") ?: str("content") ?: str("text") ?: str("summary") ?: return item
    return ChatItem.Peer(
        key = "peer:out:${item.toolUseId}",
        incoming = false,
        peerName = str("toName") ?: str("name"),
        peer = str("to") ?: str("recipient"),
        text = text,
        at = item.startedAt,
    )
}

/** Time of a row, when it has one (user messages and tool calls carry one). */
private fun ChatItem.timeOrNull(): Long? = when (this) {
    is ChatItem.User -> timestamp
    is ChatItem.ToolCall -> startedAt
    is ChatItem.Peer -> at
    else -> null
}

/**
 * Inserts incoming peer messages into [items] by time: before the first row known to be later.
 * Rows without a time inherit the latest time seen above them; a message with no time goes last.
 * Messages already present (same key) are not added twice.
 */
internal fun mergePeers(items: List<ChatItem>, peers: List<PeerMessage>): List<ChatItem> {
    if (peers.isEmpty()) return items
    val existing = items.mapTo(HashSet()) { it.key }
    val rows = peers
        .map { m ->
            ChatItem.Peer(
                key = "peer:in:${m.at ?: 0}:${m.peer.orEmpty()}:${m.text.hashCode()}",
                incoming = true,
                peerName = m.peerName,
                peer = m.peer ?: m.peerSessionId,
                text = m.text,
                at = m.at,
            )
        }
        .filter { it.key !in existing }
        .distinctBy { it.key }
        .sortedBy { it.at ?: Long.MAX_VALUE }
    if (rows.isEmpty()) return items
    val out = ArrayList<ChatItem>(items.size + rows.size)
    var next = 0
    var lastTime = Long.MIN_VALUE
    for (item in items) {
        item.timeOrNull()?.let { lastTime = maxOf(lastTime, it) }
        val t = item.timeOrNull() ?: lastTime
        while (next < rows.size && rows[next].at != null && t != Long.MIN_VALUE && rows[next].at!! < t) {
            out += rows[next++]
        }
        out += item
    }
    while (next < rows.size) out += rows[next++]
    return out
}

/**
 * The daemon's permission prompt offers "don't ask again"; the shared decision panel shows it as a
 * suggestion button. A question has no suggestions.
 */
internal fun withSessionSuggestions(pending: List<ChatItem.Permission>): List<ChatItem.Permission> = pending.map { p ->
    if (p.toolName == ASK_USER_QUESTION || p.suggestions.isNotEmpty()) p
    else p.copy(suggestions = listOf(PermissionSuggestion(ALWAYS_ALLOW_SENTINEL, "Yes, and don't ask again")))
}

/** What answering a prompt from the decision panel sends to the session. */
internal sealed interface SessionReply {
    data class Answer(val decision: SessionDecision, val message: String?) : SessionReply
    data class Ask(val answers: List<AskAnswer>) : SessionReply
    /** Dismiss the prompt the way Esc does in the terminal (skipping a question). */
    data object Dismiss : SessionReply
}

internal fun sessionReplyFor(toolName: String, decision: PermissionDecision): SessionReply = when (decision) {
    is PermissionDecision.Answer -> SessionReply.Ask(decision.answers)
    is PermissionDecision.Allow ->
        if (decision.alwaysAllow.isNotEmpty()) SessionReply.Answer(SessionDecision.ALLOW_ALWAYS, null)
        else SessionReply.Answer(SessionDecision.ALLOW, null)
    is PermissionDecision.Deny -> when {
        toolName == ASK_USER_QUESTION -> SessionReply.Dismiss
        else -> SessionReply.Answer(SessionDecision.DENY, decision.message.takeIf { it != DEFAULT_DENY && it.isNotBlank() })
    }
}

private val DEFAULT_DENY = PermissionDecision.Deny().message

/**
 * Shift+Tab presses that move the terminal from [current] to [target] along the CLI's mode cycle.
 * 0 when already there; null when [target] is not reachable by cycling (Bypass is a launch flag).
 */
internal fun modeKeyPresses(current: String?, target: PermissionMode): Int? {
    val cycle = PermissionMode.cycle
    val to = cycle.indexOf(target).takeIf { it >= 0 } ?: return null
    val from = cycle.indexOf(PermissionMode.fromCli(current) ?: PermissionMode.DEFAULT).takeIf { it >= 0 } ?: 0
    return (to - from + cycle.size) % cycle.size
}

/** The mode one Shift+Tab after [current]. */
internal fun nextMode(current: String?): PermissionMode {
    val cycle = PermissionMode.cycle
    val idx = cycle.indexOf(PermissionMode.fromCli(current) ?: PermissionMode.DEFAULT)
    return if (idx < 0) PermissionMode.DEFAULT else cycle[(idx + 1) % cycle.size]
}

/** A dialog key name from the helper (`"down"`, `"3"`…) as a key press; other text is typed. */
internal fun dialogKey(name: String): SessionKey =
    if (name in SessionKey.NAMES) SessionKey.Named(name) else SessionKey.Text(name)

/** Label for the generic key pad. */
internal fun keyLabel(name: String): String = when (name) {
    "up" -> "↑"
    "down" -> "↓"
    "left" -> "←"
    "right" -> "→"
    "space" -> "Space"
    "enter" -> "Enter"
    "esc" -> "Esc"
    "tab" -> "Tab"
    "shift-tab" -> "⇧Tab"
    else -> name
}

/** Keys the generic pad offers when the helper did not say which make sense. */
internal val DefaultDialogKeys = listOf("up", "down", "space", "enter", "esc")

/** The spinner verb without its trailing ellipsis (the indicator adds its own). */
internal fun statusVerb(verb: String): String = verb.trim().trimEnd('…', '.').trim().ifEmpty { "Working" }

/** The `/model` command the model chip sends. */
internal fun modelCommand(model: String): String = "/model ${model.trim()}"
