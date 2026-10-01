package app.tether.analytics

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.tether.AppContainer
import app.tether.BuildConfig
import app.tether.core.AskAnswer
import app.tether.core.ImageAttachment
import app.tether.core.LinkState
import app.tether.core.NewSessionRequest
import app.tether.core.NewSessionResult
import app.tether.core.SessionDecision
import app.tether.core.SessionHub
import app.tether.core.SessionRef
import app.tether.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import kotlin.random.Random

/**
 * Anonymous usage analytics via Aptabase (https://aptabase.com, open source, privacy-first) — just
 * enough to know whether people use Tether and which features matter. See PRIVACY.md in the repo.
 *
 * Sent: an event name with the coarse properties listed at each [track] call (see [install] and
 * [TrackedSessionHub]), app version/build, Android version, locale, debug flag and a random session
 * id that rotates after an hour of inactivity. Never sent: device or advertising ids, accounts,
 * machine names, hosts, IPs, usernames, paths, prompts, code or any session content.
 *
 * Nothing leaves the phone when the build has no Aptabase key (forks, local builds), when the user
 * turned it off, or before they've seen the notice on Home — events wait in memory until then.
 */
class Analytics(private val settings: SettingsRepository, private val scope: CoroutineScope) {
    private val key = BuildConfig.APTABASE_KEY
    private val endpoint: String? = when {
        key.startsWith("A-EU-") -> "https://eu.aptabase.com/api/v0/events"
        key.startsWith("A-US-") -> "https://us.aptabase.com/api/v0/events"
        else -> null
    }

    /** False for builds without a key: the notice and the Settings toggle are hidden. */
    val available: Boolean get() = endpoint != null

    private val queue = ArrayDeque<JsonObject>()
    private var sessionId = newSessionId()
    private var lastEventAt = 0L
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        if (available) {
            scope.launch {
                while (true) {
                    withTimeoutOrNull(FLUSH_INTERVAL_MS) { flushSignal.receive() }
                    flush()
                }
            }
            // Turning analytics off also drops whatever was still waiting.
            scope.launch {
                settings.settings.map { it.analyticsEnabled }.distinctUntilChanged().collect { on ->
                    if (!on) synchronized(queue) { queue.clear() }
                }
            }
        }
    }

    fun track(event: String, props: Map<String, Any> = emptyMap()) {
        if (!available || !settings.settings.value.analyticsEnabled) return
        val now = System.currentTimeMillis()
        synchronized(queue) {
            if (now - lastEventAt > SESSION_TIMEOUT_MS) sessionId = newSessionId()
            lastEventAt = now
            if (queue.size >= MAX_QUEUE) return
            queue.addLast(
                buildJsonObject {
                    put("timestamp", Instant.ofEpochMilli(now).toString())
                    put("sessionId", sessionId)
                    put("eventName", event)
                    put("systemProps", systemProps)
                    put("props", JsonObject(props.mapValues { (_, v) -> v.toJson() }))
                }
            )
        }
    }

    /** Sends what's queued now instead of on the next tick (e.g. when the app goes to background). */
    fun flushSoon() {
        flushSignal.trySend(Unit)
    }

    private suspend fun flush() {
        val s = settings.settings.value
        if (!s.analyticsEnabled || !s.analyticsNoticeSeen) return
        while (true) {
            val batch = synchronized(queue) {
                if (queue.isEmpty()) return
                List(minOf(queue.size, BATCH_SIZE)) { queue.removeFirst() }
            }
            // Best effort: a failed batch is dropped rather than retried or stored.
            if (!withContext(Dispatchers.IO) { runCatching { post(batch) }.getOrDefault(false) }) return
        }
    }

    private fun post(batch: List<JsonObject>): Boolean {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("App-Key", key)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JsonArray(batch).toString().toByteArray()) }
            return c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }

    private val systemProps = buildJsonObject {
        put("isDebug", BuildConfig.DEBUG)
        put("osName", "Android")
        put("osVersion", Build.VERSION.RELEASE)
        put("locale", Locale.getDefault().toLanguageTag().take(10))
        put("appVersion", BuildConfig.VERSION_NAME)
        put("appBuildNumber", BuildConfig.VERSION_CODE.toString())
        put("sdkVersion", "tether-http@1")
    }

    private fun Any.toJson(): JsonPrimitive = when (this) {
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    companion object {
        private const val FLUSH_INTERVAL_MS = 60_000L
        private const val SESSION_TIMEOUT_MS = 60 * 60_000L
        private const val BATCH_SIZE = 25
        private const val MAX_QUEUE = 200

        /** Aptabase's session id shape: epoch seconds + 8 random digits. Not tied to the device. */
        private fun newSessionId() = "${System.currentTimeMillis() / 1000}${Random.nextInt(10_000_000, 100_000_000)}"

        /** Coarse bucket so counts never pin down a person: 0, 1, 2-3, 4+. */
        fun bucket(n: Int) = when {
            n <= 1 -> n.toString()
            n <= 3 -> "2-3"
            else -> "4+"
        }

        /** App-wide hooks; call once from [app.tether.TetherApp.onCreate] (main thread). */
        fun install(app: Application, c: AppContainer) {
            val a = c.analytics
            if (!a.available) return
            val prefs = app.getSharedPreferences("analytics", Context.MODE_PRIVATE)
            val last = prefs.getInt("last_version_code", 0)
            when {
                last == 0 -> a.track("first_open")
                last != BuildConfig.VERSION_CODE -> a.track("app_updated", mapOf("from" to last))
            }
            prefs.edit().putInt("last_version_code", BuildConfig.VERSION_CODE).apply()

            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) =
                    a.track("app_opened", mapOf("machines" to bucket(c.connections.connections.value.size)))
                override fun onStop(owner: LifecycleOwner) = a.flushSoon()
            })

            // One event per machine per process when its link first comes up, not on every reconnect.
            val seen = HashSet<String>()
            c.scope.launch {
                c.ssh.states.collect { states ->
                    states.forEach { (id, s) -> if (s is LinkState.Connected && seen.add(id)) a.track("machine_connected") }
                }
            }
        }
    }
}

