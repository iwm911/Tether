package app.tether.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tether.BuildConfig
import app.tether.core.AppSettings
import app.tether.core.SettingsRepository
import app.tether.core.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Exactly one DataStore instance per process (the delegate guarantees it). */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * [AppSettings] persisted in Preferences DataStore. The initial [settings] value is read
 * synchronously at construction (a few bytes) so the first frame — including the navigation start
 * destination — already reflects what the user chose.
 */
class DataStoreSettingsRepository(context: Context, scope: CoroutineScope) : SettingsRepository {

    private val store = context.applicationContext.settingsDataStore
    private val state: MutableStateFlow<AppSettings>

    override val settings: StateFlow<AppSettings>

    init {
        val initial = try {
            runBlocking { store.data.first() }.toSettings()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't read settings; using defaults", e)
            AppSettings()
        }
        state = MutableStateFlow(initial)
        settings = state.asStateFlow()
        scope.launch {
            store.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .map { it.toSettings() }
                .distinctUntilChanged()
                .collect { state.value = it }
        }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.write(next)
            // Reflect immediately so toggles feel instant; the collector will confirm the same value.
            state.value = next
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = this[K.theme]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: d.theme,
            dynamicColor = this[K.dynamicColor] ?: d.dynamicColor,
            biometricLock = this[K.biometricLock] ?: d.biometricLock,
            lockAfterSeconds = this[K.lockAfterSeconds] ?: d.lockAfterSeconds,
            defaultModel = this[K.defaultModel] ?: d.defaultModel,
            defaultPermissionMode = this[K.defaultPermissionMode] ?: d.defaultPermissionMode,
            showThinking = this[K.showThinking] ?: d.showThinking,
            notifyPermissions = this[K.notifyPermissions] ?: d.notifyPermissions,
            notifyCompletion = this[K.notifyCompletion] ?: d.notifyCompletion,
            notifyAppUpdates = this[K.notifyAppUpdates] ?: d.notifyAppUpdates,
            // A beta build keeps getting betas until the user turns them off.
            betaUpdates = this[K.betaUpdates] ?: (d.betaUpdates || BuildConfig.VERSION_NAME.contains("-beta")),
            backgroundWatch = this[K.backgroundWatch] ?: d.backgroundWatch,
            keepConnectionsAlive = this[K.keepAlive] ?: d.keepConnectionsAlive,
            batteryPromptDismissed = this[K.batteryPrompt] ?: d.batteryPromptDismissed,
            haptics = this[K.haptics] ?: d.haptics,
            codeFontScale = this[K.codeFontScale] ?: d.codeFontScale,
            onboardingDone = this[K.onboardingDone] ?: d.onboardingDone,
            compactTools = this[K.compactTools] ?: d.compactTools,
            keepScreenOn = this[K.keepScreenOn] ?: d.keepScreenOn,
            analyticsEnabled = this[K.analyticsEnabled] ?: d.analyticsEnabled,
            analyticsNoticeSeen = this[K.analyticsNoticeSeen] ?: d.analyticsNoticeSeen,
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[K.theme] = s.theme.name
        this[K.dynamicColor] = s.dynamicColor
        this[K.biometricLock] = s.biometricLock
        this[K.lockAfterSeconds] = s.lockAfterSeconds
        this[K.defaultModel] = s.defaultModel
        this[K.defaultPermissionMode] = s.defaultPermissionMode
        this[K.showThinking] = s.showThinking
        this[K.notifyPermissions] = s.notifyPermissions
        this[K.notifyCompletion] = s.notifyCompletion
        this[K.notifyAppUpdates] = s.notifyAppUpdates
        this[K.betaUpdates] = s.betaUpdates
        this[K.backgroundWatch] = s.backgroundWatch
        this[K.keepAlive] = s.keepConnectionsAlive
        this[K.batteryPrompt] = s.batteryPromptDismissed
        this[K.haptics] = s.haptics
        this[K.codeFontScale] = s.codeFontScale
        this[K.onboardingDone] = s.onboardingDone
        this[K.compactTools] = s.compactTools
        this[K.keepScreenOn] = s.keepScreenOn
        this[K.analyticsEnabled] = s.analyticsEnabled
        this[K.analyticsNoticeSeen] = s.analyticsNoticeSeen
    }

    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val biometricLock = booleanPreferencesKey("biometric_lock")
        val lockAfterSeconds = intPreferencesKey("lock_after_seconds")
        val defaultModel = stringPreferencesKey("default_model")
        val defaultPermissionMode = stringPreferencesKey("default_permission_mode")
        val showThinking = booleanPreferencesKey("show_thinking")
        val notifyPermissions = booleanPreferencesKey("notify_permissions")
        val notifyCompletion = booleanPreferencesKey("notify_completion")
        val notifyAppUpdates = booleanPreferencesKey("notify_app_updates")
        val betaUpdates = booleanPreferencesKey("beta_updates")
        val backgroundWatch = booleanPreferencesKey("background_watch")
        val keepAlive = booleanPreferencesKey("keep_connections_alive")
        val batteryPrompt = booleanPreferencesKey("battery_prompt_dismissed")
        val haptics = booleanPreferencesKey("haptics")
        val codeFontScale = floatPreferencesKey("code_font_scale")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val compactTools = booleanPreferencesKey("compact_tools")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val analyticsEnabled = booleanPreferencesKey("analytics_enabled")
        val analyticsNoticeSeen = booleanPreferencesKey("analytics_notice_seen")
    }

    private companion object {
        const val TAG = "TetherSettings"
    }
}
