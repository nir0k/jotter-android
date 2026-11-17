package io.github.nir0k.jotter.core.body

import io.github.nir0k.jotter.core.format.attachmentSource
import io.github.nir0k.jotter.core.format.bodyProblem
import io.github.nir0k.jotter.core.format.escapedAttribute
import io.github.nir0k.jotter.core.format.escapedText
import io.github.nir0k.jotter.core.format.isCustomColor
import io.github.nir0k.jotter.core.format.noteLinkTarget
import io.github.nir0k.jotter.core.format.paperHighlight

// Writing the model as a note body in the desktop's form (editor/HtmlCodec.cpp): one block per
// line, lists nested inside their parent items, table rows on lines of their own and inline
// formatting as nested elements in a fixed order, so equal documents give equal text.

/** Formats a colour as six lower-case hexadecimal digits. */
private fun hex6(rgb: Int): String = (rgb and 0xFFFFFF).toString(16).padStart(6, '0')

/** Returns the start tags a run of text is written in, outermost first. */
private fun wrappersOf(format: TextFormat): List<String> {
    val open = ArrayList<String>(4)
    format.href?.let { href ->
        // A link to another note carries its class.
        val noteLink = noteLinkTarget(href) != null
        open += "<a ${if (noteLink) "class=\"note-link\" " else ""}href=\"${escapedAttribute(href)}\">"
    }
    if (!format.code && format.family != null) open += "<span style=\"font-family: '${escapedAttribute(format.family)}'\">"
    // A custom colour has no rule in the note style sheet, so it carries its own style for
    // browsers; reading takes the colour from data-color only.
    format.color?.let { color ->
        open += if (isCustomColor(color)) "<span data-color=\"$color\" style=\"color: #${hex6(color.substring(1).toInt(16))}\">"
        else "<span data-color=\"${escapedAttribute(color)}\">"
    }
    format.highlight?.let { highlight ->
        open += if (isCustomColor(highlight)) "<mark data-color=\"$highlight\" style=\"background: #${hex6(paperHighlight(highlight.substring(1).toInt(16)))}\">"
        else "<mark data-color=\"${escapedAttribute(highlight)}\">"
    }
    if (format.bold) open += "<strong>"
    if (format.italic) open += "<em>"
    if (format.underline) open += "<u>"
    if (format.strike) open += "<s>"
    when (format.valign) {
        VAlign.Sup -> open += "<sup>"
        VAlign.Sub -> open += "<sub>"
        null -> Unit
    }
    if (format.code) open += "<code>"
    return open
}

/** Returns the end tag of a start tag written by [wrappersOf]. */
private fun closingOf(openTag: String): String {
    val end = openTag.indexOfFirst { it == ' ' || it == '>' }
    return "</${openTag.substring(1, end)}>"
}

/** Writes an image or an attachment chip; pasted objects not stored yet have no HTML. */
private fun objectHtml(item: InlineObject): String {
    if (item.pending.isNotEmpty() || item.note.isEmpty() || item.file.isEmpty()) return ""
    val source = escapedAttribute(attachmentSource(item.note, item.file))
    return when (item) {
        is ImageObject -> buildString {
            append("<img src=\"").append(source).append('"')
            if (item.width > 0) append(" data-width=\"").append(item.width).append('"')
            if (item.wrap == "square" || item.wrap == "top-bottom") append(" data-wrap=\"").append(item.wrap).append('"')
            append('>')
        }
        is AttachmentObject -> "<a class=\"attachment\" href=\"$source\" data-size=\"${item.size}\">${escapedText(item.name)}</a>"
    }
}

/** A run of inline HTML with the elements around it. */
private class HtmlRun(val wrappers: List<String>, val html: StringBuilder, val isObject: Boolean)

