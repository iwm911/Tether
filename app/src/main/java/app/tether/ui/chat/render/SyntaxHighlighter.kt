package app.tether.ui.chat.render

/*
 * Lightweight regex/scanner syntax highlighter. Pure Kotlin: produces token spans that the
 * Compose layer maps onto TetherTheme.colors.syn*. It is intentionally approximate — it only needs
 * to make code pleasant to read on a phone, never to be a compiler.
 */

enum class SynKind { KEYWORD, STRING, COMMENT, NUMBER, TYPE, KEY, VARIABLE, ANNOTATION, ADD, DEL, HUNK, META }

data class SynSpan(val start: Int, val end: Int, val kind: SynKind)

object SyntaxHighlighter {

    /** Beyond this many chars we highlight only the head (the rest renders plain). */
    private const val MAX_CHARS = 80_000

    /** Canonical language id for a fence label / alias, or null when we have no highlighter. */
    fun normalize(language: String?): String? {
        val l = language?.trim()?.lowercase()?.removePrefix(".") ?: return null
        return when (l) {
            "kotlin", "kt", "kts", "gradle", "groovy" -> "kotlin"
            "java" -> "java"
            "js", "javascript", "jsx", "mjs", "cjs", "node" -> "js"
            "ts", "typescript", "tsx" -> "ts"
            "py", "python", "python3", "py3" -> "python"
            "sh", "bash", "zsh", "shell", "console", "shellscript", "fish", "ksh", "dockerfile", "makefile", "make" -> "bash"
            "json", "jsonc", "json5", "jsonl", "ndjson" -> "json"
            "go", "golang" -> "go"
            "rs", "rust" -> "rust"
            "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "hh", "objc", "objective-c", "cuda", "arduino", "ino" -> "cpp"
            "swift" -> "swift"
            "yaml", "yml", "toml", "ini", "cfg", "conf", "properties" -> "yaml"
            "diff", "patch", "udiff" -> "diff"
            else -> null
        }
    }

