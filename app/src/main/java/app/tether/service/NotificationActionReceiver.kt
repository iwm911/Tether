package app.tether.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import app.tether.TetherApp
import app.tether.core.SessionDecision
import app.tether.core.SessionErrorCodes
import app.tether.core.SessionEvent
import app.tether.core.SessionPending
import app.tether.core.SessionRef
import app.tether.remote.RemoteException
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
        val sid = intent.getStringExtra(Notifications.EXTRA_SESSION_ID) ?: return
        onSessionAnswer(context, intent, action, SessionRef(connectionId, sid))
    }

    /** Allow / Deny / "Tell Claude" on a session's permission notification. */
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
                inputJson = intent.getStringExtra(Notifications.EXTRA_INPUT) ?: "{}",
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
        // Never let a stale notification answer a newer prompt. With the prompt's toolUseId the helper checks it
        // on the machine (ESTALE when another prompt is open); without one, only a session this process knows to
        // still be waiting on that prompt is answered (after a cold start the list is empty: not answered).
        val toolUseId = (event.pending as SessionPending.Permission).toolUseId.ifBlank { null }
        if (!SessionAlerts.stillWaiting(container.sessions.sessions.value, ref, identity, unknownDefault = toolUseId != null)) {
            Log.i(TAG, "Session ${ref.short} is no longer waiting on that prompt; not answering")
            return
        }
        val pending = goAsync()
        val job = container.scope.launch {
            try {
                container.sessions.answer(ref, decision, message, toolUseId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException) {
                if (e.code != SessionErrorCodes.ESTALE) {
                    Log.w(TAG, "Couldn't deliver decision for session ${ref.short}", e)
                    Notifications.showSessionNeedsYou(
                        context.applicationContext, event, machine,
                        reason = "Couldn't reach the machine to send your answer — try again or open Tether.",
                    )
                    ServiceController.rememberSessionNotification(notificationId, ref, identity)
                } else {
                    Log.i(TAG, "Session ${ref.short} moved on to another prompt; not answering")
                }
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

    private companion object {
        const val TAG = "TetherNotifAction"
    }
}
