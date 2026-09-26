package app.tether.ui.chat.render

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* Pure diff model: from Claude Code's `structuredPatch`, or computed (LCS) from old/new strings. */

enum class DiffLineKind { CONTEXT, ADD, DEL, HUNK, NOTE }

data class DiffLine(val kind: DiffLineKind, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

data class DiffData(val lines: List<DiffLine>, val added: Int, val removed: Int) {
    val hasNumbers: Boolean get() = lines.any { it.oldNo != null || it.newNo != null }
    val maxLineNo: Int get() = lines.maxOfOrNull { maxOf(it.oldNo ?: 0, it.newNo ?: 0) } ?: 0
    val isEmpty: Boolean get() = lines.none { it.kind == DiffLineKind.ADD || it.kind == DiffLineKind.DEL || it.kind == DiffLineKind.CONTEXT }
}

object DiffModel {

    /** Above this many cells the LCS falls back to "all removed, then all added". */
    private const val MAX_LCS_CELLS = 400_000

    /**
     * Builds a diff from `structuredPatch`: `[{oldStart, oldLines, newStart, newLines, lines:["+x","-y"," z"]}]`.
     * Returns null when [patch] is missing, not an array, or has no usable hunks.
     */
    fun fromStructuredPatch(patch: JsonElement?): DiffData? {
        val hunks = patch as? JsonArray ?: return null
        if (hunks.isEmpty()) return null
        val out = ArrayList<DiffLine>()
        var added = 0
        var removed = 0
        for (h in hunks) {
            val o = h as? JsonObject ?: continue
            val oldStart = o.intField("oldStart") ?: 1
            val oldLines = o.intField("oldLines") ?: 0
            val newStart = o.intField("newStart") ?: 1
            val newLines = o.intField("newLines") ?: 0
            val lines = (o["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: continue
            out += DiffLine(DiffLineKind.HUNK, hunkHeader(oldStart, oldLines, newStart, newLines))
            var on = oldStart
            var nn = newStart
            for (l in lines) {
                when {
                    l.startsWith("+") -> { out += DiffLine(DiffLineKind.ADD, l.substring(1), null, nn); nn++; added++ }
                    l.startsWith("-") -> { out += DiffLine(DiffLineKind.DEL, l.substring(1), on, null); on++; removed++ }
                    l.startsWith("\\") -> out += DiffLine(DiffLineKind.NOTE, l.removePrefix("\\").trim())
                    else -> { out += DiffLine(DiffLineKind.CONTEXT, if (l.startsWith(" ")) l.substring(1) else l, on, nn); on++; nn++ }
                }
            }
        }
        if (out.none { it.kind != DiffLineKind.HUNK }) return null
        return DiffData(out, added, removed)
    }

    /** Line diff of two snippets (Edit fallback). No line numbers: snippet offsets are unknown. */
    fun fromStrings(old: String, new: String): DiffData {
        val a = if (old.isEmpty()) emptyList() else old.split('\n')
        val b = if (new.isEmpty()) emptyList() else new.split('\n')
        val lines = diffLines(a, b)
        return DiffData(lines, lines.count { it.kind == DiffLineKind.ADD }, lines.count { it.kind == DiffLineKind.DEL })
    }

    /** MultiEdit fallback: one section per edit, separated by "Edit k of n" headers. */
    fun fromEdits(edits: List<Pair<String, String>>): DiffData {
        if (edits.size == 1) return fromStrings(edits[0].first, edits[0].second)
        val out = ArrayList<DiffLine>()
        var added = 0
        var removed = 0
        edits.forEachIndexed { idx, (o, n) ->
            val d = fromStrings(o, n)
            out += DiffLine(DiffLineKind.HUNK, "Edit ${idx + 1} of ${edits.size}")
            out += d.lines
            added += d.added
            removed += d.removed
        }
        return DiffData(out, added, removed)
    }

    /** Plain unified-diff text (for Copy). */
    fun toUnified(diff: DiffData, path: String? = null): String = buildString {
        if (path != null) { append("--- a/").append(path).append('\n'); append("+++ b/").append(path).append('\n') }
        for (l in diff.lines) {
            when (l.kind) {
                DiffLineKind.HUNK -> append(l.text.replace('−', '-'))
                DiffLineKind.ADD -> append('+').append(l.text)
                DiffLineKind.DEL -> append('-').append(l.text)
                DiffLineKind.CONTEXT -> append(' ').append(l.text)
                DiffLineKind.NOTE -> append("\\ ").append(l.text)
            }
            append('\n')
        }
    }.trimEnd('\n')

    fun hunkHeader(oldStart: Int, oldLines: Int, newStart: Int, newLines: Int): String =
        "@@ −$oldStart,$oldLines +$newStart,$newLines @@"

    internal fun diffLines(a: List<String>, b: List<String>): List<DiffLine> {
        // Trim the common prefix / suffix first — edits are usually small islands in big snippets.
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf)
        val bm = b.subList(pre, b.size - suf)

        val out = ArrayList<DiffLine>(a.size + b.size)
        for (k in 0 until pre) out += DiffLine(DiffLineKind.CONTEXT, a[k])

        if (am.isEmpty() || bm.isEmpty() || am.size.toLong() * bm.size > MAX_LCS_CELLS) {
            am.forEach { out += DiffLine(DiffLineKind.DEL, it) }
            bm.forEach { out += DiffLine(DiffLineKind.ADD, it) }
        } else {
            val n = am.size
            val m = bm.size
            // lcs[i][j] = LCS length of am[i..] and bm[j..]
            val lcs = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    lcs[i][j] = if (am[i] == bm[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    am[i] == bm[j] -> { out += DiffLine(DiffLineKind.CONTEXT, am[i]); i++; j++ }
                    lcs[i + 1][j] >= lcs[i][j + 1] -> { out += DiffLine(DiffLineKind.DEL, am[i]); i++ }
                    else -> { out += DiffLine(DiffLineKind.ADD, bm[j]); j++ }
                }
            }
            while (i < n) { out += DiffLine(DiffLineKind.DEL, am[i]); i++ }
            while (j < m) { out += DiffLine(DiffLineKind.ADD, bm[j]); j++ }
        }
        for (k in a.size - suf until a.size) out += DiffLine(DiffLineKind.CONTEXT, a[k])
        return out
    }

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
}
