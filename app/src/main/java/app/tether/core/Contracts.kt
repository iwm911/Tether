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

    /** Starts `claude --bg`; [trust] = mark the folder trusted for Claude Code first when it is not. */
    suspend fun startNative(connectionId: String, request: StartRunRequest, trust: Boolean): NativeStartResult =
        throw UnsupportedOperationException("Background agents are not supported here.")
    /**
     * Sends a message to a native agent. A running one gets it typed into its terminal via
     * `claude attach` (queued while it works, like at the keyboard); a stopped one is resumed.
     * Returns the (maybe new) agent.
     */
    suspend fun replyNative(ref: RunRef, message: String): RunInfo = throw UnsupportedOperationException()
    /** Answers the native agent's open permission prompt ("1" = Yes / Esc = No). */
    suspend fun answerNative(ref: RunRef, allow: Boolean): RunInfo = throw UnsupportedOperationException()
    /** Presses Esc in the native agent: interrupts the current turn, keeps the agent. */
    suspend fun interruptNative(ref: RunRef): RunInfo = throw UnsupportedOperationException()
    /** Presses Shift+Tab in the running native agent until it is in permission [mode]. */
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

    /** While true, keeps per-machine watch streams open even with no UI collecting (background service). */
    fun setBackgroundWatch(enabled: Boolean)

    // ── native background agents (`claude --bg`); stop/remove/conversation also accept their refs ──

    suspend fun startNative(connectionId: String, request: StartRunRequest, trustFolder: Boolean = false): NativeStartResult =
        throw UnsupportedOperationException("Background agents are not supported here.")
    /** Replies to a finished native agent; returns the ref that continues the conversation (normally the same). */
    suspend fun continueNative(ref: RunRef, text: String): RunRef = throw UnsupportedOperationException()
    suspend fun nativeTimeline(ref: RunRef): List<NativeTimelineEntry> = emptyList()
    suspend fun nativeLogs(ref: RunRef): String = ""
}
