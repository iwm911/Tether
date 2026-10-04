package app.tether.ui.chat

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
}