/** Counts session activity on its way through; never looks at prompts, paths or output. */
class TrackedSessionHub(private val hub: SessionHub, private val analytics: Analytics) : SessionHub by hub {
    override suspend fun new(connectionId: String, request: NewSessionRequest, images: List<ImageAttachment>): NewSessionResult =
        hub.new(connectionId, request, images).also { res ->
            if (res is NewSessionResult.Started) startedEvent(request.model, request.permissionMode, resumed = false, images = images.size)
        }

    override suspend fun send(ref: SessionRef, text: String, images: List<ImageAttachment>): Boolean =
        hub.send(ref, text, images).also { woke ->
            if (woke) startedEvent(null, null, resumed = true, images = images.size)
            analytics.track("message_sent", mapOf("images" to images.size))
        }

    override suspend fun setMode(ref: SessionRef, mode: String?): String? = hub.setMode(ref, mode)

    override suspend fun answer(ref: SessionRef, decision: SessionDecision, message: String?, toolUseId: String?) {
        hub.answer(ref, decision, message, toolUseId)
        val kind = when (decision) {
            SessionDecision.ALLOW -> "allow"
            SessionDecision.ALLOW_ALWAYS -> "always"
            SessionDecision.DENY -> "deny"
        }
        analytics.track("permission_answered", mapOf("decision" to kind))
    }

    override suspend fun ask(ref: SessionRef, answers: List<AskAnswer>) {
        hub.ask(ref, answers)
        analytics.track("permission_answered", mapOf("decision" to "answer"))
    }

    private fun startedEvent(model: String?, mode: String?, resumed: Boolean, images: Int) = analytics.track(
        "agent_started",
        mapOf("model" to (model ?: "default"), "mode" to (mode ?: "default"), "resumed" to resumed, "images" to images),
    )
}
