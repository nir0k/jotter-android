package io.github.nir0k.jotter.core.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's commands over the body model: what each change writes, checked through the
 * desktop's writer, so the phone writes what the desktop would.
 */
class EditingTest {
    /** Returns a state for a body with the cursor at a run offset of the root's first run. */
    private fun state(body: String, at: Int = 0, to: Int = at): EditorState {
        val doc = parseBody(body)
        val run = doc.runsIn(RootContainer).first()
        return EditorState(doc, run.select(at, to))
    }

    private val EditorState.body: String get() = serializeBody(doc)

    /** Types text at the cursor of a state. */
    private fun EditorState.type(text: String): EditorState {
        val range = textRange()!!
        return replaceText(range.run.container, range.run.key, range.from, range.to, text)!!
    }

    /** Typing joins the text in the format before the cursor; deleting across blocks joins them. */
    @Test
    fun typingAndJoining() {
        val typed = state("<p><strong>ab</strong></p>\n<p>cd</p>", 2).type("X")
        assertEquals("<p><strong>abX</strong></p>\n<p>cd</p>", typed.body)
        val joined = state("<h2>ab</h2>\n<p>cd</p>", 1, 4).type("")
        assertEquals("<h2>ad</h2>", joined.body)
        val lines = state("<p>ab</p>", 1).type("1\n2")
        assertEquals("<p>a1</p>\n<p>2b</p>", lines.body)
    }

    /** Enter splits blocks; at the end of a heading it starts a paragraph; an empty item leaves its list. */
    @Test
    fun enter() {
        assertEquals("<h1>ab</h1>\n<p></p>", state("<h1>ab</h1>", 2).enter { it }!!.body)
        val item = state("<ul>\n<li>a</li>\n</ul>", 1).enter { it }!!
        assertEquals("<ul>\n<li>a</li>\n<li></li>\n</ul>", item.body)
        assertEquals("<ul>\n<li>a</li>\n</ul>\n<p></p>", item.enter { it }!!.body)
        val fence = state("<p>```kt</p>", 5).enter { if (it == "kt") "kotlin" else it }!!
        assertEquals("<pre data-language=\"kotlin\"><code></code></pre>", fence.body)
        assertTrue(fence.selection is CodeSelection)
        assertEquals("<hr>\n<p></p>", state("<p>---</p>", 3).enter { it }!!.body)
    }

    /** Backspace at the start of a list item or a quote takes it out; elsewhere it joins the block before. */
    @Test
    fun backspaceAtStart() {
        assertEquals("<p>a</p>", state("<ul>\n<li>a</li>\n</ul>").backspaceAtStart()!!.body)
        assertEquals("<p>a</p>", state("<blockquote>\n<p>a</p>\n</blockquote>").backspaceAtStart()!!.body)
        assertEquals("<p>ab</p>", state("<p>a</p>\n<p>b</p>", 2).backspaceAtStart()!!.body)
        // After a divider, joining removes the divider.
        assertEquals("<p>a</p>", state("<hr>\n<p>a</p>", 1).backspaceAtStart()!!.body)
    }

    /** Indenting nests items under the item before, with their own nested items; outdenting moves them back. */
    @Test
    fun indentAndOutdent() {
        val body = "<ol>\n<li>a</li>\n<li>b\n<ul>\n<li>c</li>\n</ul>\n</li>\n</ol>"
        val doc = parseBody(body)
        val run = doc.runsIn(RootContainer).first()
        val b = EditorState(doc, run.cursor(run.startOf(1)))
        assertFalse(EditorState(doc, run.cursor(0)).canIndent())
        val indented = b.indent()!!
        assertEquals("<ol>\n<li>a\n<ol>\n<li>b\n<ul>\n<li>c</li>\n</ul>\n</li>\n</ol>\n</li>\n</ol>", indented.body)
        assertEquals(body, indented.outdent()!!.body)
        assertEquals("<ol>\n<li>a</li>\n</ol>\n<p>b</p>\n<ul>\n<li>c</li>\n</ul>", b.outdent()!!.body)
    }

    /** Lists: making, changing the kind, numbering styles and starting numbers, and the markers drawn. */
    @Test
    fun lists() {
        val made = state("<p>a</p>\n<p>b</p>", 0, 3).toggleList(ListKind.Check)!!
        assertEquals("<ul class=\"checklist\">\n<li data-checked=\"false\">a</li>\n<li data-checked=\"false\">b</li>\n</ul>", made.body)
        assertEquals("<ul class=\"checklist\">\n<li data-checked=\"true\">a</li>\n<li data-checked=\"false\">b</li>\n</ul>", made.toggleChecked()!!.body)
        val numbered = made.toggleList(ListKind.Number)!!.setNumbering("upper-roman")!!
        assertEquals("<ol data-style=\"upper-roman\">\n<li>a</li>\n<li>b</li>\n</ol>", numbered.body)
        val run = numbered.doc.runsIn(RootContainer).first()
        val restarted = numbered.copy(selection = run.cursor(2)).startNumbering(5)!!
        assertEquals("<ol data-style=\"upper-roman\">\n<li>a</li>\n</ol>\n<ol data-style=\"upper-roman\" start=\"5\">\n<li>b</li>\n</ol>", restarted.body)
        val nested = parseBody("<ol data-style=\"multilevel\">\n<li>a\n<ol data-style=\"multilevel\">\n<li>b</li>\n<li>c</li>\n</ol>\n</li>\n<li>d</li>\n</ol>")
        assertEquals(listOf("1.", "1.1.", "1.2.", "2."), listMarkers(nested.runsIn(RootContainer).first().blocks).map { it.first })
    }

