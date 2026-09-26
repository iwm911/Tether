package app.tether.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import app.tether.TetherApp
import app.tether.core.AgentEvent
import app.tether.core.PermissionDecision
import app.tether.core.RunRef
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
                val event = AgentEvent.PermissionRequested(
                    ref = ref,
                    title = intent.getStringExtra(Notifications.EXTRA_TITLE) ?: "Agent",
                    requestId = requestId,
                    toolName = intent.getStringExtra(Notifications.EXTRA_TOOL) ?: "Tool",
                    summary = intent.getStringExtra(Notifications.EXTRA_SUMMARY) ?: "",
                )
                Notifications.showPermission(
                    context.applicationContext,
                    event,
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

    private companion object {
        const val TAG = "TetherNotifAction"
    }
}
