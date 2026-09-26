package app.tether.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tether.AppContainer
import app.tether.core.AuthMethod
import app.tether.core.Connection
import app.tether.core.MachineAccents
import app.tether.core.SecretKeys
import app.tether.core.SshKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class AuthKind { KEY, PASSWORD }

internal data class EditorUi(
    val loading: Boolean = false,
    val missing: Boolean = false,
    val isEdit: Boolean = false,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val auth: AuthKind = AuthKind.KEY,
    val password: String = "",
    val hasSavedPassword: Boolean = false,
    val keyId: String? = null,
    val keys: List<SshKey> = emptyList(),
    val claudePath: String = "",
    val defaultCwd: String = "",
    val accent: Int = 0,
    val advancedOpen: Boolean = false,
    val generating: Boolean = false,
    /** Key that was just generated/imported here — its card plays the reveal animation. */
    val revealKeyId: String? = null,
    val importOpen: Boolean = false,
    val importing: Boolean = false,
    val importError: String? = null,
    val installOpen: Boolean = false,
    val installing: Boolean = false,
    val installError: String? = null,
    val test: TestUi = TestUi(),
    val saving: Boolean = false,
    val dirty: Boolean = false,
) {
    val selectedKey: SshKey? get() = keys.firstOrNull { it.id == keyId }
    val portNumber: Int? get() = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    val hostError: String? get() = when {
        host.isBlank() -> null
        host.trim().any { it.isWhitespace() } -> "Host can't contain spaces"
        else -> null
    }
    val portError: String? get() = if (portNumber == null) "Use a port from 1 to 65535" else null
    val usernameError: String? get() = if (username.trim().any { it.isWhitespace() }) "Username can't contain spaces" else null

    val hostOk: Boolean get() = host.isNotBlank() && hostError == null
    val userOk: Boolean get() = username.isNotBlank() && usernameError == null
    val authOk: Boolean get() = when (auth) {
        AuthKind.KEY -> selectedKey != null
        AuthKind.PASSWORD -> password.isNotEmpty() || hasSavedPassword
    }
    val canTest: Boolean get() = hostOk && portNumber != null && userOk && authOk && !test.running
    val canSave: Boolean get() = hostOk && portNumber != null && userOk && authOk && !saving && !loading && !missing
    val canInstall: Boolean get() = hostOk && portNumber != null && userOk && selectedKey != null

    /** What's still needed before Save lights up, as one sentence. */
    val missingSummary: String? get() {
        val parts = buildList {
            if (!hostOk) add("a host")
            if (portNumber == null) add("a valid port")
            if (!userOk) add("a username")
            if (!authOk) add(if (auth == AuthKind.KEY) "a key" else "a password")
        }
        if (parts.isEmpty()) return null
        val joined = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        return "Add $joined to save."
    }

    val displayName: String get() = name.trim().ifEmpty { host.trim() }
    val address: String get() {
        val u = username.trim()
        val h = host.trim()
        if (h.isEmpty()) return ""
        val p = portNumber
        return buildString {
            if (u.isNotEmpty()) append(u).append('@')
            append(h)
            if (p != null && p != 22) append(':').append(p)
        }
    }
}

