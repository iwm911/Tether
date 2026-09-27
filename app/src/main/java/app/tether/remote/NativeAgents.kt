package app.tether.remote

import app.tether.core.NATIVE_RUN_PREFIX
import app.tether.core.NativeSubagent
import app.tether.core.NativeTimelineEntry
import app.tether.core.RunInfo
import app.tether.core.RunKind
import app.tether.core.RunStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One Claude Code background agent as the helper's `native-list` / `watch` reports it:
 * `claude agents --json --all` merged with `~/.claude/jobs/<id>/state.json`.
 */
@Serializable
internal data class NativeAgentDto(
    val id: String,
    val sessionId: String? = null,
    val cwd: String = "",
    val kind: String = "background",
    val name: String? = null,
    val intent: String? = null,
    /** "working" | "done" | "blocked" | … */
    val state: String? = null,
    /** "busy" | "idle" | "waiting" (a permission prompt is open) — only while the process exists. */
    val status: String? = null,
    /** The tool call a "waiting" agent is asking about (newest tool_use without a result). */
    val pendingTool: NativePendingTool? = null,
    val pid: Long? = null,
    val alive: Boolean = false,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val detail: String? = null,
    val tempo: String? = null,
    val tasks: Int = 0,
    val queued: Int = 0,
    val fan: List<NativeSubagent> = emptyList(),
    val tokens: Long = 0,
    val result: String? = null,
    val model: String? = null,
    val permissionMode: String? = null,
    val transcript: Boolean = false,
    val timeline: List<NativeTimelineEntry> = emptyList(),
    /** native-reply only: the id replied to, and whether claude continued under a new id. */
    val previousId: String? = null,
    val forked: Boolean = false,
    /** Earlier ids of this same conversation (replies claude continued under a new id); hidden from the list. */
    val previousIds: List<String> = emptyList(),
)

@Serializable
internal data class NativePendingTool(
    val toolUseId: String,
    val toolName: String,
    val summary: String = "",
    val inputJson: String? = null,
)

internal object NativeAgents {
    /** Request ids of native approvals are synthesized from the tool_use id. */
    const val PERMISSION_PREFIX = "native:"

    private val listSerializer = ListSerializer(NativeAgentDto.serializer())

    fun parseList(json: String): List<NativeAgentDto> = RemoteJson.json.decodeFromString(listSerializer, json)

    fun parseOne(json: String): NativeAgentDto = RemoteJson.json.decodeFromString(NativeAgentDto.serializer(), json)

    /** A `watch` line `{"native":[…]}` → the agents, or null when the line is something else. */
    fun parseWatchLine(line: String): List<NativeAgentDto>? {
        val o: JsonObject = RemoteJson.parseObject(line) ?: return null
        val arr = o["native"] as? JsonArray ?: return null
        return RemoteJson.json.decodeFromJsonElement(listSerializer, arr)
    }

    fun parseTimeline(json: String): List<NativeTimelineEntry> =
        RemoteJson.json.decodeFromString(ListSerializer(NativeTimelineEntry.serializer()), json)

    fun status(d: NativeAgentDto): RunStatus = when {
        !d.alive -> RunStatus.ENDED
        d.status == "waiting" && d.kind == "interactive" -> RunStatus.IDLE // answered in the terminal, not here
        d.status == "waiting" -> RunStatus.AWAITING_PERMISSION
        d.status == "busy" -> RunStatus.WORKING
        d.state == "starting" -> RunStatus.STARTING
        d.status == null && d.state == "working" -> RunStatus.WORKING
        else -> RunStatus.IDLE // idle: done / blocked-on-a-question — waits for the next message
    }

    fun toRunInfo(d: NativeAgentDto): RunInfo {
        val last = d.result?.takeIf { it.isNotBlank() }
            ?: d.timeline.lastOrNull { !it.text.isNullOrBlank() }?.text
        return RunInfo(
            runId = NATIVE_RUN_PREFIX + d.id,
            cwd = d.cwd,
            title = d.name?.takeIf { it.isNotBlank() } ?: d.intent?.takeIf { it.isNotBlank() },
            sessionId = d.sessionId,
            model = d.model,
            permissionMode = d.permissionMode,
            startedAt = d.startedAt,
            updatedAt = maxOf(d.updatedAt, d.startedAt),
            alive = d.alive,
            status = status(d),
            lastText = last?.trim()?.let { if (it.length > 200) it.take(199).trimEnd() + "…" else it },
            kind = RunKind.NATIVE,
            nativeId = d.id,
            nativeState = d.state,
            nativeStatus = d.status,
            pending = d.pendingTool?.takeIf { d.alive && d.status == "waiting" }?.let {
                app.tether.core.PendingPermission(
                    requestId = PERMISSION_PREFIX + it.toolUseId,
                    toolName = it.toolName,
                    summary = it.summary,
                    inputJson = it.inputJson,
                )
            },
            detail = d.detail?.takeIf { it.isNotBlank() },
            subagents = d.fan,
            tokens = d.tokens,
            timeline = d.timeline,
            terminal = d.kind == "interactive",
        )
    }

    /** Is this `native-follow` line the history/live separator? */
    fun isCaughtUpMarker(line: String): Boolean = line.startsWith("{\"tether\":\"caught-up\"")

    fun isHeartbeat(line: String): Boolean = line.startsWith("{\"hb\":")
}
