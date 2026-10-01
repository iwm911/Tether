package app.tether.service

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.tether.AppContainer
import app.tether.core.SessionEvent
import app.tether.core.SessionRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires the background behaviour:
 *  - runs [AgentWatchService] while `backgroundWatch` is on and any session is live. Android 12+
 *    forbids starting a foreground service from the background, so it is started while the app
 *    is still in the foreground and kept until no live sessions remain;
 *  - turns [app.tether.core.SessionHub.events] into notifications while the app isn't visible;
 *  - clears needs-you notifications once the prompt has been answered elsewhere.
 */
object ServiceController {
    private const val TAG = "TetherService"
    private val installed = AtomicBoolean(false)

    @OptIn(FlowPreview::class)
    fun install(app: Application, container: AppContainer) {
        if (!installed.compareAndSet(false, true)) return
        Notifications.ensureChannels(app)
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val foreground = lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged()

        // 0 ── Opening the app lifts a "Disconnect" from the notification.
        container.scope.launch(Dispatchers.Main.immediate) {
            foreground.collect {
                if (it && suspended.value) {
                    suspended.value = false
                    container.sessions.setPaused(false)
                }
            }
        }

        // 1 ── Foreground service lifetime: live agents (background watch) OR open SSH sessions (keep-alive).
        container.scope.launch(Dispatchers.Main.immediate) {
            val sessionsOpen = container.ssh.states.map { it.isNotEmpty() }.distinctUntilChanged()
            val agentsLive = container.sessions.sessions.map { SessionAlerts.live(it).isNotEmpty() }
            val wants = combine(
                agentsLive.distinctUntilChanged(),
                container.settings.settings.map { it.backgroundWatch to it.keepConnectionsAlive }.distinctUntilChanged(),
                sessionsOpen,
                suspended,
            ) { anyLive, (watch, keepAlive), open, off -> !off && ((anyLive && watch) || (keepAlive && open)) }
            combine(wants, foreground, AgentWatchService.running) { should, isForeground, running -> Decision(should, isForeground, running) }
                .distinctUntilChanged()
                // Absorb momentary flaps (e.g. a session passing WORKING → IDLE → WORKING).
                .debounce(350)
                .collect { d ->
                    when {
                        d.shouldRun && !d.running && d.foreground -> start(app)
                        !d.shouldRun && d.running -> stop(app)
                    }
                }
        }

        // 2 ── Session events, only while no Tether screen is visible: needs-you, turn done, failed — keyed on session id.
        container.scope.launch {
            container.sessions.events.collect { event ->
                try {
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@collect
                    val settings = container.settings.settings.value
                    val machine = container.connections.get(event.ref.connectionId)?.name
                    when (event) {
                        is SessionEvent.NeedsYou -> if (settings.notifyPermissions) {
                            Notifications.showSessionNeedsYou(app, event, machine)
                            val identity = SessionAlerts.identityOf(event.pending, event.waitingFor)
                            rememberSessionNotification(SessionAlerts.needsYouId(event.ref, identity), event.ref, identity)
                        }
                        is SessionEvent.TurnDone -> if (settings.notifyCompletion) Notifications.showSessionTurnDone(app, event, machine)
                        is SessionEvent.Failed -> Notifications.showSessionFailed(app, event, machine)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't post notification for ${event::class.simpleName} on session ${event.ref.short}", e)
                }
            }
        }

        // 3 ── Dismiss session needs-you notifications once that prompt is answered (anywhere) or the session is gone.
        container.scope.launch {
            container.sessions.sessions.collect { list ->
                if (postedSessions.isEmpty()) return@collect
                val now = System.currentTimeMillis()
                for ((id, p) in postedSessions.entries.toList()) {
                    if (now - p.at < 5_000) continue
                    if (!SessionAlerts.stillWaiting(list, p.ref, p.identity, unknownDefault = false)) {
                        Notifications.cancel(app, id)
                        postedSessions.remove(id)
                    }
                }
            }
        }
    }

    private data class Decision(val shouldRun: Boolean, val foreground: Boolean, val running: Boolean)

    /** Set by the notification's Disconnect; cleared when the user opens the app again. */
    val suspended = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Notification "Disconnect": close every SSH session and stop keeping them alive. */
    fun disconnectAll(app: Application, container: AppContainer) {
        suspended.value = true
        container.sessions.setPaused(true)         // cancel every watch stream first…
        container.sessions.setBackgroundWatch(false)
        stop(app)
        container.scope.launch {
            kotlinx.coroutines.delay(300)             // …so nothing reconnects behind our back
            runCatching { container.ssh.disconnectAll() }
        }
    }

    private class PostedSession(val ref: SessionRef, val identity: String, val at: Long)

    /** Session needs-you notifications currently shown, by notification id. */
    private val postedSessions = ConcurrentHashMap<Int, PostedSession>()

    internal fun rememberSessionNotification(notificationId: Int, ref: SessionRef, identity: String) {
        postedSessions[notificationId] = PostedSession(ref, identity, System.currentTimeMillis())
    }

    internal fun forgetSessionNotification(notificationId: Int) {
        postedSessions.remove(notificationId)
    }

    private fun start(app: Application) {
        try {
            ContextCompat.startForegroundService(app, Intent(app, AgentWatchService::class.java))
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+) if we raced into the background;
            // we'll try again the next time the app comes to the foreground.
            Log.w(TAG, "Background watch couldn't start", e)
        }
    }

    private fun stop(app: Application) {
        try {
            app.stopService(Intent(app, AgentWatchService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Background watch couldn't stop", e)
        }
    }
}
