package app.tether.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// ───────────────────────────── Storage ─────────────────────────────

/** Small secrets (passwords, private keys) encrypted at rest with an Android Keystore AES-GCM key. */
interface SecretStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)

    /** Secrets can only be decrypted while the phone is unlocked (Android 9+). */
    val requireUnlock: Boolean get() = false
    val requireUnlockSupported: Boolean get() = false
    /** Re-encrypts every secret for the new mode; throws and changes nothing if that isn't possible now. */
    fun setRequireUnlock(on: Boolean): Unit = throw UnsupportedOperationException()
    /** The last [get] returned null only because [requireUnlock] is on and the phone is locked. */
    fun readBlockedByDeviceLock(): Boolean = false
}

interface ConnectionRepository {
    val connections: StateFlow<List<Connection>>
    fun get(id: String): Connection?
    suspend fun upsert(connection: Connection, password: String? = null)
    suspend fun delete(id: String)
    suspend fun markConnected(id: String, hostname: String?, claudeVersion: String?)
    fun newId(): String
}

interface KeyRepository {
    val keys: StateFlow<List<SshKey>>
    fun get(id: String): SshKey?
    /** Generates an Ed25519 key pair, stores the private half encrypted. */
    suspend fun generateEd25519(name: String): SshKey
    /** Imports an OpenSSH / PEM private key. Throws IllegalArgumentException with a friendly message if unparseable. */
    suspend fun import(name: String, privateKeyText: String, passphrase: String?): SshKey
    suspend fun delete(id: String)
    /** Private key text (OpenSSH format) for use by the SSH layer. */
    fun privateKey(id: String): String?
    fun passphrase(id: String): String?
}

interface KnownHostsStore {
    fun get(host: String, port: Int): KnownHost?
    fun put(entry: KnownHost)
    fun remove(host: String, port: Int)
    val all: StateFlow<List<KnownHost>>
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

// ───────────────────────────── Host key trust (TOFU) ─────────────────────────────

data class HostKeyPrompt(
    val id: String,
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    /** Non-null = the key CHANGED since last time (possible MITM) — UI must show a loud warning. */
    val previousFingerprint: String?,
)

/** The SSH layer asks, the UI (a dialog hosted at the app root) answers. */
interface HostKeyPromptBus {
    val pending: StateFlow<HostKeyPrompt?>
    /** Suspends until the user answers; false on timeout (60 s) or rejection. */
    suspend fun ask(prompt: HostKeyPrompt): Boolean
    fun answer(promptId: String, trust: Boolean)
}

// ───────────────────────────── SSH ─────────────────────────────

interface SshManager {
    /** Link state per connection id (absent = Idle). */
    val states: StateFlow<Map<String, LinkState>>

    /** Runs a command to completion. Connects (and reuses the connection) as needed. */
    suspend fun exec(connectionId: String, command: String, stdin: ByteArray? = null, timeoutMs: Long = 30_000): ExecResult

    /**
     * Streams stdout of a long-running command line by line (UTF-8, without the trailing '\n').
     * Completes when the remote command exits; throws on connection loss. Cancelling the collector
     * closes the channel (and the remote command gets SIGHUP).
     */
    fun streamLines(connectionId: String, command: String): Flow<String>

    /** Writes a file via SFTP, creating parent dirs. */
    suspend fun upload(connectionId: String, remotePath: String, bytes: ByteArray, mode: Int = "644".toInt(8))

    /** Streams a remote file into [dest] via SFTP, reporting (bytesSoFar, total). `~/` paths are home-relative. */
    suspend fun download(connectionId: String, remotePath: String, dest: java.io.File, onProgress: (Long, Long) -> Unit = { _, _ -> })

    /** Full connection test for a (possibly unsaved) connection, reporting checklist progress. */
    suspend fun test(connection: Connection, password: String?, onProgress: (TestProgress) -> Unit): Result<ProbeResult>

    /** Appends [publicKey] to ~/.ssh/authorized_keys using the connection's current auth. */
    suspend fun installPublicKey(connection: Connection, password: String?, publicKey: String): Result<Unit>

    suspend fun disconnect(connectionId: String)
    suspend fun disconnectAll()

    /**
     * Counter per connection id, bumped every time a link is (re)established. A stream that fails
     * while the epoch has moved on since it started should re-attach at once, not back off.
     */
    val linkEpochs: StateFlow<Map<String, Long>>

