package app.tether.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import app.tether.MainActivity
import app.tether.R
import app.tether.core.AgentEvent
import app.tether.core.AgentSummary
import app.tether.core.RunRef
import app.tether.core.RunStatus

/** Channels, ids and builders for every notification Tether posts. */
object Notifications {
    const val CHANNEL_APPROVALS = "approvals"
    const val CHANNEL_UPDATES = "updates"
    const val CHANNEL_WATCH = "watch"

    const val WATCH_NOTIFICATION_ID = 0x7E7E0001

    const val ACTION_ALLOW = "app.tether.action.ALLOW"
    const val ACTION_DISCONNECT_ALL = "app.tether.action.DISCONNECT_ALL"
    const val ACTION_DENY = "app.tether.action.DENY"
    const val ACTION_REPLY = "app.tether.action.DENY_WITH_MESSAGE"

    const val EXTRA_CONNECTION_ID = "connectionId"
    const val EXTRA_RUN_ID = "runId"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TOOL = "toolName"
    const val EXTRA_SUMMARY = "summary"
    const val KEY_REPLY_TEXT = "replyText"

    private const val ACCENT = 0xFFD97757.toInt()
    private const val GROUP_APPROVALS = "app.tether.group.APPROVALS"
    private const val GROUP_UPDATES = "app.tether.group.UPDATES"

    private val LIVE = setOf(RunStatus.STARTING, RunStatus.WORKING, RunStatus.AWAITING_PERMISSION)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val approvals = NotificationChannel(CHANNEL_APPROVALS, "Needs approval", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "An agent is waiting for you to allow or deny an action"
            enableVibration(true)
            enableLights(true)
            lightColor = ACCENT
            setShowBadge(true)
        }
        val updates = NotificationChannel(CHANNEL_UPDATES, "Agent updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Turns finished and agents that stopped"
            setShowBadge(true)
        }
        val watch = NotificationChannel(CHANNEL_WATCH, "Background watch", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Tether keeps an eye on running agents"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannels(listOf(approvals, updates, watch))
    }

    /** POST_NOTIFICATIONS granted (33+) and notifications not blocked for the app. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun permissionId(ref: RunRef, requestId: String): Int = "perm:${ref.connectionId}/${ref.runId}/$requestId".hashCode()

    fun updateId(ref: RunRef): Int = "upd:${ref.connectionId}/${ref.runId}".hashCode()

    // ───────────────────────────── ongoing watch ─────────────────────────────

    /** The foreground-service notification: "2 agents working · 1 needs you". */
    fun buildWatch(
        context: Context,
        agents: List<AgentSummary>,
        links: Map<String, app.tether.core.LinkState> = emptyMap(),
        machines: List<app.tether.core.Connection> = emptyList(),
    ): Notification {
        val live = agents.filter { it.run.status in LIVE }
        val needs = live.count { it.run.status == RunStatus.AWAITING_PERMISSION }
        val working = live.size - needs
        val summary = watchSummary(working, needs)
        val names = live.sortedByDescending { it.run.status == RunStatus.AWAITING_PERMISSION }.map { agentTitle(it) }
        val single = live.singleOrNull()

        // Termius-style session line: "Connected to Workstation" / "Reconnecting to Workstation".
        val names2 = machines.associate { it.id to it.name }
        val connected = links.filterValues { it is app.tether.core.LinkState.Connected }.keys.mapNotNull { names2[it] }
        val retrying = links.filterValues { it !is app.tether.core.LinkState.Connected }.keys.mapNotNull { names2[it] }
        val sessionLine = when {
            connected.isNotEmpty() && retrying.isEmpty() -> "Connected to " + connected.joinToString(", ")
            connected.isNotEmpty() -> "Connected to " + connected.joinToString(", ") + " · reconnecting " + retrying.joinToString(", ")
            retrying.isNotEmpty() -> "Reconnecting to " + retrying.joinToString(", ")
            else -> null
        }
        val title = if (live.isEmpty()) sessionLine ?: summary else summary
        val text = when {
            names.isNotEmpty() -> names.joinToString(" · ")
            live.isEmpty() && sessionLine != null -> "Sessions stay open in the background"
            else -> "Tether keeps watching while your agents run"
        }
        val disconnect = PendingIntent.getBroadcast(
            context, 7001,
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_DISCONNECT_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (live.isNotEmpty()) sessionLine else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context, single?.ref, WATCH_NOTIFICATION_ID))
        if (links.isNotEmpty()) builder.addAction(0, "Disconnect", disconnect)

