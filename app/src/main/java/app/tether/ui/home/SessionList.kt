package app.tether.ui.home

import app.tether.core.Session
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.core.SessionState
import app.tether.core.identity
import app.tether.core.projectRoot

/*
 * The one session list Home and the Machine screen show (one-session model, U2). Plain Kotlin so
 * the ordering, filtering and paging rules are unit-tested without Compose.
 */

/** What the row's state badge says. */
enum class SessionBadge(val label: String) {
    WORKING("Working"),
    NEEDS_YOU("Needs you"),
    IDLE("Idle"),
    DONE("Done"),
    FAILED("Failed"),
    /** The machine stopped answering: the session's last known state was working / needs you / idle. */
    OFFLINE("Offline"),
}

fun Session.badge(): SessionBadge = if (offline && state != SessionState.DONE && state != SessionState.FAILED) SessionBadge.OFFLINE else when (state) {
    SessionState.WORKING -> SessionBadge.WORKING
    SessionState.NEEDS_YOU -> SessionBadge.NEEDS_YOU
    SessionState.IDLE -> SessionBadge.IDLE
    SessionState.DONE -> SessionBadge.DONE
    SessionState.FAILED -> SessionBadge.FAILED
}

/** Stable list key of a session on a machine. */
fun Session.listKey(): String = "$connectionId/$sessionId"
fun SessionRef.listKey(): String = "$connectionId/$sessionId"

/** Needs-you first, then most recently updated; ties broken by id so the order never flickers. */
val HomeSessionOrder: Comparator<Session> =
    compareBy<Session> { if (it.needsYou) 0 else 1 }
        .thenByDescending { it.updatedAt }
        .thenBy { it.connectionId }
        .thenBy { it.sessionId }

fun List<Session>.sortedForHome(): List<Session> = sortedWith(HomeSessionOrder)

/** Machine + project filter chips. Null = all. A project is a session's `cwd` (a worktree's, its root). */
data class SessionFilter(val machine: String? = null, val project: String? = null) {
    fun matches(s: Session): Boolean =
        (machine == null || s.connectionId == machine) && (project == null || samePath(projectRoot(s.cwd), project))

    val isEmpty: Boolean get() = machine == null && project == null
}

internal fun normPath(p: String): String = p.trimEnd('/').ifEmpty { if (p.startsWith("/")) "/" else "" }

internal fun samePath(a: String, b: String): Boolean = normPath(a) == normPath(b)

/**
 * The watched sessions plus older pages loaded by paging, de-duplicated by machine + id. The
 * watched copy always wins (it is live; a page is a snapshot from when it was fetched).
 */
fun mergeSessions(watched: List<Session>, older: List<Session>): List<Session> {
    if (older.isEmpty()) return watched
    val seen = HashSet<String>(watched.size + older.size)
    val out = ArrayList<Session>(watched.size + older.size)
    for (s in watched) if (seen.add(s.listKey())) out += s
    for (s in older) if (seen.add(s.listKey())) out += s
    return out
}

/** One project chip: the folder and how many sessions it has in the current machine scope. */
data class ProjectChip(val cwd: String, val count: Int, val lastActive: Long)

/** Every project in [sessions], most recently active first: the order project chips keep until the next snapshot. */
fun projectOrder(sessions: List<Session>): List<String> =
    sessions.filter { it.cwd.isNotBlank() }
        .groupBy { normPath(projectRoot(it.cwd)) }
        .map { (cwd, list) -> cwd to list.maxOf { it.updatedAt } }
        .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
        .map { it.first }

/**
 * Project chips for the sessions of [machine] (null = every machine), at most [max]. Projects in
 * [order] (a [projectOrder] snapshot) keep that order, so live activity doesn't reshuffle them;
 * any others follow, most recently active first. The selected project always stays in the list so
 * it can be cleared.
 */
fun projectChips(sessions: List<Session>, machine: String?, selected: String? = null, max: Int = 8, order: List<String> = emptyList()): List<ProjectChip> {
    val scoped = sessions.filter { (machine == null || it.connectionId == machine) && it.cwd.isNotBlank() }
    val rank = order.withIndex().associate { (i, cwd) -> cwd to i }
    val chips = scoped.groupBy { normPath(projectRoot(it.cwd)) }
        .map { (cwd, list) -> ProjectChip(cwd, list.size, list.maxOf { it.updatedAt }) }
        .sortedWith(compareBy<ProjectChip> { rank[it.cwd] ?: Int.MAX_VALUE }.thenByDescending { it.lastActive }.thenBy { it.cwd })
    val top = chips.take(max)
    if (selected == null || top.any { samePath(it.cwd, selected) }) return top
    val sel = chips.firstOrNull { samePath(it.cwd, selected) } ?: ProjectChip(normPath(selected), 0, 0)
    return top + sel
}

