package io.github.nir0k.jotter.core.body

import io.github.nir0k.jotter.core.format.escapedAttribute
import io.github.nir0k.jotter.core.format.escapedText
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.node.Block as MarkdownBlock

// Pasted Markdown: a port of the desktop's (editor/Markdown.cpp), which parses GitHub Markdown with
// md4c into HTML in the note vocabulary and reads that HTML into the note model. Here commonmark-java
// parses it; embedded HTML stays text, and only fenced code makes a code block. Unlike the desktop,
// a link or a picture without a scheme is relative to where the text came from and is not kept.

/**
 * The block kinds notes hold. HTML blocks are left out, so embedded HTML reads as text; indented
 * code is recognised only to undo it (see [withoutIndentedCode]).
 */
private val BlockTypes: Set<Class<out MarkdownBlock>> = setOf(
    Heading::class.java, ThematicBreak::class.java, FencedCodeBlock::class.java, IndentedCodeBlock::class.java,
    BlockQuote::class.java, ListBlock::class.java,
)

/** GitHub Markdown as notes hold it: tables, strikethrough, task lists and bare web and mail addresses as links. */
private val GitHubParser: Parser = Parser.builder()
    .enabledBlockTypes(BlockTypes)
    .extensions(
        listOf(
            TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create(),
            AutolinkExtension.create(),
        ),
    )
    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
    .build()

/** CommonMark alone, which links only explicit links and addresses in angle brackets, not bare ones. */
private val CommonMarkParser: Parser = Parser.builder().enabledBlockTypes(BlockTypes).build()

/** How many times indented code is undone before the rest is left as text. */
private const val IndentPasses = 8

/** The width of the indentation that makes a CommonMark code block, and of a tab stop. */
private const val CodeIndent = 4

/** An ATX heading's first line: a run of `#` that ends the line or is followed by white space. */
private val AtxHeading = Regex("^[ \\t]*#{1,6}(?:[ \\t]|$)")

/**
 * Parses pasted text as GitHub Markdown into the note model, without interpreting embedded HTML.
 *
 * As on the desktop, the conversion keeps what notes hold and simplifies the rest: headings deeper
 * than three become level three, a list item's later paragraphs join it after a line break, a line
 * of `-` or `=` under text is a divider below it rather than a heading underline, every line break
 * stays, and a picture on the web becomes a link to it. Indentation never makes code, as copied
 * text often indents its lists by a tab or four spaces. Links keep only absolute web and mail
 * addresses; a relative one, such as `page.html`, leaves its text.
 *
 * @param text The clipboard text.
 * @param requireExplicitSyntax Whether bare web addresses alone are not Markdown, as when the
 *   clipboard offers HTML too, whose formatting they must not replace.
 * @param language Returns the stored language of a code fence's language, which is given in lower case.
 * @return The content, or null when the text has no Markdown formatting and goes in as typed.
 */
fun readMarkdown(text: String, requireExplicitSyntax: Boolean = false, language: (String) -> String = { it }): Doc? {
    val (lines, document) = withoutIndentedCode(text)
    val converter = MarkdownToHtml(lines, language)
    converter.block(document)
    var recognised = converter.formatted
    if (!recognised && converter.linked) {
        recognised = !requireExplicitSyntax || MarkdownToHtml(lines, language).also {
            it.block(CommonMarkParser.parse(lines.joinToString("\n")))
        }.linked
    }
    return if (recognised) parseMarkdownHtml(converter.html.toString()) else null
}

/**
 * Parses text with indented code undone, as md4c reads it without indented code blocks, where any
 * indentation may start a block: the lines of every indented code block, and the later lines of a
 * paragraph indented as code, which could start a list or a heading there, lose that indentation,
 * and the text is parsed again, until none is left or [IndentPasses] runs out.
 *
 * @return The lines of the text as last parsed, and its document.
 */
private fun withoutIndentedCode(text: String): Pair<List<String>, Node> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
    var document = GitHubParser.parse(lines.joinToString("\n"))
    repeat(IndentPasses) {
        val spans = buildList {
            collect(document) { node ->
                when (node) {
                    is IndentedCodeBlock -> addAll(node.sourceSpans)
                    is Paragraph -> addAll(node.sourceSpans.drop(1).filter { indentation(lines[it.lineIndex], it.columnIndex) >= CodeIndent })
                }
            }
        }
        if (spans.isEmpty()) return lines to document
        for (span in spans) lines[span.lineIndex] = unindented(lines[span.lineIndex], span.columnIndex)
        document = GitHubParser.parse(lines.joinToString("\n"))
    }
    return lines to document
}

