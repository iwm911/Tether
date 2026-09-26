package app.tether.ui.chat.render

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.ui.graphics.vector.ImageVector
import app.tether.ui.components.prettyPath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** How a tool call is labelled in its one-line row: `● Read  src/App.kt`. */
data class ToolLabel(val verb: String, val target: String, val icon: ImageVector)

object ToolPresentation {

    /** Tools whose rows the conversation never shows (the screen renders them elsewhere). */
    val hiddenTools: Set<String> = setOf("TodoWrite")

    fun describe(name: String, inputJson: String): ToolLabel = describe(name, inputJson, null)

    /** [cwd] makes paths relative to the agent's working directory when known. */
    fun describe(name: String, inputJson: String, cwd: String?): ToolLabel = try {
        describeUnsafe(name, inputJson, cwd)
    } catch (t: Throwable) {
        ToolLabel(humanize(name), "", Icons.Rounded.Build)
    }

    private fun describeUnsafe(name: String, inputJson: String, cwd: String?): ToolLabel {
        val obj = parseJsonObject(inputJson)
        fun f(key: String): String? = (obj?.str(key) ?: partialString(inputJson, key))?.takeIf { it.isNotBlank() }
        fun path(key: String = "file_path"): String = f(key)?.let { displayPath(it, cwd) } ?: ""

        return when (name) {
            "Read" -> ToolLabel("Read", path(), Icons.Rounded.Description)
            "Edit", "MultiEdit" -> ToolLabel("Edit", path(), Icons.Rounded.EditNote)
            "Write" -> ToolLabel("Write", path(), Icons.AutoMirrored.Rounded.NoteAdd)
            "NotebookEdit" -> ToolLabel("Edit notebook", path("notebook_path"), Icons.Rounded.EditNote)
            "NotebookRead" -> ToolLabel("Read notebook", path("notebook_path"), Icons.Rounded.Description)
            "Bash" -> {
                val cmd = f("command")?.let { shortCommand(it) }.orEmpty()
                val desc = f("description")
                val target = when {
                    cmd.isNotEmpty() && cmd.length <= 48 -> cmd
                    desc != null -> desc
                    else -> cmd
                }
                ToolLabel("Bash", target, Icons.Rounded.Terminal)
            }
            "BashOutput" -> ToolLabel("Read output", f("bash_id") ?: f("shell_id") ?: "", Icons.Rounded.Terminal)
            "KillShell", "KillBash" -> ToolLabel("Stop shell", f("shell_id") ?: f("bash_id") ?: "", Icons.Rounded.Terminal)
            "Grep" -> {
                val pattern = f("pattern")?.let { "\"${clip(it, 40)}\"" }.orEmpty()
                val where = f("path")?.let { displayPath(it, cwd) } ?: f("glob")
                ToolLabel("Grep", if (where != null && pattern.isNotEmpty()) "$pattern in $where" else pattern, Icons.Rounded.Search)
            }
            "Glob" -> {
                val pattern = f("pattern").orEmpty()
                val where = f("path")?.let { displayPath(it, cwd) }
                ToolLabel("Glob", if (where != null) "$pattern in $where" else pattern, Icons.Rounded.FolderOpen)
            }
            "LS" -> ToolLabel("List", path("path"), Icons.Rounded.FolderOpen)
            "WebFetch" -> ToolLabel("Fetch", f("url")?.let { hostOf(it) }.orEmpty(), Icons.Rounded.Public)
            "WebSearch" -> ToolLabel("Search web", f("query")?.let { "\"${clip(it, 60)}\"" }.orEmpty(), Icons.Rounded.TravelExplore)
            "Task", "Agent" -> ToolLabel(
                f("subagent_type")?.takeIf { it != "general-purpose" }?.let { humanize(it) } ?: "Task",
                f("description") ?: f("prompt")?.let { clip(it.lineSequence().first(), 60) } ?: "",
                Icons.Rounded.AccountTree,
            )
            "TodoWrite" -> {
                val n = obj?.arr("todos")?.size
                ToolLabel("Update plan", if (n != null) "$n items" else "", Icons.Rounded.Checklist)
            }
            "TaskCreate" -> ToolLabel("Add task", f("subject") ?: f("description") ?: "", Icons.Rounded.Checklist)
            "TaskUpdate" -> ToolLabel("Update task", listOfNotNull(f("taskId")?.let { "#$it" }, f("status")?.let { humanize(it).lowercase() }).joinToString(" · "), Icons.Rounded.Checklist)
            "TaskList" -> ToolLabel("List tasks", "", Icons.Rounded.Checklist)
            "TaskGet" -> ToolLabel("Get task", f("taskId")?.let { "#$it" } ?: "", Icons.Rounded.Checklist)
            "ExitPlanMode" -> ToolLabel("Plan ready", "", Icons.Rounded.Map)
            "EnterPlanMode" -> ToolLabel("Planning", "", Icons.Rounded.Map)
            "ToolSearch" -> ToolLabel("Load tools", f("query")?.removePrefix("select:") ?: "", Icons.Rounded.Build)
            "Skill" -> ToolLabel("Skill", f("skill") ?: f("command") ?: "", Icons.Rounded.AutoAwesome)
            "SlashCommand" -> ToolLabel("Command", f("command") ?: "", Icons.Rounded.AutoAwesome)
            "AskUserQuestion" -> ToolLabel("Ask", firstQuestion(obj) ?: "", Icons.Rounded.QuestionAnswer)
            else -> if (name.startsWith("mcp__")) {
                val (server, tool) = mcpParts(name)
                ToolLabel("$server · $tool", obj?.let { firstShortValue(it) } ?: "", Icons.Rounded.Extension)
            } else {
                ToolLabel(humanize(name), obj?.let { firstShortValue(it) } ?: "", Icons.Rounded.Build)
            }
        }
    }

