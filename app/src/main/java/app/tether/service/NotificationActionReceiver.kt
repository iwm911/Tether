package app.tether.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import app.tether.TetherApp
import app.tether.core.AgentEvent
import app.tether.core.PermissionDecision
import app.tether.core.RunRef
import app.tether.core.SessionDecision
import app.tether.core.SessionEvent
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles Allow / Deny / "Tell Claude" straight from the approval notification, without opening
 * the app. The notification is dismissed immediately; if the decision can't be delivered it is
 * re-posted with an explanation so the request is never silently lost.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Notifications.ACTION_DISCONNECT_ALL) {
            val app = context.applicationContext as? app.tether.TetherApp ?: return
            ServiceController.disconnectAll(app, app.container)
            return
        }
        val connectionId = intent.getStringExtra(Notifications.EXTRA_CONNECTION_ID) ?: return
        intent.getStringExtra(Notifications.EXTRA_SESSION_ID)?.let { sid ->
            onSessionAnswer(context, intent, action, SessionRef(connectionId, sid))
            return
        }
        val runId = intent.getStringExtra(Notifications.EXTRA_RUN_ID) ?: return
        val requestId = intent.getStringExtra(Notifications.EXTRA_REQUEST_ID) ?: return
        val ref = RunRef(connectionId, runId)
        val notificationId = intent.getIntExtra(Notifications.EXTRA_NOTIFICATION_ID, Notifications.permissionId(ref, requestId))

        val decision: PermissionDecision = when (action) {
            Notifications.ACTION_ALLOW -> PermissionDecision.Allow()
            Notifications.ACTION_DENY -> PermissionDecision.Deny()
            Notifications.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(Notifications.KEY_REPLY_TEXT)?.toString()?.trim()
                if (text.isNullOrEmpty()) PermissionDecision.Deny() else PermissionDecision.Deny(message = text)
            }
            else -> return
        }

        val app = context.applicationContext as? TetherApp ?: return
        // Never act from the lock screen: an answer can make Claude run commands. Android 12+ already
        // asks for unlock on these buttons; this covers older versions and launchers that ignore it.
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) {
            Log.i(TAG, "Ignored $action for $requestId: device is locked")
            Notifications.showPermission(
                context.applicationContext,
                pendingEvent(intent, ref, requestId),
                app.container.connections.get(connectionId)?.name,
                reason = "Unlock your phone to answer.",
            )
            return
        }

        val container = app.container
        Notifications.cancel(context, notificationId)
        ServiceController.forgetPermission(notificationId)

        val pending = goAsync()
        val job = container.scope.launch {
            try {
                container.agents.respond(ref, requestId, decision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't deliver decision for $requestId", e)
                Notifications.showPermission(
                    context.applicationContext,
                    pendingEvent(intent, ref, requestId),
                    container.connections.get(connectionId)?.name,
                    reason = "Couldn't reach the machine to send your answer — try again or open Tether.",
                )
                ServiceController.rememberPermission(notificationId, ref, requestId)
            }
        }
        // goAsync() grants ~10 s; the decision keeps going in the app scope if it takes longer.
        container.scope.launch {
            try {
                withTimeoutOrNull(9_000) { job.join() }
            } finally {
                pending.finish()
            }
        }
    }

    /** Allow / Deny / "Tell Claude" on a session's permission notification (one-session model). */
    private fun onSessionAnswer(context: Context, intent: Intent, action: String, ref: SessionRef) {
        val (decision, message) = when (action) {
            Notifications.ACTION_ALLOW -> SessionDecision.ALLOW to null
            Notifications.ACTION_DENY -> SessionDecision.DENY to null
            Notifications.ACTION_REPLY -> SessionDecision.DENY to RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(Notifications.KEY_REPLY_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            else -> return
        }
        val app = context.applicationContext as? TetherApp ?: return
        val container = app.container
        val identity = intent.getStringExtra(Notifications.EXTRA_PENDING_ID).orEmpty()
        val notificationId = intent.getIntExtra(Notifications.EXTRA_NOTIFICATION_ID, SessionAlerts.needsYouId(ref, identity))
        val event = SessionEvent.NeedsYou(
            ref = ref,
            title = intent.getStringExtra(Notifications.EXTRA_TITLE) ?: "Session",
            pending = SessionPending.Permission(
                toolUseId = intent.getStringExtra(Notifications.EXTRA_TOOL_USE_ID).orEmpty(),
                toolName = intent.getStringExtra(Notifications.EXTRA_TOOL) ?: "Tool",
                summary = intent.getStringExtra(Notifications.EXTRA_SUMMARY).orEmpty(),
            ),
            waitingFor = null,
        )
        val machine = container.connections.get(ref.connectionId)?.name
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) {
            Log.i(TAG, "Ignored $action for session ${ref.short}: device is locked")
            Notifications.showSessionNeedsYou(context.applicationContext, event, machine, reason = "Unlock your phone to answer.")
            return
        }
        Notifications.cancel(context, notificationId)
        ServiceController.forgetSessionNotification(notificationId)
        // `answer` answers whatever is open now: never let a stale notification answer a newer prompt.
        if (!SessionAlerts.stillWaiting(container.sessions.sessions.value, ref, identity)) {
            Log.i(TAG, "Session ${ref.short} is no longer waiting on that prompt; not answering")
            return
        }
        val pending = goAsync()
        val job = container.scope.launch {
            try {
                container.sessions.answer(ref, decision, message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't deliver decision for session ${ref.short}", e)
                Notifications.showSessionNeedsYou(
                    context.applicationContext, event, machine,
                    reason = "Couldn't reach the machine to send your answer — try again or open Tether.",
                )
                ServiceController.rememberSessionNotification(notificationId, ref, identity)
            }
        }
        container.scope.launch {
            try {
                withTimeoutOrNull(9_000) { job.join() }
            } finally {
                pending.finish()
            }
        }
    }

    /** The request as carried by the notification's intent, for re-posting it. */
    private fun pendingEvent(intent: Intent, ref: RunRef, requestId: String) = AgentEvent.PermissionRequested(
        ref = ref,
        title = intent.getStringExtra(Notifications.EXTRA_TITLE) ?: "Agent",
        requestId = requestId,
        toolName = intent.getStringExtra(Notifications.EXTRA_TOOL) ?: "Tool",
        summary = intent.getStringExtra(Notifications.EXTRA_SUMMARY) ?: "",
    )

    private companion object {
        const val TAG = "TetherNotifAction"
    }
}
