package app.tether.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * ════════════════════════════ One-session model (Claude Code daemon) ════════════════════════════
 *
 * Wire types of the helper ↔ app protocol, HELPER_VERSION 2.0.0 (docs/plans/one-session-daemon.md).
 * Field names match the helper's JSON exactly; every field has a default so a helper that omits
 * one (or adds new ones) still parses.
 */

/** Identifies one Claude Code session on one machine. Route-safe (no slashes). */
@Serializable
data class SessionRef(val connectionId: String, val sessionId: String) {
    /** The daemon's short id (first 8 hex chars of the session id). */
    val short: String get() = sessionId.take(8)
}

@Serializable
enum class SessionState {
    @SerialName("working") WORKING,
    @SerialName("needs_you") NEEDS_YOU,
    @SerialName("idle") IDLE,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
}

/** Whether a worker process is running for the session ("retired" = wakes on the next message). */
@Serializable
enum class SessionProcess {
    @SerialName("live") LIVE,
    @SerialName("retired") RETIRED,
}

/** Who owns the session's terminal right now. */
@Serializable
enum class Holder {
    @SerialName("daemon") DAEMON,
    /** A `claude` open in a terminal on the computer: watch-only here, the reply box is replaced. */
    @SerialName("terminal") TERMINAL,
    @SerialName("none") NONE,
}

@Serializable
enum class DialogKind {
    @SerialName("mcp_servers") MCP_SERVERS,
    @SerialName("trust") TRUST,
    @SerialName("other") OTHER,
}

@Serializable
data class DialogOption(
    val label: String = "",
    /** Checkbox state for multi-select dialogs (MCP servers); null = not a checkbox. */
    val checked: Boolean? = null,
    /** Key that selects / toggles this option (sent through `key`). */
    val key: String? = null,
)

/** What a session is blocked on. `kind` on the wire picks the subclass. */
@Serializable(with = SessionPendingSerializer::class)
sealed interface SessionPending {
    val kind: String

    /** A tool permission prompt. */
    @Serializable
    data class Permission(
        val toolUseId: String = "",
        val toolName: String = "",
        val summary: String = "",
        val inputJson: String = "{}",
        override val kind: String = KIND_PERMISSION,
    ) : SessionPending

    /** An AskUserQuestion prompt; [inputJson] is the tool input (questions + options). */
    @Serializable
    data class Question(
        val toolUseId: String = "",
        val toolName: String = ASK_USER_QUESTION,
        val summary: String = "",
        val inputJson: String = "{}",
        override val kind: String = KIND_QUESTION,
    ) : SessionPending

    /** A startup / session dialog cut from the screen (MCP servers, folder trust, notices…). */
    @Serializable
    data class Dialog(
        val dialog: DialogKind = DialogKind.OTHER,
        val title: String = "",
        val body: String = "",
        val options: List<DialogOption> = emptyList(),
        /** Keys that make sense here, e.g. ["up","down","space","enter","esc"]. */
        val keys: List<String> = emptyList(),
        override val kind: String = KIND_DIALOG,
    ) : SessionPending

    companion object {
        const val KIND_PERMISSION = "permission"
        const val KIND_QUESTION = "question"
        const val KIND_DIALOG = "dialog"
    }
}

/** Identity of a pending prompt, for de-duplicating notifications ("same prompt still open"). */
val SessionPending.identity: String
    get() = when (this) {
        is SessionPending.Permission -> "perm:$toolUseId"
        is SessionPending.Question -> "ask:$toolUseId"
        is SessionPending.Dialog -> "dialog:${dialog.name}:$title"
    }

