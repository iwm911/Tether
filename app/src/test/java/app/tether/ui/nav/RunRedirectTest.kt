package app.tether.ui.nav

import app.tether.core.AgentSummary
import app.tether.core.AuthMethod
import app.tether.core.Connection
import app.tether.core.Holder
import app.tether.core.RunInfo
import app.tether.core.RunKind
import app.tether.core.RunRef
import app.tether.core.RunStatus
import app.tether.core.Session
import app.tether.core.SessionRef
import app.tether.core.nativeRunRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Old run-model refs (Home rows, old notifications, new-agent screen) open the session screen by session id. */
class RunRedirectTest {

    private val conn = Connection(id = "c1", name = "box", host = "h", username = "u", auth = AuthMethod.Password, createdAt = 0)
    private val sid = "7b9f8c4c-6ac7-4d5a-9111-003abd24390e"

    private fun run(id: String, sessionId: String? = null, nativeId: String? = null) = AgentSummary(
        ref = RunRef("c1", id),
        connection = conn,
        run = RunInfo(
            runId = id, cwd = "/p", sessionId = sessionId, startedAt = 0, updatedAt = 0, alive = true, status = RunStatus.IDLE,
            kind = if (nativeId != null) RunKind.NATIVE else RunKind.TETHER, nativeId = nativeId,
        ),
    )

    @Test
    fun aRunWithASessionIdOpensThatSession() {
        assertEquals(SessionRef("c1", sid), sessionRefForRun(RunRef("c1", "r-1"), listOf(run("r-1", sessionId = sid)), emptyList()))
    }

    @Test
    fun aRunWithoutOneWaits() {
        assertNull(sessionRefForRun(RunRef("c1", "r-1"), listOf(run("r-1")), emptyList()))
        assertNull(sessionRefForRun(RunRef("c1", "r-2"), emptyList(), emptyList()))
    }

    @Test
    fun aNativeAgentMatchesTheDaemonSessionByShortId() {
        val s = Session(sessionId = sid, connectionId = "c1")
        val other = Session(sessionId = sid, connectionId = "c2")
        assertEquals(SessionRef("c1", sid), sessionRefForRun(nativeRunRef("c1", "7b9f8c4c"), emptyList(), listOf(other, s)))
        assertNull(sessionRefForRun(nativeRunRef("c1", "deadbeef"), emptyList(), listOf(s)))
    }

    @Test
    fun aTerminalSessionMatchesByPid() {
        val s = Session(sessionId = sid, connectionId = "c1", heldBy = Holder.TERMINAL, terminalPid = 4242)
        assertEquals(SessionRef("c1", sid), sessionRefForRun(nativeRunRef("c1", "term-4242"), emptyList(), listOf(s)))
        assertNull(sessionRefForRun(nativeRunRef("c1", "term-1"), emptyList(), listOf(s)))
    }
}
