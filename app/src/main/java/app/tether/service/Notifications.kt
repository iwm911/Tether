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
import app.tether.TetherApp
import app.tether.core.Session
import app.tether.core.SessionEvent
import app.tether.core.SEND_MESSAGE
import app.tether.core.SessionPending
import app.tether.core.SessionRef

/** Channels, ids and builders for every notification Tether posts. */
object Notifications {
    const val CHANNEL_APPROVALS = "approvals"
    const val CHANNEL_UPDATES = "updates"
    const val CHANNEL_WATCH = "watch"
    const val CHANNEL_APP_UPDATES = "app_updates"

    const val WATCH_NOTIFICATION_ID = 0x7E7E0001
    const val APP_UPDATE_NOTIFICATION_ID = 0x7E7E0002

    const val ACTION_ALLOW = "app.tether.action.ALLOW"
    const val ACTION_DISCONNECT_ALL = "app.tether.action.DISCONNECT_ALL"
    const val ACTION_DENY = "app.tether.action.DENY"
    const val ACTION_REPLY = "app.tether.action.DENY_WITH_MESSAGE"

    const val EXTRA_CONNECTION_ID = "connectionId"
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TOOL = "toolName"
    const val EXTRA_SUMMARY = "summary"
    const val KEY_REPLY_TEXT = "replyText"

