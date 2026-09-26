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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Built-in over-the-air updates from the project's GitHub Releases — no store needed.
 *
 * `tools/publish_update.sh` (in the repo) builds a signed APK and publishes it as a GitHub release
 * together with a manifest asset (`update.json`; `update-debug.json` for debug builds, published as
 * a pre-release). The app lists the releases of [BuildConfig.UPDATE_REPO], reads the newest
 * manifest for its variant, downloads the APK asset over HTTPS, checks its SHA-256 and hands it to
 * Android's PackageInstaller — Android then asks the user to confirm, as it must for any app
 * installed outside a store, and refuses APKs not signed with the installed app's key.
 */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** APK asset name in the same release */
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
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    /** Android needs "Install unknown apps" allowed for Tether before it can install the update. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

@Serializable
private data class GhRelease(
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)

class UpdateManager(
    private val app: Application,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null
    private var lastCheck = 0L
    /** APK download URL + manifest of the update on offer. */
    private var source: Pair<String, UpdateInfo>? = null

    private val manifestName = if (BuildConfig.DEBUG) "update-debug.json" else "update.json"

    init {
        instance = this
        // First look shortly after launch, off the startup path.
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
            _state.value = try {
                val found = withContext(Dispatchers.IO) { newestRelease() }
                if (found != null && found.second.versionCode > BuildConfig.VERSION_CODE) {
                    source = found
                    UpdateState.Available(found.second)
                } else {
                    UpdateState.UpToDate(System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (silent) prev.takeIf { it is UpdateState.Available } ?: UpdateState.Idle
                else UpdateState.Failed("Couldn't check GitHub for updates: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** Newest release (API order: newest first) carrying this variant's manifest and its APK. */
    private fun newestRelease(): Pair<String, UpdateInfo>? {
        val body = httpText("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=20", "application/vnd.github+json")
        val releases = json.decodeFromString(ListSerializer(GhRelease.serializer()), body)
        for (r in releases) {
            // Release builds ignore pre-releases (debug builds and betas are published as those).
            if (r.draft || (r.prerelease && !BuildConfig.DEBUG)) continue
            val manifest = r.assets.firstOrNull { it.name == manifestName } ?: continue
            val info = json.decodeFromString(UpdateInfo.serializer(), httpText(manifest.url, "application/octet-stream"))
            val apk = r.assets.firstOrNull { it.name == info.apk } ?: continue
            return apk.url to info
        }
        return null
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "Tether/${BuildConfig.VERSION_NAME}")
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException(
                when (code) {
                    403, 429 -> "GitHub rate limit reached, try again later"
                    404 -> "release not found on GitHub"
                    else -> "GitHub answered HTTP $code"
                }
            )
        }
        return c
    }

    private fun httpText(url: String, accept: String): String {
        val c = open(url, accept)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File, expected: Long, onProgress: (Long, Long) -> Unit) {
        val part = File(dest.path + ".part")
        val c = open(url, "application/octet-stream")
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: expected
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - reported >= buf.size) {
                            onProgress(done, total)
                            reported = done
                        }
                    }
                    onProgress(done, total)
                }
            }
        } finally {
            c.disconnect()
        }
        if (!part.renameTo(dest)) throw IOException("Couldn't save the downloaded update.")
    }

    /** Downloads (with progress), verifies and installs the available update. */
    fun install() {
        val (url, info) = source ?: return
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
                    withContext(Dispatchers.IO) {
                        download(url, file, info.size) { done, total ->
                            _state.value = UpdateState.Downloading(info, done, if (total > 0) total else info.size)
                        }
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
            _state.value = availableOrIdle()
            install()
        }
    }

    fun dismissError() {
        val s = _state.value
        if (s is UpdateState.Failed) _state.value = availableOrIdle()
    }

    private fun availableOrIdle(): UpdateState = source?.let { UpdateState.Available(it.second) } ?: UpdateState.Idle

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
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = availableOrIdle()
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
