package app.tether.ui.chat.render

/*
 * A small, dependency-free Markdown parser tuned for Claude's output (GFM subset).
 *
 * Pure Kotlin — no Android / Compose types — so it is unit-testable on the JVM and cheap to run
 * on every streaming delta. It is deliberately forgiving: anything it does not understand is kept
 * as literal text, unclosed code fences render as code (streaming-safe), and it never throws.
 */

enum class MdAlign { START, CENTER, END }

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val children: List<MdInline>) : MdInline
    data class Italic(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val code: String) : MdInline
    data class Link(val url: String, val children: List<MdInline>) : MdInline
    /** A file path such as `src/foo/Bar.kt:12` found in running text — rendered in mono. */
    data class Path(val path: String) : MdInline
    data object LineBreak : MdInline
}

data class MdListItem(val blocks: List<MdBlock>, val checked: Boolean? = null)

sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    /** [closed] = false while a fence is still streaming in (no closing ``` yet). */
    data class CodeFence(val code: String, val language: String?, val closed: Boolean = true) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
    data class Table(
        val header: List<List<MdInline>>,
        val aligns: List<MdAlign>,
        val rows: List<List<List<MdInline>>>,
    ) : MdBlock
}

object MarkdownParser {

    fun parse(text: String): List<MdBlock> {
        if (text.isEmpty()) return emptyList()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return try {
            parseBlocks(lines, 0)
        } catch (t: Throwable) {
            // Never let odd input take the conversation down.
            listOf(MdBlock.Paragraph(listOf(MdInline.Text(text))))
        }
    }

    fun parseInlines(text: String): List<MdInline> = try {
        InlineParser(text).parse(0, text.length, allowAuto = true)
    } catch (t: Throwable) {
        listOf(MdInline.Text(text))
    }

    /** Plain text of an inline run (used for copy, accessibility and tests). */
    fun plainText(inlines: List<MdInline>): String = buildString {
        fun walk(list: List<MdInline>) {
            for (n in list) when (n) {
                is MdInline.Text -> append(n.text)
                is MdInline.Bold -> walk(n.children)
                is MdInline.Italic -> walk(n.children)
                is MdInline.Strike -> walk(n.children)
                is MdInline.Code -> append(n.code)
                is MdInline.Link -> walk(n.children)
                is MdInline.Path -> append(n.path)
                MdInline.LineBreak -> append('\n')
            }
        }
        walk(inlines)
    }

    // ───────────────────────────── Block level ─────────────────────────────

    private const val MAX_DEPTH = 12

    private val HEADING = Regex("^(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$")
    private val HR = Regex("^(?:(?:\\*[ \\t]*){3,}|(?:-[ \\t]*){3,}|(?:_[ \\t]*){3,})$")
    private val SETEXT_EQ = Regex("^=+[ \\t]*$")
    private val SETEXT_DASH = Regex("^-+[ \\t]*$")
    private val TABLE_DELIM = Regex("^\\|?[ \\t]*:?-+:?[ \\t]*(?:\\|[ \\t]*:?-+:?[ \\t]*)*\\|?[ \\t]*$")
    private val TASK = Regex("^\\[([ xX])](?:[ \\t]+|$)")

