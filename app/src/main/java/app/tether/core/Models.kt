package app.tether.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Shared domain contracts. Every layer codes against these types — change them only in
 * coordination, never unilaterally inside one feature package.
 */

// ───────────────────────────── Machines (SSH connections) ─────────────────────────────

@Serializable
data class Connection(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: AuthMethod,
    /** Absolute path to the `claude` binary on the remote; null = auto-detect. */
    val claudePath: String? = null,
    /** Index into [MachineAccents]. */
    val accent: Int = 0,
    /** Directory the new-agent flow suggests first; null = remote $HOME. */
    val defaultCwd: String? = null,
    val createdAt: Long,
    val lastConnectedAt: Long? = null,
    /** Cached from the last successful probe, for display while offline. */
    val lastClaudeVersion: String? = null,
    val lastHostname: String? = null,
    /** Claude subscription name ("Claude Team"…) from the last probe; null = API-key billing / unknown. */
    val lastPlan: String? = null,
)

@Serializable
sealed interface AuthMethod {
    /** Password lives in [SecretStore] under [SecretKeys.password]. */
    @Serializable @SerialName("password")
    data object Password : AuthMethod

    /** Private key lives in [SecretStore] under [SecretKeys.privateKey]; passphrase (optional) under [SecretKeys.keyPassphrase]. */
    @Serializable @SerialName("key")
    data class Key(val keyId: String) : AuthMethod
}

object SecretKeys {
    fun password(connectionId: String) = "pw:$connectionId"
    fun privateKey(keyId: String) = "key:$keyId"
    fun keyPassphrase(keyId: String) = "keypass:$keyId"
}

@Serializable
data class SshKey(
    val id: String,
    val name: String,
    /** "ed25519", "rsa", "ecdsa". */
    val algorithm: String,
    /** Single OpenSSH line, e.g. `ssh-ed25519 AAAA… tether@pixel`. */
    val publicKey: String,
    /** `SHA256:…` fingerprint of the public key. */
    val fingerprint: String,
    val createdAt: Long,
    val imported: Boolean = false,
    val hasPassphrase: Boolean = false,
)

@Serializable
data class KnownHost(
    val host: String,
    val port: Int,
    val keyType: String,
    /** `SHA256:…` */
    val fingerprint: String,
    val addedAt: Long,
)

/** Live state of the SSH link to one machine. */
sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Connected(val since: Long, val latencyMs: Long? = null) : LinkState
    data class Failed(val message: String, val at: Long) : LinkState
}

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok get() = exitCode == 0
}

/** One step of the connection test checklist shown in the editor. */
enum class TestStep(val label: String) {
    RESOLVE("Reaching host"),
    HOST_KEY("Verifying host key"),
    AUTH("Authenticating"),
    CLAUDE("Finding Claude Code"),
    READY("Ready"),
}

data class TestProgress(val step: TestStep, val done: Boolean, val error: String? = null, val detail: String? = null)

data class ProbeResult(
    val hostname: String,
    val os: String,
    val home: String,
    val claudePath: String?,
    val claudeVersion: String?,
    val pythonVersion: String?,
    val helperReady: Boolean,
    /** Human-readable problem that blocks agent use (no claude, no python3…), or null. */
    val problem: String? = null,
    /** Claude subscription name, or null when Claude Code bills an API key. */
    val plan: String? = null,
)

// ───────────────────────────── Claude sessions / projects on a machine ─────────────────────────────

@Serializable
data class ProjectSummary(
    val cwd: String,
    val sessionCount: Int,
    val lastActiveAt: Long,
    val exists: Boolean = true,
    val gitBranch: String? = null,
)

@Serializable
data class DirEntry(val name: String, val path: String, val isDir: Boolean, val isGitRepo: Boolean = false, val modifiedAt: Long = 0)

@Serializable
data class DirListing(val path: String, val parent: String?, val entries: List<DirEntry>)

// ───────────────────────────── Conversations ─────────────────────────────

/** What a conversation is doing right now (see [ConversationState.status]). */
@Serializable
enum class RunStatus { STARTING, WORKING, AWAITING_PERMISSION, IDLE, ENDED, FAILED }

data class ImageAttachment(val bytes: ByteArray, val mimeType: String, val name: String)

sealed interface PermissionDecision {
    /** [alwaysAllow] = raw `permission_suggestions` objects to persist (the "Always allow" button). */
    data class Allow(val updatedInputJson: String? = null, val alwaysAllow: List<String> = emptyList()) : PermissionDecision
    data class Deny(val message: String = "The user denied this action.", val interrupt: Boolean = false) : PermissionDecision
    /** Answers to an AskUserQuestion prompt, one per question in order. */
    data class Answer(val answers: List<AskAnswer>) : PermissionDecision
}

