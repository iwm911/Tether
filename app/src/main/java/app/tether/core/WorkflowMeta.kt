package app.tether.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The `Workflow` tool: Claude Code's multi-agent orchestration (what "ultracode" turns on). */
const val WORKFLOW_TOOL = "Workflow"

/**
 * What a `Workflow` call says about itself: its `export const meta = {...}` literal (name, description, phase
 * titles) read from the inline script, or the input's own description / name / scriptPath when it runs a saved one.
 */
data class WorkflowMeta(
    val name: String?,
    val description: String?,
    val phases: List<String>,
    /** The saved script it runs, when not inline. */
    val scriptPath: String?,
    /** A resumed run's id (`resumeFromRunId`). */
    val resumeFrom: String?,
) {
    /** One line for a row or a prompt: the description, else the name, else the script's file name. */
    val title: String?
        get() = description ?: name ?: scriptPath?.substringAfterLast('/')?.removeSuffix(".js")?.takeIf { it.isNotBlank() }

    companion object {
        private val META_START = Regex("""\bmeta\s*=\s*\{""")
        private val PHASES = Regex("""\bphases\s*:\s*\[""")

        private fun field(key: String) = Regex("""\b$key\s*:\s*(['"`])((?:\\.|(?!\1).)*)\1""", RegexOption.DOT_MATCHES_ALL)
        private val NAME = field("name")
        private val DESCRIPTION = field("description")
        private val TITLE = field("title")
        private val ESCAPE = Regex("""\\(.)""")

        fun of(input: JsonObject?): WorkflowMeta {
            fun s(k: String) = (input?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            val meta = s("script")?.let { metaBlock(it) }
            return WorkflowMeta(
                name = meta?.let { str(NAME, it) } ?: s("name"),
                description = s("description") ?: meta?.let { str(DESCRIPTION, it) },
                phases = meta?.let { phases(it) }.orEmpty(),
                scriptPath = s("scriptPath"),
                resumeFrom = s("resumeFromRunId"),
            )
        }

        /** The text of the meta literal: from `meta = {` to its matching brace (strings skipped), at most 6000 chars. */
        internal fun metaBlock(script: String): String? {
            val m = META_START.find(script) ?: return null
            var depth = 1
            var quote: Char? = null
            var i = m.range.last + 1
            val end = minOf(script.length, i + 6000)
            while (i < end) {
                val c = script[i]
                if (quote != null) {
                    if (c == '\\') i++ else if (c == quote) quote = null
                } else when (c) {
                    '\'', '"', '`' -> quote = c
                    '{' -> depth++
                    '}' -> if (--depth == 0) return script.substring(m.range.last + 1, i)
                }
                i++
            }
            return script.substring(m.range.last + 1, end)
        }

        /** A top-level string field: skips the ones inside `phases: [...]` (their `title` / `detail`). */
        private fun str(re: Regex, block: String): String? {
            val phasesAt = PHASES.find(block)?.range?.first
            val phasesEnd = phasesAt?.let { closing(block, it) }
            return re.findAll(block).firstOrNull { r -> phasesAt == null || phasesEnd == null || r.range.first !in phasesAt..phasesEnd }
                ?.let { unescape(it.groupValues[2]) }?.takeIf { it.isNotBlank() }
        }

        private fun phases(block: String): List<String> {
            val at = PHASES.find(block) ?: return emptyList()
            val end = closing(block, at.range.first) ?: block.length
            return TITLE.findAll(block.substring(at.range.last, end)).map { unescape(it.groupValues[2]) }.filter { it.isNotBlank() }.toList()
        }

        /** Index of the `]` closing the first `[` at or after [from]. */
        private fun closing(s: String, from: Int): Int? {
            var depth = 0
            var quote: Char? = null
            var i = s.indexOf('[', from).takeIf { it >= 0 } ?: return null
            while (i < s.length) {
                val c = s[i]
                if (quote != null) {
                    if (c == '\\') i++ else if (c == quote) quote = null
                } else when (c) {
                    '\'', '"', '`' -> quote = c
                    '[' -> depth++
                    ']' -> if (--depth == 0) return i
                }
                i++
            }
            return null
        }

        private fun unescape(s: String) = s.replace(ESCAPE, "$1").replace(Regex("\\s+"), " ").trim()
    }
}
