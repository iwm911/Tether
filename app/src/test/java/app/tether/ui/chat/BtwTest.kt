package app.tether.ui.chat

import app.tether.core.SlashCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BtwTest {
    @Test
    fun readsTheQuestionOfABtwDraft() {
        assertEquals("what file was that?", btwQuestion("/btw what file was that?"))
        assertEquals("line one\nline two", btwQuestion("  /btw   line one\nline two  "))
        assertEquals("", btwQuestion("/btw"))
        assertEquals("", btwQuestion("/btw   "))
    }

    @Test
    fun otherDraftsAreNotSideQuestions() {
        assertNull(btwQuestion("/btwx hi"))
        assertNull(btwQuestion("/compact"))
        assertNull(btwQuestion("by the way /btw hi"))
        assertNull(btwQuestion(""))
    }

    @Test
    fun addsBtwToCommandsOnce() {
        val cmds = listOf(SlashCommand("compact", ""))
        assertEquals(listOf("compact", "btw"), withBtw(cmds).map { it.name })
        assertEquals(1, withBtw(listOf(SlashCommand("/btw", ""))).size)
        assertEquals(listOf("btw"), matchSlashCommands("/bt", withBtw(cmds)).map { it.name })
    }
}
