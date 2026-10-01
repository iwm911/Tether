package app.tether

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import app.tether.analytics.Analytics
import app.tether.analytics.TrackedAgentHub
import app.tether.core.AgentHub
import app.tether.core.ClaudeRemote
import app.tether.core.ConnectionRepository
import app.tether.core.HostKeyPromptBus
import app.tether.core.KeyRepository
import app.tether.core.KnownHostsStore
import app.tether.core.SecretStore
import app.tether.core.SessionHub
import app.tether.core.SettingsRepository
import app.tether.core.SshManager
import app.tether.data.DataStoreSettingsRepository
import app.tether.data.FileConnectionRepository
import app.tether.data.FileKnownHostsStore
import app.tether.data.KeystoreSecretStore
import app.tether.data.SecureKeyRepository
import app.tether.remote.DefaultAgentHub
import app.tether.remote.DefaultSessionHub
import app.tether.remote.HelperClaudeRemote
import app.tether.ssh.DefaultHostKeyPromptBus
import app.tether.ssh.SshjManager
import app.tether.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI. One instance per process, created in [TetherApp.onCreate]. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val secrets: SecretStore = KeystoreSecretStore(app)
    val settings: SettingsRepository = DataStoreSettingsRepository(app, scope)
    val connections: ConnectionRepository = FileConnectionRepository(app, secrets)
    val keys: KeyRepository = SecureKeyRepository(app, secrets)
    val knownHosts: KnownHostsStore = FileKnownHostsStore(app)
    val hostKeyPrompts: HostKeyPromptBus = DefaultHostKeyPromptBus()
    val ssh: SshManager = SshjManager(app, connections, keys, secrets, knownHosts, hostKeyPrompts, scope)
    private val helperRemote = HelperClaudeRemote(app, ssh, connections, scope)
    val remote: ClaudeRemote = helperRemote
    val analytics = Analytics(settings, scope)
    val agents: AgentHub = TrackedAgentHub(DefaultAgentHub(remote, ssh, connections, settings, scope), analytics)
    /** One-session model (Claude Code daemon); replaces [agents] once the UI has moved over (phase R). */
    val sessions: SessionHub = DefaultSessionHub(helperRemote, ssh, connections, scope)
    val updates = UpdateManager(app, settings, scope)
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
