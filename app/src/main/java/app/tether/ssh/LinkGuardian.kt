package app.tether.ssh

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.tether.core.SshManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps SSH links honest across the phone's life. A backgrounded app gets frozen, NATs forget idle
 * flows and Wi-Fi ↔ mobile hand-offs kill every socket — yet a pooled client still *looks*
 * connected until keepalives time out (tens of seconds), and a quiet `tail` stream never notices.
 * So whenever the app returns to the foreground or the default network changes, every link is
 * probed at once and dead ones are replaced before the user sees a stale screen.
 */
object LinkGuardian {
    @Volatile private var installed = false
    private var pending: Job? = null

    fun install(app: Application, ssh: SshManager, scope: CoroutineScope) {
        if (installed) return
        installed = true

        fun revalidateSoon(delayMs: Long) {
            synchronized(this) {
                pending?.cancel()
                pending = scope.launch(Dispatchers.IO) {
                    if (delayMs > 0) delay(delayMs)
                    runCatching { ssh.revalidate() }
                }
            }
        }

        scope.launch {
            withContext(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = revalidateSoon(0)
                })
            }
        }

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null
                override fun onAvailable(network: Network) {
                    val previous = current
                    current = network
                    // First callback is just the network we already use; later ones are hand-offs.
                    if (previous != null && previous != network) revalidateSoon(300)
                }
                override fun onLost(network: Network) {
                    if (network == current) current = null
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // Regaining validated internet (captive portal cleared, signal back) — re-check.
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && network != current) {
                        current = network
                        revalidateSoon(300)
                    }
                }
            })
        }
    }
}