    private fun parseBlocks(lines: List<String>, depth: Int): List<MdBlock> {
        if (depth > MAX_DEPTH) {
            return listOf(MdBlock.Paragraph(parseInlines(lines.joinToString("\n") { it.trim() })))
        }
        val out = ArrayList<MdBlock>()
        val para = ArrayList<String>()
        fun flushPara() {
            if (para.isEmpty()) return
            out += MdBlock.Paragraph(parseInlines(joinParagraph(para)))
            para.clear()
        }

        var i = 0
        val n = lines.size
        while (i < n) {
            val line = lines[i]
            if (line.isBlank()) { flushPara(); i++; continue }
            val ind = indentOf(line)

            if (ind >= 4) {
                if (para.isNotEmpty()) { para += line; i++; continue } // lazy paragraph continuation
                // Indented code block.
                val code = ArrayList<String>()
                while (i < n && (lines[i].isBlank() || indentOf(lines[i]) >= 4)) {
                    code += stripIndent(lines[i], 4); i++
                }
                while (code.isNotEmpty() && code.last().isBlank()) code.removeAt(code.size - 1)
                out += MdBlock.CodeFence(code.joinToString("\n"), null, closed = true)
                continue
            }

            val t = line.trimStart()

            // Fenced code.
            val fence = fenceOpen(t)
            if (fence != null) {
                flushPara()
                val (ch, len, lang) = fence
                val code = ArrayList<String>()
                var closed = false
                i++
                while (i < n) {
                    val l = lines[i]
                    if (indentOf(l) < 4 && isFenceClose(l.trimStart(), ch, len)) { closed = true; i++; break }
                    code += stripIndent(l, ind)
                    i++
                }
                if (!closed && code.isNotEmpty()) {
                    // A half-typed closing fence while streaming ("`" or "``") is noise, not code.
                    val last = code.last().trim()
                    if (last.isNotEmpty() && last.length < len && last.all { it == ch }) code.removeAt(code.size - 1)
                }
                out += MdBlock.CodeFence(code.joinToString("\n"), lang, closed)
                continue
            }

            // ATX heading.
            val heading = HEADING.matchEntire(t)
            if (heading != null) {
                flushPara()
                out += MdBlock.Heading(heading.groupValues[1].length, parseInlines(heading.groupValues[2].trim()))
                i++
                continue
            }

            // Setext heading (only directly under paragraph text).
            if (para.isNotEmpty() && (SETEXT_EQ.matches(t) || (SETEXT_DASH.matches(t) && t.trim().length >= 2))) {
                val level = if (t.startsWith("=")) 1 else 2
                out += MdBlock.Heading(level, parseInlines(joinParagraph(para)))
                para.clear()
                i++
                continue
            }

            // Thematic break.
            if (HR.matches(t.trimEnd())) { flushPara(); out += MdBlock.Rule; i++; continue }

            // Blockquote.
            if (t.startsWith(">")) {
                flushPara()
                val q = ArrayList<String>()
                while (i < n) {
                    val l = lines[i]
                    val lt = l.trimStart()
                    if (indentOf(l) < 4 && lt.startsWith(">")) {
                        var rest = lt.substring(1)
                        if (rest.startsWith(" ")) rest = rest.substring(1)
                        q += rest; i++
                    } else if (l.isNotBlank() && q.isNotEmpty() && q.last().isNotBlank() && !startsBlock(l)) {
                        q += l.trimStart(); i++ // lazy continuation
                    } else break
                }
                out += MdBlock.Quote(parseBlocks(q, depth + 1))
                continue
            }

            // List.
            val marker = listMarker(line)
            if (marker != null) {
                val canInterrupt = para.isEmpty() ||
                    (!marker.ordered && marker.content.isNotBlank()) ||
                    (marker.ordered && marker.number == 1 && marker.content.isNotBlank())
                if (canInterrupt) {
                    flushPara()
                    i = parseList(lines, i, marker, out, depth)
                    continue
                }
            }

            // GFM table: header row followed by a delimiter row.
            if (t.contains('|') && i + 1 < n && isTableStart(t, lines[i + 1])) {
                flushPara()
                i = parseTable(lines, i, out)
                continue
            }

            para += line
            i++
        }
        flushPara()
        return out
    }

    private fun joinParagraph(lines: List<String>): String {
        val sb = StringBuilder()
        lines.forEachIndexed { idx, raw ->
            var l = raw.trim()
            if (l.endsWith("\\") && !l.endsWith("\\\\")) l = l.dropLast(1)
            sb.append(l)
            if (idx < lines.size - 1) sb.append('\n')
        }
        return sb.toString()
    }

    /** Returns (fence char, fence length, language) when [t] opens a code fence. */
    private fun fenceOpen(t: String): Triple<Char, Int, String?>? {
        if (t.length < 3) return null
        val ch = t[0]
        if (ch != '`' && ch != '~') return null
        var len = 0
        while (len < t.length && t[len] == ch) len++
        if (len < 3) return null
        val info = t.substring(len).trim()
        if (ch == '`' && info.contains('`')) return null
        val lang = info.split(' ', '\t').firstOrNull()
            ?.trim('{', '}', '.')
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase()
        return Triple(ch, len, lang)
    }

    private fun isFenceClose(t: String, ch: Char, len: Int): Boolean {
        var k = 0
        while (k < t.length && t[k] == ch) k++
        return k >= len && t.substring(k).isBlank()
    }

