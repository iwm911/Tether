package app.tether.ui.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Composer drafts, one per conversation, outliving the screen's ViewModel: leave a chat mid-sentence,
 * open another, come back — the text is still there. Text is persisted (survives process death);
 * staged images are kept in memory only (they can be several MB).
 */
object Drafts {
    private const val PREFS = "composer_drafts"
    @Volatile private var prefs: SharedPreferences? = null
    private val images = ConcurrentHashMap<String, List<ComposerAttachment>>()

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun session(connectionId: String, sessionId: String) = "session:$connectionId:$sessionId"

    fun text(key: String): String = prefs?.getString(key, null).orEmpty()
    fun attachments(key: String): List<ComposerAttachment> = images[key].orEmpty()

    fun save(key: String, text: String, attachments: List<ComposerAttachment>) {
        prefs?.let { p ->
            if (text.isEmpty()) { if (p.contains(key)) p.edit().remove(key).apply() }
            else if (p.getString(key, null) != text) p.edit().putString(key, text).apply()
        }
        if (attachments.isEmpty()) images.remove(key) else images[key] = attachments
    }

    /** The same conversation shown under a new key: carry the draft over. */
    fun move(from: String, to: String) {
        if (from == to) return
        val t = text(from)
        val a = attachments(from)
        save(from, "", emptyList())
        if (t.isNotEmpty() || a.isNotEmpty()) save(to, t, a)
    }
}

/**
 * Loads the saved draft for the first key and then mirrors every edit into [Drafts]. When [keys]
 * emits a new key, the draft is re-keyed, not reloaded.
 */
internal fun ComposerState.bindDraft(scope: CoroutineScope, keys: Flow<String>) {
    scope.launch {
        var current: String? = null
        keys.distinctUntilChanged().collectLatest { key ->
            val prev = current
            if (prev == null) {
                if (isEmpty) {
                    val t = Drafts.text(key)
                    if (t.isNotEmpty()) value = TextFieldValue(t, TextRange(t.length))
                    attachments.addAll(Drafts.attachments(key))
                }
            } else {
                Drafts.move(prev, key)
            }
            current = key
            snapshotFlow { value.text to attachments.toList() }.collect { (t, a) -> Drafts.save(key, t, a) }
        }
    }
}
