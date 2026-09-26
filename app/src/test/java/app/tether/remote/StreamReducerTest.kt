package app.tether.remote

import app.tether.core.ChatItem
import app.tether.core.NoticeKind
import app.tether.core.PermissionState
import app.tether.core.RunStatus
import app.tether.core.TodoStatus
import app.tether.core.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamReducerTest {

    private fun fixture(name: String): List<String> {
        val url = javaClass.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")
        return url.readText().lines().filter { it.isNotBlank() }
    }

    private fun live(lines: List<String>): StreamReducer = StreamReducer(clock = { 1_000L }).also { r -> lines.forEach { r.accept(it) } }

    private inline fun <reified T : ChatItem> List<ChatItem>.ofType(): List<T> = filterIsInstance<T>()

    // ───────────────────────────── live stream: multi-turn with partial messages ─────────────────────────────

    @Test
    fun multiturnYieldsTwoTurnSummariesAndIdle() {
        val s = live(fixture("stream_multiturn_partial_interrupt.jsonl")).snapshot()
        val turns = s.items.ofType<ChatItem.TurnSummary>()
        assertEquals(2, turns.size)
        assertTrue(turns.all { it.success })
        assertEquals(RunStatus.IDLE, s.status)
        assertEquals(0.0286293, s.totalCostUsd, 1e-9)
        assertEquals(0.023629, turns[0].costUsd!!, 1e-6)
        assertEquals(0.0286293 - 0.023629, turns[1].costUsd!!, 1e-6)
        assertEquals("76585878-a387-4dcf-a47f-e0156bd6caf9", s.sessionId)
        assertEquals("/home/user/tether-exp/work", s.cwd)
        assertEquals("claude-haiku-4-5-20251001", s.model)
        assertNull(s.workingSince)
        assertNull(s.thinkingTokens)
    }

    @Test
    fun multiturnFinalBlocksReplaceStreamedDrafts() {
        val s = live(fixture("stream_multiturn_partial_interrupt.jsonl")).snapshot()
        val texts = s.items.ofType<ChatItem.AssistantText>()
        assertEquals(listOf("OK."), texts.filter { it.text == "OK." }.map { it.text })
        val count = texts.single { it.text.startsWith("1\n2\n3") }
        assertTrue(count.text.trimEnd().endsWith("200"))
        assertTrue("no item may still be streaming", s.items.none { (it as? ChatItem.AssistantText)?.streaming == true || (it as? ChatItem.Thinking)?.streaming == true })
        // one row per block: 4 messages → 4 thinking rows, never duplicated by the partial stream
        assertEquals(4, s.items.ofType<ChatItem.Thinking>().size)
        assertEquals(s.items.size, s.items.map { it.key }.toSet().size)
        assertEquals(1, s.items.ofType<ChatItem.Notice>().count { it.kind == NoticeKind.SESSION_START })
    }

    @Test
    fun writeToolCallHasStructuredResult() {
        val s = live(fixture("stream_multiturn_partial_interrupt.jsonl")).snapshot()
        val tools = s.items.ofType<ChatItem.ToolCall>()
        assertEquals(listOf("Write", "ToolSearch", "TaskCreate", "TaskCreate"), tools.map { it.name })
        val write = tools.first { it.name == "Write" }
        assertEquals(ToolStatus.SUCCESS, write.status)
        assertNotNull(write.result)
        assertTrue(write.result!!.text.startsWith("File created successfully"))
        assertTrue(write.result!!.structuredJson!!.contains("\"type\":\"create\""))
        assertTrue(write.inputJson.contains("notes.md"))
        assertEquals("Loaded TaskCreate", tools.first { it.name == "ToolSearch" }.result!!.text)
        assertTrue(tools.all { it.status == ToolStatus.SUCCESS })
    }

    @Test
    fun taskToolsDriveTodos() {
        val s = live(fixture("stream_multiturn_partial_interrupt.jsonl")).snapshot()
        assertEquals(2, s.todos.size)
        assertEquals("Review project requirements and gather stakeholder feedback", s.todos[0].content)
        assertTrue(s.todos.all { it.status == TodoStatus.PENDING })
    }

    @Test
    fun todoWriteDrivesTodos() {
        val r = StreamReducer()
        r.accept("""{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"TodoWrite","input":{"todos":[{"content":"Write tests","activeForm":"Writing tests","status":"in_progress"},{"content":"Ship","activeForm":"Shipping","status":"pending"},{"content":"Plan","activeForm":"Planning","status":"completed"}]}}]}}""")
        val s = r.snapshot()
        assertEquals(3, s.todos.size)
        assertEquals(TodoStatus.IN_PROGRESS, s.todos[0].status)
        assertEquals("Writing tests", s.todos[0].activeForm)
        assertEquals(TodoStatus.COMPLETED, s.todos[2].status)
        // the row is kept (the UI hides TodoWrite rows)
        assertEquals("TodoWrite", s.items.ofType<ChatItem.ToolCall>().single().name)
    }

    @Test
    fun initResponseProvidesCommandsModelsAndRateLimit() {
        val s = live(fixture("stream_multiturn_partial_interrupt.jsonl")).snapshot()
        // The public fixture keeps only Claude Code's built-in commands (account-specific ones scrubbed).
        assertTrue(s.commands.size >= 5)
        assertTrue(s.commands.any { it.name == "compact" })
        assertTrue(s.models.any { it.value == "opus" })
        assertEquals("5-hour", s.rateLimit!!.windowLabel)
        assertEquals(0.46, s.rateLimit!!.utilization!!, 1e-9)
        assertEquals(1790365200L * 1000, s.rateLimit!!.resetsAt)
        assertNotNull(s.contextTokens)
    }

    @Test
    fun partialStreamShowsStreamingTextWhileWorking() {
        val lines = fixture("stream_multiturn_partial_interrupt.jsonl")
        val r = live(lines.take(150)) // mid-way through the "count to 200" text deltas
        val s = r.snapshot()
        assertEquals(RunStatus.WORKING, s.status)
        val last = s.items.last()
        assertTrue(last is ChatItem.AssistantText)
        last as ChatItem.AssistantText
        assertTrue(last.streaming)
        assertTrue(last.text.startsWith("1\n"))
        // finishing the stream swaps the draft for the final block under the same key
        lines.drop(150).forEach { r.accept(it) }
        val done = r.snapshot().items.first { it.key == last.key } as ChatItem.AssistantText
        assertFalse(done.streaming)
        assertTrue(done.text.trimEnd().endsWith("200"))
    }

    @Test
    fun thinkingTokensAndWorkingSinceWhileThinking() {
        val lines = fixture("stream_multiturn_partial_interrupt.jsonl")
        val s = live(lines.take(10)).snapshot()
        assertEquals(RunStatus.WORKING, s.status)
        assertEquals(250, s.thinkingTokens)
        assertNotNull(s.workingSince)
        assertTrue(s.items.last() is ChatItem.Thinking)
    }

    // ───────────────────────────── permissions ─────────────────────────────

    @Test
    fun permissionPendingThenAllowedViaApplyInput() {
        val lines = fixture("stream_permission_request.jsonl")
        val r = live(lines.take(1))
        var s = r.snapshot()
        val perm = s.items.ofType<ChatItem.Permission>().single()
        assertEquals(PermissionState.PENDING, perm.state)
        assertEquals("Bash", perm.toolName)
        assertEquals("/home/user/tether-exp/work/f.txt", perm.blockedPath)
        assertEquals(2, perm.suggestions.size)
        assertEquals("Always allow Bash(echo hello-tether *) in this project", perm.suggestions[0].label)
        assertEquals("Allow access to work this session", perm.suggestions[1].label)
        assertTrue(perm.suggestions[0].rawJson.contains("\"addRules\""))
        assertEquals(RunStatus.AWAITING_PERMISSION, s.status)
        assertEquals(1, s.pendingPermissions.size)
        assertEquals(ToolStatus.AWAITING_PERMISSION, s.items.ofType<ChatItem.ToolCall>().single().status)
        assertTrue(r.permissionInput(perm.requestId)!!.contains("echo hello-tether"))

        r.applyInput(listOf("""{"type":"control_response","response":{"subtype":"success","request_id":"${perm.requestId}","response":{"behavior":"allow","updatedInput":{}}}}"""))
        s = r.snapshot()
        assertEquals(PermissionState.ALLOWED, s.items.ofType<ChatItem.Permission>().single().state)
        assertTrue(s.pendingPermissions.isEmpty())
        assertEquals(RunStatus.WORKING, s.status)
        assertEquals(ToolStatus.RUNNING, s.items.ofType<ChatItem.ToolCall>().single().status)

        r.accept(lines[1])
        val tool = r.snapshot().items.ofType<ChatItem.ToolCall>().single()
        assertEquals(ToolStatus.SUCCESS, tool.status)
        assertEquals("hello-tether", tool.result!!.text)
    }

    @Test
    fun decisionRecordedBeforeRequestIsHonoured() {
        // On attach, in.jsonl (with our answer) is applied before out.jsonl is tailed.
        val lines = fixture("stream_permission_request.jsonl")
        val r = StreamReducer()
        r.applyInput(listOf("""{"type":"control_response","response":{"subtype":"success","request_id":"b211a7dd-0a58-4a75-923b-dbc2859a92b1","response":{"behavior":"allow","updatedInput":{}}}}"""), historical = true)
        r.accept(lines[0])
        val s = r.snapshot()
        assertEquals(PermissionState.ALLOWED, s.items.ofType<ChatItem.Permission>().single().state)
        assertTrue(s.pendingPermissions.isEmpty())
    }

    @Test
    fun deniedPermissionMarksToolDenied() {
        val lines = fixture("stream_permission_request.jsonl")
        val r = live(lines.take(1))
        r.applyInput(listOf("""{"type":"control_response","response":{"subtype":"success","request_id":"b211a7dd-0a58-4a75-923b-dbc2859a92b1","response":{"behavior":"deny","message":"No thanks","interrupt":false}}}"""))
        r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"No thanks","is_error":true,"tool_use_id":"toolu_01586xDoMXjCDntKUCZCNnxU"}]},"parent_tool_use_id":null}""")
        val s = r.snapshot()
        assertEquals(PermissionState.DENIED, s.items.ofType<ChatItem.Permission>().single().state)
        assertEquals(ToolStatus.DENIED, s.items.ofType<ChatItem.ToolCall>().single().status)
    }

    @Test
    fun cancelledPermissionAndInterrupt() {
        val lines = fixture("stream_permission_request.jsonl")
        val r = live(lines.take(1))
        r.accept("""{"type":"control_cancel_request","request_id":"b211a7dd-0a58-4a75-923b-dbc2859a92b1"}""")
        r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"The user doesn't want to proceed with this tool use.","is_error":true,"tool_use_id":"toolu_01586xDoMXjCDntKUCZCNnxU"}]},"parent_tool_use_id":null}""")
        r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]},"parent_tool_use_id":null,"uuid":"x1"}""")
        r.accept("""{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":2,"terminal_reason":"aborted_streaming","total_cost_usd":0.01,"errors":["[ede_diagnostic] junk"]}""")
        val s = r.snapshot()
        assertEquals(PermissionState.CANCELLED, s.items.ofType<ChatItem.Permission>().single().state)
        assertEquals(ToolStatus.INTERRUPTED, s.items.ofType<ChatItem.ToolCall>().single().status)
        assertEquals(1, s.items.ofType<ChatItem.Notice>().count { it.kind == NoticeKind.INTERRUPTED })
        assertTrue(s.items.ofType<ChatItem.TurnSummary>().isEmpty())
        assertTrue(s.items.ofType<ChatItem.User>().isEmpty())
        assertEquals(RunStatus.IDLE, s.status)
    }

    // ───────────────────────────── init + mode changes ─────────────────────────────

    @Test
    fun initResponseAndModeChange() {
        val s = live(fixture("stream_init_setmode.jsonl")).snapshot()
        assertTrue(s.commands.isNotEmpty())
        assertEquals("plan", s.permissionMode)
        assertEquals(RunStatus.IDLE, s.status)
        val notices = s.items.ofType<ChatItem.Notice>().filter { it.kind == NoticeKind.MODE_CHANGE }
        assertEquals(listOf("Mode · Plan"), notices.map { it.text })
    }

    @Test
    fun optimisticModeChangeDoesNotDuplicateNotice() {
        val r = live(fixture("stream_init_setmode.jsonl").take(1))
        r.accept("""{"type":"system","subtype":"status","status":null,"permissionMode":"default"}""")
        r.applyInput(listOf("""{"type":"control_request","request_id":"m9","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}"""))
        r.accept("""{"type":"control_response","response":{"subtype":"success","request_id":"m9","response":{"mode":"acceptEdits"}}}""")
        r.accept("""{"type":"system","subtype":"status","status":null,"permissionMode":"acceptEdits"}""")
        val modes = r.snapshot().items.ofType<ChatItem.Notice>().filter { it.kind == NoticeKind.MODE_CHANGE }
        assertEquals(listOf("Mode · Accept edits"), modes.map { it.text })
    }

    @Test
    fun setModelEchoBecomesModelNotice() {
        val r = StreamReducer()
        r.applyInput(listOf("""{"type":"control_request","request_id":"s1","request":{"subtype":"set_model","model":"sonnet"}}"""))
        r.accept("""{"type":"user","message":{"role":"user","content":"<local-command-stdout>Set model to `sonnet (claude-sonnet-5)`</local-command-stdout>"},"parent_tool_use_id":null,"uuid":"u9"}""")
        r.accept("""{"type":"control_response","response":{"subtype":"success","request_id":"s1"}}""")
        val s = r.snapshot()
        assertEquals("claude-sonnet-5", s.model)
        val n = s.items.ofType<ChatItem.Notice>().single()
        assertEquals(NoticeKind.MODEL_CHANGE, n.kind)
        assertEquals("Model set to sonnet (claude-sonnet-5)", n.text)
        assertTrue(s.items.ofType<ChatItem.User>().isEmpty())
    }

    // ───────────────────────────── user echoes / optimistic sends ─────────────────────────────

    @Test
    fun optimisticUserIsReconciledByReplayEcho() {
        val r = live(fixture("stream_init_setmode.jsonl").take(1))
        val key = r.addOptimisticUser("Reply with just the word ONE.", 0, queued = true)
        var s = r.snapshot()
        assertEquals(1, s.queuedCount)
        assertTrue(s.items.ofType<ChatItem.User>().single().queued)
        r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Reply with just the word ONE."}]},"parent_tool_use_id":null,"uuid":"b9f6","timestamp":"2026-09-25T20:07:01.000Z","isReplay":true}""")
        s = r.snapshot()
        val u = s.items.ofType<ChatItem.User>().single()
        assertEquals(key, u.key)
        assertFalse(u.queued)
        assertEquals(0, s.queuedCount)
        assertEquals(RunStatus.WORKING, s.status)
        assertEquals(1790366821000L, s.workingSince)
    }

    @Test
    fun slashCommandRendersAsCommandText() {
        val r = StreamReducer()
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":"<command-name>/compact</command-name>\n<command-message>compact</command-message>\n<command-args>keep tests</command-args>"},"uuid":"c1","isSidechain":false}""")
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":"<system-reminder>noise</system-reminder>"},"uuid":"c2","isSidechain":false}""")
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":"Caveat: The messages below were generated by the user while running local commands."},"uuid":"c3","isMeta":true}""")
        val users = r.snapshot().items.ofType<ChatItem.User>()
        assertEquals(listOf("/compact keep tests"), users.map { it.text })
    }

    // ───────────────────────────── subagents ─────────────────────────────

    @Test
    fun subagentCallsNestUnderTaskToolCall() {
        val r = StreamReducer()
        r.accept("""{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"T1","name":"Task","input":{"description":"Explore repo","prompt":"look"}}]},"parent_tool_use_id":null}""")
        r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"look"}]},"parent_tool_use_id":"T1","uuid":"s0"}""")
        r.accept("""{"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"R1","name":"Read","input":{"file_path":"/x/a.kt"}}]},"parent_tool_use_id":"T1"}""")
        r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"R1","content":"file body"}]},"parent_tool_use_id":"T1","uuid":"s1"}""")
        var s = r.snapshot()
        val task = s.items.ofType<ChatItem.ToolCall>().single()
        assertEquals("Task", task.name)
        assertEquals(ToolStatus.RUNNING, task.status)
        val child = task.children.single() as ChatItem.ToolCall
        assertEquals("Read", child.name)
        assertEquals(ToolStatus.SUCCESS, child.status)
        assertTrue(s.items.ofType<ChatItem.User>().isEmpty())
        r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"T1","content":[{"type":"text","text":"Found it"}]}]},"parent_tool_use_id":null,"uuid":"s2"}""")
        s = r.snapshot()
        val done = s.items.ofType<ChatItem.ToolCall>().single()
        assertEquals(ToolStatus.SUCCESS, done.status)
        assertEquals("Found it", done.result!!.text)
        assertEquals(1, done.children.size)
    }

    // ───────────────────────────── transcripts ─────────────────────────────

    @Test
    fun transcriptYieldsConversationWithoutNoise() {
        val r = StreamReducer()
        fixture("transcript_session.jsonl").forEach { r.acceptTranscript(it) }
        val s = r.snapshot()
        val users = s.items.ofType<ChatItem.User>()
        assertEquals(2, users.size)
        assertTrue(users[0].text.startsWith("Write a file notes.md"))
        assertEquals("Count slowly from 1 to 200, one number per line.", users[1].text)
        assertNotNull(users[0].timestamp)
        val texts = s.items.ofType<ChatItem.AssistantText>()
        assertTrue(texts.any { it.text == "OK." })
        assertTrue(texts.any { it.text.startsWith("1\n2\n3") })
        assertTrue(s.items.all {
            it is ChatItem.User || it is ChatItem.AssistantText || it is ChatItem.Thinking || it is ChatItem.ToolCall
        })
        val write = s.items.ofType<ChatItem.ToolCall>().first { it.name == "Write" }
        assertEquals(ToolStatus.SUCCESS, write.status)
        assertTrue(write.result!!.structuredJson!!.contains("structuredPatch"))
        assertEquals(2, s.todos.size)
        assertEquals("/home/user/tether-exp/work", r.snapshot().cwd ?: "/home/user/tether-exp/work")
        assertEquals(s.items.size, s.items.map { it.key }.toSet().size)
        assertNull(s.title)
    }

    @Test
    fun transcriptTitlePrefersCustomOverAi() {
        val r = StreamReducer()
        r.acceptTranscript("""{"type":"ai-title","aiTitle":"AI name"}""")
        r.acceptTranscript("""{"type":"custom-title","customTitle":"My name"}""")
        r.acceptTranscript("""{"type":"ai-title","aiTitle":"AI name 2"}""")
        assertEquals("My name", r.snapshot().title)
    }

    @Test
    fun transcriptSkipsSidechainAndDuplicateUuids() {
        val r = StreamReducer()
        val line = """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"hi"}]},"uuid":"a1","isSidechain":false}"""
        r.acceptTranscript(line)
        r.acceptTranscript(line)
        r.acceptTranscript("""{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"side"}]},"uuid":"a2","isSidechain":true}""")
        assertEquals(listOf("hi"), r.snapshot().items.ofType<ChatItem.AssistantText>().map { it.text })
    }

    @Test
    fun historyThenLiveKeepsOneTimeline() {
        val r = StreamReducer()
        fixture("transcript_session.jsonl").forEach { r.acceptTranscript(it) }
        val before = r.snapshot().items.size
        r.addHistoryBoundary()
        fixture("stream_permission_request.jsonl").take(1).forEach { r.accept(it) }
        val s = r.snapshot()
        assertEquals(before + 3, s.items.size) // boundary notice + placeholder tool + permission
        assertEquals(RunStatus.AWAITING_PERMISSION, s.status)
    }

    @Test
    fun liveUserTextWithoutReplayFlagIsNotAPrompt() {
        val r = StreamReducer()
        r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"synthetic nudge"}]},"parent_tool_use_id":null,"uuid":"z1"}""")
        assertTrue(r.snapshot().items.isEmpty())
    }

    @Test
    fun largeStreamStaysLinear() {
        val r = StreamReducer()
        val t0 = System.nanoTime()
        r.accept("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"big","usage":{"input_tokens":1}}},"parent_tool_use_id":null}""")
        r.accept("""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}},"parent_tool_use_id":null}""")
        for (i in 0 until 40_000) {
            r.accept("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"line $i\n"}},"parent_tool_use_id":null}""")
            if (i % 100 == 0) r.snapshot()
        }
        for (i in 0 until 3_000) {
            r.accept("""{"type":"assistant","message":{"id":"t$i","content":[{"type":"tool_use","id":"tool$i","name":"Read","input":{"file_path":"/f$i"}}]},"parent_tool_use_id":null,"uuid":"a$i"}""")
            r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool$i","content":"ok"}]},"parent_tool_use_id":null,"uuid":"r$i"}""")
            if (i % 30 == 0) r.snapshot()
        }
        val s = r.snapshot()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(3_001, s.items.size)
        assertTrue((s.items[0] as ChatItem.AssistantText).text.endsWith("line 39999\n"))
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test
    fun garbageLinesAreIgnored() {
        val r = StreamReducer()
        assertFalse(r.accept(""))
        assertFalse(r.accept("not json"))
        assertFalse(r.accept("{\"truncated"))
        assertFalse(r.accept("[1,2]"))
        assertTrue(r.snapshot().items.isEmpty())
    }

    @Test
    fun transcriptLinesProvideCwdAndSessionId() {
        val r = StreamReducer(clock = { 1_000L })
        for (l in fixture("transcript_session.jsonl")) r.acceptTranscript(l)
        val snap = r.snapshot()
        assertNotNull(snap.cwd)
        assertTrue(snap.cwd!!.startsWith("/"))
        assertNotNull(snap.sessionId)
    }

    @Test
    fun oversizeToolResultIsCapped() {
        val r = StreamReducer(clock = { 1_000L })
        r.accept("""{"type":"assistant","message":{"id":"m1","role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/x"}}]}}""")
        val big = "a".repeat(200_000)
        r.accept("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"$big"}]},"tool_use_result":{"originalFile":"$big","filePath":"/x"}}""")
        val tool = r.snapshot().items.filterIsInstance<ChatItem.ToolCall>().single()
        assertTrue(tool.result!!.text.length < 70_000)
        assertFalse(tool.result!!.structuredJson!!.contains("originalFile"))
    }

    // ───────────────────────────── background tasks (task_* system events) ─────────────────────────────
    // Shapes from CLI 2.1.283's SDK emitters (task_started / task_progress / task_updated / task_notification).

    @Test
    fun backgroundTasksOutliveTheTurnUntilNotified() {
        val r = live(fixture("stream_multiturn_partial_interrupt.jsonl"))
        val shell = """{"type":"system","subtype":"task_started","task_id":"b1","tool_use_id":"toolu_1","description":"npm test","task_type":"local_bash","session_id":"s","uuid":"u1"}"""
        val agent = """{"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_2","description":"Audit auth module","subagent_type":"Explore","is_backgrounded":true,"task_type":"local_agent","session_id":"s","uuid":"u2"}"""
        val ambient = """{"type":"system","subtype":"task_started","task_id":"m1","description":"monitor","task_type":"monitor_ws","ambient":true,"session_id":"s","uuid":"u3"}"""
        listOf(shell, agent, ambient).forEach { r.accept(it) }
        var s = r.snapshot()
        assertEquals(RunStatus.IDLE, s.status)
        assertEquals(listOf("b1", "a1"), s.backgroundTasks.map { it.id })
        assertFalse(s.backgroundTasks[0].isAgent)
        assertTrue(s.backgroundTasks[1].isAgent)

        r.accept("""{"type":"system","subtype":"task_progress","task_id":"a1","description":"Audit auth module","subagent_type":"Explore","usage":{"total_tokens":12345,"tool_uses":7,"duration_ms":9000},"last_tool_name":"Grep","session_id":"s","uuid":"u4"}""")
        s = r.snapshot()
        val a = s.backgroundTasks.single { it.id == "a1" }
        assertEquals("Grep", a.lastToolName)
        assertEquals(12345L, a.totalTokens)
        assertEquals(7, a.toolUses)
        assertEquals(RunStatus.IDLE, s.status)

        r.accept("""{"type":"system","subtype":"task_updated","task_id":"b1","patch":{"status":"completed","end_time":5},"session_id":"s","uuid":"u5"}""")
        assertEquals(listOf("a1"), r.snapshot().backgroundTasks.map { it.id })
        r.accept("""{"type":"system","subtype":"task_notification","task_id":"a1","tool_use_id":"toolu_2","status":"completed","output_file":"","summary":"Agent finished","session_id":"s","uuid":"u6"}""")
        assertTrue(r.snapshot().backgroundTasks.isEmpty())
    }
}