    private fun startsBlock(line: String): Boolean {
        if (indentOf(line) >= 4) return false
        val t = line.trimStart()
        return fenceOpen(t) != null || HEADING.matches(t) || HR.matches(t.trimEnd()) ||
            t.startsWith(">") || listMarker(line) != null
    }

    internal data class Marker(
        val indent: Int,
        val ordered: Boolean,
        val number: Int,
        val contentCol: Int,
        val content: String,
    )

    internal fun listMarker(line: String): Marker? {
        val ind = indentOf(line)
        val rest = line.trimStart()
        if (rest.isEmpty()) return null
        var markerLen: Int
        var ordered = false
        var number = 0
        val c0 = rest[0]
        if (c0 == '-' || c0 == '*' || c0 == '+') {
            markerLen = 1
        } else if (c0.isDigit()) {
            var k = 0
            while (k < rest.length && k < 9 && rest[k].isDigit()) k++
            if (k >= rest.length || (rest[k] != '.' && rest[k] != ')')) return null
            number = rest.substring(0, k).toIntOrNull() ?: return null
            markerLen = k + 1
            ordered = true
        } else return null
        if (markerLen < rest.length && rest[markerLen] != ' ' && rest[markerLen] != '\t') return null
        var spaces = 0
        var p = markerLen
        while (p < rest.length && (rest[p] == ' ' || rest[p] == '\t')) { spaces += if (rest[p] == '\t') 4 else 1; p++ }
        val content = rest.substring(p)
        val contentCol = when {
            content.isEmpty() -> ind + markerLen + 1
            spaces > 4 -> ind + markerLen + 1
            else -> ind + markerLen + spaces
        }
        return Marker(ind, ordered, number, contentCol, content)
    }

    private fun parseList(lines: List<String>, startIdx: Int, first: Marker, out: MutableList<MdBlock>, depth: Int): Int {
        val items = ArrayList<MdListItem>()
        var cur = ArrayList<String>()
        var curMarker = first
        cur += first.content
        var i = startIdx + 1
        val n = lines.size

        fun finishItem() {
            while (cur.isNotEmpty() && cur.last().isBlank()) cur.removeAt(cur.size - 1)
            var checked: Boolean? = null
            if (cur.isNotEmpty()) {
                TASK.find(cur[0])?.let { m ->
                    checked = m.groupValues[1] != " "
                    cur[0] = cur[0].substring(m.range.last + 1)
                }
            }
            items += MdListItem(parseBlocks(cur, depth + 1), checked)
        }

        while (i < n) {
            val line = lines[i]
            val m = listMarker(line)
            if (m != null && m.ordered == first.ordered && m.indent < curMarker.contentCol &&
                m.indent <= first.indent + 3 && !HR.matches(line.trim())
            ) {
                finishItem()
                cur = ArrayList()
                cur += m.content
                curMarker = m
                i++
                continue
            }
            if (line.isBlank()) {
                var j = i + 1
                while (j < n && lines[j].isBlank()) j++
                if (j >= n) break
                val nl = lines[j]
                val nm = listMarker(nl)
                val continues = indentOf(nl) >= curMarker.contentCol ||
                    (nm != null && nm.ordered == first.ordered && nm.indent < curMarker.contentCol && nm.indent <= first.indent + 3)
                if (continues) {
                    for (k in i until j) cur += ""
                    i = j
                    continue
                }
                break
            }
            val ind = indentOf(line)
            if (ind >= curMarker.contentCol) { cur += stripIndent(line, curMarker.contentCol); i++; continue }
            // Lenient nesting: a marker indented past this item's marker belongs to it.
            if (m != null && m.indent > curMarker.indent) { cur += stripIndent(line, ind); i++; continue }
            // Lazy continuation of the item's paragraph.
            if (cur.isNotEmpty() && cur.last().isNotBlank() && !startsBlock(line) && fenceOpen(cur.last().trimStart()) == null) {
                cur += line.trimStart(); i++; continue
            }
            break
        }
        finishItem()
        out += MdBlock.ListBlock(first.ordered, if (first.ordered) first.number else 1, items)
        return i
    }

    private fun isTableStart(header: String, delim: String): Boolean {
        val d = delim.trim()
        if (!d.contains('-') || !TABLE_DELIM.matches(d)) return false
        if (!d.contains('|') && !header.contains('|')) return false
        val hc = splitRow(header).size
        val dc = splitRow(d).size
        return hc == dc && hc > 0
    }

