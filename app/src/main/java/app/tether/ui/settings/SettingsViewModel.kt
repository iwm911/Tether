package app.tether.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.AppSettings
import app.tether.core.KnownHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settings.settings

    val knownHosts: StateFlow<List<KnownHost>> = container.knownHosts.all

    val machineCount: StateFlow<Int> = container.connections.connections
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.connections.connections.value.size)

    val keyCount: StateFlow<Int> = container.keys.keys
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.keys.keys.value.size)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            runCatching { container.settings.update(transform) }
                .onFailure { _messages.tryEmit("Couldn't save that setting") }
        }
    }

    fun removeHost(h: KnownHost) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.knownHosts.remove(h.host, h.port) }
                .onSuccess { _messages.tryEmit("Forgot ${h.host}") }
                .onFailure { _messages.tryEmit("Couldn't remove ${h.host}") }
        }
    }

    fun say(message: String) {
        _messages.tryEmit(message)
    }
}