    /**
     * Probes every pooled link right now (app back in foreground, network changed): a link that
     * doesn't answer a keepalive within ~2.5 s is dropped and re-established immediately.
     */
    suspend fun revalidate()
}

// ───────────────────────────── Claude Code on a machine ─────────────────────────────

/**
 * Low-level remote operations, implemented on top of [SshManager] and a small Python helper
 * (`assets/tether_helper.py`) installed under `~/.tether/` on the remote.
 */
interface ClaudeRemote {
    suspend fun probe(connectionId: String): ProbeResult
    suspend fun listProjects(connectionId: String): List<ProjectSummary>
    suspend fun listSessions(connectionId: String, cwd: String? = null, limit: Int = 60): List<SessionSummary>
    /** Raw JSONL lines of a Claude Code transcript (~/.claude/projects/…/<id>.jsonl). */
    suspend fun loadTranscript(connectionId: String, sessionId: String): List<String>
    suspend fun listRuns(connectionId: String): List<RunInfo>
    suspend fun startRun(connectionId: String, request: StartRunRequest): RunInfo
    /** Appends raw stream-json lines to the run's stdin log. */
    suspend fun writeInput(ref: RunRef, jsonLines: List<String>)
    /** Raw lines previously written to the run's stdin (to know which permission requests were answered). */
    suspend fun readInput(ref: RunRef): List<String>
    /** Tails the run's stdout log from a byte offset; each element carries the offset just past the line. */
    fun tail(ref: RunRef, fromOffset: Long = 0): Flow<TailLine>
    /** One JSON status snapshot per change for all runs of a machine (drives dashboard + notifications). */
    fun watch(connectionId: String): Flow<List<RunInfo>>
    suspend fun stopRun(ref: RunRef)
    suspend fun deleteRun(ref: RunRef)
    suspend fun listDir(connectionId: String, path: String?): DirListing
    /** Slash commands Claude Code offers in [cwd] (built-ins, custom commands, skills, plugins, MCP prompts). */
    suspend fun listCommands(connectionId: String, cwd: String): List<SlashCommand> = emptyList()

    // ── Claude Code's own background agents (`claude --bg`). [listRuns] and [watch] include them
    //    as RunInfo(kind = NATIVE, runId = "native-<id>"). ──

    /**
     * Starts `claude --bg`; [trust] = mark the folder trusted for Claude Code first when it is not,
     * [mcp] = enable or skip the folder's not-yet-approved project MCP servers.
     */
    suspend fun startNative(connectionId: String, request: StartRunRequest, trust: Boolean, mcp: McpChoice? = null): NativeStartResult =
        throw UnsupportedOperationException("Background agents are not supported here.")
    /**
     * Sends a message to a native agent. A running one gets it typed into its terminal via
     * `claude attach` (queued while it works, like at the keyboard); a stopped one is resumed.
     * Returns the (maybe new) agent. A [model] / [permissionMode] restarts it with the message under
     * those settings (`claude --bg --resume … --model …`): a new id, the same conversation.
     */
    suspend fun replyNative(ref: RunRef, message: String, model: String? = null, permissionMode: String? = null): RunInfo =
        throw UnsupportedOperationException()
    /** Answers the native agent's open permission prompt ("1" = Yes / Esc = No). */
    suspend fun answerNative(ref: RunRef, allow: Boolean): RunInfo = throw UnsupportedOperationException()
    /** Presses Esc in the native agent: interrupts the current turn, keeps the agent. */
    suspend fun interruptNative(ref: RunRef): RunInfo = throw UnsupportedOperationException()
    /** Shift+Tabs the running native agent to [mode] in its terminal, like at the keyboard. */
    suspend fun setNativeMode(ref: RunRef, mode: String): RunInfo = throw UnsupportedOperationException()
    /** Restores (or with [dryRun] previews restoring) files to before user message [messageId] of [sessionId]. */
    suspend fun rewindFiles(connectionId: String, sessionId: String, messageId: String, cwd: String, dryRun: Boolean, runId: String? = null): RewindResult =
        RewindResult(false, error = "Not supported.")
    /** Reads the native agent's pending AskUserQuestion from its screen; returns tool input JSON. */
    suspend fun nativeQuestion(ref: RunRef): String = throw UnsupportedOperationException()
    /** Answers the native agent's pending AskUserQuestion in its TUI. */
    suspend fun askNative(ref: RunRef, answers: List<AskAnswer>): RunInfo = throw UnsupportedOperationException()
    suspend fun nativeTimeline(ref: RunRef): List<NativeTimelineEntry> = emptyList()
    /** `claude logs <id>`, rendered to plain text. */
    suspend fun nativeLogs(ref: RunRef): String = ""
    /** The native agent's transcript lines, then new ones live; a `{"tether":"caught-up"}` line marks the end of history. */
    fun followNative(ref: RunRef): Flow<String> = kotlinx.coroutines.flow.emptyFlow()
}

data class TailLine(val line: String, val endOffset: Long)

data class AgentSummary(val ref: RunRef, val connection: Connection, val run: RunInfo)

sealed interface AgentEvent {
    val ref: RunRef
    val title: String