    private fun parseTable(lines: List<String>, startIdx: Int, out: MutableList<MdBlock>): Int {
        val headerCells = splitRow(lines[startIdx].trim())
        val aligns = splitRow(lines[startIdx + 1].trim()).map { c ->
            val s = c.trim()
            when {
                s.startsWith(":") && s.endsWith(":") -> MdAlign.CENTER
                s.endsWith(":") -> MdAlign.END
                else -> MdAlign.START
            }
        }
        val cols = headerCells.size
        val rows = ArrayList<List<List<MdInline>>>()
        var i = startIdx + 2
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank() || !l.contains('|') || (startsBlock(l) && !l.trimStart().startsWith("|"))) break
            val cells = splitRow(l.trim())
            rows += (0 until cols).map { c -> parseInlines(cells.getOrElse(c) { "" }) }
            i++
        }
        out += MdBlock.Table(headerCells.map { parseInlines(it) }, aligns, rows)
        return i
    }

    /** Splits a table row on unescaped pipes outside code spans. */
    internal fun splitRow(row: String): List<String> {
        var s = row.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = ArrayList<String>()
        val sb = StringBuilder()
        var inCode = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> { sb.append('|'); i += 2; continue }
                c == '`' -> {
                    var run = 0
                    while (i + run < s.length && s[i + run] == '`') run++
                    inCode = if (inCode == 0) run else if (inCode == run) 0 else inCode
                    sb.append(s, i, i + run); i += run; continue
                }
                c == '|' && inCode == 0 -> { cells += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        cells += sb.toString().trim()
        return cells
    }

    internal fun indentOf(line: String): Int {
        var col = 0
        for (c in line) {
            when (c) {
                ' ' -> col++
                '\t' -> col += 4 - (col % 4)
                else -> return col
            }
        }
        return col
    }

    /** Removes up to [cols] columns of leading whitespace (tabs count to the next multiple of 4). */
    internal fun stripIndent(line: String, cols: Int): String {
        if (cols <= 0) return line
        var col = 0
        var i = 0
        while (i < line.length && col < cols) {
            val c = line[i]
            if (c == ' ') { col++; i++ }
            else if (c == '\t') {
                val w = 4 - (col % 4)
                if (col + w > cols) {
                    // Tab overshoots: keep the remainder as spaces.
                    return " ".repeat(col + w - cols) + line.substring(i + 1)
                }
                col += w; i++
            } else break
        }
        return line.substring(i)
    }

    // ───────────────────────────── Inline level ─────────────────────────────

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>\"'$&%=:;,/?@^"

    private val URL = Regex("https?://[^\\s<>\"'`]+")
    private val PATH = Regex(
        "(?<![\\w/.~@:\\-])" +
            "((?:(?:~|\\.{1,2})?/(?:[\\w.@+\\-]+/)*|(?:[\\w.@+\\-]+/)+)[\\w.@+\\-]+(?::\\d+(?:[:\\-]\\d+)?)?" +
            "|[\\w.\\-]+\\.[A-Za-z][A-Za-z0-9]{0,7}:\\d+(?:[:\\-]\\d+)?)" +
            "(?![\\w/])"
    )
    private val EXT = Regex("\\.[A-Za-z][A-Za-z0-9]{0,7}$")
    private val LINE_SUFFIX = Regex(":\\d+(?:[:\\-]\\d+)?$")
    private val NUMERIC_PATH = Regex("^[\\d/.:\\-]+$")

    private class InlineParser(val s: String) {

        fun parse(from: Int, to: Int, allowAuto: Boolean, nest: Int = 0): List<MdInline> {
            val out = ArrayList<MdInline>()
            val buf = StringBuilder()
            fun flush() {
                if (buf.isEmpty()) return
                val text = buf.toString()
                buf.clear()
                if (allowAuto) out.addAll(autolink(text)) else out += MdInline.Text(text)
            }
            if (nest > 16) { buf.append(s, from, to); flush(); return out }

            var i = from
            while (i < to) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < to && s[i + 1] == '\n' -> { flush(); out += MdInline.LineBreak; i += 2 }
                    c == '\\' && i + 1 < to && ESCAPABLE.indexOf(s[i + 1]) >= 0 -> { buf.append(s[i + 1]); i += 2 }
                    c == '\n' -> { flush(); out += MdInline.LineBreak; i++ }
                    c == '`' -> {
                        val run = runLength(i, to, '`')
                        val close = findBacktickClose(i + run, to, run)
                        if (close >= 0) {
                            flush()
                            var code = s.substring(i + run, close).replace('\n', ' ')
                            if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) {
                                code = code.substring(1, code.length - 1)
                            }
                            out += MdInline.Code(code)
                            i = close + run
                        } else {
                            buf.append(s, i, i + run); i += run
                        }
                    }
                    c == '!' && i + 1 < to && s[i + 1] == '[' -> {
                        val link = tryLink(i + 1, to)
                        if (link != null) {
                            flush()
                            val alt = s.substring(link.textStart, link.textEnd).trim().ifEmpty { "image" }
                            out += MdInline.Link(link.url, listOf(MdInline.Text(alt)))
                            i = link.end
                        } else { buf.append(c); i++ }
                    }
                    c == '[' -> {
                        val link = tryLink(i, to)
                        if (link != null) {
                            flush()
                            val children = parse(link.textStart, link.textEnd, allowAuto = false, nest = nest + 1)
                            out += MdInline.Link(link.url, children.ifEmpty { listOf(MdInline.Text(link.url)) })
                            i = link.end
                        } else { buf.append(c); i++ }
                    }
                    c == '<' -> {
                        val close = s.indexOf('>', i + 1)
                        val inner = if (close in (i + 1) until to) s.substring(i + 1, close) else null
                        if (inner != null && !inner.contains(' ') &&
                            (inner.startsWith("http://") || inner.startsWith("https://") || inner.startsWith("mailto:"))
                        ) {
                            flush()
                            out += MdInline.Link(inner, listOf(MdInline.Text(inner.removePrefix("mailto:"))))
                            i = close + 1
                        } else { buf.append(c); i++ }
                    }
                    c == '*' || c == '_' -> {
                        val run = runLength(i, to, c)
                        val prev = if (i > 0) s[i - 1] else ' '
                        val next = if (i + run < to) s[i + run] else ' '
                        val canOpen = !next.isWhitespace() && !(c == '_' && prev.isLetterOrDigit())
                        var matched = false
                        if (canOpen) {
                            var k = minOf(run, 3)
                            while (k >= 1 && !matched) {
                                val close = findDelim(i + run, to, c, k)
                                if (close > i + run) {
                                    if (run > k) buf.append(c.toString().repeat(run - k))
                                    flush()
                                    val inner = parse(i + run, close, allowAuto, nest + 1)
                                    out += when (k) {
                                        3 -> MdInline.Bold(listOf(MdInline.Italic(inner)))
                                        2 -> MdInline.Bold(inner)
                                        else -> MdInline.Italic(inner)
                                    }
                                    i = close + k
                                    // Swallow the rest of a longer closing run (e.g. "***" closing "*").
                                    matched = true
                                }
                                k--
                            }
                        }
                        if (!matched) { buf.append(s, i, i + run); i += run }
                    }
                    c == '~' && i + 1 < to && s[i + 1] == '~' -> {
                        val run = runLength(i, to, '~')
                        val next = if (i + run < to) s[i + run] else ' '
                        val close = if (run == 2 && !next.isWhitespace()) findDelim(i + 2, to, '~', 2) else -1
                        if (close > i + 2) {
                            flush()
                            out += MdInline.Strike(parse(i + 2, close, allowAuto, nest + 1))
                            i = close + 2
                        } else { buf.append(s, i, i + run); i += run }
                    }
                    else -> { buf.append(c); i++ }
                }
            }
            flush()
            return out
        }

        private fun runLength(i: Int, to: Int, c: Char): Int {
            var k = 0
            while (i + k < to && s[i + k] == c) k++
            return k
        }

        private fun findBacktickClose(from: Int, to: Int, run: Int): Int {
            var j = from
            while (j < to) {
                if (s[j] == '`') {
                    val r = runLength(j, to, '`')
                    if (r == run) return j
                    j += r
                } else j++
            }
            return -1
        }

        /** Finds a closing delimiter run for emphasis/strike, skipping code spans and escapes. */
        private fun findDelim(from: Int, to: Int, c: Char, k: Int): Int {
            var j = from
            while (j < to) {
                val ch = s[j]
                when {
                    ch == '\\' -> j += 2
                    ch == '`' -> {
                        val r = runLength(j, to, '`')
                        val close = findBacktickClose(j + r, to, r)
                        j = if (close >= 0) close + r else j + r
                    }
                    ch == c -> {
                        val r = runLength(j, to, c)
                        val prev = s[j - 1]
                        val after = if (j + r < to) s[j + r] else ' '
                        val validPrev = !prev.isWhitespace()
                        val validAfter = !(c == '_' && after.isLetterOrDigit())
                        if (validPrev && validAfter) {
                            if (r == k) return j
                            if (r == 3 && k < 3) return j + (3 - k)
                        }
                        j += r
                    }
                    else -> j++
                }
            }
            return -1
        }

        private class LinkMatch(val textStart: Int, val textEnd: Int, val url: String, val end: Int)

        private fun tryLink(open: Int, to: Int): LinkMatch? {
            var depth = 0
            var j = open
            var closeBracket = -1
            while (j < to) {
                val ch = s[j]
                when (ch) {
                    '\\' -> { j += 2; continue }
                    '`' -> {
                        val r = runLength(j, to, '`')
                        val close = findBacktickClose(j + r, to, r)
                        j = if (close >= 0) close + r else j + r
                        continue
                    }
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { closeBracket = j; break } }
                    '\n' -> if (j + 1 < to && s[j + 1] == '\n') return null
                }
                j++
            }
            if (closeBracket < 0 || closeBracket + 1 >= to || s[closeBracket + 1] != '(') return null
            var k = closeBracket + 2
            var parens = 1
            val urlStart = k
            while (k < to) {
                val ch = s[k]
                if (ch == '\\') { k += 2; continue }
                if (ch == '\n') return null
                if (ch == '(') parens++
                if (ch == ')') { parens--; if (parens == 0) break }
                k++
            }
            if (k >= to) return null
            var raw = s.substring(urlStart, k).trim()
            if (raw.startsWith("<")) raw = raw.substringAfter('<').substringBefore('>')
            else raw = raw.split(' ', '\t').first()
            if (raw.isEmpty()) return null
            return LinkMatch(open + 1, closeBracket, raw, k + 1)
        }
    }

    /** Splits plain text into text, bare URLs and file paths. */
    internal fun autolink(text: String): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<MdInline>()
        var last = 0
        for (m in URL.findAll(text)) {
            var url = m.value
            // Trim trailing punctuation, and a ')' that is not balanced inside the URL.
            while (url.isNotEmpty()) {
                val lc = url.last()
                if (lc in ".,;:!?'\"*_") { url = url.dropLast(1); continue }
                if (lc == ')' && url.count { it == '(' } < url.count { it == ')' }) { url = url.dropLast(1); continue }
                break
            }
            if (url.length <= "https://".length) continue
            val start = m.range.first
            if (start > last) out.addAll(linkPaths(text.substring(last, start)))
            out += MdInline.Link(url, listOf(MdInline.Text(url)))
            last = start + url.length
        }
        if (last < text.length) out.addAll(linkPaths(text.substring(last)))
        return out
    }

    private fun linkPaths(text: String): List<MdInline> {
        if (!text.contains('/') && !text.contains(':')) return listOf(MdInline.Text(text))
        val out = ArrayList<MdInline>()
        var last = 0
        for (m in PATH.findAll(text)) {
            var p = m.value
            while (p.isNotEmpty() && p.last() == '.') p = p.dropLast(1)
            if (!looksLikePath(p)) continue
            val start = m.range.first
            if (start > last) out += MdInline.Text(text.substring(last, start))
            out += MdInline.Path(p)
            last = start + p.length
        }
        if (last == 0) return listOf(MdInline.Text(text))
        if (last < text.length) out += MdInline.Text(text.substring(last))
        return out
    }

    internal fun looksLikePath(p: String): Boolean {
        if (p.length < 3 || NUMERIC_PATH.matches(p)) return false
        val bare = p.replace(LINE_SUFFIX, "")
        val hasLine = bare.length != p.length
        val slashes = bare.count { it == '/' }
        val lastSeg = bare.substringAfterLast('/')
        return bare.startsWith("/") && slashes >= 2 ||
            bare.startsWith("~/") || bare.startsWith("./") || bare.startsWith("../") ||
            (slashes >= 1 && EXT.containsMatchIn(lastSeg)) ||
            slashes >= 2 ||
            (hasLine && EXT.containsMatchIn(lastSeg))
    }
}