internal class ConnectionEditorViewModel(
    private val container: AppContainer,
    private val connectionId: String?,
) : ViewModel() {

    private val id: String = connectionId ?: container.connections.newId()
    private var original: Connection? = null

    private val _state = MutableStateFlow(EditorUi(isEdit = connectionId != null, loading = connectionId != null))
    val state: StateFlow<EditorUi> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            container.keys.keys.collect { ks -> _state.update { it.copy(keys = ks) } }
        }
        if (connectionId != null) {
            viewModelScope.launch {
                val c = container.connections.get(connectionId)
                    ?: withTimeoutOrNull(1_500) {
                        container.connections.connections.first { list -> list.any { it.id == connectionId } }
                    }?.firstOrNull { it.id == connectionId }
                if (c == null) {
                    _state.update { it.copy(loading = false, missing = true) }
                } else {
                    original = c
                    val savedPw = c.auth is AuthMethod.Password && runCatching { container.secrets.get(SecretKeys.password(c.id)) }.getOrNull() != null
                    _state.update {
                        it.copy(
                            loading = false,
                            name = if (c.name == c.host) "" else c.name,
                            host = c.host,
                            port = c.port.toString(),
                            username = c.username,
                            auth = if (c.auth is AuthMethod.Key) AuthKind.KEY else AuthKind.PASSWORD,
                            keyId = (c.auth as? AuthMethod.Key)?.keyId,
                            hasSavedPassword = savedPw,
                            claudePath = c.claudePath.orEmpty(),
                            defaultCwd = c.defaultCwd.orEmpty(),
                            accent = c.accent,
                            advancedOpen = c.claudePath != null || c.defaultCwd != null,
                            dirty = false,
                        )
                    }
                }
            }
        } else {
            val existing = container.connections.connections.value.size
            _state.update {
                it.copy(
                    keyId = container.keys.keys.value.firstOrNull()?.id,
                    accent = existing % MachineAccents.size,
                )
            }
        }
    }

    // ───────── field edits ─────────

    private fun edit(invalidatesTest: Boolean = true, f: (EditorUi) -> EditorUi) {
        if (invalidatesTest) { testJob?.cancel(); testJob = null }
        _state.update { s -> f(s).copy(dirty = true).let { if (invalidatesTest) it.copy(test = TestUi()) else it } }
    }

    fun setName(v: String) = edit(invalidatesTest = false) { it.copy(name = v) }

    /**
     * Accepts plain hosts and pasted `user@host:port`, `ssh user@host -p 2222`, `[::1]:22`.
     * A multi-character insertion (a paste) is split immediately; typing stays raw until the field
     * loses focus ([commitHost]) — splitting mid-typing would cut "host:22" at "host:2".
     */
    fun setHost(v: String) {
        val old = _state.value.host
        val pasted = v.length - old.length > 1
        val parsed = if (pasted) parseAddress(v) else null
        if (parsed == null) edit { it.copy(host = v) } else applyParsed(parsed)
    }

    /** Called when the host field loses focus, and before test/save. */
    fun commitHost() {
        parseAddress(_state.value.host)?.let { applyParsed(it) }
    }

    private fun applyParsed(parsed: ParsedAddress) = edit { s ->
        s.copy(
            host = parsed.host,
            username = parsed.user ?: s.username,
            port = parsed.port?.toString() ?: s.port,
        )
    }

    fun setPort(v: String) = edit { it.copy(port = v.filter { c -> c.isDigit() }.take(5)) }
    fun setUsername(v: String) = edit { it.copy(username = v) }
    fun setAuth(kind: AuthKind) = edit { it.copy(auth = kind) }
    fun setPassword(v: String) = edit { it.copy(password = v) }
    fun selectKey(id: String) = edit { it.copy(keyId = id) }
    fun setClaudePath(v: String) = edit { it.copy(claudePath = v) }
    fun setDefaultCwd(v: String) = edit(invalidatesTest = false) { it.copy(defaultCwd = v) }
    fun setAccent(i: Int) = edit(invalidatesTest = false) { it.copy(accent = i) }
    fun toggleAdvanced() = _state.update { it.copy(advancedOpen = !it.advancedOpen) }

    // ───────── keys ─────────

    fun generateKey() {
        if (_state.value.generating) return
        viewModelScope.launch {
            _state.update { it.copy(generating = true) }
            try {
                val k = container.keys.generateEd25519(defaultKeyName())
                testJob?.cancel()
                _state.update {
                    it.copy(
                        generating = false, keyId = k.id, revealKeyId = k.id, auth = AuthKind.KEY,
                        keys = if (it.keys.any { e -> e.id == k.id }) it.keys else listOf(k) + it.keys,
                        dirty = true, test = TestUi(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(generating = false) }
                _messages.tryEmit("Couldn't generate a key: ${friendlyError(e)}")
            }
        }
    }

    fun openImport() = _state.update { it.copy(importOpen = true, importError = null) }
    fun closeImport() = _state.update { it.copy(importOpen = false, importError = null, importing = false) }

    fun importKey(name: String, text: String, passphrase: String?) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, importError = null) }
            try {
                val k = container.keys.import(name, text, passphrase)
                testJob?.cancel()
                _state.update {
                    it.copy(
                        importing = false, importOpen = false, keyId = k.id, revealKeyId = k.id, auth = AuthKind.KEY,
                        keys = if (it.keys.any { e -> e.id == k.id }) it.keys else listOf(k) + it.keys,
                        dirty = true, test = TestUi(),
                    )
                }
                _messages.tryEmit("Imported “${k.name}”")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importing = false, importError = friendlyError(e)) }
            }
        }
    }

    fun openInstall() = _state.update { it.copy(installOpen = true, installError = null) }
    fun closeInstall() = _state.update { it.copy(installOpen = false, installError = null, installing = false) }

    /** Signs in once with [password] and appends the selected key to authorized_keys, then switches auth to it. */
    fun installKey(password: String) {
        val s = _state.value
        val key = s.selectedKey ?: return
        if (s.installing || !s.canInstall) return
        viewModelScope.launch {
            _state.update { it.copy(installing = true, installError = null) }
            val conn = buildConnection(_state.value).copy(auth = AuthMethod.Password)
            val result = try {
                container.ssh.installPublicKey(conn, password, key.publicKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = {
                    testJob?.cancel()
                    _state.update {
                        it.copy(
                            installing = false, installOpen = false, auth = AuthKind.KEY, keyId = key.id,
                            dirty = true, test = TestUi(),
                        )
                    }
                    _messages.tryEmit("Key installed — ${_state.value.displayName} now signs in with “${key.name}”")
                },
                onFailure = { e -> _state.update { it.copy(installing = false, installError = friendlyError(e)) } },
            )
        }
    }

    // ───────── test & save ─────────

    fun test() {
        commitHost()
        val s = _state.value
        if (!s.canTest) return
        testJob?.cancel()
        testJob = viewModelScope.launch {
            val conn = buildConnection(s)
            val pw = s.password.takeIf { s.auth == AuthKind.PASSWORD && it.isNotEmpty() }
            runPacedTest(
                block = { onProgress -> container.ssh.test(conn, pw, onProgress) },
                onUpdate = { f -> _state.update { it.copy(test = f(it.test)) } },
            )
        }
    }

    fun save(onSaved: (String) -> Unit) {
        commitHost()
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val conn = buildConnection(s)
                val pw = s.password.takeIf { s.auth == AuthKind.PASSWORD && it.isNotEmpty() }
                val before = original
                container.connections.upsert(conn, pw)
                if (before != null && linkChanged(before, conn, pw != null)) {
                    runCatching { container.ssh.disconnect(conn.id) }
                }
                original = conn
                _state.update { it.copy(saving = false, dirty = false) }
                onSaved(conn.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false) }
                _messages.tryEmit("Couldn't save: ${friendlyError(e)}")
            }
        }
    }

    private fun linkChanged(a: Connection, b: Connection, newPassword: Boolean) =
        newPassword || a.host != b.host || a.port != b.port || a.username != b.username || a.auth != b.auth || a.claudePath != b.claudePath

    private fun buildConnection(s: EditorUi): Connection {
        val o = original
        val probe = s.test.probe?.takeIf { s.test.succeeded }
        val host = s.host.trim()
        return Connection(
            id = id,
            name = s.name.trim().ifEmpty { host },
            host = host,
            port = s.portNumber ?: 22,
            username = s.username.trim(),
            auth = if (s.auth == AuthKind.KEY && s.keyId != null) AuthMethod.Key(s.keyId) else AuthMethod.Password,
            claudePath = s.claudePath.trim().ifEmpty { null },
            accent = s.accent,
            defaultCwd = s.defaultCwd.trim().ifEmpty { null },
            createdAt = o?.createdAt ?: System.currentTimeMillis(),
            lastConnectedAt = if (probe != null) System.currentTimeMillis() else o?.lastConnectedAt,
            lastClaudeVersion = probe?.claudeVersion ?: o?.lastClaudeVersion,
            lastHostname = probe?.hostname ?: o?.lastHostname,
        )
    }

    override fun onCleared() {
        testJob?.cancel()
    }
}

