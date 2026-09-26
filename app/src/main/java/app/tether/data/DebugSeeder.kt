package app.tether.data

import android.content.Context
import android.util.Log
import app.tether.AppContainer
import app.tether.core.AppSettings
import app.tether.core.Connection
import app.tether.core.SecretKeys
import app.tether.core.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Debug-only end-to-end hook (SPEC §6). If `getExternalFilesDir(null)/seed.json` exists it is
 * imported and deleted:
 *
 * ```
 * {
 *   "keys":        [{"id": "k1", "name": "CI key", "privateKey": "-----BEGIN OPENSSH…", "passphrase": null}],
 *   "connections": [<Connection JSON>, …],          // may carry an extra "password": "…" field
 *   "settings":    {"onboardingDone": true, …}      // any AppSettings field, by name
 * }
 * ```
 *
 * Only runs when explicitly enabled from adb (`adb shell setprop debug.tether.seed 1`), so a debug
 * build on someone's phone can't be fed machines and keys just by dropping a file into its storage.
 *
 * Host keys are deliberately NOT seeded: the first connection still goes through the
 * trust-on-first-use dialog, which is part of what the E2E test exercises.
 */
object DebugSeeder {
    private const val TAG = "TetherSeeder"

    fun run(context: Context, container: AppContainer) {
        val file = File(context.getExternalFilesDir(null) ?: return, "seed.json")
        if (!file.exists()) return
        if (!seedingEnabled()) {
            Log.w(TAG, "Ignoring ${file.name}: run `adb shell setprop debug.tether.seed 1` to allow seeding")
            file.delete()
            return
        }
        try {
            val root = TetherJson.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val keys = seedKeys(root["keys"], container)
            val machines = seedConnections(root["connections"], container)
            seedSettings(root["settings"], container)
            Log.i(TAG, "Seeded $keys key(s), $machines machine(s) from ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "seed.json could not be applied", e)
        } finally {
            if (!file.delete()) Log.w(TAG, "Couldn't delete ${file.absolutePath}")
        }
    }

    /** `debug.*` properties can only be set by the shell user (adb) or root, not by other apps. */
    private fun seedingEnabled(): Boolean = try {
        val p = ProcessBuilder("getprop", "debug.tether.seed").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.trim() == "1"
    } catch (_: Exception) {
        false
    }

    private fun seedKeys(element: JsonElement?, container: AppContainer): Int {
        val repo = container.keys as? SecureKeyRepository ?: return 0
        var count = 0
        element?.jsonArray?.forEach { item ->
            val o = item.jsonObject
            val id = o.string("id") ?: return@forEach
            val text = o.string("privateKey") ?: return@forEach
            try {
                repo.importWithId(id, o.string("name") ?: "Seeded key", text, o.string("passphrase"))
                count++
            } catch (e: Exception) {
                Log.e(TAG, "Seed key '$id' rejected: ${e.message}", e)
            }
        }
        return count
    }

    private fun seedConnections(element: JsonElement?, container: AppContainer): Int {
        var count = 0
        element?.jsonArray?.forEach { item ->
            try {
                val o = item.jsonObject
                val password = o.string("password")
                val withDefaults = JsonObject(
                    o.filterKeys { it != "password" } +
                        (if ("createdAt" !in o) mapOf("createdAt" to JsonPrimitive(System.currentTimeMillis())) else emptyMap()),
                )
                val connection = TetherJson.decodeFromJsonElement(Connection.serializer(), withDefaults)
                val repo = container.connections
                if (repo is FileConnectionRepository) {
                    repo.upsertBlocking(connection)
                    if (password != null) container.secrets.put(SecretKeys.password(connection.id), password)
                } else {
                    runBlocking { repo.upsert(connection, password) }
                }
                count++
            } catch (e: Exception) {
                Log.e(TAG, "Seed connection rejected", e)
            }
        }
        return count
    }

    private fun seedSettings(element: JsonElement?, container: AppContainer) {
        val o = (element as? JsonObject) ?: return
        runBlocking {
            container.settings.update { s -> apply(s, o) }
        }
    }

    private fun apply(s: AppSettings, o: JsonObject): AppSettings = s.copy(
        theme = o.string("theme")?.let { v -> ThemeMode.entries.firstOrNull { it.name.equals(v, true) } } ?: s.theme,
        dynamicColor = o.bool("dynamicColor") ?: s.dynamicColor,
        biometricLock = o.bool("biometricLock") ?: s.biometricLock,
        defaultModel = o.string("defaultModel") ?: s.defaultModel,
        defaultPermissionMode = o.string("defaultPermissionMode") ?: s.defaultPermissionMode,
        showThinking = o.bool("showThinking") ?: s.showThinking,
        notifyPermissions = o.bool("notifyPermissions") ?: s.notifyPermissions,
        notifyCompletion = o.bool("notifyCompletion") ?: s.notifyCompletion,
        backgroundWatch = o.bool("backgroundWatch") ?: s.backgroundWatch,
        haptics = o.bool("haptics") ?: s.haptics,
        codeFontScale = (o["codeFontScale"] as? JsonPrimitive)?.floatOrNull ?: s.codeFontScale,
        onboardingDone = o.bool("onboardingDone") ?: s.onboardingDone,
        compactTools = o.bool("compactTools") ?: s.compactTools,
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