    private const val ACCENT = 0xFFD97757.toInt()
    private const val GROUP_APPROVALS = "app.tether.group.APPROVALS"
    private const val GROUP_UPDATES = "app.tether.group.UPDATES"

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
        val appUpdates = NotificationChannel(CHANNEL_APP_UPDATES, "New Tether versions", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A new version of Tether is ready to install"
            setShowBadge(true)
        }
        nm.createNotificationChannels(listOf(approvals, updates, watch, appUpdates))
    }

    /** POST_NOTIFICATIONS granted (33+) and notifications not blocked for the app. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Settings › App lock is on: notifications must not reveal agent content or act without auth. */
    fun appLockOn(context: Context): Boolean =
        (context.applicationContext as? TetherApp)?.container?.settings?.settings?.value?.biometricLock == true

    /**
     * The lock screen only shows [publicTitle], never commands, paths or replies. Approvals are always
     * guarded ([always]); other notifications only while app lock is on.
     */
    private fun NotificationCompat.Builder.guard(context: Context, channel: String, publicTitle: String, always: Boolean = false): NotificationCompat.Builder {
        if (!always && !appLockOn(context)) return this
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle(publicTitle)
            .setContentText("Unlock to see details")
            .build()
        return setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
    }

    // ───────────────────────────── ongoing watch ─────────────────────────────

    /** The foreground-service notification: "2 agents working · 1 needs you". */
    fun buildWatch(
        context: Context,
        sessions: List<Session>,
        links: Map<String, app.tether.core.LinkState> = emptyMap(),
        machines: List<app.tether.core.Connection> = emptyList(),
    ): Notification {
        val liveSessions = SessionAlerts.live(sessions).sortedByDescending { it.needsYou }
        val (working, needs) = SessionAlerts.watchCounts(sessions)
        val summary = watchSummary(working, needs)
        val names = liveSessions.map { it.title }
        val singleSession = liveSessions.singleOrNull()

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
        val anyLive = liveSessions.isNotEmpty()
        val title = if (!anyLive) sessionLine ?: summary else summary
        val text = when {
            names.isNotEmpty() -> names.joinToString(" · ")
            !anyLive && sessionLine != null -> "Sessions stay open in the background"
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
            .setSubText(if (anyLive) sessionLine else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                if (singleSession != null) openSession(context, singleSession.ref, WATCH_NOTIFICATION_ID)
                else openApp(context, WATCH_NOTIFICATION_ID),
            )
            .guard(context, CHANNEL_WATCH, "Tether")
        if (links.isNotEmpty()) builder.addAction(0, "Disconnect", disconnect)

        val machineNames = machines.associate { it.id to it.name }
        val total = liveSessions.size
        if (total > 1) {
            val inbox = NotificationCompat.InboxStyle().setBigContentTitle(summary)
            val lines = liveSessions.map { s ->
                "${s.title} — ${if (s.needsYou) "Needs you" else "Working"}" + (machineNames[s.connectionId]?.let { " · $it" } ?: "")
            }
            lines.take(6).forEach { inbox.addLine(it) }
            if (total > 6) inbox.setSummaryText("+${total - 6} more")
            builder.setStyle(inbox)
        } else if (singleSession != null) {
            val machine = machineNames[singleSession.connectionId]
            val head = singleSession.title + (machine?.let { " · $it" } ?: "")
            val detail = if (singleSession.needsYou) SessionAlerts.needsYouText(singleSession.pending, singleSession.waitingFor)
            else singleSession.lastText?.takeIf { it.isNotBlank() } ?: "Working"
            builder.setContentText(head)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$head\n$detail"))
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

    // ───────────────────────────── session events ─────────────────────────────

    const val EXTRA_SESSION_ID = "sessionId"
    /** Identity of the prompt a session notification was posted for (see [SessionAlerts.identityOf]). */
    const val EXTRA_PENDING_ID = "pendingIdentity"
    const val EXTRA_TOOL_USE_ID = "toolUseId"
    /** A SendMessage prompt's tool input, so a re-posted notification still shows the message. */
    const val EXTRA_INPUT = "toolInput"

    /**
     * A session started waiting for the user: a tool permission (Allow / Deny / Tell Claude inline),
     * a question or a dialog (both open the session, where the picker / dialog panel answers it).
     */
    @SuppressLint("MissingPermission")
    fun showSessionNeedsYou(context: Context, event: SessionEvent.NeedsYou, machineName: String?, reason: String? = null) {
        if (!canPost(context)) return
        val identity = SessionAlerts.identityOf(event.pending, event.waitingFor)
        val id = SessionAlerts.needsYouId(event.ref, identity)
        val text = SessionAlerts.needsYouText(event.pending, event.waitingFor)
        val permission = event.pending as? SessionPending.Permission
        fun action(action: String, code: Int): PendingIntent {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("tether://session-answer/${Uri.encode(event.ref.connectionId)}/${Uri.encode(event.ref.sessionId)}/${Uri.encode(identity)}/$code")
                putExtra(EXTRA_CONNECTION_ID, event.ref.connectionId)
                putExtra(EXTRA_SESSION_ID, event.ref.sessionId)
                putExtra(EXTRA_PENDING_ID, identity)
                putExtra(EXTRA_TOOL_USE_ID, permission?.toolUseId ?: "")
                putExtra(EXTRA_NOTIFICATION_ID, id)
                putExtra(EXTRA_TITLE, event.title)
                putExtra(EXTRA_TOOL, permission?.toolName ?: "")
                putExtra(EXTRA_SUMMARY, permission?.summary ?: "")
                if (permission?.toolName == SEND_MESSAGE) putExtra(EXTRA_INPUT, permission.inputJson)
            }
            val mutability = if (action == ACTION_REPLY && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, id + code, intent, mutability or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        fun button(label: String, intent: PendingIntent) =
            NotificationCompat.Action.Builder(0, label, intent).setAuthenticationRequired(true)

        val open = openSession(context, event.ref, id)
        val builder = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle(SessionAlerts.needsYouTitle(event.title, event.pending))
            .setContentText(text)
            .setSubText(machineName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (reason != null) "$text\n\n$reason" else text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setGroup(GROUP_APPROVALS)
            .setContentIntent(open)
            .guard(context, CHANNEL_APPROVALS, "A session needs you", always = true)
        // An answer can make Claude run commands on the user's machine, so it always needs the phone
        // unlocked: Android 12+ enforces that on the buttons, and the receiver refuses answers from the
        // keyguard on every version. With app lock on, older versions get no inline actions at all, so
        // the only way in is the app, behind its own lock.
        val inlineActions = SessionAlerts.answerableInline(event.pending) && (!appLockOn(context) || Build.VERSION.SDK_INT >= 31)
        if (inlineActions) {
            val reply = button("Tell Claude", action(ACTION_REPLY, 3))
                .addRemoteInput(RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("What should Claude do instead?").build())
                .setAllowGeneratedReplies(false)
                .build()
            val (allow, deny) = SessionAlerts.answerLabels(event.pending)
            builder.addAction(button(allow, action(ACTION_ALLOW, 1)).build())
                .addAction(button(deny, action(ACTION_DENY, 2)).build())
                .addAction(reply)
        } else {
            builder.addAction(0, if (event.pending is SessionPending.Permission) "Review" else "Answer", open)
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    @SuppressLint("MissingPermission")
    fun showSessionTurnDone(context: Context, event: SessionEvent.TurnDone, machineName: String?) {
        if (!canPost(context)) return
        val id = SessionAlerts.updateId(event.ref)
        val text = event.snippet?.trim()?.takeIf { it.isNotEmpty() } ?: "Finished — your turn"
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle("✓ ${event.title}")
            .setContentText(text)
            .setSubText(machineName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setGroup(GROUP_UPDATES)
            .setContentIntent(openSession(context, event.ref, id))
            .guard(context, CHANNEL_UPDATES, "A session finished")
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    @SuppressLint("MissingPermission")
    fun showSessionFailed(context: Context, event: SessionEvent.Failed, machineName: String?) {
        if (!canPost(context)) return
        val id = SessionAlerts.updateId(event.ref)
        val text = event.detail?.trim()?.takeIf { it.isNotEmpty() } ?: "The session stopped with an error"
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle("${event.title} hit an error")
            .setContentText(text)
            .setSubText(machineName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setGroup(GROUP_UPDATES)
            .setContentIntent(openSession(context, event.ref, id))
            .guard(context, CHANNEL_UPDATES, "A session hit an error")
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    /** Opens MainActivity on session [ref] (see [MainActivity.pendingSession]). */
    fun openSession(context: Context, ref: SessionRef, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("tether://session/${Uri.encode(ref.connectionId)}/${Uri.encode(ref.sessionId)}")
            putExtra(MainActivity.EXTRA_CONNECTION_ID, ref.connectionId)
            putExtra(MainActivity.EXTRA_SESSION_ID, ref.sessionId)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * A new Tether release is out. Tapping opens Home (with its update banner); Update opens the app
     * and starts the install there — Android's install confirmation can't appear from the background.
     */
    @SuppressLint("MissingPermission")
    fun showAppUpdate(context: Context, versionName: String, notes: String) {
        if (!canPost(context)) return
        val text = notes.trim().ifEmpty { "Tap to update" }
        val notification = NotificationCompat.Builder(context, CHANNEL_APP_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ACCENT)
            .setContentTitle("Tether $versionName is available")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, APP_UPDATE_NOTIFICATION_ID))
            .addAction(0, "Update", installUpdate(context))
            .build()
        NotificationManagerCompat.from(context).notify(APP_UPDATE_NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** Opens MainActivity on Home. */
    fun openApp(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun installUpdate(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = MainActivity.ACTION_INSTALL_UPDATE
        }
        return PendingIntent.getActivity(context, APP_UPDATE_NOTIFICATION_ID + 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