    /** Language id from a file path's extension / name, used for Write/Read previews. */
    fun languageForPath(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val name = path.substringAfterLast('/').lowercase()
        when (name) {
            "makefile", "gnumakefile" -> return "makefile"
            "dockerfile", "containerfile" -> return "dockerfile"
            "cmakelists.txt" -> return "cmake"
            ".bashrc", ".zshrc", ".profile", ".bash_profile" -> return "bash"
        }
        val ext = name.substringAfterLast('.', "")
        if (ext.isEmpty()) return null
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "gradle" -> "gradle"
            "java" -> "java"
            "js", "jsx", "mjs", "cjs" -> "javascript"
            "ts", "tsx", "mts", "cts" -> "typescript"
            "py", "pyi" -> "python"
            "sh", "bash", "zsh" -> "bash"
            "json", "jsonc" -> "json"
            "jsonl", "ndjson" -> "jsonl"
            "go" -> "go"
            "rs" -> "rust"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp", "hh", "ino" -> "cpp"
            "swift" -> "swift"
            "yml", "yaml" -> "yaml"
            "toml" -> "toml"
            "ini", "cfg", "conf", "properties" -> "ini"
            "diff", "patch" -> "diff"
            "md", "markdown" -> "markdown"
            "html", "htm" -> "html"
            "xml" -> "xml"
            "css", "scss" -> "css"
            "sql" -> "sql"
            "txt", "log" -> "text"
            else -> ext
        }
    }

    fun highlight(code: String, language: String?): List<SynSpan> {
        val lang = normalize(language) ?: return emptyList()
        val src = if (code.length > MAX_CHARS) code.substring(0, MAX_CHARS) else code
        return try {
            when (lang) {
                "diff" -> diff(src)
                "json" -> json(src)
                "yaml" -> yaml(src)
                "bash" -> CodeScanner(src, BASH).scan()
                else -> CodeScanner(src, SPECS.getValue(lang)).scan()
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    // ───────────────────────────── Language specs ─────────────────────────────

    private class Spec(
        val keywords: Set<String>,
        val literals: Set<String> = setOf("true", "false", "null"),
        val lineComments: List<String> = listOf("//"),
        val blockComment: Pair<String, String>? = "/*" to "*/",
        val quotes: String = "\"'",
        val multilineQuote: Char? = null,
        val tripleQuotes: Boolean = false,
        val annotations: Boolean = false,
        val hashComment: Boolean = false,
        val dollarVars: Boolean = false,
        val capitalizedTypes: Boolean = true,
    )

    private fun words(s: String): Set<String> = s.trim().split(Regex("\\s+")).toSet()

    private val KOTLIN = Spec(
        keywords = words(
            """
            package import class interface object fun val var if else when for while do return break continue
            throw try catch finally is in as by get set init constructor companion data sealed enum abstract open
            override private protected public internal inline suspend operator infix tailrec lateinit const vararg
            reified crossinline noinline typealias this super where out annotation expect actual value external
            """
        ),
        tripleQuotes = true, annotations = true,
    )
    private val JAVA = Spec(
        keywords = words(
            """
            package import class interface enum extends implements new return if else for while do switch case
            default break continue throw throws try catch finally public private protected static final abstract
            synchronized volatile transient native strictfp void int long short byte char boolean float double
            this super instanceof var record sealed permits yield assert
            """
        ),
        annotations = true,
    )
    private val JS = Spec(
        keywords = words(
            """
            const let var function return if else for while do switch case default break continue throw try catch
            finally new delete typeof instanceof in of class extends super this import export from as async await
            yield static get set void debugger with
            """
        ),
        literals = setOf("true", "false", "null", "undefined", "NaN", "Infinity"),
        quotes = "\"'`", multilineQuote = '`',
    )
    private val TS = Spec(
        keywords = JS.keywords + words(
            """
            interface type enum implements namespace declare readonly private public protected abstract keyof
            infer is satisfies unique never unknown any string number boolean symbol bigint object
            """
        ),
        literals = JS.literals, quotes = "\"'`", multilineQuote = '`', annotations = true,
    )
    private val PYTHON = Spec(
        keywords = words(
            """
            def class return if elif else for while in not and or is import from as with try except finally raise
            pass break continue lambda yield global nonlocal assert del async await match case self print
            """
        ),
        literals = setOf("True", "False", "None"),
        lineComments = emptyList(), blockComment = null, tripleQuotes = true, annotations = true, hashComment = true,
    )
    private val GO = Spec(
        keywords = words(
            """
            package import func return if else for range switch case default break continue go defer select chan
            map struct interface type var const fallthrough goto string int int8 int16 int32 int64 uint uint8 uint16
            uint32 uint64 float32 float64 byte rune bool error any make new len cap append panic recover
            """
        ),
        literals = setOf("true", "false", "nil", "iota"),
        quotes = "\"'`", multilineQuote = '`',
    )
    private val RUST = Spec(
        keywords = words(
            """
            fn let mut const static struct enum trait impl pub crate mod use super self Self as return if else match
            for while loop in break continue move ref where type unsafe async await dyn extern box i8 i16 i32 i64
            i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str
            """
        ),
        literals = setOf("true", "false", "None", "Some", "Ok", "Err"),
        quotes = "\"", annotations = false,
    )
    private val CPP = Spec(
        keywords = words(
            """
            int long short char float double void bool unsigned signed const static extern volatile struct union
            enum typedef sizeof return if else for while do switch case default break continue goto class public
            private protected virtual override final template typename namespace using new delete this friend
            inline explicit operator try catch throw auto constexpr nullptr noexcept decltype mutable static_cast
            dynamic_cast reinterpret_cast const_cast std size_t uint8_t uint16_t uint32_t uint64_t int8_t int16_t
            int32_t int64_t
            """
        ),
        literals = setOf("true", "false", "NULL", "nullptr"),
    )
    private val SWIFT = Spec(
        keywords = words(
            """
            import class struct enum protocol extension func var let return if else guard for while repeat switch
            case default break continue in where throw throws rethrows try catch do defer init deinit self Self
            super static private fileprivate public internal open final override mutating lazy weak unowned
            inout some any async await actor typealias associatedtype subscript is as
            """
        ),
        literals = setOf("true", "false", "nil"),
        tripleQuotes = true, annotations = true,
    )
    private val BASH = Spec(
        keywords = words(
            """
            if then else elif fi for while until do done case esac function in select return exit break continue
            local export readonly declare unset source alias set shift trap eval exec echo printf cd sudo time
            """
        ),
        literals = setOf("true", "false"),
        lineComments = emptyList(), blockComment = null, quotes = "\"'", multilineQuote = '"',
        hashComment = true, dollarVars = true, capitalizedTypes = false,
    )

    private val SPECS = mapOf(
        "kotlin" to KOTLIN, "java" to JAVA, "js" to JS, "ts" to TS, "python" to PYTHON, "go" to GO,
        "rust" to RUST, "cpp" to CPP, "swift" to SWIFT, "bash" to BASH,
    )

    // ───────────────────────────── Generic scanner ─────────────────────────────

    private class CodeScanner(val s: String, val spec: Spec) {
        val out = ArrayList<SynSpan>()

        fun scan(): List<SynSpan> {
            var i = 0
            val n = s.length
            while (i < n) {
                val c = s[i]
                // Block comment.
                val bc = spec.blockComment
                if (bc != null && s.startsWith(bc.first, i)) {
                    val end = s.indexOf(bc.second, i + bc.first.length).let { if (it < 0) n else it + bc.second.length }
                    add(i, end, SynKind.COMMENT); i = end; continue
                }
                // Line comments.
                val lc = spec.lineComments.firstOrNull { s.startsWith(it, i) }
                val hashComment = spec.hashComment && c == '#' && (i == 0 || s[i - 1].isWhitespace())
                if (lc != null || hashComment) {
                    val end = s.indexOf('\n', i).let { if (it < 0) n else it }
                    add(i, end, SynKind.COMMENT); i = end; continue
                }
                // Triple-quoted strings.
                if (spec.tripleQuotes && (s.startsWith("\"\"\"", i) || s.startsWith("'''", i))) {
                    val q = s.substring(i, i + 3)
                    val end = s.indexOf(q, i + 3).let { if (it < 0) n else it + 3 }
                    add(i, end, SynKind.STRING); i = end; continue
                }
                // Strings / chars.
                if (spec.quotes.indexOf(c) >= 0) {
                    val end = stringEnd(i, c)
                    add(i, end, SynKind.STRING); i = end; continue
                }
                // Shell variables.
                if (spec.dollarVars && c == '$' && i + 1 < n) {
                    val end = if (s[i + 1] == '{') {
                        s.indexOf('}', i + 2).let { if (it < 0) n else it + 1 }
                    } else if (s[i + 1] == '(') {
                        i + 2 // highlight just "$(" as variable-ish punctuation
                    } else {
                        var k = i + 1
                        if (k < n && (s[k].isDigit() || s[k] in "@#?*!$-")) k++
                        else while (k < n && (s[k].isLetterOrDigit() || s[k] == '_')) k++
                        k
                    }
                    if (end > i + 1) { add(i, end, SynKind.VARIABLE); i = end; continue }
                }
                // Annotations / decorators.
                if (spec.annotations && c == '@' && i + 1 < n && s[i + 1].isLetter()) {
                    var k = i + 1
                    while (k < n && (s[k].isLetterOrDigit() || s[k] == '_' || s[k] == '.')) k++
                    add(i, k, SynKind.ANNOTATION); i = k; continue
                }
                // Numbers.
                if (c.isDigit() && (i == 0 || !isIdent(s[i - 1]))) {
                    val end = numberEnd(i)
                    add(i, end, SynKind.NUMBER); i = end; continue
                }
                // Identifiers.
                if (c.isLetter() || c == '_') {
                    var k = i + 1
                    while (k < n && isIdent(s[k])) k++
                    val w = s.substring(i, k)
                    when {
                        w in spec.keywords -> add(i, k, SynKind.KEYWORD)
                        w in spec.literals -> add(i, k, SynKind.NUMBER)
                        spec.capitalizedTypes && w[0].isUpperCase() && w.length > 1 -> add(i, k, SynKind.TYPE)
                    }
                    i = k; continue
                }
                i++
            }
            return out
        }

        private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || (c == '$' && !spec.dollarVars)

        private fun stringEnd(start: Int, q: Char): Int {
            val n = s.length
            var k = start + 1
            val multiline = spec.multilineQuote == q
            while (k < n) {
                val ch = s[k]
                // Shell single quotes have no escapes; everything else honours backslashes.
                if (ch == '\\' && !(spec.dollarVars && q == '\'')) { k += 2; continue }
                if (ch == q) return k + 1
                if (ch == '\n' && !multiline) return k
                k++
            }
            return n
        }

        private fun numberEnd(start: Int): Int {
            val n = s.length
            var k = start
            if (s[k] == '0' && k + 1 < n && (s[k + 1] == 'x' || s[k + 1] == 'X' || s[k + 1] == 'b' || s[k + 1] == 'B')) {
                k += 2
                while (k < n && (s[k].isLetterOrDigit() || s[k] == '_')) k++
                return k
            }
            while (k < n && (s[k].isDigit() || s[k] == '_')) k++
            if (k + 1 < n && s[k] == '.' && s[k + 1].isDigit()) {
                k++
                while (k < n && (s[k].isDigit() || s[k] == '_')) k++
            }
            if (k < n && (s[k] == 'e' || s[k] == 'E')) {
                var e = k + 1
                if (e < n && (s[e] == '+' || s[e] == '-')) e++
                if (e < n && s[e].isDigit()) {
                    k = e
                    while (k < n && s[k].isDigit()) k++
                }
            }
            while (k < n && s[k] in "fFlLuUdD") k++
            return k
        }

        private fun add(start: Int, end: Int, kind: SynKind) {
            if (end > start) out += SynSpan(start, end.coerceAtMost(s.length), kind)
        }
    }

    // ───────────────────────────── Special languages ─────────────────────────────

    private fun diff(s: String): List<SynSpan> {
        val out = ArrayList<SynSpan>()
        var start = 0
        while (start <= s.length) {
            val nl = s.indexOf('\n', start).let { if (it < 0) s.length else it }
            val line = s.substring(start, nl)
            val kind = when {
                line.startsWith("+++") || line.startsWith("---") -> SynKind.META
                line.startsWith("@@") -> SynKind.HUNK
                line.startsWith("+") -> SynKind.ADD
                line.startsWith("-") -> SynKind.DEL
                line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("\\ ") -> SynKind.COMMENT
                else -> null
            }
            if (kind != null && nl > start) out += SynSpan(start, nl, kind)
            if (nl >= s.length) break
            start = nl + 1
        }
        return out
    }

    private fun json(s: String): List<SynSpan> {
        val out = ArrayList<SynSpan>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' -> {
                    var k = i + 1
                    while (k < n && s[k] != '"' && s[k] != '\n') { if (s[k] == '\\') k++; k++ }
                    val end = (k + 1).coerceAtMost(n)
                    var j = end
                    while (j < n && (s[j] == ' ' || s[j] == '\t')) j++
                    out += SynSpan(i, end, if (j < n && s[j] == ':') SynKind.KEY else SynKind.STRING)
                    i = end
                }
                c == '/' && i + 1 < n && s[i + 1] == '/' -> {
                    val end = s.indexOf('\n', i).let { if (it < 0) n else it }
                    out += SynSpan(i, end, SynKind.COMMENT); i = end
                }
                c == '-' || c.isDigit() -> {
                    var k = i + 1
                    while (k < n && (s[k].isDigit() || s[k] in ".eE+-")) k++
                    if (k > i + (if (c == '-') 1 else 0)) out += SynSpan(i, k, SynKind.NUMBER)
                    i = k
                }
                s.startsWith("true", i) || s.startsWith("null", i) -> { out += SynSpan(i, i + 4, SynKind.KEYWORD); i += 4 }
                s.startsWith("false", i) -> { out += SynSpan(i, i + 5, SynKind.KEYWORD); i += 5 }
                else -> i++
            }
        }
        return out
    }

    private val YAML_KEY = Regex("^(\\s*(?:-\\s+)?)([\"']?[\\w.\\-/ ]+?[\"']?)(\\s*[:=])(?=\\s|$)")
    private val YAML_SECTION = Regex("^\\s*\\[[^\\]]+]\\s*$")
    private val YAML_SCALAR = Regex("^(true|false|null|yes|no|on|off|~|-?\\d+(?:\\.\\d+)?)$", RegexOption.IGNORE_CASE)

    private fun yaml(s: String): List<SynSpan> {
        val out = ArrayList<SynSpan>()
        var start = 0
        while (start <= s.length) {
            val nl = s.indexOf('\n', start).let { if (it < 0) s.length else it }
            val line = s.substring(start, nl)
            // Comment (whole line or trailing " #").
            val hash = line.indexOf('#').let { h -> if (h == 0 || (h > 0 && line[h - 1].isWhitespace())) h else -1 }
            val body = if (hash >= 0) line.substring(0, hash) else line
            if (YAML_SECTION.matches(body)) {
                out += SynSpan(start, start + body.trimEnd().length, SynKind.TYPE)
            } else {
                val m = YAML_KEY.find(body)
                var valueFrom = 0
                if (m != null) {
                    val g = m.groups[2]!!
                    out += SynSpan(start + g.range.first, start + g.range.last + 1, SynKind.KEY)
                    valueFrom = m.range.last + 1
                }
                var value = body.substring(valueFrom).trim()
                if (m == null && value.startsWith("- ")) value = value.removePrefix("- ").trim()
                if (value.isNotEmpty()) {
                    val vStart = start + body.indexOf(value, valueFrom)
                    val kind = when {
                        value.startsWith("\"") || value.startsWith("'") -> SynKind.STRING
                        YAML_SCALAR.matches(value) -> if (value[0].isDigit() || value[0] == '-') SynKind.NUMBER else SynKind.KEYWORD
                        value.startsWith("&") || value.startsWith("*") -> SynKind.ANNOTATION
                        else -> null
                    }
                    if (kind != null) out += SynSpan(vStart, vStart + value.length, kind)
                }
            }
            if (hash >= 0) out += SynSpan(start + hash, nl, SynKind.COMMENT)
            if (nl >= s.length) break
            start = nl + 1
        }
        return out
    }
}
