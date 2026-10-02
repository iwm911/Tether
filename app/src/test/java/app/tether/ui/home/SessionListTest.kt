package app.tether.ui.home

import app.tether.core.Holder
import app.tether.core.PermissionMode
import app.tether.core.Session
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.ui.newagent.newSessionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordering, filtering and paging rules of the one session list (Home + Machine). */
class SessionListTest {

    private fun s(
        id: String,
        conn: String = "a",
        cwd: String = "/home/me/app",
        state: SessionState = SessionState.IDLE,
        updated: Long = 0,
        pending: SessionPending? = null,
        heldBy: Holder = Holder.DAEMON,
    ) = Session(sessionId = id, cwd = cwd, state = state, updatedAt = updated, pending = pending, heldBy = heldBy, connectionId = conn)

    private val perm = SessionPending.Permission(toolUseId = "tu1", toolName = "Bash", summary = "ls")

    @Test
    fun needsYouFirstThenMostRecent() {
        val list = listOf(
            s("old", updated = 10),
            s("ask", state = SessionState.NEEDS_YOU, updated = 5, pending = perm),
            s("new", state = SessionState.WORKING, updated = 50),
            s("ask2", conn = "b", state = SessionState.NEEDS_YOU, updated = 20),
            s("done", state = SessionState.DONE, updated = 40),
            s("fail", state = SessionState.FAILED, updated = 30),
        ).sortedForHome()
        assertEquals(listOf("ask2", "ask", "new", "done", "fail", "old"), list.map { it.sessionId })
    }

    @Test
    fun orderIsStableForEqualTimes() {
        val a = s("aaaa", updated = 7)
        val b = s("bbbb", updated = 7)
        assertEquals(listOf(a, b), listOf(b, a).sortedForHome())
        assertEquals(listOf(a, b), listOf(a, b).sortedForHome())
    }

    @Test
    fun badgeFollowsState() {
        assertEquals(SessionBadge.WORKING, s("x", state = SessionState.WORKING).badge())
        assertEquals(SessionBadge.NEEDS_YOU, s("x", state = SessionState.NEEDS_YOU).badge())
        assertEquals(SessionBadge.IDLE, s("x").badge())
        assertEquals(SessionBadge.DONE, s("x", state = SessionState.DONE).badge())
        assertEquals(SessionBadge.FAILED, s("x", state = SessionState.FAILED).badge())
        assertEquals("Needs you", SessionBadge.NEEDS_YOU.label)
    }

    @Test
    fun anOfflineMachinesLiveSessionsShowOfflineWithoutInlineAnswers() {
        val perm = app.tether.core.SessionPending.Permission("toolu_1", "Bash", "ls")
        for (st in listOf(SessionState.WORKING, SessionState.NEEDS_YOU, SessionState.IDLE)) {
            assertEquals(SessionBadge.OFFLINE, s("x", state = st).copy(offline = true).badge())
        }
        assertEquals(SessionBadge.DONE, s("x", state = SessionState.DONE).copy(offline = true).badge())
        val waiting = s("x", state = SessionState.NEEDS_YOU).copy(pending = perm)
        assertEquals(perm, waiting.inlinePermission())
        assertNull(waiting.copy(offline = true).inlinePermission())
        assertEquals(mapOf("c" to 1), listOf(s("w", state = SessionState.WORKING), s("o", state = SessionState.WORKING).copy(offline = true))
            .map { it.copy(connectionId = "c") }.liveCountByMachine())
    }

    @Test
    fun filterByMachineAndProject() {
        val all = listOf(
            s("a1", conn = "a", cwd = "/p/one"),
            s("a2", conn = "a", cwd = "/p/two/"),
            s("b1", conn = "b", cwd = "/p/one"),
        )
        assertEquals(3, all.count { SessionFilter().matches(it) })
        assertEquals(2, all.count { SessionFilter(machine = "a").matches(it) })
        assertEquals(2, all.count { SessionFilter(project = "/p/one").matches(it) })
        assertEquals(1, all.count { SessionFilter(machine = "b", project = "/p/one").matches(it) })
        // Trailing slashes do not matter.
        assertEquals(1, all.count { SessionFilter(machine = "a", project = "/p/two").matches(it) })
        assertTrue(SessionFilter().isEmpty)
        assertFalse(SessionFilter(project = "/x").isEmpty)
    }

