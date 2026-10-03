package app.tether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerMessagesTest {

    @Test
    fun readsRecipientAndText() {
        val m = parseSendMessage("{\"to\":\"builder\",\"message\":\"thanks, merging\"}")
        assertEquals("builder", m.to)
        assertEquals("thanks, merging", m.text)
        assertEquals("builder", m.recipient)
    }

    @Test
    fun aNameWinsOverTheAddressAndAnAddressIsShortened() {
        assertEquals("api", parseSendMessage("{\"to\":\"uds:/tmp/c/x.sock\",\"name\":\"api\",\"message\":\"hi\"}").recipient)
        assertEquals("x.sock", parseSendMessage("{\"to\":\"uds:/tmp/c/x.sock\",\"message\":\"hi\"}").recipient)
    }

    @Test
    fun badOrPartialInputNeverThrows() {
        val m = parseSendMessage("{\"to\":\"bui")
        assertNull(m.to)
        assertNull(m.text)
        assertEquals("another session", m.recipient)
        assertNull(parseSendMessage("{\"to\":\"x\"}").text)
    }
}
