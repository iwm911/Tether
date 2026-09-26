package app.tether.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.Connection
import app.tether.core.LinkState
import app.tether.core.RunStatus
import app.tether.ui.components.isLive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class MachineItem(
    val connection: Connection,
    val link: LinkState,
    val running: Int,
    val needsYou: Int,
    val error: String?,
)

internal data class MachineTestUi(val connection: Connection, val test: TestUi)

internal class MachinesViewModel(private val container: AppContainer) : ViewModel() {

    val items: StateFlow<List<MachineItem>> = combine(
        container.connections.connections,
        container.ssh.states,
        container.agents.agents,
        container.agents.machineErrors,
    ) { conns, links, agents, errors ->
        conns.map { c ->
            val mine = agents.filter { it.connection.id == c.id }
            MachineItem(
                connection = c,
                link = links[c.id] ?: LinkState.Idle,
                running = mine.count { it.run.displayStatus.isLive },
                needsYou = mine.count { it.run.status == RunStatus.AWAITING_PERMISSION },
                error = errors[c.id],
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialItems())

    private fun initialItems(): List<MachineItem> =
        container.connections.connections.value.map {
            MachineItem(it, container.ssh.states.value[it.id] ?: LinkState.Idle, 0, 0, null)
        }

    private val _test = MutableStateFlow<MachineTestUi?>(null)
    val test: StateFlow<MachineTestUi?> = _test.asStateFlow()
    private var testJob: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun test(connection: Connection) {
        testJob?.cancel()
        _test.value = MachineTestUi(connection, TestUi(running = true))
        testJob = viewModelScope.launch {
            val result = runPacedTest(
                block = { onProgress -> container.ssh.test(connection, null, onProgress) },
                onUpdate = { f -> _test.update { it?.copy(test = f(it.test)) } },
            )
            result.getOrNull()?.let { probe ->
                if (probe.problem == null || probe.claudeVersion != null) {
                    runCatching { container.connections.markConnected(connection.id, probe.hostname, probe.claudeVersion) }
                }
            }
        }
    }

    fun dismissTest() {
        testJob?.cancel()
        testJob = null
        _test.value = null
    }

    fun disconnect(connection: Connection) {
        viewModelScope.launch {
            runCatching { container.ssh.disconnect(connection.id) }
            _messages.tryEmit("Disconnected from ${connection.name}")
        }
    }

    fun delete(connection: Connection) {
        viewModelScope.launch {
            try {
                runCatching { container.ssh.disconnect(connection.id) }
                container.connections.delete(connection.id)
                _messages.tryEmit("Removed ${connection.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't remove ${connection.name}: ${friendlyError(e)}")
            }
        }
    }

    override fun onCleared() {
        testJob?.cancel()
    }
}