/** Calls an action on a node and on all its descendants, in document order. */
private fun collect(node: Node, action: (Node) -> Unit) {
    action(node)
    var child = node.firstChild
    while (child != null) {
        collect(child, action)
        child = child.next
    }
}

/** Returns the column a tab stop or another character at a column moves to. */
private fun nextColumn(c: Char, column: Int): Int = if (c == '\t') (column / CodeIndent + 1) * CodeIndent else column + 1

/** Returns the visual column of an offset of a line, with tab stops every [CodeIndent] columns. */
private fun columnOf(line: String, offset: Int): Int = (0 until offset).fold(0) { column, i -> nextColumn(line[i], column) }

/** Returns how many columns the spaces and tabs at an offset of a line fill. */
private fun indentation(line: String, from: Int): Int {
    val start = columnOf(line, from)
    var column = start
    for (i in from until line.length) {
        if (line[i] != ' ' && line[i] != '\t') break
        column = nextColumn(line[i], column)
    }
    return column - start
}

/**
 * Removes the code indentation of a line at an offset: spaces and tabs filling [CodeIndent]
 * columns; a tab that reaches past them leaves the columns it has left as spaces.
 */
private fun unindented(line: String, from: Int): String {
    var column = columnOf(line, from)
    val end = column + CodeIndent
    var i = from
    while (i < line.length && column < end) {
        if (line[i] != ' ' && line[i] != '\t') break
        column = nextColumn(line[i], column)
        i++
    }
    return line.substring(0, from) + " ".repeat(maxOf(0, column - end)) + line.substring(i)
}

/**
 * Converts a parsed Markdown document into HTML in the note vocabulary, which the reader turns
 * into the note model, and notes whether the text has Markdown formatting at all.
 *
 * @param lines The parsed lines, to tell a heading opened with `#` from an underlined one.
 * @param language Returns the stored language of a code fence's language.
 */
private class MarkdownToHtml(private val lines: List<String>, private val language: (String) -> String) {
    /** The HTML of the converted text. */
    val html = StringBuilder()

    /** Whether the text has block structure, emphasis, code or images. */
    var formatted = false

    /** Whether the text has a link to a web or mail address. */
    var linked = false

    /** Per open list item: whether it has text yet. */
    private val itemText = ArrayList<Boolean>()

    /** How many links the current text is in. */
    private var links = 0

    /** Writes a block and its content. */
    fun block(node: Node) {
        when (node) {
            is BlockQuote -> {
                formatted = true
                wrap(node, "<blockquote>", "</blockquote>")
            }
            is BulletList -> {
                formatted = true
                // A bulleted list whose first item is a task is a checklist; its other items are unchecked tasks.
                val checklist = node.firstChild?.firstChild is TaskListItemMarker
                html.append(if (checklist) "<ul class=\"checklist\">" else "<ul>")
                items(node, checklist)
                html.append("</ul>")
            }
            is OrderedList -> {
                formatted = true
                val start = node.markerStartNumber ?: 1
                html.append(if (start == 1) "<ol>" else "<ol start=\"$start\">")
                items(node, false)
                html.append("</ol>")
            }
            is ThematicBreak -> {
                formatted = true
                html.append("<hr>")
            }
            is Heading -> heading(node)
            is FencedCodeBlock -> {
                formatted = true
                // Every code line ends with a line feed, the last one included.
                val code = node.literal.orEmpty().removeSuffix("\n")
                val key = node.info.orEmpty().trim().substringBefore(' ').substringBefore('\t').lowercase()
                val stored = if (key.isEmpty()) "" else language(key)
                html.append(if (stored.isEmpty()) "<pre>" else "<pre data-language=\"${escapedAttribute(stored)}\">")
                html.append("<code>").append(escapedText(code)).append("</code></pre>")
            }
            is IndentedCodeBlock -> {
                // Indentation deeper than the passes undo stays text.
                html.append("<p>")
                html.append(node.literal.orEmpty().removeSuffix("\n").split('\n').joinToString("<br>") { escapedText(it) })
                html.append("</p>")
            }
            is Paragraph -> if (node.parent is ListItem) {
                if (itemText.last()) html.append("<br>")
                itemText[itemText.lastIndex] = true
                inlines(node)
            } else {
                wrap(node, "<p>", "</p>", inline = true)
            }
            is TableBlock -> {
                formatted = true
                wrap(node, "<table>", "</table>")
            }
            is TableHead -> wrap(node, "<thead>", "</thead>")
            is TableBody -> wrap(node, "<tbody>", "</tbody>")
            is TableRow -> wrap(node, "<tr>", "</tr>")
            is TableCell -> if (node.isHeader) wrap(node, "<th><p>", "</p></th>", inline = true) else wrap(node, "<td><p>", "</p></td>", inline = true)
            else -> children(node) // The document; link reference definitions write nothing.
        }
    }

