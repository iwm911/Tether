package app.tether.remote

import app.tether.core.BackgroundTask
import app.tether.core.ChatItem
import app.tether.core.ConversationState
import app.tether.core.NoticeKind
import app.tether.core.PermissionMode
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
 * ════════════════════════════ Claude Code transcript → ConversationState ════════════════════════════
 *
 * Pure Kotlin (no Android), single-threaded: callers serialise access (the hub holds a lock).
 * [SessionReducer] feeds it the `line` events of `follow` and overlays the live draft, status,
 * peers, tasks and the session itself.
 *
 * Inputs
 *   acceptTranscript(line)  a line of ~/.claude/projects/…/<session>.jsonl
 *   addOptimisticUser(...)  a message the phone just sent, shown before the transcript has it
 *
 * Mapping (verified against Claude Code 2.1.28x, see test fixtures)
 *   assistant {message:{id, content:[ONE block]}} → one item per block; several lines share an id,
 *                                            one block each, keyed "$messageId#$ordinal".
 *   user tool_result (+ toolUseResult)     → ToolCall.result/status
 *                                            (is_error → ERROR; "doesn't want to proceed" / denied →
 *                                            DENIED; interrupted → INTERRUPTED)
 *   user text                              → ChatItem.User (meta skipped; <command-name> → "/cmd args",
 *                                            command = true; "[Request interrupted…" → Notice INTERRUPTED;
 *                                            <local-command-stdout> → the command's commandOutput, else Notice)
 *   system/local_command                   → the command's commandOutput (like <local-command-stdout>)
 *   parent_tool_use_id ≠ null             → nested under that ToolCall.children (Task / Agent subagents)
 *   TodoWrite input / TaskCreate+TaskUpdate → state.todos. The ToolCall rows are KEPT in items; the UI
 *                                            hides TodoWrite rows (TaskCreate/TaskUpdate rows are shown).
 *   system/init, status, compact_boundary, permission_denied, task_*, api errors → notices / state
 *   custom-title / ai-title / summary      → title
 * Sidechain lines and repeated uuids are skipped. The status is decided by [SessionReducer].
 *
 * Cost: O(1) amortised per line — every item lives in an index map; unchanged subagent child lists
 * are cached.
 */
class TranscriptReducer(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private class Slot(var item: ChatItem)

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
    private val finalOrdinal = HashMap<String, Int>()
    private val optimistic = ArrayList<String>()

    // ── todos ──
    private var todoWrite: List<TodoItem> = emptyList()
    private val tasks = LinkedHashMap<String, TodoItem>()
    private val taskCreates = HashMap<String, TodoItem>()
    private var todosFromTasks = false

    // ── background tasks (task_started … task_notification) ──
    private val bgTasks = LinkedHashMap<String, BackgroundTask>()

    // ── scalar state ──
    private var customTitle: String? = null
    private var aiTitle: String? = null
    private var summaryTitle: String? = null
    private var cwd: String? = null
    private var sessionId: String? = null
    private var model: String? = null
    private var permissionMode: String? = null
    /** False until a line names the mode; the first one is silent, later changes get a notice. */
    private var modeConfirmed = false
    private var contextTokens: Long? = null
    private var sessionNoticeShown = false

    private var noticeSeq = 0
    private var localSeq = 0
    private var userSeq = 0

    /** Increments on every state change; lets callers skip redundant snapshots. */
    var version: Long = 0L
        private set

    // ═══════════════════════════════════════ public API ═══════════════════════════════════════

    /** Feeds one transcript line. Sidechain / attachment / bookkeeping lines are ignored. Returns true when the state changed. */
    fun acceptTranscript(line: String): Boolean {
        val o = RemoteJson.parseObject(line) ?: return false
        val before = version
        val uuid = o.str("uuid")
        val type = o.str("type") ?: return false
        if (uuid != null && (type == "user" || type == "assistant" || type == "attachment")) {
            if (!seenUuids.add(uuid)) return false
        }
        if (o.bool("isSidechain") == true) return false
        // Transcript lines carry the session's cwd / id on every user & assistant line.
        if (type == "user" || type == "assistant") {
            if (cwd == null) o.str("cwd")?.takeIf { it.startsWith("/") }?.let { cwd = it }
            if (sessionId == null) o.str("sessionId")?.let { sessionId = it }
        }
        when (type) {
            "assistant" -> onAssistant(o)
            "user" -> onUser(o)
            "system" -> onSystem(o)
            "custom-title" -> o.str("customTitle")?.takeIf { it.isNotBlank() }?.let { customTitle = it; version++ }
            "ai-title" -> o.str("aiTitle")?.takeIf { it.isNotBlank() }?.let { aiTitle = it; version++ }
            "summary" -> o.str("summary")?.takeIf { it.isNotBlank() }?.let { summaryTitle = it; version++ }
            "tether-truncated" -> {
                val n = o.long("droppedLines") ?: 0
                addNotice("Earlier history not shown ($n lines)", NoticeKind.INFO)
                version++
            }
            "attachment" -> onAttachment(o)
            else -> Unit // queue-operation, atis-latch, last-prompt, file-history-snapshot…
        }
        return version != before
    }

    /** Shows a just-sent message immediately; reconciled when the transcript has it. Returns its key. */
    fun addOptimisticUser(text: String, imageCount: Int, queued: Boolean): String {
        val key = "local:${localSeq++}"
        append(root, ChatItem.User(key = key, text = text, imageCount = imageCount, timestamp = clock(), queued = queued))
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

    fun snapshot(): ConversationState {
        val todos = if (todosFromTasks) tasks.values.toList() else todoWrite
        val waiting = optimistic.filterTo(HashSet()) { (find(it)?.item as? ChatItem.User)?.queued == true }
        val items = materialize(root)
        return ConversationState(
            // Messages still waiting in the queue stay below the turn that is running, as in the terminal.
            items = if (waiting.isEmpty()) items else items.filter { it.key !in waiting } + items.filter { it.key in waiting },
            title = customTitle ?: aiTitle ?: summaryTitle,
            cwd = cwd,
            sessionId = sessionId,
            model = model,
            permissionMode = permissionMode,
            todos = todos,
            contextTokens = contextTokens,
            queuedCount = waiting.size,
            loadingHistory = false,
            backgroundTasks = bgTasks.values.toList(),
        )
    }

    // ═══════════════════════════════════════ assistant ═══════════════════════════════════════

    private fun onAssistant(o: JsonObject) {
        val msg = o.obj("message") ?: return
        val parent = o.str("parent_tool_use_id")
        val msgId = msg.str("id") ?: o.str("uuid") ?: "m${userSeq++}"
        val ts = isoToEpochMs(o.str("timestamp"))
        val lineUuid = o.str("uuid")
        val container = containerFor(parent)
        if (parent == null) {
            msg.obj("usage")?.let { u -> usageTokens(u)?.let { contextTokens = it } }
            msg.str("model")?.takeIf { it.isNotBlank() && !it.startsWith("<") }?.let { model = it }
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
                    val key = "$msgId#$ordinal"
                    if (text.isBlank() && !home.containsKey(key)) continue
                    put(container, ChatItem.AssistantText(key = key, text = text, streaming = false, uuid = lineUuid))
                }
                "thinking", "redacted_thinking" -> {
                    val key = "$msgId#$ordinal"
                    put(container, ChatItem.Thinking(key = key, text = block.str("thinking").orEmpty(), streaming = false))
                }
                "tool_use", "server_tool_use" -> onToolUse(container, block, ts ?: clock())
                else -> Unit
            }
        }
        version++
    }

    private fun onToolUse(container: Container, block: JsonObject, ts: Long) {
        val toolId = block.str("id") ?: return
        val key = toolKey(toolId)
        val name = block.str("name") ?: "Tool"
        val input = block["input"] ?: JsonObject(emptyMap())
        val existingSlot = locate(key)
        val existing = existingSlot?.second?.item as? ChatItem.ToolCall
        val keepStatus = existing?.status?.takeIf {
            it == ToolStatus.SUCCESS || it == ToolStatus.ERROR || it == ToolStatus.DENIED || it == ToolStatus.INTERRUPTED
        }
        val item = ChatItem.ToolCall(
            key = key, toolUseId = toolId, name = name, inputJson = input.toString(),
            status = keepStatus ?: ToolStatus.RUNNING,
            result = existing?.result, startedAt = existing?.startedAt ?: ts, finishedAt = existing?.finishedAt,
        )
        put(existingSlot?.first ?: container, item)
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

    private fun onUser(o: JsonObject) {
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
                return
            }
            val text = blocks.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }
            val images = blocks.count { it.str("type") == "image" }
            onUserText(text, images, o, parent, ts)
        } else if (content is JsonPrimitive && content.isString) {
            onUserText(content.content, 0, o, parent, ts)
        }
    }

    /**
     * A message typed while Claude was working is handed to it mid-turn as a `queued_command` attachment, not
     * a user line: show it there. Task notifications and messages from other sessions ride the same queue.
     */
    private fun onAttachment(o: JsonObject) {
        val a = o.obj("attachment") ?: return
        if (a.str("type") != "queued_command" || a.str("commandMode") != "prompt" || a.bool("isMeta") == true) return
        val origin = a.obj("origin")?.str("kind")
        if (origin != null && origin != "human") return
        val ts = isoToEpochMs(a.str("timestamp")) ?: isoToEpochMs(o.str("timestamp"))
        when (val p = a["prompt"]) {
            is JsonPrimitive -> if (p.isString) onUserText(p.content, 0, o, null, ts)
            is JsonArray -> {
                val blocks = p.mapNotNull { it as? JsonObject }
                val text = blocks.filter { it.str("type") == "text" }.joinToString("\n") { it.str("text").orEmpty() }
                onUserText(text, blocks.count { it.str("type") == "image" }, o, null, ts)
            }
            else -> Unit
        }
    }

    private fun onUserText(raw: String, images: Int, o: JsonObject, parent: String?, ts: Long?) {
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
        val reconciled = takeOptimistic(display)
        val key = reconciled ?: (o.str("uuid")?.let { "u:$it" } ?: "u:#${userSeq++}")
        if (reconciled != null) remove(reconciled)
        append(root, ChatItem.User(key = key, text = display, imageCount = images, timestamp = ts ?: clock(), queued = false,
            uuid = o.str("uuid"), command = isCommand))
        version++
    }

    /**
     * `<local-command-stdout>` (a user line, or a `system`/`local_command` line): a model switch
     * becomes its notice; anything else is folded into the command that printed it.
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
        slot.item = cur.copy(commandOutput = if (output.length > COMMAND_OUTPUT_CAP) output.take(COMMAND_OUTPUT_CAP - 1) + "…" else output)
        touch(root)
        return true
    }

    private fun onToolResult(block: JsonObject, structured: JsonElement?, ts: Long?) {
        val toolId = block.str("tool_use_id") ?: return
        val (container, slot) = locate(toolKey(toolId)) ?: return
        val cur = slot.item as? ChatItem.ToolCall ?: return
        val text = capResult(resultText(block["content"]))
        val isError = block.bool("is_error") == true
        val structuredObj = structured as? JsonObject
        // Drop whole-file copies and oversize payloads so a long session with big reads/edits cannot
        // hold hundreds of MB in memory.
        val structuredJson = when (structured) {
            is JsonObject -> JsonObject(structured - "originalFile").toString().takeIf { it.length <= STRUCTURED_CAP }
            is JsonArray -> structured.toString().takeIf { it.length <= STRUCTURED_CAP }
            else -> null
        }
        val interrupted = structuredObj?.bool("interrupted") == true || text.contains("[Request interrupted by user")
        val denied = !interrupted && (cur.status == ToolStatus.DENIED ||
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

    // ═══════════════════════════════════════ system ═══════════════════════════════════════

    private fun onSystem(o: JsonObject) {
        when (o.str("subtype")) {
            "local_command" -> o.str("content")?.let(::onLocalCommandOutput)
            "init" -> {
                o.str("session_id")?.let { sessionId = it }
                o.str("model")?.takeIf { it.isNotBlank() && !it.startsWith("<") }?.let { model = it }
                o.str("cwd")?.let { cwd = it }
                o.str("permissionMode")?.let { permissionMode = it; modeConfirmed = true }
                if (!sessionNoticeShown) {
                    sessionNoticeShown = true
                    addNotice("Session started", NoticeKind.SESSION_START)
                }
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
                if (o.str("status") == "compacting") {
                    addNotice("Compacting conversation…", NoticeKind.COMPACTED)
                    version++
                }
            }
            "compact_boundary" -> {
                addNotice("Conversation compacted", NoticeKind.COMPACTED)
                version++
            }
            "permission_denied" -> {
                val toolId = o.str("tool_use_id")
                if (toolId != null) {
                    val loc = locate(toolKey(toolId))
                    val cur = loc?.second?.item as? ChatItem.ToolCall
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

    /** Replaces the item with the same key where it lives, or appends it to [container]. */
    private fun put(container: Container, item: ChatItem) {
        val loc = locate(item.key)
        if (loc != null) {
            loc.second.item = item
            touch(loc.first)
        } else {
            append(container, item)
        }
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
            var item = slot.item
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
                    slot.item = t.copy(status = ToolStatus.INTERRUPTED, finishedAt = clock())
                    touched.add(c)
                }
            }
        }
        touched.forEach { touch(it) }
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

    private fun stripAnsi(s: String) = s.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")

    private fun isMetaText(t: String): Boolean =
        t.startsWith("<system-reminder>") || t.startsWith("<command-message>") || t.startsWith("<local-command") ||
            t.startsWith("Caveat:") || t.startsWith("<task-notification>") || t.startsWith("<bash-") ||
            t.startsWith("<user-memory-input>")
}

/** Per-tool-result caps (chars) for what the reducer keeps in memory. */
private const val RESULT_TEXT_CAP = 64 * 1024
private const val STRUCTURED_CAP = 512 * 1024
private const val COMMAND_OUTPUT_CAP = 64 * 1024
