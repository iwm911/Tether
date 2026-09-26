package app.tether.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.AuthMethod
import app.tether.core.Connection
import app.tether.core.SshKey
import kotlinx.coroutines.CancellationException
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

internal data class KeyItem(val key: SshKey, val usedBy: List<Connection>)

internal data class KeysUi(
    val generateOpen: Boolean = false,
    val generating: Boolean = false,
    val importOpen: Boolean = false,
    val importing: Boolean = false,
    val importError: String? = null,
    /** Key whose detail sheet is open. */
    val sheetKeyId: String? = null,
    /** Key that was just created — plays the reveal animation once in its sheet. */
    val revealKeyId: String? = null,
    val confirmDeleteId: String? = null,
)

internal class KeysViewModel(private val container: AppContainer) : ViewModel() {

    val items: StateFlow<List<KeyItem>> = combine(container.keys.keys, container.connections.connections) { keys, conns ->
        keys.map { k -> KeyItem(k, conns.filter { (it.auth as? AuthMethod.Key)?.keyId == k.id }) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        container.keys.keys.value.map { k ->
            KeyItem(k, container.connections.connections.value.filter { (it.auth as? AuthMethod.Key)?.keyId == k.id })
        },
    )

    private val _ui = MutableStateFlow(KeysUi())
    val ui: StateFlow<KeysUi> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun openGenerate() = _ui.update { it.copy(generateOpen = true) }
    fun closeGenerate() = _ui.update { it.copy(generateOpen = false) }
    fun openImport() = _ui.update { it.copy(importOpen = true, importError = null) }
    fun closeImport() = _ui.update { it.copy(importOpen = false, importError = null, importing = false) }
    fun openSheet(id: String) = _ui.update { it.copy(sheetKeyId = id) }
    fun closeSheet() = _ui.update { it.copy(sheetKeyId = null, revealKeyId = null) }
    fun askDelete(id: String) = _ui.update { it.copy(confirmDeleteId = id) }
    fun cancelDelete() = _ui.update { it.copy(confirmDeleteId = null) }

    fun generate(name: String) {
        if (_ui.value.generating) return
        viewModelScope.launch {
            _ui.update { it.copy(generating = true) }
            try {
                val k = container.keys.generateEd25519(name)
                _ui.update { it.copy(generating = false, generateOpen = false, sheetKeyId = k.id, revealKeyId = k.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(generating = false) }
                _messages.tryEmit("Couldn't generate a key: ${friendlyError(e)}")
            }
        }
    }

    fun importKey(name: String, text: String, passphrase: String?) {
        if (_ui.value.importing) return
        viewModelScope.launch {
            _ui.update { it.copy(importing = true, importError = null) }
            try {
                val k = container.keys.import(name, text, passphrase)
                _ui.update { it.copy(importing = false, importOpen = false, sheetKeyId = k.id, revealKeyId = k.id) }
                _messages.tryEmit("Imported “${k.name}”")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(importing = false, importError = friendlyError(e)) }
            }
        }
    }

    fun delete(id: String) {
        val name = container.keys.get(id)?.name ?: "key"
        _ui.update { it.copy(confirmDeleteId = null, sheetKeyId = if (it.sheetKeyId == id) null else it.sheetKeyId) }
        viewModelScope.launch {
            try {
                container.keys.delete(id)
                _messages.tryEmit("Deleted “$name”")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Couldn't delete: ${friendlyError(e)}")
            }
        }
    }
}