        if (live.size > 1) {
            val inbox = NotificationCompat.InboxStyle().setBigContentTitle(summary)
            live.sortedByDescending { it.run.status == RunStatus.AWAITING_PERMISSION }.take(6).forEach { a ->
                inbox.addLine("${agentTitle(a)} — ${statusLabel(a.run.status)} · ${a.connection.name}")
            }
            if (live.size > 6) inbox.setSummaryText("+${live.size - 6} more")
            builder.setStyle(inbox)
        } else if (single != null) {
            val detail = single.run.pending?.let { "Wants to use ${it.toolName}: ${it.summary}" }
                ?: single.run.lastText?.takeIf { it.isNotBlank() }
                ?: "${statusLabel(single.run.status)} on ${single.connection.name}"
            builder.setContentText("${agentTitle(single)} · ${single.connection.name}")
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("${agentTitle(single)} · ${single.connection.name}\n$detail"))
        }
        return builder.build()
    }

    fun watchSummary(working: Int, needs: Int): String {
        val parts = buildList {
            if (working > 0) add(if (working == 1) "1 agent working" else "$working agents working")
            if (needs > 0) add(if (needs == 1) "1 needs you" else "$needs need you")
        }
        return if (parts.isEmpty()) "Watching your agents" else parts.joinToString(" · ")
    }

    // ───────────────────────────── events ─────────────────────────────

    @SuppressLint("MissingPermission")
    fun showPermission(context: Context, event: AgentEvent.PermissionRequested, machineName: String?, reason: String? = null) {
        if (!canPost(context)) return
        val id = permissionId(event.ref, event.requestId)
        val text = if (event.toolName == app.tether.core.ASK_USER_QUESTION) event.summary else "${event.toolName}: ${event.summary}"
        fun action(action: String, code: Int): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("tether://permission/${Uri.encode(event.ref.connectionId)}/${Uri.encode(event.ref.runId)}/${Uri.encode(event.requestId)}/$code")
                putExtra(EXTRA_CONNECTION_ID, event.ref.connectionId)
                putExtra(EXTRA_RUN_ID, event.ref.runId)
                putExtra(EXTRA_REQUEST_ID, event.requestId)
                putExtra(EXTRA_NOTIFICATION_ID, id)
                putExtra(EXTRA_TITLE, event.title)
                putExtra(EXTRA_TOOL, event.toolName)
                putExtra(EXTRA_SUMMARY, event.summary)
            }
            val mutability = if (action == ACTION_REPLY && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, id + code, intent, mutability or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val reply = NotificationCompat.Action.Builder(0, "Tell Claude", action(ACTION_REPLY, 3))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("What should Claude do instead?").build())
            .setAllowGeneratedReplies(false)
            .build()

        val question = event.toolName == app.tether.core.ASK_USER_QUESTION
        val builder = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle(if (question) "${event.title} has a question" else "${event.title} needs approval")
            .setContentText(text)
            .setSubText(machineName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (reason != null) "$text\n\n$reason" else text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setGroup(GROUP_APPROVALS)
            .setContentIntent(openApp(context, event.ref, id))
        // A question needs the picker: tapping opens it; Allow/Deny would answer nothing.
        if (question) builder.addAction(0, "Answer", openApp(context, event.ref, id))
        else builder.addAction(0, "Allow", action(ACTION_ALLOW, 1)).addAction(0, "Deny", action(ACTION_DENY, 2)).addAction(reply)
        val notification = builder.build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    @SuppressLint("MissingPermission")
    fun showTurnCompleted(context: Context, event: AgentEvent.TurnCompleted, machineName: String?) {
        if (!canPost(context)) return
        val id = updateId(event.ref)
        val title = if (event.success) "✓ ${event.title}" else "${event.title} hit an error"
        val text = event.snippet?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (event.success) "Finished — your turn" else "The turn ended with an error"
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(machineName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (event.success) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setGroup(GROUP_UPDATES)
            .setContentIntent(openApp(context, event.ref, id))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    @SuppressLint("MissingPermission")
    fun showEnded(context: Context, event: AgentEvent.Ended, machineName: String?) {
        val error = event.error ?: return
        if (!canPost(context)) return
        val id = updateId(event.ref)
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle("${event.title} stopped")
            .setContentText(error)
            .setSubText(machineName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setGroup(GROUP_UPDATES)
            .setContentIntent(openApp(context, event.ref, id))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Opens MainActivity, deep-linking to [ref] when given. */
    fun openApp(context: Context, ref: RunRef?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (ref != null) {
                data = Uri.parse("tether://agent/${Uri.encode(ref.connectionId)}/${Uri.encode(ref.runId)}")
                putExtra(MainActivity.EXTRA_CONNECTION_ID, ref.connectionId)
                putExtra(MainActivity.EXTRA_RUN_ID, ref.runId)
            }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun agentTitle(a: AgentSummary): String =
        a.run.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: a.run.cwd.trimEnd('/').substringAfterLast('/').ifEmpty { a.run.cwd.ifEmpty { "Agent" } }

    private fun statusLabel(s: RunStatus): String = when (s) {
        RunStatus.STARTING -> "Starting"
        RunStatus.WORKING -> "Working"
        RunStatus.AWAITING_PERMISSION -> "Needs you"
        RunStatus.IDLE -> "Idle"
        RunStatus.ENDED -> "Ended"
        RunStatus.FAILED -> "Failed"
    }
}