    @Test
    fun projectChipsAreScopedToTheMachineAndMostRecentFirst() {
        val all = listOf(
            s("a1", conn = "a", cwd = "/p/one", updated = 1),
            s("a2", conn = "a", cwd = "/p/two", updated = 9),
            s("a3", conn = "a", cwd = "/p/one/", updated = 5),
            s("b1", conn = "b", cwd = "/p/three", updated = 100),
        )
        assertEquals(listOf("/p/two" to 1, "/p/one" to 2), projectChips(all, "a").map { it.cwd to it.count })
        assertEquals(listOf("/p/three", "/p/two", "/p/one"), projectChips(all, null).map { it.cwd })
        assertEquals(listOf("/p/three"), projectChips(all, null, max = 1).map { it.cwd })
        // The selected project stays even when it would fall off the top.
        assertEquals(listOf("/p/three", "/p/one"), projectChips(all, null, selected = "/p/one", max = 1).map { it.cwd })
        // A selected project with no sessions left still shows (so it can be cleared).
        assertEquals(listOf("/p/two", "/p/one", "/gone"), projectChips(all, "a", selected = "/gone/").map { it.cwd })
    }

    @Test
    fun machineChipsFollowTheUsersMachineOrder() {
        val all = listOf(s("x", conn = "c"), s("y", conn = "a"))
        assertEquals(listOf("a", "c"), machinesWithSessions(all, listOf("a", "b", "c")))
    }

    @Test
    fun liveCountCountsWorkingAndNeedsYou() {
        val all = listOf(
            s("1", conn = "a", state = SessionState.WORKING),
            s("2", conn = "a", state = SessionState.NEEDS_YOU),
            s("3", conn = "a", state = SessionState.IDLE),
            s("4", conn = "b", state = SessionState.DONE),
        )
        assertEquals(mapOf("a" to 2), all.liveCountByMachine())
    }

    @Test
    fun inlineAllowDenyOnlyForPermissionsNotHeldByATerminal() {
        assertEquals(perm, s("p", state = SessionState.NEEDS_YOU, pending = perm).inlinePermission())
        assertNull(s("q", state = SessionState.NEEDS_YOU, pending = SessionPending.Question(toolUseId = "q")).inlinePermission())
        assertNull(s("d", state = SessionState.NEEDS_YOU, pending = SessionPending.Dialog(title = "MCP")).inlinePermission())
        assertNull(s("t", state = SessionState.NEEDS_YOU, pending = perm, heldBy = Holder.TERMINAL).inlinePermission())
        // A stale pending on a session that moved on is not answerable.
        assertNull(s("w", state = SessionState.WORKING, pending = perm).inlinePermission())
    }

    @Test
    fun decisionKeyIsPerPrompt() {
        val ref = SessionRef("a", "sid")
        assertEquals("a/sid#perm:tu1", decisionKey(ref, perm))
        assertFalse(decisionKey(ref, perm) == decisionKey(ref, perm.copy(toolUseId = "tu2")))
    }

    @Test
    fun mergeKeepsTheWatchedCopy() {
        val live = s("x", updated = 50, state = SessionState.WORKING)
        val stale = s("x", updated = 10)
        val older = s("y", updated = 5)
        val other = s("x", conn = "b", updated = 1)
        assertEquals(listOf(live, older, other), mergeSessions(listOf(live), listOf(stale, older, other)))
        assertEquals(listOf(live), mergeSessions(listOf(live), emptyList()))
    }

