package app.tether.remote

import app.tether.core.ChatItem
import app.tether.ui.chat.turnEnds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BranchPointsTest {
    private fun line(type: String, uuid: String, content: String) =
        """{"type":"$type","uuid":"$uuid","isSidechain":false,"message":{"role":"$type","id":"m-$uuid","content":$content}}"""

    @Test
    fun userMessagesKnowTheirUuidAndForkPoint() {
        val r = StreamReducer()
        r.acceptTranscript(line("user", "u1", "\"first\""))
        r.acceptTranscript(line("assistant", "a1", """[{"type":"text","text":"reply one"}]"""))
        r.acceptTranscript(line("user", "u2", "\"second\""))
        r.acceptTranscript(line("assistant", "a2", """[{"type":"text","text":"reply two"}]"""))
        val items = r.snapshot().items
        val users = items.filterIsInstance<ChatItem.User>()
        assertEquals(listOf("u1", "u2"), users.map { it.uuid })
        assertNull(users[0].forkPointUuid)           // editing the first message = fresh start
        assertEquals("a1", users[1].forkPointUuid)   // editing the second forks after reply one
        val replies = items.filterIsInstance<ChatItem.AssistantText>()
        assertEquals(listOf("a1", "a2"), replies.map { it.uuid })
        // action rows: one per turn, paired with that turn's prompt
        val ends = turnEnds(items)
        assertEquals(2, ends.size)
        assertEquals("u2", ends[replies[1].key]?.uuid)
        assertNotNull(ends[replies[0].key])
    }
}