    data class PermissionRequested(
        override val ref: RunRef, override val title: String,
        val requestId: String, val toolName: String, val summary: String,
    ) : AgentEvent

    data class TurnCompleted(override val ref: RunRef, override val title: String, val success: Boolean, val snippet: String?) : AgentEvent
    data class Ended(override val ref: RunRef, override val title: String, val error: String?) : AgentEvent
}

/**
 * The app-facing agent API used by every screen and the background service.
 * Owns tailing, parsing and reduction of stream-json into [ConversationState].
 */
interface AgentHub {
    /** Every run on every machine, newest activity first. */
    val agents: StateFlow<List<AgentSummary>>
    /** Per-machine refresh errors (e.g. unreachable). */
    val machineErrors: StateFlow<Map<String, String>>
    val events: SharedFlow<AgentEvent>

    /** Hot conversation state while collected; replays history on first collection. */
    fun conversation(ref: RunRef): Flow<ConversationState>

    /** Read-only conversation for a past session transcript (no live run). */
    suspend fun transcript(connectionId: String, sessionId: String): ConversationState

    suspend fun refresh(connectionId: String? = null)
    suspend fun start(connectionId: String, request: StartRunRequest, images: List<ImageAttachment> = emptyList()): RunRef
    suspend fun send(ref: RunRef, text: String, images: List<ImageAttachment> = emptyList())
    suspend fun respond(ref: RunRef, requestId: String, decision: PermissionDecision)
    suspend fun interrupt(ref: RunRef)
    suspend fun setPermissionMode(ref: RunRef, mode: String)
    suspend fun setModel(ref: RunRef, model: String)
    suspend fun stop(ref: RunRef)
    suspend fun remove(ref: RunRef)

    /**
     * Branches a conversation into a new live run: history up to [atUuid] (null = the whole session,
     * or a fresh start when [sessionId] is null), then [prompt] if given. With [restoreBefore] set,
     * files are first restored to how they were before that user message. The original is untouched.
     */
    suspend fun branch(
        connectionId: String,
        sessionId: String?,
        cwd: String,
        atUuid: String?,
        prompt: String?,
        title: String?,
        restoreBefore: String? = null,
        sourceRunId: String? = null,
    ): RunRef = throw UnsupportedOperationException()

    suspend fun previewRewind(connectionId: String, sessionId: String, messageId: String, cwd: String, sourceRunId: String? = null): RewindResult =
        RewindResult(false)

    /** Tool input JSON of a background agent's pending question (read from its screen when needed). */
    suspend fun nativeQuestion(ref: RunRef): String = throw UnsupportedOperationException()

    /** Slash commands for a composer that has no live stream-json run to ask (empty when unavailable). */
    suspend fun slashCommands(connectionId: String, cwd: String): List<SlashCommand> = emptyList()

    /** Stops every machine watch stream until un-paused (the notification's "Disconnect"). */
    fun setPaused(paused: Boolean) {}

    /**
     * Stops watching one machine (and so re-opening its SSH link) after the user disconnected it.
     * Returns once its watch stream is gone. Lifted by [release] or [refresh] of that machine;
     * a refresh of every machine skips held ones.
     */
    suspend fun hold(connectionId: String) {}

    /** Lifts [hold]: the machine is watched (and connected) again. */
    fun release(connectionId: String) {}

    /** While true, keeps per-machine watch streams open even with no UI collecting (background service). */
    fun setBackgroundWatch(enabled: Boolean)

    // ── native background agents (`claude --bg`); stop/remove/conversation also accept their refs ──

