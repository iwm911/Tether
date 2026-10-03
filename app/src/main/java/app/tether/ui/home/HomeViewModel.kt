package app.tether.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.AppSettings
import app.tether.core.Connection
import app.tether.core.LinkState
import app.tether.core.ProjectSummary
import app.tether.core.Session
import app.tether.core.SessionHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuickStart(val connection: Connection, val projects: Loadable<List<ProjectSummary>>)

data class HomeUiState(
    val connections: List<Connection> = emptyList(),
    val links: Map<String, LinkState> = emptyMap(),
    val machineErrors: Map<String, String> = emptyMap(),
    /** The one list: every session of every machine that matches [filter], needs-you first then most recent. */
    val sessions: List<Session> = emptyList(),
    /** Sessions before filtering (to tell "nothing here yet" from "nothing matches the chips"). */
    val totalSessions: Int = 0,
    val needsYouCount: Int = 0,
    val workingCount: Int = 0,
    val filter: SessionFilter = SessionFilter(),
    /** Machine chips (ids, in the user's machine order); shown when sessions span more than one machine. */
    val machineChips: List<String> = emptyList(),
    val projectChips: List<ProjectChip> = emptyList(),
    /** Optimistic Allow (true) / Deny (false) by [decisionKey]. */
    val decisions: Map<String, Boolean> = emptyMap(),
    val liveCountByMachine: Map<String, Int> = emptyMap(),
    val canLoadOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val showSkeleton: Boolean = false,
    val refreshing: Boolean = false,
    val retrying: Set<String> = emptySet(),
    val quickStart: QuickStart? = null,
) {
    val hasSessions get() = totalSessions > 0
}

sealed interface HomeMessage {
    data class Error(val text: String) : HomeMessage
}

private data class Local(
    val refreshing: Boolean = false,
    val retrying: Set<String> = emptySet(),
    /** 0 = first 2.5 s, 1 = grace while a machine is still connecting, 2 = settled. */
    val loadPhase: Int = 0,
    val firstRefreshDone: Boolean = false,
    val quickStart: QuickStart? = null,
)

private data class ListBits(
    val filter: SessionFilter,
    val pages: Map<PageKey, Page>,
    val decisions: Map<String, Boolean>,
    val showTerminal: Boolean,
    val projectOrder: List<String>,
)