/** Writes the inline content of a block; runs with the same elements are joined, and images and chips stand outside any formatting. */
private fun writeInline(block: Block): String {
    val runs = ArrayList<HtmlRun>()
    for (piece in block.inline) {
        if (piece is InlineObject) {
            val html = objectHtml(piece)
            if (html.isNotEmpty()) runs += HtmlRun(emptyList(), StringBuilder(html), true)
            continue
        }
        piece as TextRun
        val text = escapedText(piece.text).replace(LineSeparator.toString(), "<br>").replace(ObjectCharacter.toString(), "")
        val wrappers = wrappersOf(piece.format)
        val last = runs.lastOrNull()
        if (last != null && !last.isObject && last.wrappers == wrappers) last.html.append(text)
        else runs += HtmlRun(wrappers, StringBuilder(text), false)
    }
    val html = StringBuilder()
    for (run in runs) {
        if (run.html.isEmpty()) continue
        run.wrappers.forEach(html::append)
        html.append(run.html)
        run.wrappers.asReversed().forEach { html.append(closingOf(it)) }
    }
    return html.toString()
}

/** A list open while writing. */
private class OpenList(val list: ListInfo, val indent: Int, val ordered: Boolean, var itemOpen: Boolean = false)

/** Writes the model frame by frame: the body, code blocks and tables, whose cells hold blocks written like the body's. */
private class Writer {
    private var html = StringBuilder()
    private val lists = ArrayList<OpenList>()
    private var quote = false

    /** Writes the root items as a note body. */
    fun write(root: List<Item>): String {
        writeItems(root, true)
        closeContext()
        if (html.endsWith("\n")) html.setLength(html.length - 1)
        return html.toString()
    }

    /** Closes open lists down to a number of them. */
    private fun closeLists(keep: Int) {
        while (lists.size > keep) {
            val last = lists.removeAt(lists.lastIndex)
            if (last.itemOpen) html.append("</li>\n")
            html.append(if (last.ordered) "</ol>\n" else "</ul>\n")
        }
    }

    /** Closes the open lists and quote, before a frame and at the end of a cell. */
    private fun closeContext() {
        closeLists(0)
        if (quote) {
            html.append("</blockquote>\n")
            quote = false
        }
    }

    /**
     * Writes items of the root or of a cell; an empty paragraph that only starts the note before a
     * frame or ends it after one is left out at the root.
     */
    private fun writeItems(items: List<Item>, root: Boolean) {
        items.forEachIndexed { i, item ->
            when (item) {
                is Block -> {
                    val skip = root && item.isEmptyParagraph &&
                        ((i == 0 && i + 1 < items.size && items[i + 1] !is Block) || (i == items.lastIndex && i > 0 && items[i - 1] !is Block))
                    writeBlock(item, skip)
                }
                is CodeBlock -> {
                    closeContext()
                    writeCode(item)
                }
                is Table -> {
                    closeContext()
                    writeTable(item)
                }
            }
        }
    }

    /** Writes a code block. */
    private fun writeCode(code: CodeBlock) {
        html.append("<pre")
        if (code.language.isNotEmpty()) html.append(" data-language=\"").append(escapedAttribute(code.language)).append('"')
        if (code.title.isNotEmpty()) html.append(" data-title=\"").append(escapedAttribute(code.title)).append('"')
        html.append("><code>").append(escapedText(code.text)).append("</code></pre>\n")
    }

    /** Writes a table: rows on lines of their own, cells holding blocks written like the body's. */
    private fun writeTable(table: Table) {
        val headerRows = minOf(table.headerRows, table.rows)
        html.append(if (table.width > 0) "<table data-width=\"${table.width}\">\n" else "<table>\n")
        // Widths are written only when the columns do not share the width equally.
        if (!hasEqualColumns(table.widths, table.columns)) {
            html.append("<colgroup>")
            for (width in columnWidths(table.widths, table.columns)) html.append("<col style=\"width: ${Math.round(width)}%\">")
            html.append("</colgroup>\n")
        }
        for (row in 0 until table.rows) {
            val header = row < headerRows
            if (row == 0 && header) html.append("<thead>\n") else if (row == headerRows) html.append("<tbody>\n")
            val tag = if (header) "th" else "td"
            html.append("<tr>")
            for (column in 0 until table.columns) {
                val outer = html
                html = StringBuilder()
                writeItems(table.cells[row][column], false)
                closeContext()
                if (html.endsWith("\n")) html.setLength(html.length - 1)
                val inner = html
                html = outer
                html.append('<').append(tag).append('>').append(inner).append("</").append(tag).append('>')
            }
            html.append("</tr>\n")
            if (header && row == headerRows - 1) html.append("</thead>\n")
        }
        if (table.rows > headerRows) html.append("</tbody>\n")
        html.append("</table>\n")
    }