/** Machines with at least one session in [sessions], in [order] (the user's machine order). */
fun machinesWithSessions(sessions: List<Session>, order: List<String>): List<String> {
    val present = sessions.mapTo(HashSet()) { it.connectionId }
    return order.filter { it in present }
}

/** Sessions that count as running on a machine (working or waiting for the user). */
fun List<Session>.liveCountByMachine(): Map<String, Int> =
    filter { (it.state == SessionState.WORKING || it.state == SessionState.NEEDS_YOU) && !it.offline }
        .groupingBy { it.connectionId }.eachCount()

// ───────────────────────────── Inline Allow / Deny ─────────────────────────────

/** Rows offer inline Allow / Deny only for a tool permission; questions and dialogs open the session. */
fun Session.inlinePermission(): SessionPending.Permission? =
    (pending as? SessionPending.Permission)?.takeIf { needsYou && !heldByTerminal && !offline }

/** Identifies one answer to one prompt (a later prompt on the same session is a new key). */
fun decisionKey(ref: SessionRef, pending: SessionPending): String = "${ref.listKey()}#${pending.identity}"

// ───────────────────────────── Paging ─────────────────────────────

/** Which slice an older page belongs to: one machine, optionally one project. */
data class PageKey(val connectionId: String, val cwd: String?)

/** Older sessions loaded so far for one [PageKey]. */
data class Page(val sessions: List<Session> = emptyList(), val loading: Boolean = false, val exhausted: Boolean = false, val error: String? = null)

object SessionPaging {
    const val PAGE_SIZE = 30

    /** The page keys "Show older" loads for [filter] across [machines]. */
    fun keysFor(filter: SessionFilter, machines: List<String>): List<PageKey> =
        (filter.machine?.let { listOf(it) } ?: machines).map { PageKey(it, filter.project?.let(::normPath)) }

    /** `before` cursor for [key]: the oldest `updatedAt` already shown in that slice, or null. */
    fun cursor(key: PageKey, shown: List<Session>): Long? =
        shown.asSequence()
            .filter { it.connectionId == key.connectionId && (key.cwd == null || samePath(projectRoot(it.cwd), key.cwd)) && it.updatedAt > 0 }
            .minOfOrNull { it.updatedAt }

    /**
     * Folds a fetched page into [current]: adds the sessions not seen yet. A page shorter than the
     * limit, or one that brought nothing new, means there is nothing older.
     */
    fun append(current: Page, fetched: List<Session>, alreadyShown: Set<String>, limit: Int = PAGE_SIZE): Page {
        val known = HashSet(alreadyShown).apply { current.sessions.forEach { add(it.listKey()) } }
        val fresh = fetched.filter { known.add(it.listKey()) }
        return Page(
            sessions = current.sessions + fresh,
            loading = false,
            exhausted = fetched.size < limit || fresh.isEmpty(),
            error = null,
        )
    }

    /** Whether "Show older" is worth offering for [filter]. */
    fun canLoadMore(filter: SessionFilter, machines: List<String>, pages: Map<PageKey, Page>): Boolean =
        keysFor(filter, machines).any { pages[it]?.exhausted != true }

    fun loading(filter: SessionFilter, machines: List<String>, pages: Map<PageKey, Page>): Boolean =
        keysFor(filter, machines).any { pages[it]?.loading == true }

    /** Every older session relevant to [filter]. */
    fun olderFor(filter: SessionFilter, pages: Map<PageKey, Page>): List<Session> =
        pages.values.flatMap { it.sessions }.filter { filter.matches(it) }
}

/** The list a screen shows: watched + paged, filtered, ordered. */
/** [show] off = leave out sessions a terminal holds (the "Show terminal sessions" setting). */
fun List<Session>.withTerminal(show: Boolean): List<Session> = if (show) this else filterNot { it.heldByTerminal }

fun visibleSessions(watched: List<Session>, pages: Map<PageKey, Page>, filter: SessionFilter): List<Session> =
    mergeSessions(watched, SessionPaging.olderFor(filter, pages))
        .filter { filter.matches(it) }
        .sortedForHome()
