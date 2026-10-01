package app.tether.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.tether.AppContainer
import app.tether.TetherApp
import app.tether.core.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that keeps the per-machine watch streams open while agents
 * are live and the app is in the background, with an ongoing low-importance summary
 * notification ("2 agents working · 1 needs you"). Started and stopped by [ServiceController].
 */
class AgentWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var container: AppContainer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        val c = (application as TetherApp).container
        container = c
        val initial = Notifications.buildWatch(this, c.agents.agents.value, c.ssh.states.value, c.connections.connections.value, c.sessions.sessions.value)
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.WATCH_NOTIFICATION_ID,
                initial,
                when {
                    // Android 14+: "special use" has no daily cap (dataSync is limited to 6 h/24 h on 15+).
                    Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    else -> 0
                },
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException / time limit exhausted: give up quietly.
            Log.w(TAG, "Couldn't enter the foreground", e)
            stopSelf()
            return
        }
        runningState.value = true
        c.agents.setBackgroundWatch(true)
        c.sessions.setBackgroundWatch(true)
        watchSessions(c)

        // Keep-alive: hold the CPU and Wi-Fi awake while sessions are open, so keepalives go out and
        // replies arrive with the screen off (what terminal apps like Termius do).
        scope.launch {
            combine(c.settings.settings, c.ssh.states) { s: app.tether.core.AppSettings, st: Map<String, app.tether.core.LinkState> ->
                s.keepConnectionsAlive && st.isNotEmpty()
            }.distinctUntilChanged()
                .collect { hold -> if (hold) acquireLocks() else releaseLocks() }
        }
        // Redraw when connections change (connected / reconnecting / machine added).
        scope.launch {
            combine(c.ssh.states, c.connections.connections) { st: Map<String, app.tether.core.LinkState>, conns: List<app.tether.core.Connection> ->
                st.mapValues { it.value::class.simpleName } to conns.size
            }.distinctUntilChanged().collect { redraw(c) }
        }

        scope.launch {
            c.agents.agents
                // Only redraw when something visible changes (not on every streamed token).
                .distinctUntilChangedBy { list ->
                    list.filter { it.run.displayStatus in LIVE && !it.run.terminal }.map { Triple(it.ref, it.run.displayStatus, it.run.title ?: it.run.cwd) } to
                        list.firstOrNull { it.run.displayStatus in LIVE && !it.run.terminal }?.let { it.run.pending?.requestId ?: it.run.lastText?.take(80) }
                }
                .collect { redraw(c) }
        }
    }

    private fun watchSessions(c: AppContainer) {
        scope.launch {
            c.sessions.sessions
                // Redraw on what the notification shows: which sessions are live, their state and prompt.
                .distinctUntilChangedBy { list ->
                    app.tether.service.SessionAlerts.live(list).map { s ->
                        listOf(s.connectionId, s.sessionId, s.state, s.title, s.pending?.let { p -> SessionAlerts.identityOf(p, s.waitingFor) }, s.lastText?.take(80))
                    }
                }
                .collect { redraw(c) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun redraw(c: AppContainer) {
        if (Notifications.canPost(this)) {
            NotificationManagerCompat.from(this).notify(
                Notifications.WATCH_NOTIFICATION_ID,
                Notifications.buildWatch(this, c.agents.agents.value, c.ssh.states.value, c.connections.connections.value, c.sessions.sessions.value),
            )
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Tether:keepalive").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm?.createWifiLock(mode, "Tether:keepalive")?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Android 15 caps dataSync services at 6 h per day; leave gracefully when told to. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.i(TAG, "Foreground time limit reached; stopping background watch")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        if (runningState.value) {
            container?.agents?.setBackgroundWatch(false)
            container?.sessions?.setBackgroundWatch(false)
            runningState.value = false
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TetherWatch"
        private val LIVE = setOf(RunStatus.STARTING, RunStatus.WORKING, RunStatus.AWAITING_PERMISSION)
        private val runningState = MutableStateFlow(false)

        /** True between a successful startForeground and onDestroy. */
        val running: StateFlow<Boolean> = runningState.asStateFlow()
    }
}
