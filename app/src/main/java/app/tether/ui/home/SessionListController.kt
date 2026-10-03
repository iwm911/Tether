package app.tether.ui.home

import app.tether.core.Session
import app.tether.core.SessionDecision
import app.tether.core.SessionHub
import app.tether.core.projectRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State shared by Home and the Machine screen around the session list: the filter chips, older
 * pages ("Show older") and optimistic inline Allow / Deny. Lives in a ViewModel's scope.
 */
class SessionListController(
    private val scope: CoroutineScope,
    private val hub: SessionHub,
    initialFilter: SessionFilter = SessionFilter(),
    private val onError: (String) -> Unit = {},
    private val pageSize: Int = SessionPaging.PAGE_SIZE,
    /** Minimum time the optimistic "Allowed / Denied" shows before a failure can undo it. */
    private val minDecisionMs: Long = 750,
    /** How long an answered prompt's decision is remembered (the hub has caught up long before). */
    private val forgetDecisionMs: Long = 60_000,
) {
    private val _filter = MutableStateFlow(initialFilter)
    val filter: StateFlow<SessionFilter> = _filter.asStateFlow()

    private val _pages = MutableStateFlow<Map<PageKey, Page>>(emptyMap())
    val pages: StateFlow<Map<PageKey, Page>> = _pages.asStateFlow()

    /** Unsettled and settled optimistic decisions by [decisionKey]: true = allowed. */
    private val _decisions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val decisions: StateFlow<Map<String, Boolean>> = _decisions.asStateFlow()

    private val _projectOrder = MutableStateFlow<List<String>>(emptyList())
    /** The project chips' order, a [projectOrder] snapshot taken by [resortProjects]. */
    val projectOrder: StateFlow<List<String>> = _projectOrder.asStateFlow()

    /**
     * Re-sorts the project chips by recent activity. Called when the list is (re)entered or
     * refreshed, not on every update, so the chips don't jump around while sessions run.
     */
    fun resortProjects() {
        _projectOrder.value = projectOrder(hub.sessions.value + _pages.value.values.flatMap { it.sessions })
    }

    fun setMachine(machine: String?) = _filter.update { f ->
        // A project belongs to a machine: changing machines clears it unless it is still in scope.
        if (f.machine == machine) f else f.copy(machine = machine, project = null)
    }

    fun setProject(project: String?) = _filter.update { f -> f.copy(project = project?.let { normPath(projectRoot(it)) }) }

    /**
     * Loads the next older page of every slice the current filter covers ([machines] = every
     * machine in scope when the filter has none). [shown] is what the screen lists right now.
     */
    fun loadOlder(machines: List<String>, shown: List<Session>) {
        val f = _filter.value
        val keys = SessionPaging.keysFor(f, machines).filter { k -> _pages.value[k].let { it == null || (!it.loading && !it.exhausted) } }
        if (keys.isEmpty()) return
        _pages.update { m -> m + keys.associateWith { k -> (m[k] ?: Page()).copy(loading = true, error = null) } }
        val shownKeys = shown.mapTo(HashSet()) { it.listKey() }
        for (k in keys) scope.launch {
            val before = SessionPaging.cursor(k, shown + (_pages.value[k]?.sessions ?: emptyList()))
            attempt { hub.history(k.connectionId, k.cwd, pageSize, before) }
                .onSuccess { fetched ->
                    _pages.update { m -> m + (k to SessionPaging.append(m[k] ?: Page(), fetched, shownKeys, pageSize)) }
                }
                .onFailure { e ->
                    val msg = e.humanMessage()
                    _pages.update { m -> m + (k to (m[k] ?: Page()).copy(loading = false, error = msg)) }
                    onError("Couldn't load older sessions — $msg")
                }
        }
    }

    /** Forget older pages (pull-to-refresh starts the list over from what the watch carries). */
    fun resetPages() { _pages.value = emptyMap() }

    /** Inline Allow / Deny on a needs-you row: shows the decision at once, undoes it on failure. */
    fun respond(session: Session, allow: Boolean) {
        val pending = session.inlinePermission() ?: return
        val key = decisionKey(session.ref, pending)
        if (_decisions.value.containsKey(key)) return
        _decisions.update { it + (key to allow) }
        scope.launch {
            val minDelay = async { delay(minDecisionMs) }
            val result = attempt {
                hub.answer(session.ref, if (allow) SessionDecision.ALLOW else SessionDecision.DENY, toolUseId = pending.toolUseId.ifBlank { null })
            }
            minDelay.await()
            result.fold(
                onSuccess = {
                    delay(forgetDecisionMs)
                    _decisions.update { it - key }
                },
                onFailure = { e ->
                    _decisions.update { it - key }
                    onError("Couldn't send your answer — ${e.humanMessage()}")
                },
            )
        }
    }
}