internal data class ParsedAddress(val user: String?, val host: String, val port: Int?)

/**
 * Splits a pasted address. Returns null when [raw] is just a host (nothing to split), so typing
 * stays untouched. Handles `user@host`, `user@host:port`, `host:port`, `[v6]:port`,
 * `ssh -p 2222 user@host` and `ssh://user@host:port`.
 */
internal fun parseAddress(raw: String): ParsedAddress? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    var port: Int? = null
    if (s.startsWith("ssh://")) s = s.removePrefix("ssh://").trimEnd('/')
    if (s.startsWith("ssh ")) {
        val tokens = s.split(Regex("\\s+")).drop(1).toMutableList()
        val pIdx = tokens.indexOf("-p")
        if (pIdx >= 0 && pIdx + 1 < tokens.size) {
            port = tokens[pIdx + 1].toIntOrNull()
            tokens.removeAt(pIdx + 1); tokens.removeAt(pIdx)
        }
        s = tokens.firstOrNull { !it.startsWith("-") } ?: return null
    }
    if (s.any { it.isWhitespace() }) return null
    var user: String? = null
    val at = s.lastIndexOf('@')
    if (at > 0) {
        user = s.substring(0, at)
        s = s.substring(at + 1)
    }
    var host = s
    if (s.startsWith("[")) {
        val close = s.indexOf(']')
        if (close > 0) {
            host = s.substring(1, close)
            val rest = s.substring(close + 1)
            if (rest.startsWith(":")) rest.drop(1).toIntOrNull()?.let { port = it }
        }
    } else if (s.count { it == ':' } == 1) {
        val p = s.substringAfter(':').toIntOrNull()
        if (p != null) {
            host = s.substringBefore(':')
            port = p
        }
    }
    if (host.isEmpty()) return null
    if (user == null && port == null && host == raw.trim()) return null
    return ParsedAddress(user?.takeIf { it.isNotEmpty() }, host, port?.takeIf { it in 1..65535 })
}
