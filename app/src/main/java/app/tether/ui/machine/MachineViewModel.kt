package app.tether.ui.machine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.Connection
import app.tether.core.LinkState
import app.tether.core.ProbeResult
import app.tether.core.Session
import app.tether.core.SessionHub
import app.tether.ui.home.Loadable
import app.tether.ui.home.Page
import app.tether.ui.home.PageKey
import app.tether.ui.home.ProjectChip
import app.tether.ui.home.SessionFilter
import app.tether.ui.home.SessionListController
import app.tether.ui.home.SessionPaging
import app.tether.ui.home.attempt
import app.tether.ui.home.humanMessage
import app.tether.ui.home.mergeSessions
import app.tether.ui.home.projectChips
import app.tether.ui.home.visibleSessions
import app.tether.ui.home.withTerminal
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

data class MachineUiState(
    val connection: Connection? = null,
    /** False only until the repository has been consulted once. */
    val known: Boolean = true,
    val link: LinkState? = null,
    val machineError: String? = null,
    val probe: Loadable<ProbeResult> = Loadable.Loading,
    /** This machine's sessions matching [filter]: needs-you first, then most recent. */
    val sessions: List<Session> = emptyList(),
    /** Every session of this machine (before the project filter). */
    val totalSessions: Int = 0,
    val filter: SessionFilter = SessionFilter(),
    val projectChips: List<ProjectChip> = emptyList(),
    val decisions: Map<String, Boolean> = emptyMap(),
    /** True until the first `sessions` of this machine came back (skeleton rows meanwhile). */
    val loadingSessions: Boolean = true,
    val canLoadOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    val refreshing: Boolean = false,
    val disconnecting: Boolean = false,
)

private data class MachineLocal(
    val probe: Loadable<ProbeResult> = Loadable.Loading,
    val firstLoadDone: Boolean = false,
    val refreshing: Boolean = false,
    val disconnecting: Boolean = false,
)

private data class MachineList(
    val filter: SessionFilter,
    val pages: Map<PageKey, Page>,
    val decisions: Map<String, Boolean>,
    val showTerminal: Boolean,
)

/** One machine: its header (probe) and the session list filtered to it. */
class MachineViewModel(private val container: AppContainer, val connectionId: String) : ViewModel() {
    private val hub: SessionHub = container.sessions
    private val local = MutableStateFlow(MachineLocal())
    private val messages = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = messages.receiveAsFlow()

    val list = SessionListController(viewModelScope, hub, SessionFilter(machine = connectionId), onError = { messages.trySend(it) })

    private val listBits = combine(list.filter, list.pages, list.decisions, container.settings.settings.map { it.showTerminalSessions }) { f, p, d, t -> MachineList(f, p, d, t) }

    val state: StateFlow<MachineUiState> = combine(
        container.connections.connections,
        container.ssh.states,
        hub.sessions,
        hub.machineErrors,
        combine(local, listBits) { l, b -> l to b },
    ) { conns, links, sessions, errors, (l, b) ->
        val conn = conns.firstOrNull { it.id == connectionId }
        reduce(conn, links[connectionId], errors[connectionId], sessions, l, b)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MachineUiState(connection = container.connections.get(connectionId), known = container.connections.get(connectionId) != null),
    )

    init {
        if (container.connections.get(connectionId) != null) {
            // Opening a machine connects it again.
            hub.release(connectionId)
            loadProbe()
            viewModelScope.launch {
                attempt { hub.refresh(connectionId) }
                local.update { it.copy(firstLoadDone = true) }
            }
        }
    }

    private fun reduce(conn: Connection?, link: LinkState?, error: String?, all: List<Session>, l: MachineLocal, b: MachineList): MachineUiState {
        val mine = all.filter { it.connectionId == connectionId }.withTerminal(b.showTerminal)
        val filter = b.filter.copy(machine = connectionId)
        val everything = mergeSessions(mine, SessionPaging.olderFor(SessionFilter(machine = connectionId), b.pages).withTerminal(b.showTerminal))
        return MachineUiState(
            connection = conn,
            known = conn != null,
            link = link,
            machineError = error,
            probe = l.probe,
            sessions = visibleSessions(mine, b.pages, filter).withTerminal(b.showTerminal),
            totalSessions = everything.size,
            filter = filter,
            projectChips = projectChips(everything, connectionId, filter.project),
            decisions = b.decisions,
            loadingSessions = mine.isEmpty() && !l.firstLoadDone && error == null,
            canLoadOlder = everything.isNotEmpty() && SessionPaging.canLoadMore(filter, listOf(connectionId), b.pages),
            loadingOlder = SessionPaging.loading(filter, listOf(connectionId), b.pages),
            refreshing = l.refreshing,
            disconnecting = l.disconnecting,
        )
    }

    fun selectProject(cwd: String?) = list.setProject(cwd)

    fun loadOlder() = list.loadOlder(listOf(connectionId), state.value.sessions)

    fun respond(session: Session, allow: Boolean) = list.respond(session, allow)

    fun loadProbe(): Job = viewModelScope.launch {
        if (local.value.probe !is Loadable.Ready) local.update { it.copy(probe = Loadable.Loading) }
        val r = attempt { container.remote.probe(connectionId) }
        r.onSuccess { p ->
            local.update { it.copy(probe = Loadable.Ready(p)) }
            attempt { container.connections.markConnected(connectionId, p.hostname, p.claudeVersion) }
        }.onFailure { e ->
            // Keep a previous good probe on a failed refresh; the link dot already tells the story.
            if (local.value.probe !is Loadable.Ready) local.update { it.copy(probe = Loadable.Failed(e.humanMessage())) }
        }
    }

    fun refresh() {
        if (local.value.refreshing) return
        local.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val sessions = launch {
                attempt { hub.refresh(connectionId) }.onFailure { messages.trySend("Couldn't refresh sessions — ${it.humanMessage()}") }
                list.resetPages()
            }
            listOf(loadProbe(), sessions).joinAll()
            val spent = System.currentTimeMillis() - t0
            if (spent < 650) delay(650 - spent)
            local.update { it.copy(refreshing = false, firstLoadDone = true) }
        }
    }

    fun disconnect() {
        if (local.value.disconnecting) return
        local.update { it.copy(disconnecting = true) }
        viewModelScope.launch {
            val name = state.value.connection?.name ?: "machine"
            // Else the watch streams reconnect straight away.
            hub.hold(connectionId)
            attempt { container.ssh.disconnect(connectionId) }
                .onSuccess { messages.trySend("Disconnected from $name") }
                .onFailure { messages.trySend("Couldn't disconnect — ${it.humanMessage()}") }
            local.update { it.copy(disconnecting = false) }
        }
    }
}