    @Test
    fun pagingKeysCursorAndAppend() {
        val f = SessionFilter()
        assertEquals(listOf(PageKey("a", null), PageKey("b", null)), SessionPaging.keysFor(f, listOf("a", "b")))
        assertEquals(listOf(PageKey("b", "/p")), SessionPaging.keysFor(SessionFilter("b", "/p/"), listOf("a", "b")))

        val shown = listOf(s("1", updated = 30), s("2", updated = 20), s("3", conn = "b", updated = 5), s("4", cwd = "/q", updated = 10), s("5", updated = 0))
        assertEquals(10L, SessionPaging.cursor(PageKey("a", null), shown))
        assertEquals(20L, SessionPaging.cursor(PageKey("a", "/home/me/app"), shown))
        assertNull(SessionPaging.cursor(PageKey("z", null), shown))

        val shownKeys = shown.map { it.listKey() }.toSet()
        val full = (1..3).map { s("o$it", updated = 9L - it) }
        val p1 = SessionPaging.append(Page(loading = true), full + shown[0], shownKeys, limit = 4)
        assertEquals(3, p1.sessions.size)
        assertFalse(p1.loading)
        assertFalse(p1.exhausted)
        // A short page ends paging.
        val p2 = SessionPaging.append(p1, listOf(s("o9", updated = 1)), shownKeys, limit = 4)
        assertEquals(4, p2.sessions.size)
        assertTrue(p2.exhausted)
        // A full page of nothing new ends paging too (no endless "Show older").
        val p3 = SessionPaging.append(p1, full + shown[0], shownKeys, limit = 4)
        assertTrue(p3.exhausted)
        assertEquals(3, p3.sessions.size)
    }

    @Test
    fun canLoadMoreUntilEverySliceIsExhausted() {
        val machines = listOf("a", "b")
        assertTrue(SessionPaging.canLoadMore(SessionFilter(), machines, emptyMap()))
        val pages = mapOf(PageKey("a", null) to Page(exhausted = true))
        assertTrue(SessionPaging.canLoadMore(SessionFilter(), machines, pages))
        assertFalse(SessionPaging.canLoadMore(SessionFilter(machine = "a"), machines, pages))
        assertFalse(SessionPaging.canLoadMore(SessionFilter(), machines, pages + (PageKey("b", null) to Page(exhausted = true))))
        assertTrue(SessionPaging.loading(SessionFilter(), machines, mapOf(PageKey("b", null) to Page(loading = true))))
    }

    @Test
    fun visibleSessionsMergesFiltersAndOrders() {
        val watched = listOf(s("w1", updated = 100), s("w2", conn = "b", updated = 90, state = SessionState.NEEDS_YOU))
        val pages = mapOf(
            PageKey("a", null) to Page(listOf(s("o1", updated = 3), s("w1", updated = 1))),
            PageKey("b", null) to Page(listOf(s("o2", conn = "b", cwd = "/other", updated = 2))),
        )
        fun names(l: List<Session>) = l.map { it.sessionId }
        assertEquals(listOf("w2", "w1", "o1", "o2"), names(visibleSessions(watched, pages, SessionFilter())))
        assertEquals(listOf("w1", "o1"), names(visibleSessions(watched, pages, SessionFilter(machine = "a"))))
        assertEquals(listOf("o2"), names(visibleSessions(watched, pages, SessionFilter(project = "/other"))))
        // The watched copy wins over a stale page copy.
        assertEquals(100L, visibleSessions(watched, pages, SessionFilter(machine = "a")).first().updatedAt)
    }

    @Test
    fun newSessionRequestLeavesDefaultsOut() {
        val r = newSessionRequest("/p", "  fix it \n", "default", PermissionMode.DEFAULT, trust = false)
        assertEquals("/p", r.cwd)
        assertEquals("fix it", r.prompt)
        assertNull(r.model)
        assertNull(r.permissionMode)
        assertFalse(r.trust)
        val r2 = newSessionRequest("/p", "go", "haiku", PermissionMode.fromCli("acceptEdits")!!, trust = true)
        assertEquals("haiku", r2.model)
        assertEquals("acceptEdits", r2.permissionMode)
        assertTrue(r2.trust)
    }
}
