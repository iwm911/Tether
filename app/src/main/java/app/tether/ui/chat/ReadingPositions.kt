package app.tether.ui.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the user was in a conversation when they last looked at it.
 *
 * [key] is the bottom-most transcript row on screen and [offset] how far it was scrolled (the list
 * is laid out bottom-up, so this is the same pair [androidx.compose.foundation.lazy.LazyListState]
 * keeps). [newestKey] is the newest row that existed then: rows after it are what the user hasn't
 * seen. [following] says they were reading at the live end.
 */
internal data class ReadingAnchor(val key: String, val offset: Int, val newestKey: String, val following: Boolean)

/**
 * Reading positions, one per conversation, outliving the screen: leave a session while Claude keeps
 * writing, come back, and the list opens where you stopped (with "New activity" below) instead of at
 * the end. Persisted, so it survives process death too.
 */
object ReadingPositions {
    private const val PREFS = "reading_positions"
    private const val SEP = '\u001f'
    private const val MAX = 200
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun session(connectionId: String, sessionId: String, agentId: String? = null) =
        "pos:$connectionId:$sessionId" + (agentId?.let { ":$it" } ?: "")

    internal fun get(id: String): ReadingAnchor? = decode(prefs?.getString(id, null))

    internal fun put(id: String, anchor: ReadingAnchor) {
        val p = prefs ?: return
        val v = encode(anchor)
        if (p.getString(id, null) == v) return
        val e = p.edit().putString(id, v)
        // Bounded: a phone that has seen hundreds of sessions shouldn't keep every position forever.
        if (!p.contains(id) && p.all.size >= MAX) p.all.keys.firstOrNull()?.let(e::remove)
        e.apply()
    }

    internal fun encode(a: ReadingAnchor): String = listOf(a.key, a.offset.toString(), a.newestKey, if (a.following) "1" else "0").joinToString(SEP.toString())

    internal fun decode(s: String?): ReadingAnchor? {
        val parts = s?.split(SEP) ?: return null
        if (parts.size != 4) return null
        val offset = parts[1].toIntOrNull() ?: return null
        if (parts[0].isEmpty() || parts[2].isEmpty()) return null
        return ReadingAnchor(parts[0], offset.coerceAtLeast(0), parts[2], parts[3] == "1")
    }
}