    /** `mcp__github__create_issue` → ("github", "create issue"). */
    fun mcpParts(name: String): Pair<String, String> {
        val rest = name.removePrefix("mcp__")
        val idx = rest.indexOf("__")
        return if (idx < 0) "mcp" to humanize(rest) else humanize(rest.substring(0, idx)).lowercase() to humanize(rest.substring(idx + 2)).lowercase()
    }

    /** Path relative to [cwd] when inside it, else home-shortened ([prettyPath]). */
    fun displayPath(path: String, cwd: String?, maxLen: Int = 64): String {
        val p = path.trim()
        if (cwd != null && cwd.length > 1) {
            val base = cwd.trimEnd('/')
            if (p == base) return "."
            if (p.startsWith("$base/")) return p.removePrefix("$base/")
        }
        return prettyPath(p, maxLen = maxLen)
    }

    /** First line of a command, `cd <dir> && ` dropped, whitespace collapsed, trimmed to ~80 chars. */
    fun shortCommand(command: String): String {
        var c = command.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val multiline = command.trim().contains('\n')
        c = c.replace(Regex("^cd\\s+(\"[^\"]*\"|'[^']*'|\\S+)\\s*&&\\s*"), "")
        c = c.replace(Regex("\\s+"), " ")
        if (multiline) c += " …"
        return clip(c, 80)
    }

    fun hostOf(url: String): String = try {
        (URI(url.trim()).host ?: url).removePrefix("www.")
    } catch (t: Throwable) {
        url.substringAfter("://").substringBefore('/').removePrefix("www.")
    }

    /** `general-purpose` → `General purpose`, `create_issue` → `Create issue`, `NotebookEdit` stays. */
    fun humanize(raw: String): String {
        if (raw.isEmpty()) return raw
        val spaced = raw.replace('_', ' ').replace('-', ' ').trim()
        return spaced.replaceFirstChar { it.uppercaseChar() }
    }

    internal fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    private fun firstQuestion(obj: JsonObject?): String? {
        val q = obj?.arr("questions")?.firstOrNull() as? JsonObject ?: return obj?.str("question")
        return q.str("question")?.let { clip(it, 60) }
    }

    private fun firstShortValue(obj: JsonObject): String? {
        val preferred = listOf("file_path", "path", "url", "query", "name", "title", "command", "id")
        for (k in preferred) obj.str(k)?.takeIf { it.isNotBlank() }?.let { return clip(it.lineSequence().first(), 60) }
        for ((_, v) in obj) {
            val p = v as? JsonPrimitive ?: continue
            if (p.isString && p.content.isNotBlank() && p.content.length <= 80) return clip(p.content.lineSequence().first(), 60)
        }
        return null
    }
}
