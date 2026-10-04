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
 * Machine-level remote operations (no session involved), implemented on top of [SshManager] and a
 * small Python helper (`assets/tether_helper.py`) installed under `~/.tether/` on the remote.
 */
interface ClaudeRemote {
    suspend fun probe(connectionId: String): ProbeResult
    suspend fun listProjects(connectionId: String): List<ProjectSummary>
    suspend fun listDir(connectionId: String, path: String?): DirListing
    /** Slash commands Claude Code offers in [cwd] (built-ins, custom commands, skills, plugins, MCP prompts). */
    suspend fun listCommands(connectionId: String, cwd: String): List<SlashCommand> = emptyList()
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
    /**
     * Shift+Tab until the session's footer shows [mode] (a CLI permission mode), or once when [mode] is null;
     * returns the mode it landed on (null when the footer couldn't be read). The cycle depends on the model
     * (auto mode is not offered everywhere), so the helper reads it back instead of counting presses.
     */
    suspend fun setMode(connectionId: String, sessionId: String, mode: String?): String? =
        throw UnsupportedOperationException("setMode")
    /**
     * Answers the open permission prompt. With [toolUseId] (the prompt the user saw) the helper refuses with
     * code `ESTALE` when a different prompt is open now.
     */
    suspend fun answer(connectionId: String, sessionId: String, decision: SessionDecision, message: String? = null, toolUseId: String? = null): Session
    suspend fun ask(connectionId: String, sessionId: String, answers: List<AskAnswer>): Session
    suspend fun interrupt(connectionId: String, sessionId: String): Session
    /** `/btw`: Claude answers [question] (Markdown) from the session's context; nothing is added to the conversation. */
    suspend fun btw(connectionId: String, sessionId: String, question: String): String =
        throw UnsupportedOperationException("btw")
    suspend fun stop(connectionId: String, sessionId: String)
    suspend fun rm(connectionId: String, sessionId: String)
    /** Copies an image to the machine (like a file dropped on the terminal); returns its absolute path there. */
    suspend fun uploadImage(connectionId: String, image: ImageAttachment): String
    /** Slash commands Claude Code offers in [cwd], for the composer's popup (empty when unknown). */
    suspend fun listCommands(connectionId: String, cwd: String): List<SlashCommand> = emptyList()
}

/**
 * The app-facing API of the one-session model: every Claude Code session on every machine,
 * keyed by session id. Used by every screen and the background service.
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
    /** Shift+Tab to [mode] (null: once); returns the mode the session landed on. See [ClaudeRemote.setMode]. */
    suspend fun setMode(ref: SessionRef, mode: String?): String? = throw UnsupportedOperationException("setMode")
    /** Answers the open permission prompt; [toolUseId] guards against answering a newer prompt (`ESTALE`). */
    suspend fun answer(ref: SessionRef, decision: SessionDecision, message: String? = null, toolUseId: String? = null)
    suspend fun ask(ref: SessionRef, answers: List<AskAnswer>)
    /** Esc: interrupts the current turn, keeps the session. */
    suspend fun interrupt(ref: SessionRef)
    /** `/btw` side question (wakes a retired session); returns Claude's answer in Markdown. See [SessionRemote.btw]. */
    suspend fun btw(ref: SessionRef, question: String): String = throw UnsupportedOperationException("btw")
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

    /** Slash commands of a machine folder for the composer (cached; empty when unavailable). */
    suspend fun slashCommands(connectionId: String, cwd: String): List<SlashCommand> = emptyList()
}
