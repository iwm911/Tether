package app.tether.remote

import app.tether.core.BackgroundTask
import app.tether.core.ChatItem
import app.tether.core.ConversationState
import app.tether.core.ModelOption
import app.tether.core.NoticeKind
import app.tether.core.PermissionMode
import app.tether.core.PermissionState
import app.tether.core.PermissionSuggestion
import app.tether.core.RateLimitInfo
import app.tether.core.UsageWindow
import app.tether.core.RunStatus
import app.tether.core.SlashCommand
import app.tether.core.TodoItem
import app.tether.core.TodoStatus
import app.tether.core.ToolResultData
import app.tether.core.ToolStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * ════════════════════════════ Claude Code stream-json → ConversationState ════════════════════════════
 *
 * Pure Kotlin (no Android), single-threaded: callers serialise access (the hub holds a lock).
 *
 * Inputs
 *   accept(line)            a line of the live run's out.jsonl (claude -p --output-format stream-json
 *                           --verbose --include-partial-messages --replay-user-messages)
 *   acceptTranscript(line)  a line of ~/.claude/projects/…/<session>.jsonl (history / read-only view)
 *   applyInput(lines)       lines WE wrote to the run's in.jsonl (permission answers, control requests)
 *   addOptimisticUser(...)  a message the phone just sent, shown before claude echoes it
 *
 * Mapping (verified against CLI 2.1.282, see test fixtures)
 *   stream_event message_start            → current message id per parent_tool_use_id (+ context tokens)
 *   stream_event content_block_start/delta → draft AssistantText / Thinking / ToolCall(STREAMING_INPUT)
 *                                            keyed "$messageId#$index" (tools: "tool:$toolUseId")
 *   assistant {message:{id, content:[ONE block]}} → the authoritative block; several events share an id,
 *                                            one block each. Replaces the draft of the same type
 *                                            (else keyed "$messageId#$ordinal").
 *   user tool_result (+ tool_use_result | toolUseResult) → ToolCall.result/status
 *                                            (is_error → ERROR; our deny / "doesn't want to proceed" →
 *                                            DENIED; cancelled prompt or interrupted → INTERRUPTED)
 *   user text (isReplay echo / transcript) → ChatItem.User (meta skipped; <command-name> → "/cmd args",
 *                                            command = true; "[Request interrupted…" → Notice INTERRUPTED;
 *                                            <local-command-stdout> → the command's commandOutput, else Notice)
 *   system/local_command (transcript)     → the command's commandOutput (like <local-command-stdout>)
 *   live <synthetic> assistant text        → output of a local command (/context, /cost…): never echoed,
 *                                            so it settles the oldest queued command instead
 *   parent_tool_use_id ≠ null             → nested under that ToolCall.children (Task / Agent subagents)
 *   TodoWrite input / TaskCreate+TaskUpdate → state.todos. The ToolCall rows are KEPT in items; the UI
 *                                            hides TodoWrite rows (TaskCreate/TaskUpdate rows are shown).
 *   control_request can_use_tool          → ChatItem.Permission(PENDING) + ToolCall AWAITING_PERMISSION
 *   control_response (ours, echoed or via applyInput) → ALLOWED / DENIED
 *   control_cancel_request                → CANCELLED
 *   control_response to initialize        → commands + models (+ current permission mode)
 *   system/init                           → sessionId, model, cwd, mode; Notice SESSION_START (first only)
 *   system/status permissionMode change   → Notice MODE_CHANGE
 *   system/thinking_tokens                → thinkingTokens
 *   system/task_started … task_notification → backgroundTasks (running only; ambient ones skipped).
 *                                            task_progress → last tool / tokens; task_updated patch.status
 *                                            terminal → removed. They outlive `result`: the UI shows them
 *                                            as background work while the session is IDLE.
 *   result                                → TurnSummary, totalCostUsd (cumulative), status IDLE
 *   rate_limit_event                      → rateLimit
 * Status: STARTING → (init response) IDLE; WORKING from a user echo / model activity until `result`;
 * AWAITING_PERMISSION whenever a prompt is pending. ENDED/FAILED are decided by the hub (process state).
 *
 * Cost: O(1) amortised per line — every item lives in an index map; text deltas append to a
 * StringBuilder and are materialised lazily in snapshot(); unchanged subagent child lists are cached.
 */
class StreamReducer(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private class Slot(var item: ChatItem) {
        var buffer: StringBuilder? = null
        var dirty = false

        fun materialize(): ChatItem {
            val b = buffer
            if (dirty && b != null) {
                val t = b.toString()
                item = when (val cur = item) {
                    is ChatItem.AssistantText -> cur.copy(text = t)
                    is ChatItem.Thinking -> cur.copy(text = t)
                    is ChatItem.ToolCall -> cur.copy(inputJson = t)
                    else -> cur
                }
            }
            dirty = false
            return item
        }
    }

    private class Container(val owner: String?) {
        val slots = ArrayList<Slot>()
        val index = HashMap<String, Int>()
        var cache: List<ChatItem>? = null

        fun reindex() {
            index.clear()
            for (i in slots.indices) index[slots[i].item.key] = i
        }
    }

    // ── item storage ──
    private val root = Container(null)
    private val children = HashMap<String, Container>()      // toolUseId → its subagent container
    private val home = HashMap<String, Container>()          // item key → container holding it
    private val seenUuids = HashSet<String>()

    // ── streaming bookkeeping ──
    private val streamMessage = HashMap<String, String>()    // parent id ("" = root) → current message id
    private val drafts = HashMap<String, java.util.TreeMap<Int, String>>() // message id → block index → key
    private val finalizedDrafts = HashSet<String>()
    private val finalOrdinal = HashMap<String, Int>()
    private val openDrafts = LinkedHashSet<String>()

    // ── permissions & our requests ──
    private val decisions = HashMap<String, PermissionState>()
    private val permissionKeys = LinkedHashMap<String, String>()  // requestId → item key
    private val permissionTool = HashMap<String, String>()        // requestId → toolUseId
    private val toolPermission = HashMap<String, String>()        // toolUseId → requestId
    private val interruptRequests = HashSet<String>()
    private val modelRequests = HashMap<String, String>()
    private val optimistic = ArrayList<String>()

    // ── todos ──
    private var todoWrite: List<TodoItem> = emptyList()
    private val tasks = LinkedHashMap<String, TodoItem>()
    private val taskCreates = HashMap<String, TodoItem>()
    private var todosFromTasks = false

    // ── background tasks (task_started … task_notification) ──
    private val bgTasks = LinkedHashMap<String, BackgroundTask>()

    // ── scalar state ──
    private var status = RunStatus.STARTING
    private var customTitle: String? = null
    private var aiTitle: String? = null
    private var summaryTitle: String? = null
    private var cwd: String? = null
    private var sessionId: String? = null
    private var model: String? = null
    private var permissionMode: String? = null
    /** False while the mode only comes from the initialize response; the first confirmation is silent. */
    private var modeConfirmed = false
    private var commands: List<SlashCommand> = emptyList()
    private var models: List<ModelOption> = emptyList()
    private var totalCost = 0.0
    private var lastTotalCost: Double? = null
    private var contextTokens: Long? = null
    /** Context window of the current model as the CLI reports it (`modelUsage[..].contextWindow`). */
    private var contextWindow: Long? = null
    private var rateLimit: RateLimitInfo? = null
    private var planName: String? = null
    /** uuid of the newest top-level assistant line — the fork point for the next user message. */
    private var lastAssistantUuid: String? = null
    private var workingSince: Long? = null
    private var thinkingTokens: Int? = null
    private var sessionNoticeShown = false

    private var noticeSeq = 0
    private var turnSeq = 0
    private var localSeq = 0
    private var userSeq = 0

    /** Increments on every state change; lets callers skip redundant snapshots. */
    var version: Long = 0L
        private set

    // ═══════════════════════════════════════ public API ═══════════════════════════════════════

    /** Feeds one line of the live stream (out.jsonl). Returns true when the state changed. */
    fun accept(line: String): Boolean = handle(line, live = true)

    /** Feeds one transcript line (history). Sidechain / attachment / bookkeeping lines are ignored. */
    fun acceptTranscript(line: String): Boolean = handle(line, live = false)

    fun acceptAll(lines: Iterable<String>, transcript: Boolean = false) {
        for (l in lines) handle(l, live = !transcript)
    }

    /**
     * Applies lines that were written to the run's stdin. [historical] = replaying an existing
     * in.jsonl on attach (no optimistic mode flips / notices; decisions are still recorded).
     */
    fun applyInput(lines: List<String>, historical: Boolean = false) {
        var changed = false
        for (line in lines) {
            val o = RemoteJson.parseObject(line) ?: continue
            when (o.str("type")) {
                "control_response" -> {
                    val resp = o.obj("response") ?: continue
                    val rid = resp.str("request_id") ?: continue
                    val behavior = resp.obj("response")?.str("behavior") ?: continue
                    if (applyDecision(rid, behavior)) changed = true
                }
                "control_request" -> {
                    val rid = o.str("request_id") ?: continue
                    val req = o.obj("request") ?: continue
                    when (req.str("subtype")) {
                        "interrupt" -> interruptRequests.add(rid)
                        "set_model" -> req.str("model")?.let { modelRequests[rid] = it }
                        "set_permission_mode" -> {
                            val mode = req.str("mode")
                            if (rid == INITIAL_MODE_REQUEST) {
                                // The helper's start-up mode pin (Ask) is the initial mode, not a change.
                                if (mode != null && !modeConfirmed) permissionMode = mode
                            } else if (!historical && mode != null && mode != permissionMode) {
                                permissionMode = mode
                                modeConfirmed = true
                                addNotice(modeLabel(mode), NoticeKind.MODE_CHANGE)
                                changed = true
                            }
                        }
                    }
                }
            }
        }
        if (changed) version++
    }

    /** Shows a just-sent message immediately; reconciled when claude echoes it. Returns its key. */
    fun addOptimisticUser(text: String, imageCount: Int, queued: Boolean): String {
        val key = "local:${localSeq++}"
        append(root, ChatItem.User(key = key, text = text, imageCount = imageCount, timestamp = clock(), queued = queued,
            command = isKnownCommand(text)))
        optimistic.add(key)
        version++
        return key
    }

    /** Drops an optimistic message (its send failed). */
    fun removeOptimistic(key: String) {
        if (optimistic.remove(key)) {
            remove(key)
            version++
        }
    }

    /** Adds a separator notice between resumed history and the live run. */
    fun addHistoryBoundary(text: String = "Resumed session") {
        addNotice(text, NoticeKind.INFO)
        sessionNoticeShown = true // the boundary already marks where this run begins
        version++
    }

    /** Input JSON of a permission request, as claude sent it (to echo back as updatedInput). */
    fun permissionInput(requestId: String): String? {
        val key = permissionKeys[requestId] ?: return null
        return (find(key)?.item as? ChatItem.Permission)?.inputJson
    }

    val currentStatus: RunStatus get() = status

    fun snapshot(): ConversationState {
        val items = materialize(root)
        val pending = ArrayList<ChatItem.Permission>()
        for (key in permissionKeys.values) {
            val p = find(key)?.item as? ChatItem.Permission ?: continue
            if (p.state == PermissionState.PENDING) pending.add(p)
        }
        val effective = if (pending.isNotEmpty() && (status == RunStatus.WORKING || status == RunStatus.IDLE || status == RunStatus.STARTING)) {
            RunStatus.AWAITING_PERMISSION
        } else status
        val todos = if (todosFromTasks) tasks.values.toList() else todoWrite
        var queued = 0
        for (k in optimistic) if ((find(k)?.item as? ChatItem.User)?.queued == true) queued++
        return ConversationState(
            items = items,
            status = effective,
            title = customTitle ?: aiTitle ?: summaryTitle,
            cwd = cwd,
            sessionId = sessionId,
            model = model,
            permissionMode = permissionMode,
            todos = todos,
            pendingPermissions = pending,
            commands = commands,
            models = models,
            totalCostUsd = totalCost,
            contextTokens = contextTokens,
            contextWindow = contextWindow,
            queuedCount = queued,
            loadingHistory = false,
            rateLimit = rateLimit,
            planName = planName,
            workingSince = if (effective == RunStatus.WORKING || effective == RunStatus.AWAITING_PERMISSION) workingSince else null,
            thinkingTokens = if (effective == RunStatus.WORKING) thinkingTokens else null,
            backgroundTasks = bgTasks.values.toList(),
        )
    }

    // ═══════════════════════════════════════ dispatch ═══════════════════════════════════════

    private fun handle(line: String, live: Boolean): Boolean {
        val o = RemoteJson.parseObject(line) ?: return false
        val before = version
        val uuid = o.str("uuid")
        val type = o.str("type") ?: return false
        if (uuid != null && (type == "user" || type == "assistant")) {
            if (!seenUuids.add(uuid)) return false
        }
        if (!live && o.bool("isSidechain") == true) return false
        // Transcript lines carry the session's cwd / id on every user & assistant line (no system/init):
        // without this a "Continue this conversation" run would start in ~ instead of the project.
        if (!live && (type == "user" || type == "assistant")) {
            if (cwd == null) o.str("cwd")?.takeIf { it.startsWith("/") }?.let { cwd = it }
            if (sessionId == null) o.str("sessionId")?.let { sessionId = it }
        }
        when (type) {
            "stream_event" -> if (live) onStreamEvent(o)
            "assistant" -> onAssistant(o, live)
            "user" -> onUser(o, live)
            "system" -> onSystem(o, live)
            "result" -> if (live) onResult(o)
            "control_request" -> if (live) onControlRequest(o)
            "control_cancel_request" -> if (live) onControlCancel(o)
            "control_response" -> onControlResponse(o)
            "rate_limit_event" -> onRateLimit(o)
            "custom-title" -> o.str("customTitle")?.takeIf { it.isNotBlank() }?.let { customTitle = it; version++ }
            "ai-title" -> o.str("aiTitle")?.takeIf { it.isNotBlank() }?.let { aiTitle = it; version++ }
            "summary" -> o.str("summary")?.takeIf { it.isNotBlank() }?.let { summaryTitle = it; version++ }
            "tether-truncated" -> {
                val n = o.long("droppedLines") ?: 0
                addNotice("Earlier history not shown ($n lines)", NoticeKind.INFO)
                version++
            }
            else -> Unit // attachment, queue-operation, atis-latch, last-prompt, file-history-snapshot…
        }
        return version != before
    }

    // ═══════════════════════════════════════ stream events ═══════════════════════════════════════

    private fun onStreamEvent(o: JsonObject) {
        val ev = o.obj("event") ?: return
        val parent = o.str("parent_tool_use_id")
        val pkey = parent ?: ""
        when (ev.str("type")) {
            "message_start" -> {
                val msg = ev.obj("message") ?: return
                val id = msg.str("id") ?: return
                streamMessage[pkey] = id
                if (parent == null) {
                    msg.obj("usage")?.let { u -> usageTokens(u)?.let { contextTokens = it } }
                    markWorking(null)
                }
                version++
            }
            "content_block_start" -> {
                val msgId = streamMessage[pkey] ?: return
                val index = ev.int("index") ?: return
                val block = ev.obj("content_block") ?: return
                val container = containerFor(parent)
                val key: String
                val slot: Slot
                when (block.str("type")) {
                    "text" -> {
                        key = "$msgId#$index"
                        if (home.containsKey(key)) return
                        slot = append(container, ChatItem.AssistantText(key = key, text = "", streaming = true))
                        slot.buffer = StringBuilder(block.str("text").orEmpty())
                        slot.dirty = true
                    }
                    "thinking" -> {
                        key = "$msgId#$index"
                        if (home.containsKey(key)) return
                        slot = append(container, ChatItem.Thinking(key = key, text = "", streaming = true))
                        slot.buffer = StringBuilder(block.str("thinking").orEmpty())
                        slot.dirty = true
                    }
                    "tool_use", "server_tool_use" -> {
                        val toolId = block.str("id") ?: return
                        key = toolKey(toolId)
                        if (home.containsKey(key)) {
                            drafts.getOrPut(msgId) { java.util.TreeMap() }[index] = key
                            return
                        }
                        slot = append(
                            container,
                            ChatItem.ToolCall(
                                key = key, toolUseId = toolId, name = block.str("name") ?: "Tool", inputJson = "",
                                status = ToolStatus.STREAMING_INPUT, startedAt = clock(),
                            ),
                        )
                        slot.buffer = StringBuilder()
                    }
                    else -> return
                }
                drafts.getOrPut(msgId) { java.util.TreeMap() }[index] = key
                openDrafts.add(key)
                if (parent == null) markWorking(null)
                version++
            }
            "content_block_delta" -> {
                val msgId = streamMessage[pkey] ?: return
                val index = ev.int("index") ?: return
                val key = drafts[msgId]?.get(index) ?: return
                if (key in finalizedDrafts) return
                val (container, slot) = locate(key) ?: return
                val delta = ev.obj("delta") ?: return
                val buf = slot.buffer ?: return
                when (delta.str("type")) {
                    "text_delta" -> buf.append(delta.str("text").orEmpty())
                    "thinking_delta" -> {
                        buf.append(delta.str("thinking").orEmpty())
                        delta.int("estimated_tokens")?.let { est ->
                            val cur = slot.item
                            if (cur is ChatItem.Thinking) slot.item = cur.copy(estimatedTokens = est)
                        }
                    }
                    "input_json_delta" -> {
                        val cur = slot.item
                        if (cur is ChatItem.ToolCall && cur.status != ToolStatus.STREAMING_INPUT) return
                        buf.append(delta.str("partial_json").orEmpty())
                    }
                    else -> return
                }
                slot.dirty = true
                touch(container)
                version++
            }
            "content_block_stop" -> {
                val msgId = streamMessage[pkey] ?: return
                val index = ev.int("index") ?: return
                val key = drafts[msgId]?.get(index) ?: return
                if (key in finalizedDrafts) return
                val (container, slot) = locate(key) ?: return
                val cur = slot.materialize()
                val next = when (cur) {
                    is ChatItem.AssistantText -> if (cur.streaming) cur.copy(streaming = false) else null
                    is ChatItem.Thinking -> if (cur.streaming) cur.copy(streaming = false) else null
                    else -> null
                }
                if (next != null) {
                    slot.item = next
                    touch(container)
                    version++
                }
            }
            else -> Unit
        }
    }

    // ═══════════════════════════════════════ assistant ═══════════════════════════════════════

    private fun onAssistant(o: JsonObject, live: Boolean) {
        val msg = o.obj("message") ?: return
        val parent = o.str("parent_tool_use_id")
        val msgId = msg.str("id") ?: o.str("uuid") ?: "m${userSeq++}"
        val ts = isoToEpochMs(o.str("timestamp"))
        val lineUuid = o.str("uuid")
        if (parent == null && lineUuid != null && o.bool("isSidechain") != true) lastAssistantUuid = lineUuid
        if (live && parent == null && msg.str("model") == "<synthetic>") {
            val text = (msg["content"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }?.joinToString("\n")
            if (text != null && onLocalCommandReply(text)) return
        }
        val container = containerFor(parent)
        if (parent == null) {
            msg.obj("usage")?.let { u -> usageTokens(u)?.let { contextTokens = it } }
            msg.str("model")?.takeIf { it.isNotBlank() && !it.startsWith("<") }?.let { model = it }
            if (live) markWorking(ts)
        }
        val content = msg["content"]
        val blocks: List<JsonObject> = when (content) {
            is JsonArray -> content.mapNotNull { it as? JsonObject }
            is JsonPrimitive -> if (content.isString) listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to content))) else emptyList()
            else -> emptyList()
        }
        for (block in blocks) {
            val ordinal = finalOrdinal[msgId] ?: 0
            finalOrdinal[msgId] = ordinal + 1
            when (block.str("type")) {
                "text" -> {
                    val text = block.str("text").orEmpty()
                    val key = claimDraft(msgId, ChatItem.AssistantText::class.java) ?: "$msgId#$ordinal"
                    if (text.isBlank() && !home.containsKey(key)) continue
                    finalize(container, key, ChatItem.AssistantText(key = key, text = text, streaming = false, uuid = lineUuid))
                }
                "thinking", "redacted_thinking" -> {
                    val text = block.str("thinking").orEmpty()
                    val key = claimDraft(msgId, ChatItem.Thinking::class.java) ?: "$msgId#$ordinal"
                    val est = (find(key)?.item as? ChatItem.Thinking)?.estimatedTokens ?: thinkingTokens
                    finalize(container, key, ChatItem.Thinking(key = key, text = text, streaming = false, estimatedTokens = est))
                }
                "tool_use", "server_tool_use" -> onToolUse(container, msgId, block, ts ?: clock())
                else -> Unit
            }
        }
        version++
    }

    private fun onToolUse(container: Container, msgId: String, block: JsonObject, ts: Long) {
        val toolId = block.str("id") ?: return
        val key = toolKey(toolId)
        val name = block.str("name") ?: "Tool"
        val input = block["input"] ?: JsonObject(emptyMap())
        val existingSlot = locate(key)
        val existing = existingSlot?.second?.materialize() as? ChatItem.ToolCall
        val keepStatus = existing?.status?.takeIf {
            it == ToolStatus.AWAITING_PERMISSION || it == ToolStatus.SUCCESS || it == ToolStatus.ERROR ||
                it == ToolStatus.DENIED || it == ToolStatus.INTERRUPTED
        }
        val item = ChatItem.ToolCall(
            key = key, toolUseId = toolId, name = name, inputJson = input.toString(),
            status = keepStatus ?: ToolStatus.RUNNING,
            result = existing?.result, startedAt = existing?.startedAt ?: ts, finishedAt = existing?.finishedAt,
        )
        finalize(existingSlot?.first ?: container, key, item)
        val inObj = input as? JsonObject
        when (name) {
            "TodoWrite" -> inObj?.arr("todos")?.let { arr ->
                todoWrite = arr.mapNotNull { e ->
                    val t = e as? JsonObject ?: return@mapNotNull null
                    val content = t.str("content") ?: return@mapNotNull null
                    TodoItem(content = content, activeForm = t.str("activeForm") ?: content, status = todoStatus(t.str("status")))
                }
                todosFromTasks = false
            }
            "TaskCreate" -> inObj?.let { t ->
                val subject = t.str("subject") ?: t.str("content") ?: t.str("description") ?: return@let
                taskCreates[toolId] = TodoItem(subject, t.str("activeForm") ?: subject, TodoStatus.PENDING)
            }
            "TaskUpdate" -> inObj?.let { t ->
                val id = t.str("taskId") ?: t.long("taskId")?.toString() ?: t.str("id") ?: return@let
                val cur = tasks[id]
                val st = t.str("status")
                if (st == "deleted") {
                    tasks.remove(id)
                } else {
                    val subject = t.str("subject") ?: cur?.content ?: return@let
                    tasks[id] = TodoItem(
                        content = subject,
                        activeForm = t.str("activeForm") ?: cur?.activeForm ?: subject,
                        status = if (st != null) todoStatus(st) else cur?.status ?: TodoStatus.PENDING,
                    )
                }
                todosFromTasks = true
            }
        }
    }

    // ═══════════════════════════════════════ user ═══════════════════════════════════════

    private fun onUser(o: JsonObject, live: Boolean) {
        if (o.bool("isMeta") == true || o.bool("isCompactSummary") == true) return
        val parent = o.str("parent_tool_use_id")
        val msg = o.obj("message") ?: return
        val ts = isoToEpochMs(o.str("timestamp"))
        val content = msg["content"]
        val structured = o["tool_use_result"] ?: o["toolUseResult"]
        if (content is JsonArray) {
            val blocks = content.mapNotNull { it as? JsonObject }
            val results = blocks.filter { it.str("type") == "tool_result" }
            if (results.isNotEmpty()) {
                for (r in results) onToolResult(r, if (results.size == 1) structured else null, ts)
                if (live && parent == null) markWorking(ts)
                return
            }
            val text = blocks.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }
            val images = blocks.count { it.str("type") == "image" }
            onUserText(text, images, o, parent, ts, live)
        } else if (content is JsonPrimitive && content.isString) {
            onUserText(content.content, 0, o, parent, ts, live)
        }
    }

    private fun onUserText(raw: String, images: Int, o: JsonObject, parent: String?, ts: Long?, live: Boolean) {
        if (parent != null) return // a subagent's prompt: already visible as the Task input
        val text = raw.trim()
        if (text.isEmpty() && images == 0) return
        if (text.startsWith("[Request interrupted")) {
            addNotice("Interrupted", NoticeKind.INTERRUPTED)
            interruptRunningTools()
            version++
            return
        }
        if (text.startsWith("<local-command-stdout>") || text.startsWith("<local-command-stderr>")) {
            onLocalCommandOutput(text)
            return
        }
        val isCommand = text.contains("<command-name>")
        val display: String = when {
            isCommand -> {
                val name = Regex("<command-name>\\s*(/?[^<\\s]+)\\s*</command-name>").find(text)?.groupValues?.get(1) ?: return
                val args = Regex("<command-args>(.*?)</command-args>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim().orEmpty()
                ((if (name.startsWith("/")) name else "/$name") + " " + args).trim()
            }
            isMetaText(text) -> return
            else -> text
        }
        if (o.bool("isSynthetic") == true) return
        // Live: real prompts are exactly the --replay-user-messages echoes (verified: queued ones too).
        if (live && o.bool("isReplay") != true) return
        val reconciled = takeOptimistic(display)
        val key = reconciled ?: (o.str("uuid")?.let { "u:$it" } ?: "u:#${userSeq++}")
        if (reconciled != null) remove(reconciled)
        append(root, ChatItem.User(key = key, text = display, imageCount = images, timestamp = ts ?: clock(), queued = false,
            uuid = o.str("uuid"), forkPointUuid = lastAssistantUuid, command = isCommand || isKnownCommand(display)))
        if (live) {
            status = RunStatus.WORKING
            if (workingSince == null) workingSince = ts ?: clock()
        }
        version++
    }

    /**
     * `<local-command-stdout>` (a user line, or a transcript's `system`/`local_command` line): a model
     * switch becomes its notice; anything else is folded into the command that printed it.
     */
    private fun onLocalCommandOutput(text: String) {
        val body = stripAnsi(text.replace(Regex("</?local-command-(stdout|stderr)>"), "")).trim()
        if (body.isEmpty()) return
        if (body.startsWith("Set model to")) {
            Regex("`([^`]+)`").find(body)?.groupValues?.get(1)?.let { m ->
                model = Regex("\\(([^)]+)\\)").find(m)?.groupValues?.get(1) ?: m
            }
            addNotice(body.replace("`", "").replaceFirst("Set model to", "Model set to"), NoticeKind.MODEL_CHANGE)
        } else if (!attachCommandOutput(body)) {
            addNotice(if (body.length > 300) body.take(299) + "…" else body, if (text.contains("stderr")) NoticeKind.WARNING else NoticeKind.INFO)
        }
        version++
    }

    /** Sets [output] on the newest top-level item when it is a command still without output. */
    private fun attachCommandOutput(output: String): Boolean {
        val slot = root.slots.lastOrNull() ?: return false
        val cur = slot.item as? ChatItem.User ?: return false
        if (!cur.command || cur.commandOutput != null) return false
        slot.item = cur.copy(commandOutput = capCommandOutput(output))
        touch(root)
        return true
    }

    /**
     * Live, a local command (/context, /cost…) is never echoed back as a user message: its output
     * arrives as a `<synthetic>` assistant message. That output is what settles the queued command.
     * initialize omits some built-ins (/cost), so any pending `/name` message qualifies as a fallback.
     */
    private fun onLocalCommandReply(text: String): Boolean {
        val pending = optimistic.mapNotNull { k -> (find(k)?.item as? ChatItem.User)?.let { k to it } }
        val key = (pending.firstOrNull { it.second.command } ?: pending.firstOrNull { SLASH_NAME.containsMatchIn(it.second.text) })?.first
            ?: return false
        val slot = find(key) ?: return false
        val cur = slot.item as? ChatItem.User ?: return false
        optimistic.remove(key)
        slot.item = cur.copy(queued = false, command = true, commandOutput = text.trim().takeIf { it.isNotEmpty() }?.let(::capCommandOutput))
        home[key]?.let(::touch)
        version++
        return true
    }

    private fun capCommandOutput(s: String): String = if (s.length > COMMAND_OUTPUT_CAP) s.take(COMMAND_OUTPUT_CAP - 1) + "…" else s

    /** [text] starts with `/name` where name is one of the commands Claude reported. */
    private fun isKnownCommand(text: String): Boolean {
        val name = SLASH_NAME.find(text)?.groupValues?.get(1) ?: return false
        return commands.any { it.name.removePrefix("/") == name }
    }

    private fun onToolResult(block: JsonObject, structured: JsonElement?, ts: Long?) {
        val toolId = block.str("tool_use_id") ?: return
        val key = toolKey(toolId)
        val (container, slot) = locate(key) ?: return
        val cur = slot.materialize() as? ChatItem.ToolCall ?: return
        val text = capResult(resultText(block["content"]))
        val isError = block.bool("is_error") == true
        val structuredObj = structured as? JsonObject
        // Live out.jsonl is not slimmed by the helper: drop whole-file copies and oversize payloads so a
        // long run with big reads/edits cannot hold hundreds of MB in memory.
        val structuredJson = when (structured) {
            is JsonObject -> JsonObject(structured - "originalFile").toString().takeIf { it.length <= STRUCTURED_CAP }
            is JsonArray -> structured.toString().takeIf { it.length <= STRUCTURED_CAP }
            else -> null
        }
        val rid = toolPermission[toolId]
        val permState = rid?.let { decisions[it] ?: (find(permissionKeys[it] ?: "")?.item as? ChatItem.Permission)?.state }
        val interrupted = structuredObj?.bool("interrupted") == true ||
            text.contains("[Request interrupted by user") || permState == PermissionState.CANCELLED
        val denied = !interrupted && (permState == PermissionState.DENIED || cur.status == ToolStatus.DENIED ||
            (isError && (text.startsWith("The user doesn't want to proceed") || text.startsWith("Permission to use") ||
                text.contains("was denied") || text.contains("permission denied by user", ignoreCase = true))))
        val newStatus = when {
            interrupted -> ToolStatus.INTERRUPTED
            denied -> ToolStatus.DENIED
            isError -> ToolStatus.ERROR
            else -> ToolStatus.SUCCESS
        }
        slot.item = cur.copy(
            status = newStatus,
            result = ToolResultData(text = text, isError = isError, structuredJson = structuredJson),
            finishedAt = ts ?: clock(),
        )
        slot.buffer = null
        slot.dirty = false
        openDrafts.remove(key)
        touch(container)
        if (cur.name == "TaskCreate") {
            val item = taskCreates.remove(toolId)
            if (item != null && !isError) {
                val id = structuredObj?.obj("task")?.let { it.str("id") ?: it.long("id")?.toString() }
                    ?: Regex("#(\\d+)").find(text)?.groupValues?.get(1) ?: toolId
                val subject = structuredObj?.obj("task")?.str("subject") ?: item.content
                tasks[id] = item.copy(content = subject)
                todosFromTasks = true
            }
        }
        version++
    }

    // ═══════════════════════════════════════ system / result ═══════════════════════════════════════

    private fun onSystem(o: JsonObject, live: Boolean) {
        when (o.str("subtype")) {
            "local_command" -> o.str("content")?.let(::onLocalCommandOutput)
            "init" -> {
                o.str("session_id")?.let { sessionId = it }
                o.str("model")?.takeIf { it.isNotBlank() && !it.startsWith("<") }?.let { model = it; windowHint(it) }
                o.str("cwd")?.let { cwd = it }
                o.str("permissionMode")?.let { permissionMode = it; modeConfirmed = true }
                if (!sessionNoticeShown) {
                    sessionNoticeShown = true
                    addNotice("Session started", NoticeKind.SESSION_START)
                }
                if (live && status == RunStatus.STARTING) status = RunStatus.IDLE
                version++
            }
            "status" -> {
                val mode = o.str("permissionMode")
                if (mode != null && (mode != permissionMode || !modeConfirmed)) {
                    val announce = modeConfirmed && mode != permissionMode
                    permissionMode = mode
                    modeConfirmed = true
                    if (announce) addNotice(modeLabel(mode), NoticeKind.MODE_CHANGE)
                    version++
                }
                if (live && o.str("status") == "requesting") {
                    markWorking(null)
                    version++
                } else if (o.str("status") == "compacting") {
                    addNotice("Compacting conversation…", NoticeKind.COMPACTED)
                    version++
                }
            }
            "compact_boundary" -> {
                addNotice("Conversation compacted", NoticeKind.COMPACTED)
                version++
            }
            "thinking_tokens" -> {
                o.int("estimated_tokens")?.let { thinkingTokens = it; version++ }
            }
            "permission_denied" -> {
                val toolId = o.str("tool_use_id")
                if (toolId != null) {
                    val loc = locate(toolKey(toolId))
                    val cur = loc?.second?.materialize() as? ChatItem.ToolCall
                    if (loc != null && cur != null) {
                        loc.second.item = cur.copy(status = ToolStatus.DENIED)
                        touch(loc.first)
                    }
                }
                val m = o.str("message")
                val tool = o.str("tool_name") ?: "A tool"
                addNotice(if (!m.isNullOrBlank()) "$tool blocked · $m" else "$tool was blocked", NoticeKind.WARNING)
                version++
            }
            "task_started" -> {
                val id = o.str("task_id") ?: return
                if (o.bool("ambient") == true) return
                bgTasks[id] = BackgroundTask(
                    id = id,
                    description = o.str("description")?.takeIf { it.isNotBlank() } ?: "Background task",
                    type = o.str("task_type"),
                    subagentType = o.str("subagent_type"),
                )
                version++
            }
            "task_progress" -> {
                val id = o.str("task_id") ?: return
                val cur = bgTasks[id] ?: return
                val usage = o.obj("usage")
                bgTasks[id] = cur.copy(
                    description = o.str("description")?.takeIf { it.isNotBlank() } ?: cur.description,
                    lastToolName = o.str("last_tool_name") ?: cur.lastToolName,
                    summary = o.str("summary")?.takeIf { it.isNotBlank() } ?: cur.summary,
                    totalTokens = usage?.long("total_tokens") ?: cur.totalTokens,
                    toolUses = usage?.int("tool_uses") ?: cur.toolUses,
                )
                version++
            }
            "task_updated" -> {
                val id = o.str("task_id") ?: return
                val patch = o.obj("patch") ?: return
                val st = patch.str("status")
                if (st != null && st != "running" && st != "pending") {
                    if (bgTasks.remove(id) != null) version++
                } else {
                    val d = patch.str("description")?.takeIf { it.isNotBlank() } ?: return
                    bgTasks[id]?.let { bgTasks[id] = it.copy(description = d); version++ }
                }
            }
            "task_notification" -> {
                o.str("task_id")?.let { if (bgTasks.remove(it) != null) version++ }
                o.str("summary")?.takeIf { it.isNotBlank() }?.let {
                    addNotice(if (it.length > 200) it.take(199) + "…" else it, NoticeKind.INFO)
                    version++
                }
            }
            "api_retry", "api_error", "error" -> {
                val m = o.str("message") ?: o.str("error") ?: "Claude hit an API error, retrying…"
                addNotice(m, if (o.str("subtype") == "api_retry") NoticeKind.WARNING else NoticeKind.ERROR)
                version++
            }
            else -> Unit
        }
    }

    private fun onResult(o: JsonObject) {
        val total = o.double("total_cost_usd")
        val numTurns = o.int("num_turns")
        val resultText = o.str("result")
        val isError = o.bool("is_error") == true
        val subtype = o.str("subtype") ?: "success"
        val terminal = o.str("terminal_reason").orEmpty()
        val interrupted = terminal.startsWith("aborted") || terminal == "interrupted"
        val bookkeeping = (numTurns ?: 0) == 0 && resultText.isNullOrEmpty() && !isError
        val turnCost = if (total != null) (total - (lastTotalCost ?: 0.0)).takeIf { it >= 0 } else null
        if (total != null) {
            totalCost = total
            lastTotalCost = total
        }
        o.str("session_id")?.let { sessionId = it }
        o.obj("modelUsage")?.let(::onModelUsage)
        if (!bookkeeping) {
            if (interrupted) {
                if (!lastIsInterruptNotice()) addNotice("Interrupted", NoticeKind.INTERRUPTED)
                interruptRunningTools()
            } else {
                val success = !isError && subtype == "success"
                val err = if (success) null else {
                    resultText?.takeIf { it.isNotBlank() }
                        ?: o.arr("errors")?.mapNotNull { (it as? JsonPrimitive)?.content }?.firstOrNull { !it.startsWith("[ede_diagnostic]") }
                        ?: humanizeSubtype(subtype)
                }
                if (!success) interruptRunningTools()
                val key = "turn:${turnSeq++}"
                append(
                    root,
                    ChatItem.TurnSummary(
                        key = key, success = success, costUsd = turnCost, durationMs = o.long("duration_ms"),
                        numTurns = numTurns, errorText = err,
                    ),
                )
            }
        }
        // Close any draft still marked streaming and any prompt the turn left behind.
        for (key in openDrafts.toList()) {
            val (container, slot) = locate(key) ?: continue
            val cur = slot.materialize()
            val next = when (cur) {
                is ChatItem.AssistantText -> cur.copy(streaming = false)
                is ChatItem.Thinking -> cur.copy(streaming = false)
                is ChatItem.ToolCall -> if (cur.status == ToolStatus.STREAMING_INPUT) cur.copy(status = ToolStatus.INTERRUPTED) else cur
                else -> cur
            }
            slot.item = next
            touch(container)
        }
        openDrafts.clear()
        for ((rid, key) in permissionKeys) {
            val (container, slot) = locate(key) ?: continue
            val p = slot.item as? ChatItem.Permission ?: continue
            if (p.state == PermissionState.PENDING) {
                slot.item = p.copy(state = PermissionState.CANCELLED)
                decisions[rid] = PermissionState.CANCELLED
                touch(container)
            }
        }
        status = RunStatus.IDLE
        workingSince = null
        thinkingTokens = null
        version++
    }

    // ═══════════════════════════════════════ control ═══════════════════════════════════════

    private fun onControlRequest(o: JsonObject) {
        val rid = o.str("request_id") ?: return
        val req = o.obj("request") ?: return
        if (req.str("subtype") != "can_use_tool") return
        val key = "perm:$rid"
        if (home.containsKey(key)) return
        val toolName = req.str("tool_name") ?: req.str("display_name") ?: "Tool"
        val toolId = req.str("tool_use_id")
        val input = req["input"] ?: JsonObject(emptyMap())
        val state = decisions[rid] ?: PermissionState.PENDING
        val suggestions = req.arr("permission_suggestions")?.mapNotNull { s ->
            val so = s as? JsonObject ?: return@mapNotNull null
            PermissionSuggestion(rawJson = so.toString(), label = suggestionLabel(so))
        }.orEmpty()
        append(
            root,
            ChatItem.Permission(
                key = key, requestId = rid, toolName = toolName, toolUseId = toolId, inputJson = input.toString(),
                description = req.str("description") ?: req.str("decision_reason"),
                blockedPath = req.str("blocked_path"), suggestions = suggestions, state = state,
            ),
        )
        permissionKeys[rid] = key
        if (toolId != null) {
            permissionTool[rid] = toolId
            toolPermission[toolId] = rid
            val loc = locate(toolKey(toolId))
            if (loc == null) {
                append(
                    root,
                    ChatItem.ToolCall(
                        key = toolKey(toolId), toolUseId = toolId, name = toolName, inputJson = input.toString(),
                        status = toolStatusFor(state), startedAt = clock(),
                    ),
                )
            } else {
                val cur = loc.second.materialize() as? ChatItem.ToolCall
                if (cur != null && (cur.status == ToolStatus.RUNNING || cur.status == ToolStatus.STREAMING_INPUT)) {
                    loc.second.item = cur.copy(status = toolStatusFor(state))
                    touch(loc.first)
                }
            }
        }
        if (state == PermissionState.PENDING) markWorking(null)
        version++
    }

    private fun onControlCancel(o: JsonObject) {
        val rid = o.str("request_id") ?: return
        decisions[rid] = PermissionState.CANCELLED
        setPermissionState(rid, PermissionState.CANCELLED)
        version++
    }

    private fun onControlResponse(o: JsonObject) {
        val resp = o.obj("response") ?: return
        val rid = resp.str("request_id")
        val body = resp.obj("response")
        if (resp.str("subtype") == "error") {
            val err = resp.str("error") ?: "A request to Claude failed."
            if (rid != null && rid in interruptRequests) return
            addNotice(err, NoticeKind.ERROR)
            version++
            return
        }
        if (body != null && body.containsKey("commands")) {
            commands = parseSlashCommands(body.arr("commands"))
            models = body.arr("models")?.mapNotNull { m ->
                val mo = m as? JsonObject ?: return@mapNotNull null
                val value = mo.str("value") ?: return@mapNotNull null
                ModelOption(value = value, displayName = mo.str("displayName") ?: value, description = mo.str("description").orEmpty())
            }.orEmpty()
            if (permissionMode == null) body.str("current_permission_mode")?.let { permissionMode = it }
            body.obj("account")?.let { acct ->
                val provider = acct.str("apiProvider")
                val sub = acct.str("subscriptionType")?.takeIf { it.isNotBlank() }
                planName = if (sub != null && (provider == null || provider == "firstParty")) sub else null
            }
            if (status == RunStatus.STARTING) status = RunStatus.IDLE
            version++
            return
        }
        if (rid == null) return
        val behavior = body?.str("behavior")
        if (behavior != null) {
            applyDecision(rid, behavior)
            version++
            return
        }
        if (rid in interruptRequests) return
        modelRequests.remove(rid)?.let { requested ->
            val cur = model
            if (cur == null || !cur.contains(requested)) model = requested
            contextWindow = null
            windowHint(requested)
            version++
            return
        }
        body?.str("mode")?.let { m ->
            // An answer to OUR set_permission_mode: an explicit user action, always worth a notice
            // (except the helper's start-up pin, which just establishes the initial mode).
            if (rid == INITIAL_MODE_REQUEST) {
                permissionMode = m
            } else if (m != permissionMode) {
                permissionMode = m
                addNotice(modeLabel(m), NoticeKind.MODE_CHANGE)
            }
            modeConfirmed = true
            version++
        }
    }

    /** Picks the current model's context window out of a result's per-model usage. */
    private fun onModelUsage(usage: JsonObject) {
        val windows = usage.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.long("contextWindow")?.takeIf { it > 0 }?.let { k to it } }
        if (windows.isEmpty()) return
        val base = model?.substringBefore('[')
        contextWindow = windows.firstOrNull { base != null && it.first.substringBefore('[') == base }?.second
            ?: windows.maxOf { it.second }
    }

    /** "claude-opus-4-6[1m]" names its long context before any result reports the window. */
    private fun windowHint(model: String) {
        if (contextWindow == null && "[1m]" in model.lowercase()) contextWindow = 1_000_000L
    }

    private fun onRateLimit(o: JsonObject) {
        val info = o.obj("rate_limit_info") ?: return
        val window = info.str("rateLimitType")
        val w = window?.let { info.obj("unifiedWindows")?.obj(it) }
        val resets = (w?.long("resetsAt") ?: info.long("resetsAt"))?.let { if (it < 100_000_000_000L) it * 1000 else it }
        val all = info.obj("unifiedWindows")?.entries?.mapNotNull { (k, v) ->
            val wo = v as? JsonObject ?: return@mapNotNull null
            val u = wo.double("utilization") ?: return@mapNotNull null
            UsageWindow(windowLabel(k), u, wo.long("resetsAt")?.let { if (it < 100_000_000_000L) it * 1000 else it })
        }.orEmpty().sortedBy { if (it.label == "5-hour") 0 else 1 }
        rateLimit = RateLimitInfo(
            status = info.str("status") ?: "allowed",
            windowLabel = window?.let { windowLabel(it) },
            utilization = w?.double("utilization") ?: info.double("utilization"),
            resetsAt = resets,
            windows = all.ifEmpty { rateLimit?.windows.orEmpty() },
        )
        version++
    }

    // ═══════════════════════════════════════ permission helpers ═══════════════════════════════════════

    private fun applyDecision(rid: String, behavior: String): Boolean {
        val state = when (behavior) {
            "allow" -> PermissionState.ALLOWED
            "deny" -> PermissionState.DENIED
            else -> return false
        }
        val prev = decisions[rid]
        if (prev == PermissionState.CANCELLED) return false
        decisions[rid] = state
        return setPermissionState(rid, state) || prev != state
    }

    private fun setPermissionState(rid: String, state: PermissionState): Boolean {
        var changed = false
        permissionKeys[rid]?.let { key ->
            val loc = locate(key)
            val p = loc?.second?.item as? ChatItem.Permission
            if (loc != null && p != null && p.state != state) {
                loc.second.item = p.copy(state = state)
                touch(loc.first)
                changed = true
            }
        }
        permissionTool[rid]?.let { toolId ->
            val loc = locate(toolKey(toolId)) ?: return@let
            val cur = loc.second.materialize() as? ChatItem.ToolCall ?: return@let
            val next = when (state) {
                PermissionState.ALLOWED -> if (cur.status == ToolStatus.AWAITING_PERMISSION) ToolStatus.RUNNING else cur.status
                PermissionState.DENIED -> if (cur.result == null || cur.status == ToolStatus.ERROR) ToolStatus.DENIED else cur.status
                PermissionState.CANCELLED -> if (cur.result == null) ToolStatus.INTERRUPTED else cur.status
                PermissionState.PENDING -> cur.status
            }
            if (next != cur.status) {
                loc.second.item = cur.copy(status = next)
                touch(loc.first)
                changed = true
            }
        }
        return changed
    }

    private fun toolStatusFor(state: PermissionState) = when (state) {
        PermissionState.PENDING -> ToolStatus.AWAITING_PERMISSION
        PermissionState.ALLOWED -> ToolStatus.RUNNING
        PermissionState.DENIED -> ToolStatus.DENIED
        PermissionState.CANCELLED -> ToolStatus.INTERRUPTED
    }

    private fun suggestionLabel(s: JsonObject): String {
        val dest = s.str("destination")
        val scope = when (dest) {
            "session" -> " this session"
            "localSettings" -> " in this project"
            "projectSettings" -> " for the project"
            "userSettings" -> " everywhere"
            else -> ""
        }
        return when (s.str("type")) {
            "addRules", "replaceRules" -> {
                val verb = when (s.str("behavior")) {
                    "deny" -> "Always deny"
                    "ask" -> "Always ask for"
                    else -> "Always allow"
                }
                val rules = s.arr("rules")?.mapNotNull { r ->
                    val ro = r as? JsonObject ?: return@mapNotNull null
                    val tool = ro.str("toolName") ?: return@mapNotNull null
                    val content = ro.str("ruleContent")
                    if (content.isNullOrBlank()) tool else "$tool($content)"
                }.orEmpty()
                if (rules.isEmpty()) "$verb$scope" else "$verb ${rules.joinToString(", ")}$scope"
            }
            "addDirectories" -> {
                val dirs = s.arr("directories")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
                val names = dirs.joinToString(", ") { projectNameOf(it) }
                if (dest == "session") "Allow access to $names this session" else "Always allow access to $names"
            }
            "setMode" -> when (s.str("mode")) {
                "acceptEdits" -> "Allow all edits$scope"
                "bypassPermissions" -> "Allow everything$scope"
                "plan" -> "Switch to plan mode"
                else -> "Switch to ${modeName(s.str("mode"))} mode"
            }
            else -> s.str("type") ?: "Remember this choice"
        }
    }

    // ═══════════════════════════════════════ storage helpers ═══════════════════════════════════════

    private fun toolKey(id: String) = "tool:$id"

    private fun containerFor(parent: String?): Container {
        if (parent == null) return root
        children[parent]?.let { return it }
        val c = Container(parent)
        children[parent] = c
        // A subagent line can arrive before its Task row (it never should, but never lose it).
        if (!home.containsKey(toolKey(parent))) {
            append(
                root,
                ChatItem.ToolCall(key = toolKey(parent), toolUseId = parent, name = "Task", inputJson = "{}", status = ToolStatus.RUNNING, startedAt = clock()),
            )
        }
        touchOwner(parent)
        return c
    }

    private fun append(container: Container, item: ChatItem): Slot {
        val slot = Slot(item)
        container.index[item.key] = container.slots.size
        container.slots.add(slot)
        home[item.key] = container
        touch(container)
        return slot
    }

    private fun remove(key: String) {
        val c = home.remove(key) ?: return
        val i = c.index[key] ?: return
        c.slots.removeAt(i)
        c.reindex()
        touch(c)
    }

    private fun find(key: String): Slot? {
        val c = home[key] ?: return null
        val i = c.index[key] ?: return null
        return c.slots[i]
    }

    private fun locate(key: String): Pair<Container, Slot>? {
        val c = home[key] ?: return null
        val i = c.index[key] ?: return null
        return c to c.slots[i]
    }

    /** Replaces (or appends) the final form of an item and drops its streaming buffer. */
    private fun finalize(container: Container, key: String, item: ChatItem) {
        val loc = locate(key)
        if (loc != null) {
            loc.second.item = item
            loc.second.buffer = null
            loc.second.dirty = false
            touch(loc.first)
        } else {
            append(container, item)
        }
        finalizedDrafts.add(key)
        openDrafts.remove(key)
    }

    /** The oldest not-yet-finalised streaming draft of [type] in [msgId], claimed for its final block. */
    private fun claimDraft(msgId: String, type: Class<out ChatItem>): String? {
        val d = drafts[msgId] ?: return null
        for ((_, key) in d) {
            if (key in finalizedDrafts) continue
            val item = find(key)?.item ?: continue
            if (type.isInstance(item)) return key
        }
        return null
    }

    private fun touch(container: Container) {
        container.cache = null
        container.owner?.let { touchOwner(it) }
    }

    private fun touchOwner(toolUseId: String) {
        home[toolKey(toolUseId)]?.let { touch(it) }
    }

    private fun materialize(c: Container): List<ChatItem> {
        c.cache?.let { return it }
        val out = ArrayList<ChatItem>(c.slots.size)
        for (slot in c.slots) {
            var item = slot.materialize()
            if (item is ChatItem.ToolCall) {
                val kids = children[item.toolUseId]
                if (kids != null) {
                    val list = materialize(kids)
                    if (item.children !== list) item = item.copy(children = list)
                }
            }
            out.add(item)
        }
        c.cache = out
        return out
    }

    private fun addNotice(text: String, kind: NoticeKind) {
        val key = if (kind == NoticeKind.SESSION_START) "notice:session" else "notice:${noticeSeq++}"
        if (home.containsKey(key)) return
        append(root, ChatItem.Notice(key = key, text = text, kind = kind))
    }

    private fun lastIsInterruptNotice(): Boolean {
        for (i in root.slots.indices.reversed()) {
            val it = root.slots[i].item
            if (it is ChatItem.Notice && it.kind == NoticeKind.INTERRUPTED) return true
            if (it is ChatItem.User || it is ChatItem.TurnSummary) return false
        }
        return false
    }

    private fun takeOptimistic(text: String): String? {
        if (optimistic.isEmpty()) return null
        val norm = text.trim()
        val exact = optimistic.firstOrNull { (find(it)?.item as? ChatItem.User)?.text?.trim() == norm }
        val key = exact ?: optimistic.first()
        optimistic.remove(key)
        return key
    }

    private fun interruptRunningTools() {
        val touched = HashSet<Container>()
        for (c in listOf(root) + children.values) {
            for (slot in c.slots) {
                val t = slot.item as? ChatItem.ToolCall ?: continue
                if (t.status == ToolStatus.RUNNING || t.status == ToolStatus.STREAMING_INPUT || t.status == ToolStatus.AWAITING_PERMISSION) {
                    slot.materialize()
                    slot.item = (slot.item as ChatItem.ToolCall).copy(status = ToolStatus.INTERRUPTED, finishedAt = clock())
                    slot.buffer = null
                    touched.add(c)
                }
            }
        }
        touched.forEach { touch(it) }
    }

    private fun markWorking(ts: Long?) {
        if (status != RunStatus.WORKING) {
            status = RunStatus.WORKING
        }
        if (workingSince == null) workingSince = ts ?: clock()
    }

    // ═══════════════════════════════════════ pure helpers ═══════════════════════════════════════

    private fun usageTokens(u: JsonObject): Long? {
        val a = u.long("input_tokens") ?: return null
        return a + (u.long("cache_creation_input_tokens") ?: 0) + (u.long("cache_read_input_tokens") ?: 0)
    }

    private fun capResult(t: String): String =
        if (t.length <= RESULT_TEXT_CAP) t else t.take(RESULT_TEXT_CAP) + "\n… (${t.length - RESULT_TEXT_CAP} more characters)"

    private fun resultText(content: JsonElement?): String = when (content) {
        null, JsonNull -> ""
        is JsonPrimitive -> if (content.isString) content.content else content.toString()
        is JsonArray -> content.mapNotNull { e ->
            val b = e as? JsonObject ?: return@mapNotNull null
            when (b.str("type")) {
                "text" -> b.str("text")
                "tool_reference" -> b.str("tool_name")?.let { "Loaded $it" }
                "image" -> "[image]"
                else -> null
            }
        }.joinToString("\n")
        is JsonObject -> content.str("text") ?: content.toString()
    }

    private fun todoStatus(s: String?) = when (s) {
        "in_progress" -> TodoStatus.IN_PROGRESS
        "completed" -> TodoStatus.COMPLETED
        else -> TodoStatus.PENDING
    }

    private fun modeName(mode: String?): String = PermissionMode.fromCli(mode)?.label ?: when (mode) {
        "manual" -> "Ask"
        "dontAsk" -> "Don't ask"
        null -> "Default"
        else -> mode
    }

    private fun modeLabel(mode: String): String = "Mode · ${modeName(mode)}"

    private fun windowLabel(w: String) = when (w) {
        "five_hour" -> "5-hour"
        "seven_day" -> "7-day"
        "seven_day_opus" -> "7-day Opus"
        "seven_day_sonnet" -> "7-day Sonnet"
        else -> w.replace('_', ' ')
    }

    private fun humanizeSubtype(s: String) = when (s) {
        "error_max_turns" -> "Stopped: reached the maximum number of turns"
        "error_during_execution" -> "The turn ended with an error"
        "error_max_budget_usd" -> "Stopped: budget limit reached"
        else -> s.removePrefix("error_").replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun stripAnsi(s: String) = s.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")

    private fun isMetaText(t: String): Boolean =
        t.startsWith("<system-reminder>") || t.startsWith("<command-message>") || t.startsWith("<local-command") ||
            t.startsWith("Caveat:") || t.startsWith("<task-notification>") || t.startsWith("<bash-") ||
            t.startsWith("<user-memory-input>")
}

/** request_id the helper uses for its start-up set_permission_mode (see tether_helper.py `start`). */
internal const val INITIAL_MODE_REQUEST = "mode_init"

/** Per-tool-result caps (chars) for what the reducer keeps in memory. */
private const val RESULT_TEXT_CAP = 64 * 1024
private const val STRUCTURED_CAP = 512 * 1024
private const val COMMAND_OUTPUT_CAP = 64 * 1024
private val SLASH_NAME = Regex("^/([\\w:.\\-]+)(?:\\s|$)")