enum class PermissionMode(val cli: String, val label: String, val description: String) {
    DEFAULT("default", "Ask", "Ask before edits and commands"),
    ACCEPT_EDITS("acceptEdits", "Accept edits", "Edit files freely, ask for commands"),
    PLAN("plan", "Plan", "Research and propose, change nothing"),
    AUTO("auto", "Auto", "Claude decides what needs a prompt"),
    BYPASS("bypassPermissions", "Bypass", "Never ask — full access. Use with care");

    companion object {
        fun fromCli(v: String?): PermissionMode? = entries.firstOrNull { it.cli == v }
        /** The Shift+Tab cycle of the CLI, reproduced by tapping the mode chip. */
        val cycle = listOf(DEFAULT, ACCEPT_EDITS, PLAN, AUTO)
    }
}

/** Built-in model choices shown before the CLI reports its own list (via initialize). */
val FallbackModels = listOf(
    ModelOption("default", "Default", "Recommended for most tasks"),
    ModelOption("opus", "Opus", "Most capable"),
    ModelOption("sonnet", "Sonnet", "Fast and capable"),
    ModelOption("haiku", "Haiku", "Fastest, lightest"),
)

// ───────────────────────────── Conversation (rendered chat) ─────────────────────────────

@Serializable
data class ModelOption(val value: String, val displayName: String, val description: String = "")

data class SlashCommand(val name: String, val description: String, val argumentHint: String = "")

data class TodoItem(val content: String, val activeForm: String, val status: TodoStatus)

/**
 * A shell command or subagent the session is running outside the turn (CLI `task_started` until
 * `task_notification`): `run_in_background` Bash, background Agent calls, workflows.
 */
data class BackgroundTask(
    val id: String,
    val description: String,
    /** CLI task type: "local_bash" | "local_agent" | "local_workflow" | "remote_agent" | … */
    val type: String? = null,
    val subagentType: String? = null,
    val lastToolName: String? = null,
    val summary: String? = null,
    val totalTokens: Long? = null,
    val toolUses: Int? = null,
) {
    val isAgent: Boolean get() = type?.contains("agent") == true || type == "in_process_teammate" || subagentType != null
}
enum class TodoStatus { PENDING, IN_PROGRESS, COMPLETED }

data class PermissionSuggestion(
    /** Raw JSON object to echo back in `updatedPermissions`. */
    val rawJson: String,
    /** e.g. `Always allow Bash(npm test *)` or `Allow edits in /repo this session`. */
    val label: String,
)

enum class ToolStatus { STREAMING_INPUT, RUNNING, AWAITING_PERMISSION, SUCCESS, ERROR, DENIED, INTERRUPTED }
enum class PermissionState { PENDING, ALLOWED, DENIED, CANCELLED }
enum class NoticeKind { INFO, WARNING, ERROR, MODE_CHANGE, MODEL_CHANGE, INTERRUPTED, COMPACTED, SESSION_START }

data class ToolResultData(
    val text: String,
    val isError: Boolean,
    /** Raw `tool_use_result` JSON when present (structured patch for Edit, stdout/stderr for Bash…). */
    val structuredJson: String? = null,
)

/** One row of the conversation. [key] is stable across updates (use as LazyColumn key). */
sealed interface ChatItem {
    val key: String

    data class User(
        override val key: String,
        val text: String,
        val imageCount: Int = 0,
        val timestamp: Long? = null,
        /** Sent to the agent while it was busy; not yet picked up. */
        val queued: Boolean = false,
        /** Transcript uuid of this message (null until Claude Code echoes it). */
        val uuid: String? = null,
        /** A slash command ("/compact keep tests"), not a prompt. */
        val command: Boolean = false,
        /** What a local command (/context, /cost…) printed. Claude never sees it. */
        val commandOutput: String? = null,
    ) : ChatItem

    data class AssistantText(
        override val key: String,
        val text: String,
        val streaming: Boolean = false,
        /** Transcript uuid of this block. */
        val uuid: String? = null,
    ) : ChatItem

    data class Thinking(
        override val key: String,
        val text: String,
        val streaming: Boolean = false,
        val estimatedTokens: Int? = null,
    ) : ChatItem

    data class ToolCall(
        override val key: String,
        val toolUseId: String,
        val name: String,
        /** Tool input as JSON text (complete once status != STREAMING_INPUT). */
        val inputJson: String,
        val status: ToolStatus,
        val result: ToolResultData? = null,
        /** Nested calls made by a subagent (Task / Agent tool). */
        val children: List<ChatItem> = emptyList(),
        val startedAt: Long? = null,
        val finishedAt: Long? = null,
        /** UI-only: the answer to this call's permission prompt, folded in from its Permission item. */
        val decision: PermissionState? = null,
    ) : ChatItem

