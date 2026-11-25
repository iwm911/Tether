package app.tether.ui.chat.render

/*
 * Files Claude shows on the phone: an image or a Markdown file on the session's machine, named in
 * the chat by path (`/home/me/out/chart.png`, `` `docs/report.md` ``, `![chart](out/chart.png)`).
 * Pure Kotlin so it is unit-testable; the screen reads the file over SFTP ([RemoteFiles]).
 */

enum class PreviewKind { IMAGE, MARKDOWN }

object FilePreview {
    /** Link target the renderer uses for a previewable file: the chat's link handler opens it in the app. */
    const val SCHEME = "tether-file:"

    /** Biggest file the app reads: an image is downscaled after reading, Markdown is shown as is. */
    const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    const val MAX_MARKDOWN_BYTES = 2L * 1024 * 1024

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    private val MARKDOWN_EXT = setOf("md", "markdown", "mdown", "mkd")
    private val LINE_SUFFIX = Regex(":\\d+(?:[:\\-]\\d+)?$")
    private val SCHEME_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    /**
     * The path in [raw] without decoration (`file://`, a `:12` line suffix, surrounding quotes), or null when
     * it isn't a local file path at all (a URL, an anchor, empty, a path with a line break).
     */
    fun clean(raw: String): String? {
        var p = raw.trim().trim('"', '\'')
        if (p.startsWith("file://")) p = p.removePrefix("file://")
        if (p.isEmpty() || p.startsWith("#") || '\n' in p || '\u0000' in p) return null
        p = p.replace(LINE_SUFFIX, "")
        if (SCHEME_PREFIX.containsMatchIn(p)) return null // http:, mailto:, data:, …
        return p.ifEmpty { null }
    }

    /** What the app can show for [raw], or null when it's not an image or Markdown file. */
    fun kind(raw: String): PreviewKind? {
        val p = clean(raw) ?: return null
        val name = p.substringAfterLast('/')
        if (!name.contains('.')) return null
        val ext = name.substringAfterLast('.').lowercase()
        return when (ext) {
            in IMAGE_EXT -> PreviewKind.IMAGE
            in MARKDOWN_EXT -> PreviewKind.MARKDOWN
            else -> null
        }
    }

    /**
     * Inline code that names a previewable file (`` `out/chart.png` ``): one token, no spaces, an image or
     * Markdown extension. A bare `README.md` counts; prose and shell commands don't.
     */
    fun isFileCode(code: String): Boolean {
        val c = code.trim()
        if (c.isEmpty() || c.length > 512 || c.any { it.isWhitespace() || it in "<>|*?$`;&(){}" }) return false
        return kind(c) != null
    }

    fun link(path: String): String = SCHEME + path

    /** The path a [link] points to, or null for any other link. */
    fun fromLink(url: String): String? = if (url.startsWith(SCHEME)) clean(url.removePrefix(SCHEME)) else null

    /**
     * Absolute path of [path] on the machine: absolute and `~/` paths stay as they are (SFTP expands `~/`),
     * anything else is relative to [base] (the session's folder; for a link inside a Markdown file, that
     * file's folder). `.` and `..` segments are folded. Null when a relative path has no base.
     */
    fun resolve(path: String, base: String?): String? {
        val p = clean(path) ?: return null
        val joined = when {
            p.startsWith("/") || p == "~" || p.startsWith("~/") -> p
            base.isNullOrBlank() -> return null
            else -> base.trimEnd('/') + "/" + p
        }
        val home = joined.startsWith("~/")
        val parts = ArrayList<String>()
        for (seg in joined.removePrefix("~").split('/')) when (seg) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += seg
        }
        return (if (home) "~/" else "/") + parts.joinToString("/")
    }

    /** Folder that holds [path] (for links inside a Markdown file). */
    fun parent(path: String): String = path.substringBeforeLast('/', "/").ifEmpty { "/" }

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    /**
     * The local images a paragraph shows inline (`![alt](path)`), in order; empty for none. Remote images
     * (http) are not fetched: they'd tell a third party the chat was opened.
     */
    fun inlineImages(inlines: List<MdInline>): List<MdInline.Link> {
        val out = ArrayList<MdInline.Link>()
        fun walk(list: List<MdInline>) {
            for (n in list) when (n) {
                is MdInline.Link -> if (n.image && kind(n.url) == PreviewKind.IMAGE) out += n
                is MdInline.Bold -> walk(n.children)
                is MdInline.Italic -> walk(n.children)
                is MdInline.Strike -> walk(n.children)
                else -> Unit
            }
        }
        walk(inlines)
        return out
    }

    /** True when the paragraph is nothing but local images (and blanks): the images replace its text. */
    fun onlyImages(inlines: List<MdInline>): Boolean = inlines.isNotEmpty() && inlines.all {
        when (it) {
            is MdInline.Link -> it.image && kind(it.url) == PreviewKind.IMAGE
            is MdInline.Text -> it.text.isBlank()
            MdInline.LineBreak -> true
            else -> false
        }
    }
}
