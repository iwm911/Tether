package app.tether.ui.newagent

import android.content.Context
import android.content.SharedPreferences

/**
 * Remembers the new-agent composer's last choices (SharedPreferences "new_agent").
 *
 * Model and mode are stored together with the Settings default that was in force when they
 * were saved ("basis"): if the user later changes the default in Settings, the new default wins
 * over the stale remembered value.
 */
object NewAgentPrefs {
    private const val FILE = "new_agent"
    private const val LAST_CONN = "last_conn"
    private const val MODEL = "model"
    private const val MODEL_BASIS = "model_basis"
    private const val MODE = "mode"
    private const val MODE_BASIS = "mode_basis"
    private const val BACKGROUND = "background"
    private fun folderKey(connectionId: String) = "folder:$connectionId"

    private fun prefs(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lastConnection(ctx: Context): String? = prefs(ctx).getString(LAST_CONN, null)

    /** Last chosen kind: true = native background agent (`claude --bg`), false = Tether live run. */
    fun background(ctx: Context): Boolean = prefs(ctx).getBoolean(BACKGROUND, false)

    fun rememberBackground(ctx: Context, background: Boolean) {
        prefs(ctx).edit().putBoolean(BACKGROUND, background).apply()
    }

    fun folder(ctx: Context, connectionId: String): String? = prefs(ctx).getString(folderKey(connectionId), null)

    /** Used by Home's quick-start rows: pre-select a folder before opening the composer. */
    fun rememberFolder(ctx: Context, connectionId: String, cwd: String) {
        prefs(ctx).edit().putString(folderKey(connectionId), cwd).putString(LAST_CONN, connectionId).apply()
    }

    fun model(ctx: Context, settingsDefault: String): String {
        val p = prefs(ctx)
        val v = p.getString(MODEL, null) ?: return settingsDefault
        return if (p.getString(MODEL_BASIS, null) == settingsDefault) v else settingsDefault
    }

    fun mode(ctx: Context, settingsDefault: String): String {
        val p = prefs(ctx)
        val v = p.getString(MODE, null) ?: return settingsDefault
        return if (p.getString(MODE_BASIS, null) == settingsDefault) v else settingsDefault
    }

    fun rememberChoices(
        ctx: Context,
        connectionId: String,
        cwd: String,
        model: String,
        mode: String,
        settingsModel: String,
        settingsMode: String,
    ) {
        prefs(ctx).edit()
            .putString(LAST_CONN, connectionId)
            .putString(folderKey(connectionId), cwd)
            .putString(MODEL, model)
            .putString(MODEL_BASIS, settingsModel)
            .putString(MODE, mode)
            .putString(MODE_BASIS, settingsMode)
            .apply()
    }
}
