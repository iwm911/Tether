package app.tether.data

import android.content.Context
import app.tether.core.PendingRemovalStore

/** [PendingRemovalStore] in SharedPreferences: one string set of `connectionId/sessionId`. */
class PrefsPendingRemovalStore(context: Context) : PendingRemovalStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): Map<String, Set<String>> =
        (prefs.getStringSet(KEY, null) ?: emptySet())
            .mapNotNull { e -> e.lastIndexOf('/').takeIf { it > 0 && it < e.length - 1 }?.let { e.substring(0, it) to e.substring(it + 1) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    override fun save(pending: Map<String, Set<String>>) {
        val flat = pending.flatMap { (cid, sids) -> sids.map { "$cid/$it" } }.toSet()
        prefs.edit().putStringSet(KEY, flat).apply()
    }

    private companion object {
        const val PREFS = "pending_removals"
        const val KEY = "pending"
    }
}
