package app.tether.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionSummaryTest {

    @Test
    fun sendMessageIsSummarizedAsAMessage() {
        val s = summarizePermission("SendMessage", "{\"to\":\"builder\",\"message\":\"tests are green\"}")
        assertEquals("Message builder", permissionTitle(s))
        assertEquals("builder", s.target)
        assertEquals("tests are green", s.message?.text)
        assertEquals("Message", s.verb)
    }

    @Test
    fun otherToolsCarryNoMessage() {
        assertNull(summarizePermission("Bash", "{\"command\":\"ls\"}").message)
    }
}
