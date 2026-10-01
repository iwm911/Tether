package app.tether.remote

import app.tether.core.ASK_USER_QUESTION
import app.tether.core.BackgroundTask
import app.tether.core.ChatItem
import app.tether.core.ConversationState
import app.tether.core.FollowEvent
import app.tether.core.LinkState
import app.tether.core.PeerDirection
import app.tether.core.PeerMessage
import app.tether.core.PermissionState
import app.tether.core.RunStatus
import app.tether.core.Session
import app.tether.core.SessionLive
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.SessionStatusLine
import app.tether.core.SessionTask
import app.tether.core.SessionTaskKind
import app.tether.core.SessionTaskStatus
import app.tether.core.SessionTodo
import app.tether.core.SubagentInfo
import app.tether.core.SubagentStatus
import app.tether.core.TodoItem
import app.tether.core.TodoStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * ════════════════════════════ follow events → ConversationState ════════════════════════════
 *
 * Pure Kotlin, single-threaded (the hub serialises access). One instance per open session (or
 * subagent view).
 *
 *   line        → the existing transcript reducer ([StreamReducer.acceptTranscript]); de-duplicated
 *                 by uuid so a reconnect that replays a few lines is harmless. Incoming
 *                 `<cross-session-message>` user lines are dropped (the `peer` event shows them).
 *                 In a subagent view the lines are sidechain lines: shown as the main thread.
 *   draft       → a streaming AssistantText appended after the transcript items; replaced (removed)
 *                 when a top-level assistant text line lands, and a stale screen draft of the text
 *                 that just landed is ignored until the next user message.
 *   status      → the working indicator (verb, elapsed, tokens); `{"e":"status"}` clears it.
 *   state       → the Session (state, pending, holder, model, mode…), also fed from the watch list.
 *   peer / subagent / task / todos → their lists, keyed by id (latest event wins).
 *   caughtUp    → history done; its offset is where a reconnect resumes.
 */