/** Picks the [SessionPending] subclass by `kind`; an unknown kind is shown as a generic dialog. */
object SessionPendingSerializer : JsonContentPolymorphicSerializer<SessionPending>(SessionPending::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out SessionPending> {
        val kind = ((element as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (kind) {
            SessionPending.KIND_PERMISSION -> SessionPending.Permission.serializer()
            SessionPending.KIND_QUESTION -> SessionPending.Question.serializer()
            else -> SessionPending.Dialog.serializer()
        }
    }
}

@Serializable
data class SessionInFlight(
    val tasks: Int = 0,
    val queued: Int = 0,
    val kinds: List<String> = emptyList(),
)

/** Something the session produced that lives outside the chat (a PR, an artifact…). */
@Serializable
data class SessionChild(val id: String = "", val href: String? = null, val kind: String? = null)

/** One Claude Code session as the helper describes it (`sessions`, `watch`, `follow` state, writes). */
@Serializable
data class Session(
    val sessionId: String,
    val short: String = sessionId.take(8),
    val cwd: String = "",
    val name: String = "",
    val intent: String? = null,
    val state: SessionState = SessionState.IDLE,
    val waitingFor: String? = null,
    /**
     * NEEDS_YOU because Claude ended its turn handing work back (waitingFor = its note), not because a prompt
     * waits on screen: answered with a normal message, no key pad.
     */
    val handoff: Boolean = false,
    /** A hand-off's ready-made answer from Claude (e.g. `! gh pr merge 16`), offered to pre-fill the reply. */
    val suggestedReply: String? = null,
    val pending: SessionPending? = null,
    val process: SessionProcess = SessionProcess.RETIRED,
    val heldBy: Holder = Holder.NONE,
    val terminalPid: Int? = null,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val lastText: String? = null,
    val tokens: Long? = null,
    val model: String? = null,
    val permissionMode: String? = null,
    val inFlight: SessionInFlight = SessionInFlight(),
    val children: List<SessionChild> = emptyList(),
    val gitBranch: String? = null,
    /** App-side only: the machine this came from (filled in by the hub; the helper never sends it). */
    val connectionId: String = "",
    /**
     * App-side only: the machine stopped answering, so this is its last known state, not a live one (set by
     * the hub while the machine's watch fails; never on the wire).
     */
    @Transient val offline: Boolean = false,
) {
    val ref: SessionRef get() = SessionRef(connectionId, sessionId)
    val needsYou: Boolean get() = state == SessionState.NEEDS_YOU
    val heldByTerminal: Boolean get() = heldBy == Holder.TERMINAL
    val isLive: Boolean get() = process == SessionProcess.LIVE
    /** Name, else the first prompt, else the project folder. */
    val title: String
        get() = name.trim().ifEmpty { null }
            ?: intent?.trim()?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { if (it.length > 80) it.take(79) + "…" else it }
            ?: cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd.ifEmpty { short } }
}

/** `daemon-status` (also `probe.daemon`). */
@Serializable
data class DaemonStatus(
    val running: Boolean = false,
    val proto: Int? = null,
    val version: String? = null,
    /** "ok" | "needs_login" | "unknown" */
    val auth: String = "unknown",
)

/** Error codes of the helper's `{"error", "code"}` replies. */
object SessionErrorCodes {
    const val ENODAEMON = "ENODAEMON"
    const val EAUTH = "EAUTH"
    const val EPROTO = "EPROTO"
    const val EHELD = "EHELD"
    const val ENOSESSION = "ENOSESSION"
    const val EUNTRUSTED = "EUNTRUSTED"
    const val ETIMEOUT = "ETIMEOUT"
    const val EDAEMON = "EDAEMON"
    /** The prompt being answered is gone or another one took its place (`answer` with a toolUseId, `ask`). */
    const val ESTALE = "ESTALE"
    /** The helper refused the request itself (bad keys, empty text, ...). */
    const val EINVAL = "EINVAL"
}

// ───────────────────────────── watch ─────────────────────────────

/** One line of `helper watch`. */
sealed interface WatchMessage {
    data class Snapshot(val sessions: List<Session>) : WatchMessage
    data class Changed(val changed: List<Session>, val removed: List<String>) : WatchMessage
    data class Heartbeat(val at: Long) : WatchMessage
}

// ───────────────────────────── follow ─────────────────────────────

enum class PeerDirection { IN, OUT }

@Serializable
enum class SubagentStatus {
    @SerialName("running") RUNNING,
    @SerialName("done") DONE,
}

@Serializable
enum class SessionTaskKind {
    @SerialName("shell") SHELL,
    @SerialName("monitor") MONITOR,
    @SerialName("agent") AGENT,
    @SerialName("other") OTHER,
}

@Serializable
enum class SessionTaskStatus {
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("killed") KILLED,
}

/** A message between sessions (`<cross-session-message>` in, `SendMessage` out). */
data class PeerMessage(
    val dir: PeerDirection,
    /** Sender address (`uds:…`) for incoming; recipient for outgoing. */
    val peer: String?,
    val peerName: String?,
    val peerSessionId: String?,
    val text: String,
    val at: Long?,
    /** Outgoing only: the SendMessage tool call it came from. */
    val toolUseId: String? = null,
)

@Serializable
data class SubagentInfo(
    val agentId: String,
    val agentType: String? = null,
    val description: String? = null,
    val toolUseId: String? = null,
    val model: String? = null,
    val background: Boolean = false,
    val status: SubagentStatus = SubagentStatus.RUNNING,
)

@Serializable
data class SessionTask(
    val taskId: String,
    val toolUseId: String? = null,
    val kind: SessionTaskKind = SessionTaskKind.OTHER,
    val status: SessionTaskStatus = SessionTaskStatus.RUNNING,
    val summary: String? = null,
    val outputFile: String? = null,
)

@Serializable
data class SessionTodo(
    val id: String = "",
    val subject: String = "",
    /** "pending" | "in_progress" | "completed" */
    val status: String = "pending",
)

/** One line of `helper follow` (field `e`). */
sealed interface FollowEvent {
    /** A transcript line, as raw JSON text, for [app.tether.remote.TranscriptReducer.acceptTranscript]. */
    data class Line(val json: String, val uuid: String?, val offset: Long?) : FollowEvent
    data class Draft(val text: String) : FollowEvent
    data object DraftClear : FollowEvent
    /** The spinner line; [verb] null = cleared. */
    data class Status(val verb: String?, val elapsedS: Long?, val tokens: Long?) : FollowEvent
    data class State(val session: Session) : FollowEvent
    data class Peer(val message: PeerMessage) : FollowEvent
    data class Subagent(val info: SubagentInfo) : FollowEvent
    data class Task(val task: SessionTask) : FollowEvent
    data class Todos(val listId: String, val items: List<SessionTodo>) : FollowEvent
    data class CaughtUp(val offset: Long) : FollowEvent
}

// ───────────────────────────── writes ─────────────────────────────

/** A key the phone presses in the session's terminal (`helper key`). */
sealed interface SessionKey {
    data class Named(val name: String) : SessionKey
    /** Literal text typed into the terminal. */
    data class Text(val text: String) : SessionKey

    companion object {
        val ShiftTab = Named("shift-tab")
        val Esc = Named("esc")
        val Enter = Named("enter")
        val Up = Named("up")
        val Down = Named("down")
        val Left = Named("left")
        val Right = Named("right")
        val Tab = Named("tab")
        val Space = Named("space")
        fun digit(n: Int): Named {
            require(n in 1..9) { "digit keys are 1..9" }
            return Named(n.toString())
        }
        /** The names `helper key` accepts. */
        val NAMES = setOf("shift-tab", "esc", "enter", "up", "down", "left", "right", "tab", "space") + (1..9).map { it.toString() }
    }
}

enum class SessionDecision(val wire: String) { ALLOW("allow"), ALLOW_ALWAYS("allow_always"), DENY("deny") }

data class NewSessionRequest(
    val cwd: String,
    val prompt: String,
    val model: String? = null,
    val permissionMode: String? = null,
    /** Already-uploaded image paths on the machine. */
    val images: List<String> = emptyList(),
    /** Mark [cwd] trusted for Claude Code first (the user agreed after an `EUNTRUSTED`). */
    val trust: Boolean = false,
)

sealed interface NewSessionResult {
    data class Started(val session: Session) : NewSessionResult
    /** Claude Code does not trust [cwd] yet (`EUNTRUSTED`). */
    data class Untrusted(val cwd: String) : NewSessionResult
}

// ───────────────────────────── conversation extras ─────────────────────────────

/** The spinner line ("Improvising… 12s · 1.2k tokens"). */
data class SessionStatusLine(val verb: String, val elapsedS: Long?, val tokens: Long?, val since: Long?)

/**
 * Everything a one-session conversation shows besides the chat items: carried in
 * [ConversationState.live] (null for the old run-based conversations).
 */
data class SessionLive(
    val ref: SessionRef,
    /** Subagent transcript being viewed (read-only), or null for the session itself. */
    val agentId: String? = null,
    val session: Session? = null,
    /** In-progress reply cut from the screen; also present in items as a streaming AssistantText. */
    val draft: String? = null,
    val status: SessionStatusLine? = null,
    val pending: SessionPending? = null,
    val heldByTerminal: Boolean = false,
    val terminalPid: Int? = null,
    val peers: List<PeerMessage> = emptyList(),
    val subagents: List<SubagentInfo> = emptyList(),
    val tasks: List<SessionTask> = emptyList(),
    /** Todo lists by listId (latest event wins per list). */
    val todoLists: Map<String, List<SessionTodo>> = emptyMap(),
    /** Transcript byte offset the follow stream has reached (reconnects resume from here). */
    val offset: Long = 0,
    val caughtUp: Boolean = false,
)

/** Something worth a notification, derived from the watched session list. */
sealed interface SessionEvent {
    val ref: SessionRef
    val title: String

    /** A session started waiting for the user (permission, question or dialog). */
    data class NeedsYou(override val ref: SessionRef, override val title: String, val pending: SessionPending?, val waitingFor: String?) : SessionEvent
    /** A working session finished its turn. */
    data class TurnDone(override val ref: SessionRef, override val title: String, val snippet: String?) : SessionEvent
    data class Failed(override val ref: SessionRef, override val title: String, val detail: String?) : SessionEvent
}