    suspend fun startNative(connectionId: String, request: StartRunRequest, trustFolder: Boolean = false, mcp: McpChoice? = null): NativeStartResult =
        throw UnsupportedOperationException("Background agents are not supported here.")
    /** Replies to a finished native agent; returns the ref that continues the conversation (normally the same). */
    suspend fun continueNative(ref: RunRef, text: String, model: String? = null, permissionMode: String? = null): RunRef =
        throw UnsupportedOperationException()
    suspend fun nativeTimeline(ref: RunRef): List<NativeTimelineEntry> = emptyList()
    suspend fun nativeLogs(ref: RunRef): String = ""
}

// ───────────────────────────── One-session model (Claude Code daemon) ─────────────────────────────

/**
 * Helper calls of the one-session protocol (HELPER_VERSION 2.0.0, docs/plans/one-session-daemon.md).
 * Failures throw with the helper's sentence; its error code (see [SessionErrorCodes]) rides along.
 */
interface SessionRemote {
    suspend fun daemonStatus(connectionId: String): DaemonStatus
    /** Newest first: daemon jobs + terminal sessions + transcripts with no job. */
    suspend fun sessions(connectionId: String, cwd: String? = null, limit: Int? = null, before: Long? = null): List<Session>
    /** `watch`: a snapshot, then changes and heartbeats, until the collector goes. */
    fun watchSessions(connectionId: String): Flow<WatchMessage>
    /** `follow`: history from [fromOffset] (transcript bytes), `CaughtUp`, then live events. */
    fun follow(connectionId: String, sessionId: String, agentId: String? = null, fromOffset: Long = 0): Flow<FollowEvent>
    /** Returns the new session; throws with code `EUNTRUSTED` when Claude Code does not trust the folder. */
    suspend fun newSession(connectionId: String, request: NewSessionRequest): Session
    /** Returns whether the session had to be woken (dispatch resume) first. Code `EHELD` while a terminal holds it. */
    suspend fun send(connectionId: String, sessionId: String, text: String, images: List<String> = emptyList()): Boolean
    suspend fun key(connectionId: String, sessionId: String, keys: List<SessionKey>)
    suspend fun answer(connectionId: String, sessionId: String, decision: SessionDecision, message: String? = null): Session
    suspend fun ask(connectionId: String, sessionId: String, answers: List<AskAnswer>): Session
    suspend fun interrupt(connectionId: String, sessionId: String): Session
    suspend fun stop(connectionId: String, sessionId: String)
    suspend fun rm(connectionId: String, sessionId: String)
    /** Copies an image to the machine (like a file dropped on the terminal); returns its absolute path there. */
    suspend fun uploadImage(connectionId: String, image: ImageAttachment): String
}

/**
 * The app-facing API of the one-session model: every Claude Code session on every machine,
 * keyed by session id. Lives next to [AgentHub] until the old run model is removed (phase R).
 */
interface SessionHub {
    /** Every session on every watched machine, most recently updated first. */
    val sessions: StateFlow<List<Session>>
    /** Per-machine watch errors (unreachable, no daemon…). */
    val machineErrors: StateFlow<Map<String, String>>
    /** Needs-you / turn-done / failed transitions seen on the watch streams. */
    val events: SharedFlow<SessionEvent>

    fun session(ref: SessionRef): Session?

    /**
     * Hot conversation of one session (or, with [agentId], one of its subagents, read-only) while
     * collected: history then live events from `follow`, reduced into [ConversationState] with
     * [ConversationState.live] set. Reconnects resume from the last transcript offset.
     */
    fun open(ref: SessionRef, agentId: String? = null): StateFlow<ConversationState>

    /** One-shot `sessions` of one machine (or all), merged into [sessions]. */
    suspend fun refresh(connectionId: String? = null)
    /** Older sessions of a machine (paging past what the watch carries). */
    suspend fun history(connectionId: String, cwd: String? = null, limit: Int = 60, before: Long? = null): List<Session>

    suspend fun new(connectionId: String, request: NewSessionRequest, images: List<ImageAttachment> = emptyList()): NewSessionResult
    /** Sends a message (wakes a retired session first). Returns whether it was woken. */
    suspend fun send(ref: SessionRef, text: String, images: List<ImageAttachment> = emptyList()): Boolean
    suspend fun key(ref: SessionRef, keys: List<SessionKey>)
    suspend fun answer(ref: SessionRef, decision: SessionDecision, message: String? = null)
    suspend fun ask(ref: SessionRef, answers: List<AskAnswer>)
    /** Esc: interrupts the current turn, keeps the session. */
    suspend fun interrupt(ref: SessionRef)
    /** Retires the worker; the next message wakes it with the same id. */
    suspend fun stop(ref: SessionRef)
    /** Kills, evicts and deletes the job (the transcript stays). */
    suspend fun remove(ref: SessionRef)

    /** While true, keeps per-machine watch streams open with no UI collecting (background service). */
    fun setBackgroundWatch(enabled: Boolean)
    /** Stops every watch stream until un-paused (the notification's "Disconnect"). */
    fun setPaused(paused: Boolean)
    /** Stops watching one machine after the user disconnected it; lifted by [release] / [refresh] of it. */
    suspend fun hold(connectionId: String)
    fun release(connectionId: String)
}
