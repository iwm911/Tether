package app.tether.ui.chat

import app.tether.core.ChatItem
import app.tether.core.PermissionState
import app.tether.core.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleChatItemsTest {

    private fun tool(id: String) = ChatItem.ToolCall("t:$id", id, "Edit", "{}", ToolStatus.SUCCESS)
    private fun perm(toolUseId: String?, state: PermissionState, req: String = "r1") =
        ChatItem.Permission("p:$req", req, "Edit", toolUseId, "{}", null, null, emptyList(), state)

    @Test
    fun answeredPermissionFoldsIntoItsToolRow() {
        val out = visibleChatItems(listOf(tool("u1"), perm("u1", PermissionState.ALLOWED)), showThinking = true)
        assertEquals(1, out.size)
        assertEquals(PermissionState.ALLOWED, (out[0] as ChatItem.ToolCall).decision)
    }

    @Test
    fun pendingPermissionFoldsTooAndOrphansStay() {
        val out = visibleChatItems(
            listOf(tool("u1"), perm("u1", PermissionState.PENDING, "r1"), perm(null, PermissionState.DENIED, "r2")),
            showThinking = true,
        )
        assertEquals(2, out.size)
        assertEquals(PermissionState.PENDING, (out[0] as ChatItem.ToolCall).decision)
        assertTrue(out[1] is ChatItem.Permission)
    }

    @Test
    fun consecutiveThinkingMergesAndEmptyThinkingIsDropped() {
        val out = visibleChatItems(
            listOf(
                ChatItem.Thinking("th1", "first", estimatedTokens = 10),
                ChatItem.Thinking("th2", "second", estimatedTokens = 5),
                tool("u1"),
                ChatItem.Thinking("th3", ""),
            ),
            showThinking = true,
        )
        assertEquals(2, out.size)
        val t = out[0] as ChatItem.Thinking
        assertEquals("th1", t.key)
        assertEquals("first\n\nsecond", t.text)
        assertEquals(15, t.estimatedTokens)
    }
}