    data class Permission(
        override val key: String,
        val requestId: String,
        val toolName: String,
        val toolUseId: String?,
        val inputJson: String,
        val description: String?,
        val blockedPath: String?,
        val suggestions: List<PermissionSuggestion>,
        val state: PermissionState,
    ) : ChatItem

    data class TurnSummary(
        override val key: String,
        val success: Boolean,
        val costUsd: Double?,
        val durationMs: Long?,
        val numTurns: Int?,
        val errorText: String? = null,
    ) : ChatItem

    data class Notice(override val key: String, val text: String, val kind: NoticeKind) : ChatItem

    /**
     * A message between Claude Code sessions (one-session model): [incoming] = a
     * `<cross-session-message>` from another session, else this session's own `SendMessage`.
     */
    data class Peer(
        override val key: String,
        val incoming: Boolean,
        /** Sender (incoming) or recipient (outgoing) display name, when known. */
        val peerName: String?,
        /** Sender / recipient address (`uds:…`, a name, a session id). */
        val peer: String?,
        val text: String,
        val at: Long? = null,
    ) : ChatItem
}

data class RateLimitInfo(
    val status: String,
    val windowLabel: String?,
    val utilization: Double?,
    val resetsAt: Long?,
    /** Every plan usage window the CLI reported (5-hour, weekly…), for the usage view. */
    val windows: List<UsageWindow> = emptyList(),
)

data class UsageWindow(val label: String, val utilization: Double, val resetsAt: Long?)

data class ConversationState(
    val items: List<ChatItem> = emptyList(),
    val status: RunStatus = RunStatus.STARTING,
    val title: String? = null,
    val cwd: String? = null,
    val sessionId: String? = null,
    val model: String? = null,
    val permissionMode: String? = null,
    val todos: List<TodoItem> = emptyList(),
    /** Unanswered permission requests, oldest first. */
    val pendingPermissions: List<ChatItem.Permission> = emptyList(),
    val commands: List<SlashCommand> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val totalCostUsd: Double = 0.0,
    /** Input+cache tokens of the latest assistant message ≈ context in use. */
    val contextTokens: Long? = null,
    /** Context window size the CLI reported for the current model; null until known. */
    val contextWindow: Long? = null,
    val queuedCount: Int = 0,
    val link: LinkState = LinkState.Idle,
    val loadingHistory: Boolean = true,
    val error: String? = null,
    val rateLimit: RateLimitInfo? = null,
    /** Claude subscription driving this session ("Claude Team"), from the CLI's initialize reply; null = API billing. */
    val planName: String? = null,
    /** Epoch ms when the current working stretch began (for the elapsed timer). */
    val workingSince: Long? = null,
    /** Live "thinking" token estimate while the model is thinking. */
    val thinkingTokens: Int? = null,
    /** Background shell commands / subagents still running, oldest first. */
    val backgroundTasks: List<BackgroundTask> = emptyList(),
    /** The session this conversation shows, plus draft / peers / tasks…; null until known. */
    val live: SessionLive? = null,
)

// ───────────────────────────── Settings ─────────────────────────────

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val biometricLock: Boolean = false,
    /** Re-lock after this long away (seconds); 0 = as soon as the user leaves the app. */
    val lockAfterSeconds: Int = 0,
    val defaultModel: String = "default",
    val defaultPermissionMode: String = PermissionMode.DEFAULT.cli,
    val showThinking: Boolean = true,
    val notifyPermissions: Boolean = true,
    val notifyCompletion: Boolean = true,
    /** Look for new Tether releases in the background and notify about them. */
    val notifyAppUpdates: Boolean = true,
    /** Keep a foreground service watching running agents while the app is in background. */
    val backgroundWatch: Boolean = true,
    /** Termius-style: keep SSH connections open in the background (foreground service + wake/Wi-Fi locks). */
    val keepConnectionsAlive: Boolean = true,
    /** The one-time "allow unrestricted battery" card on Home was dismissed. */
    val batteryPromptDismissed: Boolean = false,
    val haptics: Boolean = true,
    val codeFontScale: Float = 1f,
    val onboardingDone: Boolean = false,
    /** Collapse tool calls to one line by default. */
    val compactTools: Boolean = true,
    /** Keep the screen awake while a conversation is open. */
    val keepScreenOn: Boolean = false,
    /** Anonymous usage analytics (see app.tether.analytics.Analytics); on unless turned off. */
    val analyticsEnabled: Boolean = true,
    /** The one-time analytics notice on Home was acknowledged; nothing is sent before that. */
    val analyticsNoticeSeen: Boolean = false,
)

/** Accent colours (ARGB) a machine can be tagged with. */
val MachineAccents = listOf(
    0xFFD97757, 0xFF7FA7D9, 0xFF8CC084, 0xFFE3A857, 0xFFB392D9, 0xFF5FC4B8, 0xFFE58FA8, 0xFFB9B3A6,
)
