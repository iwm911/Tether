package app.tether.ui.chat.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewTest {

    @Test
    fun kindByExtension() {
        assertEquals(PreviewKind.IMAGE, FilePreview.kind("/home/me/out/chart.PNG"))
        assertEquals(PreviewKind.IMAGE, FilePreview.kind("shot.jpeg"))
        assertEquals(PreviewKind.IMAGE, FilePreview.kind("a/b.webp:3"))
        assertEquals(PreviewKind.MARKDOWN, FilePreview.kind("docs/REPORT.md"))
        assertEquals(PreviewKind.MARKDOWN, FilePreview.kind("~/notes.markdown"))
        assertNull(FilePreview.kind("src/Main.kt"))
        assertNull(FilePreview.kind("Makefile"))
        assertNull(FilePreview.kind("https://x.io/a.png")) // remote images are not fetched
        assertNull(FilePreview.kind("data:image/png;base64,AAA"))
        assertNull(FilePreview.kind("#heading.md"))
    }

    @Test
    fun cleanStripsDecoration() {
        assertEquals("/a/b.md", FilePreview.clean("file:///a/b.md"))
        assertEquals("README.md", FilePreview.clean("README.md:12"))
        assertEquals("x/y.png", FilePreview.clean(" 'x/y.png' "))
        assertNull(FilePreview.clean("mailto:a@b.c"))
        assertNull(FilePreview.clean(""))
    }

    @Test
    fun fileCodeIsOneToken() {
        assertTrue(FilePreview.isFileCode("out/chart.png"))
        assertTrue(FilePreview.isFileCode("README.md"))
        assertFalse(FilePreview.isFileCode("open out/chart.png"))
        assertFalse(FilePreview.isFileCode("cat *.md"))
        assertFalse(FilePreview.isFileCode("main.kt"))
    }

    @Test
    fun linkRoundTrip() {
        assertEquals("out/chart.png", FilePreview.fromLink(FilePreview.link("out/chart.png:4")))
        assertNull(FilePreview.fromLink("https://a.b/c.png"))
    }

    @Test
    fun resolveAgainstBase() {
        assertEquals("/p/out/chart.png", FilePreview.resolve("out/chart.png", "/p/"))
        assertEquals("/p/out/chart.png", FilePreview.resolve("./out/chart.png", "/p"))
        assertEquals("/docs/a.md", FilePreview.resolve("../docs/a.md", "/p"))
        assertEquals("/tmp/x.png", FilePreview.resolve("/tmp/./x.png", "/p"))
        assertEquals("~/notes/a.md", FilePreview.resolve("~/notes/a.md", null))
        assertNull(FilePreview.resolve("rel.md", null))
        assertEquals("/p", FilePreview.parent("/p/a.md"))
        assertEquals("/", FilePreview.parent("/a.md"))
        assertEquals("a.md", FilePreview.name("/p/a.md"))
    }

    @Test
    fun inlineImagesOnlyLocalOnes() {
        val inl = MarkdownParser.parseInlines("Here: ![the chart](out/chart.png) and ![logo](https://i.io/l.png) [doc](a.png)")
        val images = FilePreview.inlineImages(inl)
        assertEquals(listOf("out/chart.png"), images.map { it.url })
        assertFalse(FilePreview.onlyImages(inl))
        assertTrue(FilePreview.onlyImages(MarkdownParser.parseInlines("![a](/x/a.png) ![b](b.gif)")))
        assertFalse(FilePreview.onlyImages(MarkdownParser.parseInlines("![a](https://x/a.png)")))
    }

    @Test
    fun imageSyntaxIsMarked() {
        val img = MarkdownParser.parseInlines("![c](/x/c.png)").single() as MdInline.Link
        assertTrue(img.image)
        val link = MarkdownParser.parseInlines("[c](/x/c.png)").single() as MdInline.Link
        assertFalse(link.image)
    }
}