class SessionReducer(
    val ref: SessionRef,
    val agentId: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val transcript = StreamReducer(clock)
    private val seenUuids = HashSet<String>()

    private var session: Session? = null
    private var draft: String? = null
    private var draftSeq = 0
    /** Normalised text of the last top-level assistant text that landed (stale-draft guard). */
    private var lastFinalNorm: String? = null
    private var status: SessionStatusLine? = null
    private val peers = LinkedHashMap<String, PeerMessage>()
    private val subagents = LinkedHashMap<String, SubagentInfo>()
    private val tasks = LinkedHashMap<String, SessionTask>()
    private val todoLists = LinkedHashMap<String, List<SessionTodo>>()
    private var latestTodoList: String? = null
    private var tasksSeen = false

    var offset: Long = 0L
        private set
    var caughtUp: Boolean = false
        private set

    /** Increments on every state change. */
    var version: Long = 0L
        private set

    val currentSession: Session? get() = session

    // ═══════════════════════════════════════ input ═══════════════════════════════════════

    /** Applies one follow event; true when the state changed. */
    fun accept(event: FollowEvent): Boolean {
        val before = version
        when (event) {
            is FollowEvent.Line -> onLine(event)
            is FollowEvent.Draft -> onDraft(event.text)
            FollowEvent.DraftClear -> clearDraft()
            is FollowEvent.Status -> onStatus(event)
            is FollowEvent.State -> applySession(event.session)
            is FollowEvent.Peer -> {
                val m = event.message
                val key = "${m.dir}|${m.peer}|${m.at}|${m.text.hashCode()}"
                if (peers.put(key, m) != m) version++
            }
            is FollowEvent.Subagent -> {
                val prev = subagents[event.info.agentId]
                val next = if (prev == null) event.info else event.info.copy(
                    agentType = event.info.agentType ?: prev.agentType,
                    description = event.info.description ?: prev.description,
                    toolUseId = event.info.toolUseId ?: prev.toolUseId,
                    model = event.info.model ?: prev.model,
                )
                if (subagents.put(next.agentId, next) != next) version++
            }
            is FollowEvent.Task -> {
                tasksSeen = true
                val prev = tasks[event.task.taskId]
                val next = if (prev == null) event.task else event.task.copy(
                    toolUseId = event.task.toolUseId ?: prev.toolUseId,
                    summary = event.task.summary ?: prev.summary,
                    outputFile = event.task.outputFile ?: prev.outputFile,
                )
                if (tasks.put(next.taskId, next) != next) version++
            }
            is FollowEvent.Todos -> {
                todoLists[event.listId] = event.items
                latestTodoList = event.listId
                version++
            }
            is FollowEvent.CaughtUp -> {
                if (event.offset > offset) offset = event.offset
                if (!caughtUp) caughtUp = true
                version++
            }
        }
        return version != before
    }

    /** Shows a just-sent message at once; reconciled when its transcript line lands. Returns its key. */
    fun addOptimisticUser(text: String, imageCount: Int, queued: Boolean): String {
        val key = transcript.addOptimisticUser(text, imageCount, queued)
        version++
        return key
    }

    /** Drops an optimistic message whose send failed. */
    fun removeOptimistic(key: String) {
        transcript.removeOptimistic(key)
        version++
    }

    /** A newer description of the session (from `state` or the machine's watch list). */
    fun applySession(s: Session): Boolean {
        val cur = session
        val next = if (s.connectionId.isEmpty()) s.copy(connectionId = ref.connectionId) else s
        if (cur != null && next.updatedAt < cur.updatedAt) return false
        if (cur == next) return false
        session = next
        version++
        return true
    }

    /**
     * The follow stream dropped: draft and spinner are stale (the helper resends them on reconnect).
     * History stays; the next stream resumes from [offset].
     */
    fun onDisconnected() {
        var changed = false
        if (draft != null) { draft = null; draftSeq++; changed = true }
        if (status != null) { status = null; changed = true }
        if (changed) version++
    }

    private fun onLine(e: FollowEvent.Line) {
        e.offset?.let { if (it > offset) offset = it }
        if (e.uuid != null && !seenUuids.add(e.uuid)) return
        val o = RemoteJson.parseObject(e.json) ?: return
        val type = o.str("type")
        var line = e.json
        if (agentId != null && o.bool("isSidechain") == true) {
            // A subagent's own file: every line is a sidechain line; show it as the main thread.
            line = JsonObject(o + ("isSidechain" to JsonPrimitive(false))).toString()
        }
        val sidechain = agentId == null && o.bool("isSidechain") == true
        if (type == "user" && !sidechain && isIncomingPeerLine(o)) return
        if (type == "user" && !sidechain && o.str("parent_tool_use_id") == null && isPrompt(o)) {
            lastFinalNorm = null // a new turn: drafts are fresh again
        }
        if (transcript.acceptTranscript(line)) version++
        if (type == "assistant" && !sidechain && o.str("parent_tool_use_id") == null) {
            val text = assistantText(o)
            if (text != null) {
                lastFinalNorm = normalize(text)
                clearDraft()
            }
        }
    }

    private fun onDraft(text: String) {
        if (text.isBlank()) { clearDraft(); return }
        val norm = normalize(text)
        val landed = lastFinalNorm
        if (landed != null && norm.isNotEmpty() && landed.contains(norm)) return // the screen still shows what already landed
        if (draft == text) return
        draft = text
        version++
    }

    private fun clearDraft() {
        if (draft == null) return
        draft = null
        draftSeq++
        version++
    }

    private fun onStatus(e: FollowEvent.Status) {
        val verb = e.verb
        if (verb == null) {
            if (status != null) { status = null; version++ }
            return
        }
        val now = clock()
        val computed = e.elapsedS?.let { now - it * 1000 }
        val prevSince = status?.since
        // Keep the start time steady across ticks (elapsed is whole seconds).
        val since = if (prevSince != null && (computed == null || kotlin.math.abs(computed - prevSince) < 2_000)) prevSince else computed ?: now
        val next = SessionStatusLine(verb, e.elapsedS, e.tokens, since)
        if (next != status) { status = next; version++ }
    }

    // ═══════════════════════════════════════ output ═══════════════════════════════════════

    fun snapshot(link: LinkState = LinkState.Idle, error: String? = null): ConversationState {
        val base = transcript.snapshot()
        val s = session
        val d = draft
        val items = if (d != null) base.items + ChatItem.AssistantText(key = "draft:$draftSeq", text = d, streaming = true) else base.items
        val pending = s?.pending
        val runStatus = runStatusOf(s)
        val sessionTasks = tasks.values.toList()
        val todoItems = latestTodoList?.let { todoLists[it] }?.map { TodoItem(it.subject, it.subject, todoStatus(it.status)) }
        val outgoing = outgoingPeers(base.items)
        val allPeers = (peers.values + outgoing).sortedBy { it.at ?: Long.MAX_VALUE }
        return base.copy(
            items = items,
            status = runStatus,
            title = s?.title ?: base.title,
            cwd = s?.cwd?.takeIf { it.isNotEmpty() } ?: base.cwd,
            sessionId = ref.sessionId,
            model = s?.model ?: base.model,
            permissionMode = s?.permissionMode ?: base.permissionMode,
            todos = todoItems ?: base.todos,
            pendingPermissions = pendingItems(pending),
            // The daemon's queue, or ours while a just-sent message has not reached its state yet.
            queuedCount = maxOf(s?.inFlight?.queued ?: 0, base.queuedCount),
            link = link,
            loadingHistory = !caughtUp,
            error = error,
            workingSince = status?.since?.takeIf { runStatus == RunStatus.WORKING || runStatus == RunStatus.AWAITING_PERMISSION },
            thinkingTokens = null,
            backgroundTasks = if (tasksSeen) sessionTasks.filter { it.status == SessionTaskStatus.RUNNING }.map(::backgroundTaskOf) else base.backgroundTasks,
            live = SessionLive(
                ref = ref,
                agentId = agentId,
                session = s,
                draft = d,
                status = status,
                pending = pending,
                heldByTerminal = s?.heldByTerminal == true,
                terminalPid = s?.terminalPid,
                peers = allPeers,
                subagents = subagents.values.toList(),
                tasks = sessionTasks,
                todoLists = LinkedHashMap(todoLists),
                offset = offset,
                caughtUp = caughtUp,
            ),
        )
    }

    private fun runStatusOf(s: Session?): RunStatus {
        if (agentId != null) {
            val sub = subagents[agentId]
            return when {
                status != null || sub?.status == SubagentStatus.RUNNING -> RunStatus.WORKING
                caughtUp || sub != null -> RunStatus.IDLE
                else -> RunStatus.STARTING
            }
        }
        return when (s?.state) {
            null -> if (status != null) RunStatus.WORKING else if (caughtUp) RunStatus.IDLE else RunStatus.STARTING
            SessionState.WORKING -> RunStatus.WORKING
            SessionState.NEEDS_YOU -> RunStatus.AWAITING_PERMISSION
            SessionState.IDLE, SessionState.DONE -> if (status != null) RunStatus.WORKING else RunStatus.IDLE
            SessionState.FAILED -> RunStatus.FAILED
        }
    }

    private fun pendingItems(p: SessionPending?): List<ChatItem.Permission> {
        val (toolUseId, toolName, summary, input) = when (p) {
            is SessionPending.Permission -> listOf(p.toolUseId, p.toolName, p.summary, p.inputJson)
            is SessionPending.Question -> listOf(p.toolUseId, p.toolName.ifEmpty { ASK_USER_QUESTION }, p.summary, p.inputJson)
            else -> return emptyList()
        }
        return listOf(
            ChatItem.Permission(
                key = "perm:$toolUseId",
                requestId = toolUseId,
                toolName = toolName,
                toolUseId = toolUseId.ifEmpty { null },
                inputJson = input.ifBlank { "{}" },
                description = summary.ifBlank { null },
                blockedPath = null,
                suggestions = emptyList(),
                state = PermissionState.PENDING,
            ),
        )
    }

    // ═══════════════════════════════════════ helpers ═══════════════════════════════════════

    private fun backgroundTaskOf(t: SessionTask) = BackgroundTask(
        id = t.taskId,
        description = t.summary?.takeIf { it.isNotBlank() } ?: t.kind.name.lowercase(),
        type = when (t.kind) {
            SessionTaskKind.SHELL -> "local_bash"
            SessionTaskKind.AGENT -> "local_agent"
            SessionTaskKind.MONITOR -> "monitor"
            SessionTaskKind.OTHER -> null
        },
    )

    /** Outgoing peer messages: the session's own SendMessage tool calls. */
    private fun outgoingPeers(items: List<ChatItem>): List<PeerMessage> {
        val out = ArrayList<PeerMessage>()
        for (it in items) {
            val t = it as? ChatItem.ToolCall ?: continue
            if (t.name != SEND_MESSAGE) continue
            val input = RemoteJson.parseObject(t.inputJson) ?: continue
            val text = input.str("message") ?: input.str("content") ?: input.str("text") ?: input.str("summary") ?: continue
            out.add(
                PeerMessage(
                    dir = PeerDirection.OUT,
                    peer = input.str("to") ?: input.str("recipient"),
                    peerName = input.str("toName") ?: input.str("name"),
                    peerSessionId = null,
                    text = text,
                    at = t.startedAt,
                    toolUseId = t.toolUseId,
                ),
            )
        }
        return out
    }

    private fun userText(o: JsonObject): String? {
        val content = o.obj("message")?.get("content") ?: return null
        return when (content) {
            is JsonPrimitive -> if (content.isString) content.content else null
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }
                .joinToString("\n").takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    /** The user line Claude Code writes for a peer message: `origin.kind:"peer"`, text "Another Claude session sent a message:\n<cross-session-message …>". */
    private fun isIncomingPeerLine(o: JsonObject): Boolean =
        o.obj("origin")?.str("kind") == "peer" || userText(o)?.contains(CROSS_SESSION_TAG) == true

    /** A real prompt (not a tool result / meta line). */
    private fun isPrompt(o: JsonObject): Boolean {
        if (o.bool("isMeta") == true) return false
        val content = o.obj("message")?.get("content") ?: return false
        if (content is JsonArray && content.any { (it as? JsonObject)?.str("type") == "tool_result" }) return false
        val t = userText(o)?.trim() ?: return false
        return t.isNotEmpty() && !t.startsWith("<")
    }

    private fun assistantText(o: JsonObject): String? {
        val content = o.obj("message")?.get("content") as? JsonArray ?: return null
        val text = content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("\n")
        return text.takeIf { it.isNotBlank() }
    }

    private fun todoStatus(s: String) = when (s) {
        "in_progress" -> TodoStatus.IN_PROGRESS
        "completed" -> TodoStatus.COMPLETED
        else -> TodoStatus.PENDING
    }

    companion object {
        const val SEND_MESSAGE = "SendMessage"
        const val CROSS_SESSION_TAG = "<cross-session-message"

        /** Letters and digits only, lower-cased: screen text and markdown compare equal. */
        internal fun normalize(s: String): String {
            val b = StringBuilder(s.length)
            for (c in s) if (c.isLetterOrDigit()) b.append(c.lowercaseChar())
            return b.toString()
        }
    }
}
