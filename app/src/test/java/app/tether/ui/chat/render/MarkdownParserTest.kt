package app.tether.ui.chat.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private fun parse(md: String) = MarkdownParser.parse(md)
    private fun inl(md: String) = MarkdownParser.parseInlines(md)
    private fun text(list: List<MdInline>) = MarkdownParser.plainText(list)

    // ───────────── headings ─────────────

    @Test
    fun atxHeadingsAllLevels() {
        val blocks = parse("# One\n## Two ##\n###### Six\n#NotAHeading")
        assertEquals(4, blocks.size)
        val h1 = blocks[0] as MdBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("One", text(h1.inlines))
        val h2 = blocks[1] as MdBlock.Heading
        assertEquals(2, h2.level)
        assertEquals("Two", text(h2.inlines))
        assertEquals(6, (blocks[2] as MdBlock.Heading).level)
        assertTrue(blocks[3] is MdBlock.Paragraph)
    }

    @Test
    fun headingInterruptsParagraphAndKeepsInlineMarkup() {
        val blocks = parse("intro line\n## The **plan**")
        assertEquals(2, blocks.size)
        val h = blocks[1] as MdBlock.Heading
        assertTrue(h.inlines.any { it is MdInline.Bold })
    }

    @Test
    fun setextHeadings() {
        val blocks = parse("Title\n=====\n\nSub\n---")
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
    }

    @Test
    fun horizontalRule() {
        val blocks = parse("above\n\n---\n\n***\nbelow")
        assertEquals(MdBlock.Rule, blocks[1])
        assertEquals(MdBlock.Rule, blocks[2])
    }

    // ───────────── lists ─────────────

    @Test
    fun nestedBulletAndOrderedLists() {
        val md = """
            - one
            - two
              - two.a
              - two.b
                1. deep
            - three
        """.trimIndent()
        val blocks = parse(md)
        assertEquals(1, blocks.size)
        val list = blocks[0] as MdBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(3, list.items.size)
        val second = list.items[1]
        val nested = second.blocks.filterIsInstance<MdBlock.ListBlock>().single()
        assertEquals(2, nested.items.size)
        val deep = nested.items[1].blocks.filterIsInstance<MdBlock.ListBlock>().single()
        assertTrue(deep.ordered)
        assertEquals("deep", text((deep.items[0].blocks[0] as MdBlock.Paragraph).inlines))
    }

    @Test
    fun orderedListStartNumberAndLooseItems() {
        val md = "3. three\n\n4. four\n   continued\n\nafter"
        val blocks = parse(md)
        val list = blocks[0] as MdBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
        assertEquals("four\ncontinued", text((list.items[1].blocks[0] as MdBlock.Paragraph).inlines))
        assertTrue(blocks[1] is MdBlock.Paragraph)
    }

    @Test
    fun orderedListWithTwoSpaceNestedBullets() {
        val md = "1. Build\n  - compile\n  - link\n2. Test"
        val list = parse(md)[0] as MdBlock.ListBlock
        assertEquals(2, list.items.size)
        val nested = list.items[0].blocks.filterIsInstance<MdBlock.ListBlock>().single()
        assertEquals(2, nested.items.size)
    }

    @Test
    fun taskListCheckboxes() {
        val list = parse("- [ ] todo\n- [x] done\n- [X] also done\n- plain")[0] as MdBlock.ListBlock
        assertEquals(false, list.items[0].checked)
        assertEquals(true, list.items[1].checked)
        assertEquals(true, list.items[2].checked)
        assertNull(list.items[3].checked)
        assertEquals("todo", text((list.items[0].blocks[0] as MdBlock.Paragraph).inlines))
    }

    @Test
    fun boldAtLineStartIsNotAList() {
        val blocks = parse("**Note:** careful")
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue((blocks[0] as MdBlock.Paragraph).inlines.first() is MdInline.Bold)
    }

    // ───────────── code ─────────────

    @Test
    fun fencedCodeWithLanguage() {
        val md = "Before\n```kotlin\nfun main() {\n    println(\"*not italic*\")\n}\n```\nAfter"
        val blocks = parse(md)
        assertEquals(3, blocks.size)
        val code = blocks[1] as MdBlock.CodeFence
        assertEquals("kotlin", code.language)
        assertTrue(code.closed)
        assertEquals("fun main() {\n    println(\"*not italic*\")\n}", code.code)
    }

    @Test
    fun unclosedFenceIsCodeWhileStreaming() {
        val blocks = parse("Here:\n```py\nprint(1)\nprint(2)")
        val code = blocks.last() as MdBlock.CodeFence
        assertFalse(code.closed)
        assertEquals("py", code.language)
        assertEquals("print(1)\nprint(2)", code.code)
    }

    @Test
    fun partialClosingFenceIsDropped() {
        val code = parse("```\nx = 1\n``").single() as MdBlock.CodeFence
        assertFalse(code.closed)
        assertEquals("x = 1", code.code)
    }

    @Test
    fun tildeFenceAndLongerBacktickFence() {
        val a = parse("~~~\n```\ninside\n```\n~~~").single() as MdBlock.CodeFence
        assertEquals("```\ninside\n```", a.code)
        val b = parse("````md\n```\nnested\n```\n````").single() as MdBlock.CodeFence
        assertEquals("md", b.language)
        assertEquals("```\nnested\n```", b.code)
    }

    @Test
    fun indentedCodeBlock() {
        val blocks = parse("para\n\n    val x = 1\n    val y = 2\n\nend")
        val code = blocks[1] as MdBlock.CodeFence
        assertNull(code.language)
        assertEquals("val x = 1\nval y = 2", code.code)
    }

    @Test
    fun fenceInsideListItem() {
        val md = "1. Run:\n   ```bash\n   npm test\n   ```\n2. Done"
        val list = parse(md).single() as MdBlock.ListBlock
        assertEquals(2, list.items.size)
        val code = list.items[0].blocks.filterIsInstance<MdBlock.CodeFence>().single()
        assertEquals("bash", code.language)
        assertEquals("npm test", code.code)
    }

    // ───────────── quotes ─────────────

    @Test
    fun blockquoteWithNestedList() {
        val q = parse("> Note\n> - a\n> - b").single() as MdBlock.Quote
        assertTrue(q.blocks[0] is MdBlock.Paragraph)
        assertEquals(2, (q.blocks[1] as MdBlock.ListBlock).items.size)
    }

    // ───────────── tables ─────────────

    @Test
    fun gfmTableWithAlignment() {
        val md = """
            | Name | Size | Kind |
            |:-----|-----:|:----:|
            | `a.kt` | 12 | **src** |
            | b.kt | 3 |
        """.trimIndent()
        val t = parse(md).single() as MdBlock.Table
        assertEquals(3, t.header.size)
        assertEquals(listOf(MdAlign.START, MdAlign.END, MdAlign.CENTER), t.aligns)
        assertEquals(2, t.rows.size)
        assertTrue(t.rows[0][0].single() is MdInline.Code)
        assertTrue(t.rows[0][2].single() is MdInline.Bold)
        // Short rows are padded to the header width.
        assertEquals(3, t.rows[1].size)
        assertEquals("", text(t.rows[1][2]))
    }

    @Test
    fun tableInterruptsParagraphAndHandlesEscapedPipes() {
        val blocks = parse("Results:\na | b\n--|--\n`x|y` | c \\| d")
        assertTrue(blocks[0] is MdBlock.Paragraph)
        val t = blocks[1] as MdBlock.Table
        assertEquals("x|y", (t.rows[0][0].single() as MdInline.Code).code)
        assertEquals("c | d", text(t.rows[0][1]))
    }

    @Test
    fun pipeTextWithoutDelimiterIsParagraph() {
        assertTrue(parse("a | b\nc | d").single() is MdBlock.Paragraph)
    }

    // ───────────── inline ─────────────

    @Test
    fun emphasisVariants() {
        val r = inl("**bold** and *it* and _it2_ and ~~gone~~ and ***both***")
        assertEquals("bold", text((r[0] as MdInline.Bold).children))
        assertTrue(r.any { it is MdInline.Italic && text(it.children) == "it" })
        assertTrue(r.any { it is MdInline.Italic && text(it.children) == "it2" })
        assertTrue(r.any { it is MdInline.Strike && text(it.children) == "gone" })
        val both = r.last() as MdInline.Bold
        assertTrue(both.children.single() is MdInline.Italic)
    }

    @Test
    fun snakeCaseAndArithmeticStayLiteral() {
        assertEquals(listOf(MdInline.Text("my_var_name")), inl("my_var_name"))
        assertEquals(listOf(MdInline.Text("2 * 3 * 4")), inl("2 * 3 * 4"))
    }

    @Test
    fun nestedEmphasisInsideItalic() {
        val it = inl("*a **b** c*").single() as MdInline.Italic
        assertTrue(it.children.any { n -> n is MdInline.Bold })
    }

    @Test
    fun inlineCodeProtectsMarkup() {
        val r = inl("run `rm -rf *build*` now")
        val code = r.filterIsInstance<MdInline.Code>().single()
        assertEquals("rm -rf *build*", code.code)
        assertTrue(r.none { it is MdInline.Italic })
    }

    @Test
    fun doubleBacktickCodeWithBacktickInside() {
        val code = inl("`` a`b ``").single() as MdInline.Code
        assertEquals("a`b", code.code)
    }

    @Test
    fun unclosedMarkersAreLiteral() {
        assertEquals("**not closed", text(inl("**not closed")))
        assertEquals("`open", text(inl("`open")))
    }

    @Test
    fun linksImagesAndAutolinks() {
        val r = inl("See [the docs](https://example.com/a_(b)) or <https://x.io> or https://bare.dev/path.")
        val links = r.filterIsInstance<MdInline.Link>()
        assertEquals(3, links.size)
        assertEquals("https://example.com/a_(b)", links[0].url)
        assertEquals("the docs", text(links[0].children))
        assertEquals("https://x.io", links[1].url)
        assertEquals("https://bare.dev/path", links[2].url) // trailing period trimmed
        assertTrue(text(r).endsWith("."))
        val img = inl("![logo](https://i.io/l.png)").single() as MdInline.Link
        assertEquals("logo", text(img.children))
    }

    @Test
    fun linkTextWithFormatting() {
        val link = inl("[**bold** link](http://a.b)").single() as MdInline.Link
        assertTrue(link.children.first() is MdInline.Bold)
    }

    @Test
    fun filePathsBecomePathSpans() {
        val r = inl("Edited src/foo/Bar.kt:12 and ~/notes.md, see ./run.sh or App.kt:40 — not and/or, 1/2, or 2024/01/02.")
        val paths = r.filterIsInstance<MdInline.Path>().map { it.path }
        assertEquals(listOf("src/foo/Bar.kt:12", "~/notes.md", "./run.sh", "App.kt:40"), paths)
    }

    @Test
    fun urlIsNotAlsoAPath() {
        val r = inl("open https://github.com/a/b/c.kt now")
        assertEquals(1, r.filterIsInstance<MdInline.Link>().size)
        assertTrue(r.none { it is MdInline.Path })
    }

    @Test
    fun escapesAndLineBreaks() {
        val r = inl("\\*literal\\* line\nnext")
        assertEquals("*literal* line\nnext", text(r))
        assertTrue(r.contains(MdInline.LineBreak))
    }

    @Test
    fun emptyAndWhitespaceInput() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("\n\n   \n").isEmpty())
    }

    @Test
    fun neverThrowsOnHostileInput() {
        val junk = buildString {
            repeat(200) { append("*_~`[](<>|#>- 1. ```") }
            append("\n")
            repeat(50) { append("> ") }
            append("deep")
        }
        parse(junk) // must not throw
        parse("- ".repeat(500) + "x")
    }
}