    /** Writes the blocks in a node. */
    private fun children(node: Node) {
        var child = node.firstChild
        while (child != null) {
            block(child)
            child = child.next
        }
    }

    /** Writes a node's content between two tags: its blocks, or its inline content when [inline]. */
    private fun wrap(node: Node, open: String, close: String, inline: Boolean = false) {
        html.append(open)
        if (inline) inlines(node) else children(node)
        html.append(close)
    }

    /** Writes the items of a list; in a checklist each is a task, checked when its mark is `x`. */
    private fun items(list: ListBlock, checklist: Boolean) {
        var item = list.firstChild
        while (item != null) {
            if (checklist) {
                val checked = (item.firstChild as? TaskListItemMarker)?.isChecked == true
                html.append("<li data-checked=\"$checked\">")
            } else {
                html.append("<li>")
            }
            itemText += false
            children(item)
            itemText.removeAt(itemText.lastIndex)
            html.append("</li>")
            item = item.next
        }
    }

    /**
     * Writes a heading, at most of level three. One underlined with `-` or `=` is a paragraph with a
     * divider below, as plain text uses such lines to separate parts; a list item holds no divider,
     * so its headings stay.
     */
    private fun heading(node: Heading) {
        formatted = true
        val level = node.level.coerceIn(1, 3)
        val span = node.sourceSpans.firstOrNull()
        val underlined = span != null && !AtxHeading.containsMatchIn(lines[span.lineIndex].substring(span.columnIndex))
        if (underlined && node.parent !is ListItem) {
            wrap(node, "<p>", "</p>", inline = true)
            html.append("<hr>")
        } else {
            wrap(node, "<h$level>", "</h$level>", inline = true)
        }
    }

    /** Writes the inline content of a node. */
    private fun inlines(node: Node) {
        var child = node.firstChild
        while (child != null) {
            inline(child)
            child = child.next
        }
    }

    /** Writes an inline node. A link to an address notes do not accept writes its text alone. */
    private fun inline(node: Node) {
        when (node) {
            is Text -> html.append(escapedText(node.literal))
            is HtmlInline -> html.append(escapedText(node.literal))
            is SoftLineBreak, is HardLineBreak -> html.append("<br>")
            is Code -> {
                formatted = true
                html.append("<code>").append(escapedText(node.literal)).append("</code>")
            }
            is Emphasis -> span(node, "<em>", "</em>")
            is StrongEmphasis -> span(node, "<strong>", "</strong>")
            is Strikethrough -> span(node, "<s>", "</s>")
            is Link -> {
                val href = absoluteLink(node.destination.orEmpty())
                if (href.isEmpty()) {
                    inlines(node)
                } else {
                    linked = true
                    links++
                    html.append("<a href=\"").append(escapedAttribute(href)).append("\">")
                    inlines(node)
                    html.append("</a>")
                    links--
                }
            }
            is Image -> image(node)
            is TaskListItemMarker -> Unit // A task's mark is its item's state.
            else -> inlines(node)
        }
    }

    /** Writes a formatting span around its content. */
    private fun span(node: Node, open: String, close: String) {
        formatted = true
        html.append(open)
        inlines(node)
        html.append(close)
    }

    /**
     * Writes an image. One on the web is not downloaded: outside a link it becomes a link to the
     * picture, named by its description or, without one, by its address; other pictures are read as
     * pasted HTML reads them.
     */
    private fun image(node: Image) {
        formatted = true
        val source = node.destination.orEmpty()
        val description = StringBuilder()
        collect(node) {
            when (it) {
                is Text -> description.append(it.literal)
                is Code -> description.append(it.literal)
                is SoftLineBreak, is HardLineBreak -> description.append(' ')
            }
        }
        val href = absoluteLink(source)
        if (href.isNotEmpty() && links == 0) {
            val name = qtSimplified(description.toString())
            linked = true
            html.append("<a href=\"").append(escapedAttribute(href)).append("\">").append(escapedText(name.ifEmpty { href })).append("</a>")
        } else {
            html.append("<img src=\"").append(escapedAttribute(source)).append("\" alt=\"").append(escapedAttribute(description.toString())).append("\">")
        }
    }

    /**
     * Returns the address to store for a link's or a picture's destination: an absolute web or mail
     * address, normalised; empty for a relative one, which would otherwise read as a domain.
     */
    private fun absoluteLink(destination: String): String =
        if ("://" in destination || destination.startsWith("mailto:", ignoreCase = true)) normaliseLink(destination) else ""
}