    /** Inline formats toggle over a selection across blocks and for the text typed next at a cursor. */
    @Test
    fun marks() {
        val bold = state("<p>ab</p>\n<p>cd</p>", 1, 4).toggleMark(Mark.Bold)!!
        assertEquals("<p>a<strong>b</strong></p>\n<p><strong>c</strong>d</p>", bold.body)
        assertEquals("<p>ab</p>\n<p>cd</p>", bold.toggleMark(Mark.Bold)!!.body)
        val stored = state("<p>a</p>", 1).toggleMark(Mark.Italic)!!.type("b")
        assertEquals("<p>a<em>b</em></p>", stored.body)
        val sup = state("<p><sub>a</sub></p>", 0, 1).toggleMark(Mark.Sup)!!
        assertEquals("<p><sup>a</sup></p>", sup.body)
        val colored = state("<p>ab</p>", 0, 1).setColor(highlight = false, key = "#112233")!!
        assertEquals("<p><span data-color=\"#112233\" style=\"color: #112233\">a</span>b</p>", colored.body)
    }

    /** Links: inserting, editing the one at the cursor and removing it; text typed after a link is not part of it. */
    @Test
    fun links() {
        val linked = state("<p>see x</p>", 4, 5).setLink("site", "https://example.com")!!
        assertEquals("<p>see <a href=\"https://example.com\">site</a></p>", linked.body)
        assertEquals("<p>see <a href=\"https://example.com\">site</a>!</p>", linked.type("!").body)
        val atLink = linked.copy(selection = linked.doc.runsIn(RootContainer).first().cursor(6), stored = null)
        assertEquals("site" to "https://example.com", atLink.linkDialogText())
        assertEquals("<p>see site</p>", atLink.removeLink()!!.body)
    }

    /** Shortcuts typed in text: headings, lists, inline code and web addresses. */
    @Test
    fun inputRules() {
        fun typed(body: String, at: Int, char: Char): EditorState? {
            val state = state(body, at).type(char.toString())
            return (state.inputRule(char) as? RuleResult.Changed)?.state
        }
        assertEquals("<h2></h2>", typed("<p>##</p>", 2, ' ')!!.body)
        assertEquals("<ol start=\"3\">\n<li></li>\n</ol>", typed("<p>3.</p>", 2, ' ')!!.body)
        assertEquals("<p>a <code>b</code></p>", typed("<p>a `b</p>", 4, '`')!!.body)
        assertEquals("<p><a href=\"https://x.org\">https://x.org</a> </p>", typed("<p>https://x.org</p>", 13, ' ')!!.body)
        assertNull(typed("<ul>\n<li>#</li>\n</ul>", 1, ' '))
        val picker = state("<p>[</p>", 1).type("[").inputRule('[')
        assertTrue(picker is RuleResult.NotePicker)
    }

    /** Code blocks take the selected lines; frames keep the empty paragraph the desktop writes between them. */
    @Test
    fun codeBlocks() {
        val code = state("<p>a</p>\n<p>b</p>\n<p>c</p>", 0, 3).insertCodeBlock()!!
        assertEquals("<pre><code>a\nb</code></pre>\n<p>c</p>", code.body)
        val indented = code.copy(selection = CodeSelection((code.doc.items[1] as CodeBlock).id, 0, 3)).codeIndent()!!
        assertEquals("<pre><code>    a\n    b</code></pre>\n<p>c</p>", indented.body)
        val twice = code.copy(selection = CodeSelection((code.doc.items[1] as CodeBlock).id, 0, 0)).insertCodeBlock()
        assertNull(twice)
        val table = state("<pre><code>x</code></pre>").let { it.copy(selection = endOf(it.doc.items[1])) }.insertTable(1, 1, false)
        assertEquals("<pre><code>x</code></pre>\n<p></p>\n<table>\n<tbody>\n<tr><td><p></p></td></tr>\n</tbody>\n</table>", table.body)
    }

