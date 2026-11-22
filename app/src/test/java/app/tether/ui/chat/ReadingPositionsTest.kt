package app.tether.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingPositionsTest {
    @Test fun roundTrips() {
        val a = ReadingAnchor("msg:abc:1", 312, "tool:xyz", following = false)
        assertEquals(a, ReadingPositions.decode(ReadingPositions.encode(a)))
        val b = a.copy(following = true, offset = 0)
        assertEquals(b, ReadingPositions.decode(ReadingPositions.encode(b)))
    }

    @Test fun rejectsGarbage() {
        assertNull(ReadingPositions.decode(null))
        assertNull(ReadingPositions.decode(""))
        assertNull(ReadingPositions.decode("a\u001fx\u001fb\u001f1"))
        assertNull(ReadingPositions.decode("\u001f1\u001fb\u001f1"))
    }

    @Test fun keysSessionsAndSubagentsApart() {
        assertNotEquals(ReadingPositions.session("c", "s"), ReadingPositions.session("c", "s", "agent"))
    }
}