    /** Writes a block; [skip] leaves out an empty paragraph that is not part of the note. */
    private fun writeBlock(block: Block, skip: Boolean) {
        val quoted = block.list == null && block.quote
        if (quote && !quoted) {
            html.append("</blockquote>\n")
            quote = false
        }
        if (skip) return
        val list = block.list
        if (list != null) {
            writeListItem(block, list)
            return
        }
        closeLists(0)
        when {
            quoted -> {
                if (!quote) {
                    html.append("<blockquote>\n")
                    quote = true
                }
                html.append("<p>").append(writeInline(block)).append("</p>\n")
            }
            block.hr -> html.append("<hr>\n")
            block.heading in 1..3 -> html.append("<h${block.heading}>").append(writeInline(block)).append("</h${block.heading}>\n")
            else -> html.append("<p>").append(writeInline(block)).append("</p>\n")
        }
    }

    /** Writes a list item, opening and closing lists around it; a nested list goes inside its parent item. */
    private fun writeListItem(block: Block, list: ListInfo) {
        val indent = maxOf(1, list.indent)
        while (lists.isNotEmpty()) {
            val last = lists.last()
            if (!(last.indent > indent || (last.indent == indent && last.list.id != list.id))) break
            closeLists(lists.size - 1)
        }
        val last = lists.lastOrNull()
        if (last == null || last.indent < indent) {
            if (last != null) html.append('\n')
            if (list.kind == ListKind.Number) {
                html.append("<ol")
                if (list.style.isNotEmpty()) html.append(" data-style=\"").append(escapedAttribute(list.style)).append('"')
                if (list.start > 1) html.append(" start=\"").append(list.start).append('"')
                html.append(">\n")
            } else {
                html.append(if (list.kind == ListKind.Check) "<ul class=\"checklist\">\n" else "<ul>\n")
            }
            lists += OpenList(list, indent, list.kind == ListKind.Number)
        } else if (last.itemOpen) {
            html.append("</li>\n")
        }
        html.append(if (list.kind == ListKind.Check) (if (block.checked) "<li data-checked=\"true\">" else "<li data-checked=\"false\">") else "<li>")
        html.append(writeInline(block))
        lists.last().itemOpen = true
    }
}

/**
 * Returns root items that keep the desktop's frame rule: every frame has a block before and after
 * it, so an empty paragraph goes where one is missing; the writer leaves those out at the start
 * and the end.
 */
fun framed(items: List<Item>): List<Item> {
    val out = ArrayList<Item>(items.size + 2)
    for ((i, item) in items.withIndex()) {
        if (item is Frame && (i == 0 || items[i - 1] is Frame)) out += Block()
        out += item
    }
    if (out.isEmpty() || out.last() is Frame) out += Block()
    return out
}

/** Writes a document as a note body in the desktop's form. */
fun serializeBody(doc: Doc): String = Writer().write(framed(doc.items))

/**
 * Writes a body outside the vocabulary back in it (desktop spec section 3.3): read as the editor
 * reads a body, written as the desktop writes one.
 *
 * @return The body in the vocabulary, or null when the result is still outside it.
 */
fun normalizedBody(body: String): String? = serializeBody(parseBody(body)).takeIf { bodyProblem(it) == null }