    /** The table menu: columns keep their proportions, header rows follow, and the last row or column takes the table. */
    @Test
    fun tables() {
        val body = "<table>\n<colgroup><col style=\"width: 20%\"><col style=\"width: 80%\"></colgroup>\n<thead>\n<tr><th><p>a</p></th><th><p>b</p></th></tr>\n</thead>\n</table>"
        val doc = parseBody(body)
        val table = doc.items[1] as Table
        val cell = table.cells[0][1][0]
        val inCell = EditorState(doc, TextSelection(CellContainer(table.id, 0, 1), Point(cell.id, 0), Point(cell.id, 0)))
        val added = inCell.tableAction(TableAction.AddColumnAfter)!!
        assertEquals(listOf(13, 54, 33), (added.doc.items[1] as Table).widths)
        val rows = inCell.tableAction(TableAction.AddRowAfter)!!
        assertEquals("<table>\n<colgroup><col style=\"width: 20%\"><col style=\"width: 80%\"></colgroup>\n<thead>\n<tr><th><p>a</p></th><th><p>b</p></th></tr>\n</thead>\n<tbody>\n<tr><td><p></p></td><td><p></p></td></tr>\n</tbody>\n</table>", rows.body)
        assertEquals("<p></p>", inCell.tableAction(TableAction.DeleteRow)!!.body)
        val next = inCell.goToCell(1)!!
        assertEquals(2, (next.doc.items[1] as Table).rows)
    }

    /** Find ignores case across blocks, cells and code. */
    @Test
    fun find() {
        val doc = parseBody("<p>Cat cat</p>\n<pre><code>cat</code></pre>\n<p>dog</p>")
        assertEquals(3, doc.findMatches("CAT").size)
    }

    /** A paste of several blocks splits the current one; a single paragraph goes into it. */
    @Test
    fun paste() {
        val two = state("<p>ab</p>", 1).paste(parseBody("<p>1</p>\n<h1>2</h1>"))!!
        assertEquals("<p>a</p>\n<p>1</p>\n<h1>2</h1>\n<p>b</p>", two.body)
        val one = state("<p>ab</p>", 1).paste(parseBody("<p><em>1</em></p>"))!!
        assertEquals("<p>a<em>1</em>b</p>", one.body)
        val (pending, requests) = parseBody("<p><img src=\"../attachments/other/a.png\"></p>").withPendingObjects("note") { "#1" }
        assertEquals(listOf(StoreRequest("#1", "../attachments/other/a.png", true)), requests)
        val stored = pending.resolvePending("#1", "note", InsertedFile("a.png", 1, true))
        assertEquals("<p><img src=\"../attachments/note/a.png\"></p>", serializeBody(stored))
    }

    /** A selection survives as two numbers, as on rotation. */
    @Test
    fun globalOffsets() {
        val doc = parseBody("<p>ab</p>\n<pre><code>xyz</code></pre>\n<p>cd</p>")
        val run = doc.runsIn(RootContainer).last()
        val selection = run.select(0, 2)
        val anchor = doc.globalOffset(selection, head = false)
        val head = doc.globalOffset(selection, head = true)
        assertEquals(selection, doc.selectionAt(anchor, head))
        val code = CodeSelection((doc.items[1] as CodeBlock).id, 1, 2)
        assertEquals(code, doc.selectionAt(doc.globalOffset(code, false), doc.globalOffset(code, true)))
        assertNotNull(doc.selectionAt(1000, 1000))
    }
    /**
     * A selected image moves to a place in the same block, another block or a table cell, keeping
     * its width and wrapping and staying selected; a place beside it or on a divider moves nothing.
     */
    @Test
    fun moveSelectedObject() {
        val image = "<img src=\"../attachments/n/a.png\" data-width=\"50\" data-wrap=\"square\">"
        val body = "<p>a${image}bc</p>\n<p>de</p>\n<hr>\n<table>\n<tbody>\n<tr><td><p>x</p></td></tr>\n</tbody>\n</table>"
        val selected = state(body, 1, 2)
        val run = selected.doc.runsIn(RootContainer).first()
        val later = selected.moveSelectedObject(run.cursor(3))!!
        assertEquals("<p>ab${image}c</p>\n<p>de</p>", later.body.substringBefore("\n<hr>"))
        assertTrue(later.selectedObject() is ImageObject)
        val below = selected.moveSelectedObject(run.cursor(6))!!
        assertEquals("<p>abc</p>\n<p>d${image}e</p>", below.body.substringBefore("\n<hr>"))
        val table = selected.doc.items.filterIsInstance<Table>().single()
        val cell = selected.doc.runsIn(CellContainer(table.id, 0, 0)).single()
        val inCell = selected.moveSelectedObject(cell.cursor(1))!!
        assertTrue(inCell.body.contains("<td><p>x$image</p></td>"))
        assertTrue(inCell.selectedObject() is ImageObject)
        assertNull(selected.moveSelectedObject(run.cursor(1)))
        assertNull(selected.moveSelectedObject(run.cursor(2)))
        val divider = selected.doc.items.filterIsInstance<Block>().first { it.hr }
        assertNull(selected.moveSelectedObject(TextSelection(RootContainer, Point(divider.id, 0), Point(divider.id, 0))))
    }
}
