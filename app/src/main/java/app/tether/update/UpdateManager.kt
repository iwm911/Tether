package app.tether.update

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.tether.BuildConfig
import app.tether.TetherApp
import app.tether.core.ConnectionRepository
import app.tether.core.LinkState
import app.tether.core.SshManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Built-in over-the-air updates, served by your own machines — no store, no third-party hosting.
 *
 * `tools/publish_update.sh` (in the repo) builds a signed APK and stages it under `~/.tether/app/`
 * on the machine it runs on, next to a manifest (`update.json`; `update-debug.json` for debug
 * builds). The app reads that manifest over SSH from every saved machine, downloads the newest
 * APK over SFTP, checks its SHA-256 and hands it to Android's PackageInstaller — Android then
 * asks the user to confirm, as it must for any app installed outside a store.
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** APK file name inside ~/.tether/app/ */
    val apk: String,
    val sha256: String,
    val size: Long = 0,
    val notes: String = "",
    val publishedAt: Long = 0,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo, val connectionId: String, val machineName: String) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    /** Android needs "Install unknown apps" allowed for Tether before it can install the update. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

class UpdateManager(
    private val app: Application,
    private val connections: ConnectionRepository,
    private val ssh: SshManager,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null
    private var lastCheck = 0L
    private var source: Pair<String, UpdateInfo>? = null

    private val manifestName = if (BuildConfig.DEBUG) "update-debug.json" else "update.json"

    init {
        instance = this
        // First look shortly after launch, once machines had a chance to connect.
        scope.launch { delay(8_000); check(silent = true) }
    }

    /** Called when the app comes to the foreground: re-check at most every 6 h. */
    fun maybeCheck() {
        if (System.currentTimeMillis() - lastCheck > 6 * 3_600_000L) check(silent = true)
    }

    fun check(silent: Boolean = false) {
        if (job?.isActive == true) return
        val prev = _state.value
        if (prev is UpdateState.Downloading || prev is UpdateState.Installing) return
        job = scope.launch {
            if (!silent) _state.value = UpdateState.Checking
            lastCheck = System.currentTimeMillis()
            var best: Triple<String, String, UpdateInfo>? = null
            var anyReached = false
            // Connected machines first; each gets a short budget so one offline box can't stall the check.
            val states = ssh.states.value
            val ordered = connections.connections.value.sortedByDescending { states[it.id] is LinkState.Connected }
            for (c in ordered) {
                val info = withTimeoutOrNull(12_000) {
                    try {
                        val r = ssh.exec(c.id, "cat ~/.tether/app/$manifestName 2>/dev/null", timeoutMs = 10_000)
                        anyReached = true
                        r.stdout.trim().takeIf { it.startsWith("{") }?.let { json.decodeFromString(UpdateInfo.serializer(), it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                } ?: continue
                if (info.versionCode > BuildConfig.VERSION_CODE && (best == null || info.versionCode > best.third.versionCode)) {
                    best = Triple(c.id, c.name, info)
                }
            }
            val b = best
            _state.value = when {
                b != null -> {
                    source = b.first to b.third
                    UpdateState.Available(b.third, b.first, b.second)
                }
                !anyReached && !silent -> UpdateState.Failed("Couldn't reach any of your machines to check for updates.")
                !anyReached -> prev.takeIf { it is UpdateState.Available } ?: UpdateState.Idle
                else -> UpdateState.UpToDate(System.currentTimeMillis())
            }
        }
    }

    /** Downloads (with progress), verifies and installs the available update. */
    fun install() {
        val (connId, info) = source ?: return
        if (job?.isActive == true && _state.value !is UpdateState.Available && _state.value !is UpdateState.NeedsPermission) return
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(info, 0, info.size)
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { if (it.name != info.apk) it.delete() }
                val file = File(dir, info.apk)
                if (!(file.exists() && sha256(file).equals(info.sha256, ignoreCase = true))) {
                    ssh.download(connId, "~/.tether/app/${info.apk}", file) { done, total ->
                        _state.value = UpdateState.Downloading(info, done, if (total > 0) total else info.size)
                    }
                }
                val digest = withContext(Dispatchers.IO) { sha256(file) }
                if (!digest.equals(info.sha256, ignoreCase = true)) {
                    file.delete()
                    throw IllegalStateException("The downloaded update is damaged (checksum mismatch). Try again.")
                }
                _state.value = UpdateState.Installing(info)
                withContext(Dispatchers.IO) { commit(file) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = UpdateState.Failed(t.message ?: "The update couldn't be installed.", info)
            }
        }
    }

    /** Opens Android's "Install unknown apps" page for Tether. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Back from the permission page: continue if allowed. */
    fun onResumeAfterPermission() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && (Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls())) {
            _state.value = source?.let { UpdateState.Available(it.second, it.first, connections.get(it.first)?.name ?: "") } ?: UpdateState.Idle
            install()
        }
    }

    fun dismissError() {
        val s = _state.value
        if (s is UpdateState.Failed) _state.value = source?.let { UpdateState.Available(it.second, it.first, connections.get(it.first)?.name ?: "") } ?: UpdateState.Idle
    }

    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("tether.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 256 * 1024)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(app, UpdateInstallReceiver::class.java).setPackage(app.packageName)
            session.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
        }
    }

    internal fun onInstallStatus(status: Int, message: String?) {
        val info = (_state.value as? UpdateState.Installing)?.info ?: source?.second
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit // the confirm screen is up
            PackageInstaller.STATUS_SUCCESS -> Unit // the process is replaced right after this
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = source?.let { UpdateState.Available(it.second, it.first, connections.get(it.first)?.name ?: "") } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed(
                when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Android refused the update — it must be signed with the same key as the installed app."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
                    else -> message ?: "The update couldn't be installed."
                },
                info,
            )
        }
    }

    companion object {
        @Volatile internal var instance: UpdateManager? = null

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** PackageInstaller's callback: shows Android's confirm screen, reports failures to the manager. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) runCatching { context.startActivity(confirm) }
        }
        val manager = UpdateManager.instance ?: (context.applicationContext as? TetherApp)?.container?.updates
        manager?.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