/** Home: one list of every session on every machine (one-session model). */
class HomeViewModel(
    private val hub: SessionHub,
    private val connections: StateFlow<List<Connection>>,
    private val links: StateFlow<Map<String, LinkState>>,
    private val loadProjects: suspend (connectionId: String) -> List<ProjectSummary>,
    private val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings()),
    /** False in tests: skip the skeleton timers. */
    loadTimers: Boolean = true,
) : ViewModel() {
    constructor(container: AppContainer) : this(
        hub = container.sessions,
        connections = container.connections.connections,
        links = container.ssh.states,
        loadProjects = { id -> container.remote.listProjects(id) },
        settings = container.settings.settings,
    )

    private val local = MutableStateFlow(Local(loadPhase = if (loadTimers) 0 else 2))
    private val messages = Channel<HomeMessage>(Channel.BUFFERED)
    val events: Flow<HomeMessage> = messages.receiveAsFlow()
    private var quickStartJob: Job? = null

    val list = SessionListController(viewModelScope, hub, onError = { messages.trySend(HomeMessage.Error(it)) })

    private val listBits = combine(list.filter, list.pages, list.decisions, settings.map { it.showTerminalSessions }, list.projectOrder) { f, p, d, t, o -> ListBits(f, p, d, t, o) }

    val state: StateFlow<HomeUiState> = combine(connections, links, hub.sessions, hub.machineErrors, combine(local, listBits) { l, b -> l to b }) { conns, lk, sessions, errors, (l, b) ->
        reduce(conns, lk, sessions, errors, l, b)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        // Seed from current values so the first frame is already the right state (no empty-state flash).
        reduce(connections.value, links.value, hub.sessions.value, hub.machineErrors.value, local.value, ListBits(list.filter.value, list.pages.value, list.decisions.value, settings.value.showTerminalSessions, list.projectOrder.value)),
    )

    init {
        viewModelScope.launch {
            attempt { hub.refresh() }
            list.resortProjects()
            local.update { it.copy(firstRefreshDone = true) }
        }
        if (loadTimers) viewModelScope.launch {
            delay(2_500)
            local.update { it.copy(loadPhase = 1) }
            delay(5_500)
            local.update { it.copy(loadPhase = 2) }
        }
    }

    private fun reduce(
        conns: List<Connection>,
        lk: Map<String, LinkState>,
        all: List<Session>,
        errors: Map<String, String>,
        l: Local,
        b: ListBits,
    ): HomeUiState {
        val order = conns.map { it.id }
        val known = order.toSet()
        val watched = all.filter { it.connectionId in known }.withTerminal(b.showTerminal)
        // A filter on a machine that was deleted (or a project that is gone) is dropped, not a dead end.
        val filter = b.filter.let { f -> if (f.machine != null && f.machine !in known) SessionFilter() else f }
        val older = SessionPaging.olderFor(SessionFilter(machine = filter.machine), b.pages).filter { it.connectionId in known }.withTerminal(b.showTerminal)
        val everything = mergeSessions(watched, older)
        val visible = visibleSessions(watched, b.pages, filter).filter { it.connectionId in known }.withTerminal(b.showTerminal)
        val anyConnecting = conns.any { lk[it.id] == LinkState.Connecting }
        val stillLoading = when (l.loadPhase) {
            0 -> true
            1 -> anyConnecting && !l.firstRefreshDone
            else -> false
        }
        val relevantErrors = errors.filterKeys { it in known }
        val machineChips = machinesWithSessions(everything, order).let { present ->
            // The selected machine stays visible even when it has nothing left.
            if (filter.machine != null && filter.machine !in present) order.filter { it in present || it == filter.machine } else present
        }
        val pageMachines = order.filter { it !in relevantErrors }
        return HomeUiState(
            connections = conns,
            links = lk,
            machineErrors = relevantErrors,
            sessions = visible,
            totalSessions = everything.size,
            needsYouCount = watched.count { it.needsYou && !it.offline },
            workingCount = watched.count { it.state == app.tether.core.SessionState.WORKING && !it.offline },
            filter = filter,
            machineChips = if (machineChips.size > 1 || filter.machine != null) machineChips else emptyList(),
            projectChips = projectChips(everything, filter.machine, filter.project, order = b.projectOrder),
            decisions = b.decisions,
            liveCountByMachine = watched.liveCountByMachine(),
            canLoadOlder = everything.isNotEmpty() && SessionPaging.canLoadMore(filter, pageMachines, b.pages),
            loadingOlder = SessionPaging.loading(filter, pageMachines, b.pages),
            showSkeleton = watched.isEmpty() && conns.isNotEmpty() && relevantErrors.isEmpty() && stillLoading,
            refreshing = l.refreshing,
            retrying = l.retrying,
            quickStart = l.quickStart?.takeIf { qs -> conns.any { it.id == qs.connection.id } },
        )
    }

    // ───────────── Filters & paging ─────────────

    fun selectMachine(id: String?) = list.setMachine(id)

    fun selectProject(cwd: String?) = list.setProject(cwd)

    fun resortProjects() = list.resortProjects()

    fun loadOlder() {
        val s = state.value
        list.loadOlder(s.connections.map { it.id }.filter { it !in s.machineErrors }, s.sessions)
    }

    // ───────────── Inline answers ─────────────

    fun respond(session: Session, allow: Boolean) = list.respond(session, allow)

    // ───────────── Refresh ─────────────

    fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val r = attempt { hub.refresh() }
            list.resetPages()
            list.resortProjects()
            val spent = System.currentTimeMillis() - t0
            if (spent < 650) delay(650 - spent)
            local.update { it.copy(refreshing = false, firstRefreshDone = true) }
            r.exceptionOrNull()?.let { messages.trySend(HomeMessage.Error("Refresh failed — ${it.humanMessage()}")) }
            local.value.quickStart?.let { qs -> if (qs.projects is Loadable.Failed) loadQuickStart(qs.connection) }
        }
    }

    fun retryMachine(connectionId: String) {
        if (connectionId in local.value.retrying) return
        local.update { it.copy(retrying = it.retrying + connectionId) }
        viewModelScope.launch {
            attempt { hub.refresh(connectionId) }
            local.update { it.copy(retrying = it.retrying - connectionId) }
        }
    }

    // ───────────── First-session quick start ─────────────

    /** Pick the machine for the "start your first session" quick-start list (sticky once chosen). */
    fun ensureQuickStart(s: HomeUiState) {
        val current = local.value.quickStart
        if (current != null && current.projects !is Loadable.Failed) return
        if (quickStartJob?.isActive == true) return
        val target = s.connections.firstOrNull { s.links[it.id] is LinkState.Connected }
            ?: s.connections.firstOrNull { it.id !in s.machineErrors && s.links[it.id] !is LinkState.Failed }
            ?: s.connections.firstOrNull()
            ?: return
        // A failed machine is only retried by the user; auto-switch only to a different one.
        if (current != null && current.connection.id == target.id) return
        loadQuickStart(target)
    }

    fun retryQuickStart() {
        local.value.quickStart?.let { loadQuickStart(it.connection) }
    }

    private fun loadQuickStart(conn: Connection) {
        quickStartJob?.cancel()
        local.update { it.copy(quickStart = QuickStart(conn, Loadable.Loading)) }
        quickStartJob = viewModelScope.launch {
            val r = attempt { loadProjects(conn.id) }
            val value: Loadable<List<ProjectSummary>> = r.fold(
                onSuccess = { list -> Loadable.Ready(list.filter { it.exists }.sortedByDescending { it.lastActiveAt }.take(4)) },
                onFailure = { Loadable.Failed(it.humanMessage()) },
            )
            local.update { it.copy(quickStart = QuickStart(conn, value)) }
        }
    }
}
