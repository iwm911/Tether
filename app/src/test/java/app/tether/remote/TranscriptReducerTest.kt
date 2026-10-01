package app.tether.remote

import app.tether.core.ChatItem
import app.tether.core.NoticeKind
import app.tether.core.TodoStatus
import app.tether.core.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptReducerTest {

    private fun fixture(name: String): List<String> {
        val url = javaClass.classLoader!!.getResource("fixtures/$name") ?: error("missing fixture $name")
        return url.readText().lines().filter { it.isNotBlank() }
    }

    private inline fun <reified T : ChatItem> List<ChatItem>.ofType(): List<T> = filterIsInstance<T>()

    // ───────────────────────────── a recorded transcript ─────────────────────────────

    @Test
    fun transcriptYieldsConversationWithoutNoise() {
        val r = TranscriptReducer()
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
        assertTrue(texts.none { it.streaming })
        assertTrue(s.items.all {
            it is ChatItem.User || it is ChatItem.AssistantText || it is ChatItem.Thinking || it is ChatItem.ToolCall
        })
        val write = s.items.ofType<ChatItem.ToolCall>().first { it.name == "Write" }
        assertEquals(ToolStatus.SUCCESS, write.status)
        assertTrue(write.result!!.structuredJson!!.contains("structuredPatch"))
        assertTrue(write.inputJson.contains("notes.md"))
        assertEquals("Loaded TaskCreate", s.items.ofType<ChatItem.ToolCall>().first { it.name == "ToolSearch" }.result!!.text)
        // TaskCreate calls drive the todo list
        assertEquals(2, s.todos.size)
        assertEquals("Review project requirements and gather stakeholder feedback", s.todos[0].content)
        assertTrue(s.todos.all { it.status == TodoStatus.PENDING })
        assertEquals(s.items.size, s.items.map { it.key }.toSet().size)
        assertNull(s.title)
        assertNotNull(s.contextTokens)
        assertEquals("claude-haiku-4-5-20251001", s.model)
    }

    @Test
    fun transcriptLinesProvideCwdAndSessionId() {
        val r = TranscriptReducer(clock = { 1_000L })
        for (l in fixture("transcript_session.jsonl")) r.acceptTranscript(l)
        val snap = r.snapshot()
        assertNotNull(snap.cwd)
        assertTrue(snap.cwd!!.startsWith("/"))
        assertNotNull(snap.sessionId)
    }

    @Test
    fun transcriptTitlePrefersCustomOverAi() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"ai-title","aiTitle":"AI name"}""")
        r.acceptTranscript("""{"type":"custom-title","customTitle":"My name"}""")
        r.acceptTranscript("""{"type":"ai-title","aiTitle":"AI name 2"}""")
        assertEquals("My name", r.snapshot().title)
    }

    @Test
    fun transcriptSkipsSidechainAndDuplicateUuids() {
        val r = TranscriptReducer()
        val line = """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"hi"}]},"uuid":"a1","isSidechain":false}"""
        assertTrue(r.acceptTranscript(line))
        assertFalse(r.acceptTranscript(line))
        assertFalse(r.acceptTranscript("""{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"side"}]},"uuid":"a2","isSidechain":true}"""))
        assertEquals(listOf("hi"), r.snapshot().items.ofType<ChatItem.AssistantText>().map { it.text })
    }

    @Test
    fun garbageLinesAreIgnored() {
        val r = TranscriptReducer()
        assertFalse(r.acceptTranscript(""))
        assertFalse(r.acceptTranscript("not json"))
        assertFalse(r.acceptTranscript("{\"truncated"))
        assertFalse(r.acceptTranscript("[1,2]"))
        assertTrue(r.snapshot().items.isEmpty())
    }

    @Test
    fun helperTruncationBecomesANotice() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"tether-truncated","droppedLines":42}""")
        assertEquals("Earlier history not shown (42 lines)", r.snapshot().items.ofType<ChatItem.Notice>().single().text)
    }

    // ───────────────────────────── tools ─────────────────────────────

    @Test
    fun todoWriteDrivesTodos() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"TodoWrite","input":{"todos":[{"content":"Write tests","activeForm":"Writing tests","status":"in_progress"},{"content":"Ship","activeForm":"Shipping","status":"pending"},{"content":"Plan","activeForm":"Planning","status":"completed"}]}}]}}""")
        val s = r.snapshot()
        assertEquals(3, s.todos.size)
        assertEquals(TodoStatus.IN_PROGRESS, s.todos[0].status)
        assertEquals("Writing tests", s.todos[0].activeForm)
        assertEquals(TodoStatus.COMPLETED, s.todos[2].status)
        // the row is kept (the UI hides TodoWrite rows)
        assertEquals("TodoWrite", s.items.ofType<ChatItem.ToolCall>().single().name)
    }

    @Test
    fun refusedToolIsDenied() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"rm -rf x"}}]},"uuid":"a1"}""")
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"The user doesn't want to proceed with this tool use.","is_error":true,"tool_use_id":"t1"}]},"uuid":"u1"}""")
        assertEquals(ToolStatus.DENIED, r.snapshot().items.ofType<ChatItem.ToolCall>().single().status)
    }

    @Test
    fun interruptMarksRunningToolsAndAddsOneNotice() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"sleep 99"}}]},"uuid":"a1"}""")
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]},"uuid":"x1"}""")
        val s = r.snapshot()
        assertEquals(ToolStatus.INTERRUPTED, s.items.ofType<ChatItem.ToolCall>().single().status)
        assertEquals(1, s.items.ofType<ChatItem.Notice>().count { it.kind == NoticeKind.INTERRUPTED })
        assertTrue(s.items.ofType<ChatItem.User>().isEmpty())
    }

    @Test
    fun oversizeToolResultIsCapped() {
        val r = TranscriptReducer(clock = { 1_000L })
        r.acceptTranscript("""{"type":"assistant","message":{"id":"m1","role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read","input":{"file_path":"/x"}}]}}""")
        val big = "a".repeat(200_000)
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"$big"}]},"toolUseResult":{"originalFile":"$big","filePath":"/x"}}""")
        val tool = r.snapshot().items.filterIsInstance<ChatItem.ToolCall>().single()
        assertTrue(tool.result!!.text.length < 70_000)
        assertFalse(tool.result!!.structuredJson!!.contains("originalFile"))
    }

    @Test
    fun subagentCallsNestUnderTaskToolCall() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"T1","name":"Task","input":{"description":"Explore repo","prompt":"look"}}]},"parent_tool_use_id":null}""")
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"look"}]},"parent_tool_use_id":"T1","uuid":"s0"}""")
        r.acceptTranscript("""{"type":"assistant","message":{"id":"m2","content":[{"type":"tool_use","id":"R1","name":"Read","input":{"file_path":"/x/a.kt"}}]},"parent_tool_use_id":"T1"}""")
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"R1","content":"file body"}]},"parent_tool_use_id":"T1","uuid":"s1"}""")
        var s = r.snapshot()
        val task = s.items.ofType<ChatItem.ToolCall>().single()
        assertEquals("Task", task.name)
        assertEquals(ToolStatus.RUNNING, task.status)
        val child = task.children.single() as ChatItem.ToolCall
        assertEquals("Read", child.name)
        assertEquals(ToolStatus.SUCCESS, child.status)
        assertTrue(s.items.ofType<ChatItem.User>().isEmpty())
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"T1","content":[{"type":"text","text":"Found it"}]}]},"parent_tool_use_id":null,"uuid":"s2"}""")
        s = r.snapshot()
        val done = s.items.ofType<ChatItem.ToolCall>().single()
        assertEquals(ToolStatus.SUCCESS, done.status)
        assertEquals("Found it", done.result!!.text)
        assertEquals(1, done.children.size)
    }

    @Test
    fun largeTranscriptStaysLinear() {
        val r = TranscriptReducer()
        val t0 = System.nanoTime()
        val long = (0 until 40_000).joinToString("") { "line $it\\n" }
        r.acceptTranscript("""{"type":"assistant","message":{"id":"big","content":[{"type":"text","text":"$long"}]},"uuid":"b"}""")
        for (i in 0 until 3_000) {
            r.acceptTranscript("""{"type":"assistant","message":{"id":"t$i","content":[{"type":"tool_use","id":"tool$i","name":"Read","input":{"file_path":"/f$i"}}]},"parent_tool_use_id":null,"uuid":"a$i"}""")
            r.acceptTranscript("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"tool$i","content":"ok"}]},"parent_tool_use_id":null,"uuid":"r$i"}""")
            if (i % 30 == 0) r.snapshot()
        }
        val s = r.snapshot()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(3_001, s.items.size)
        assertTrue((s.items[0] as ChatItem.AssistantText).text.endsWith("line 39999\n"))
        assertTrue("took $ms ms", ms < 5_000)
    }

    // ───────────────────────────── prompts, commands, optimistic sends ─────────────────────────────

    @Test
    fun optimisticUserIsReconciledByTheTranscriptLine() {
        val r = TranscriptReducer()
        val key = r.addOptimisticUser("Reply with just the word ONE.", 0, queued = true)
        var s = r.snapshot()
        assertEquals(1, s.queuedCount)
        assertTrue(s.items.ofType<ChatItem.User>().single().queued)
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Reply with just the word ONE."}]},"uuid":"b9f6","timestamp":"2026-09-25T20:07:01.000Z"}""")
        s = r.snapshot()
        val u = s.items.ofType<ChatItem.User>().single()
        assertEquals(key, u.key)
        assertEquals("b9f6", u.uuid)
        assertFalse(u.queued)
        assertEquals(0, s.queuedCount)
        assertEquals(1790366821000L, u.timestamp)
    }

    @Test
    fun removedOptimisticMessageDisappears() {
        val r = TranscriptReducer()
        val key = r.addOptimisticUser("hello", 0, queued = false)
        r.removeOptimistic(key)
        assertTrue(r.snapshot().items.isEmpty())
    }

    @Test
    fun commandLineReconcilesOptimisticCommand() {
        val r = TranscriptReducer()
        val key = r.addOptimisticUser("/hi banana", 0, queued = true)
        assertFalse(r.snapshot().items.ofType<ChatItem.User>().single().command)
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":"<command-message>hi</command-message>\n<command-name>/hi</command-name>\n<command-args>banana</command-args>"},"uuid":"u1"}""")
        val u = r.snapshot().items.ofType<ChatItem.User>().single()
        assertEquals(key, u.key)
        assertEquals("/hi banana", u.text)
        assertTrue(u.command)
        assertFalse(u.queued)
    }

    @Test
    fun slashCommandRendersAsCommandText() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":"<command-name>/compact</command-name>\n<command-message>compact</command-message>\n<command-args>keep tests</command-args>"},"uuid":"c1","isSidechain":false}""")
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":"<system-reminder>noise</system-reminder>"},"uuid":"c2","isSidechain":false}""")
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":"Caveat: The messages below were generated by the user while running local commands."},"uuid":"c3","isMeta":true}""")
        val users = r.snapshot().items.ofType<ChatItem.User>()
        assertEquals(listOf("/compact keep tests"), users.map { it.text })
        assertTrue(users.single().command)
    }

    @Test
    fun localCommandOutputFoldsIntoCommand() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":"<command-name>/context</command-name>\n<command-message>context</command-message>\n<command-args></command-args>"},"uuid":"c1","isSidechain":false}""")
        r.acceptTranscript("""{"type":"system","subtype":"local_command","content":"<local-command-stdout>## Context Usage\n12k tokens</local-command-stdout>","uuid":"c2","isSidechain":false}""")
        val s = r.snapshot()
        val u = s.items.ofType<ChatItem.User>().single()
        assertTrue(u.command)
        assertEquals("## Context Usage\n12k tokens", u.commandOutput)
        assertTrue(s.items.ofType<ChatItem.Notice>().none { it.kind == NoticeKind.INFO && it.text.contains("Context") })
    }

    @Test
    fun setModelOutputBecomesModelNotice() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"user","message":{"role":"user","content":"<local-command-stdout>Set model to `sonnet (claude-sonnet-5)`</local-command-stdout>"},"uuid":"u9"}""")
        val s = r.snapshot()
        assertEquals("claude-sonnet-5", s.model)
        val n = s.items.ofType<ChatItem.Notice>().single()
        assertEquals(NoticeKind.MODEL_CHANGE, n.kind)
        assertEquals("Model set to sonnet (claude-sonnet-5)", n.text)
        assertTrue(s.items.ofType<ChatItem.User>().isEmpty())
    }

    @Test
    fun onlyRealModeChangesGetANotice() {
        val r = TranscriptReducer()
        r.acceptTranscript("""{"type":"system","subtype":"status","status":null,"permissionMode":"default"}""")
        r.acceptTranscript("""{"type":"system","subtype":"status","status":null,"permissionMode":"default"}""")
        r.acceptTranscript("""{"type":"system","subtype":"status","status":null,"permissionMode":"acceptEdits"}""")
        val s = r.snapshot()
        assertEquals("acceptEdits", s.permissionMode)
        assertEquals(listOf("Mode · Accept edits"), s.items.ofType<ChatItem.Notice>().filter { it.kind == NoticeKind.MODE_CHANGE }.map { it.text })
    }

    // ───────────────────────────── background tasks (task_* system lines) ─────────────────────────────

    @Test
    fun backgroundTasksStayUntilNotified() {
        val r = TranscriptReducer()
        val shell = """{"type":"system","subtype":"task_started","task_id":"b1","tool_use_id":"toolu_1","description":"npm test","task_type":"local_bash","session_id":"s","uuid":"u1"}"""
        val agent = """{"type":"system","subtype":"task_started","task_id":"a1","tool_use_id":"toolu_2","description":"Audit auth module","subagent_type":"Explore","is_backgrounded":true,"task_type":"local_agent","session_id":"s","uuid":"u2"}"""
        val ambient = """{"type":"system","subtype":"task_started","task_id":"m1","description":"monitor","task_type":"monitor_ws","ambient":true,"session_id":"s","uuid":"u3"}"""
        listOf(shell, agent, ambient).forEach { r.acceptTranscript(it) }
        var s = r.snapshot()
        assertEquals(listOf("b1", "a1"), s.backgroundTasks.map { it.id })
        assertFalse(s.backgroundTasks[0].isAgent)
        assertTrue(s.backgroundTasks[1].isAgent)

        r.acceptTranscript("""{"type":"system","subtype":"task_progress","task_id":"a1","description":"Audit auth module","subagent_type":"Explore","usage":{"total_tokens":12345,"tool_uses":7,"duration_ms":9000},"last_tool_name":"Grep","session_id":"s","uuid":"u4"}""")
        s = r.snapshot()
        val a = s.backgroundTasks.single { it.id == "a1" }
        assertEquals("Grep", a.lastToolName)
        assertEquals(12345L, a.totalTokens)
        assertEquals(7, a.toolUses)

        r.acceptTranscript("""{"type":"system","subtype":"task_updated","task_id":"b1","patch":{"status":"completed","end_time":5},"session_id":"s","uuid":"u5"}""")
        assertEquals(listOf("a1"), r.snapshot().backgroundTasks.map { it.id })
        r.acceptTranscript("""{"type":"system","subtype":"task_notification","task_id":"a1","tool_use_id":"toolu_2","status":"completed","output_file":"","summary":"Agent finished","session_id":"s","uuid":"u6"}""")
        assertTrue(r.snapshot().backgroundTasks.isEmpty())
    }
}
