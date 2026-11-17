package io.github.nir0k.jotter.core.body

import io.github.nir0k.jotter.core.format.HtmlToken
import io.github.nir0k.jotter.core.format.integerAttribute
import io.github.nir0k.jotter.core.format.isBlockElement
import io.github.nir0k.jotter.core.format.isQtSpace
import io.github.nir0k.jotter.core.format.isWebLink
import io.github.nir0k.jotter.core.format.noteLinkTarget
import io.github.nir0k.jotter.core.format.parseAttachmentSource
import io.github.nir0k.jotter.core.format.tokenizeHtml

// Reading a body into the model: a port of the desktop's reader (editor/HtmlCodec.cpp), which
// builds its document block by block from the HTML's tokens. Pasted HTML goes through the same
// reader in its paste mode.

/** Whether HTML is a note body or pasted from another program. */
private enum class Mode { Note, Paste }

/** Removes Qt's white space from both ends of text, as `QString::trimmed`. */
internal fun qtTrimmed(text: String): String = text.trim { it.isQtSpace() }

/** Trims text and joins its inner runs of white space into single spaces, as `QString::simplified`. */
internal fun qtSimplified(text: String): String {
    val out = StringBuilder(text.length)
    var space = false
    for (c in qtTrimmed(text)) {
        if (c.isQtSpace()) {
            space = true
        } else {
            if (space) out.append(' ')
            out.append(c)
            space = false
        }
    }
    return out.toString()
}

private val FontFamilyStyle = Regex("font-family\\s*:\\s*(?:'([^']*)'|\"([^\"]*)\"|([^;]+))")

/** Reads the family from a `font-family: 'Name'` style; empty when there is none. */
private fun fontFamilyFromStyle(style: String): String {
    val match = FontFamilyStyle.find(style) ?: return ""
    for (i in 1..3) {
        val group = match.groupValues[i]
        if (group.isNotEmpty()) return qtTrimmed(group)
    }
    return ""
}

/** A character format being read: the reader changes it as elements open. */
private class FormatBuilder(
    var href: String? = null,
    var family: String? = null,
    var color: String? = null,
    var highlight: String? = null,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var strike: Boolean = false,
    var valign: VAlign? = null,
    var code: Boolean = false,
) {
    /** Returns a copy, for an element opened inside this one. */
    fun copy() = FormatBuilder(href, family, color, highlight, bold, italic, underline, strike, valign, code)

    /** Returns the format; empty values count as none, as the desktop's reader keeps them. */
    fun build() = TextFormat(
        href?.ifEmpty { null }, family?.ifEmpty { null }, color?.ifEmpty { null }, highlight?.ifEmpty { null },
        bold, italic, underline, strike, valign, code,
    )
}

/**
 * Applies the basic formatting that pasted HTML gives in `style` attributes, as word processors
 * and web editors do (`<span style="font-weight:700">`). A declaration can also undo its element:
 * Google Docs wraps everything in `<b style="font-weight:normal">`.
 */
private fun applyPastedStyle(style: String, format: FormatBuilder) {
    for (declaration in style.split(';')) {
        val colon = declaration.indexOf(':')
        if (colon < 0) continue
        val property = qtTrimmed(declaration.substring(0, colon)).lowercase()
        val value = qtTrimmed(declaration.substring(colon + 1)).lowercase()
        when (property) {
            "font-weight" -> format.bold = value == "bold" || value == "bolder" || integerAttribute(value) >= 600
            "font-style" -> format.italic = value != "normal"
            "text-decoration", "text-decoration-line" -> {
                format.underline = "underline" in value
                format.strike = "line-through" in value
            }
            "vertical-align" -> format.valign = when (value) {
                "super" -> VAlign.Sup
                "sub" -> VAlign.Sub
                else -> null
            }
        }
    }
}

private val InlineElements = setOf("strong", "b", "em", "i", "u", "s", "del", "strike", "sup", "sub", "code", "a", "mark", "span")

/** Applies an inline element to the character format it opens. */
private fun applyInline(token: HtmlToken, format: FormatBuilder, mode: Mode) {
    when (token.name) {
        "strong", "b" -> format.bold = true
        "em", "i" -> format.italic = true
        "u" -> format.underline = true
        "s", "del", "strike" -> format.strike = true
        "sup" -> format.valign = VAlign.Sup
        "sub" -> format.valign = VAlign.Sub
        "code" -> format.code = true
        "a" -> {
            // Links keep only the addresses a note accepts; others become text. A note file may come
            // from another program, so its links are checked as well as pasted ones.
            var href = token.attribute("href")
            if (mode == Mode.Paste) {
                href = if (':' in href) normaliseLink(href) else ""
            } else if (noteLinkTarget(href) == null && !isWebLink(href)) {
                href = ""
            }
            if (href.isNotEmpty()) format.href = href
        }
        "mark" -> if (mode == Mode.Note) format.highlight = token.attribute("data-color")
        "span" -> if (mode == Mode.Note) {
            if (token.hasAttribute("data-color")) format.color = token.attribute("data-color")
            val family = fontFamilyFromStyle(token.attribute("style"))
            if (family.isNotEmpty()) format.family = family
        }
    }
    if (mode == Mode.Paste && token.hasAttribute("style")) applyPastedStyle(token.attribute("style"), format)
}

private val WidthStyle = Regex("width\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%")

/** Reads the percent of a `width: 30%` style, 0 for anything else. */
private fun widthFromStyle(style: String): Double = WidthStyle.find(style)?.groupValues?.get(1)?.toDouble() ?: 0.0

/** The shape of a table read ahead of its cells. */
private class TableShape(var rows: Int = 0, var columns: Int = 0, var headerRows: Int = 0, val widths: MutableList<Double> = mutableListOf(), var width: Int = 0)

/**
 * Measures the table that starts at a token by looking ahead to its end tag; the cells of tables
 * nested in it count as text of their cell.
 */
private fun measureTable(tokens: List<HtmlToken>, start: Int, mode: Mode): TableShape {
    // Pasted tables take the text column's width, like their columns share it equally.
    val shape = TableShape()
    if (mode == Mode.Note) shape.width = validWidth(integerAttribute(tokens[start].attribute("data-width")))
    var depth = 0
    var cells = 0
    var header = false
    // Without a `<thead>`, a first row of `<th>` cells is the header, as pasted tables have it.
    var firstRowHeads = false
    for (i in start until tokens.size) {
        val token = tokens[i]
        val isStart = token.kind == HtmlToken.Kind.Start
        if (token.name == "table") {
            depth += if (isStart) 1 else -1
            if (depth == 0) break
            continue
        }
        if (depth != 1) continue
        when {
            token.name == "thead" -> header = isStart
            // Pasted tables share the width equally; a note keeps the widths it was given.
            isStart && token.name == "col" && mode == Mode.Note -> shape.widths += widthFromStyle(token.attribute("style"))
            isStart && token.name == "tr" -> {
                shape.rows++
                shape.headerRows += if (header && shape.headerRows == shape.rows - 1) 1 else 0
                cells = 0
            }
            isStart && (token.name == "td" || token.name == "th") -> {
                shape.columns = maxOf(shape.columns, ++cells)
                if (shape.rows == 1) firstRowHeads = (cells == 1 || firstRowHeads) && token.name == "th"
            }
        }
    }
    if (shape.headerRows == 0 && firstRowHeads) shape.headerRows = 1
    return shape
}

/** A block being read. */
private class BlockBuilder(
    var heading: Int = 0,
    var quote: Boolean = false,
    var hr: Boolean = false,
    var list: ListInfo? = null,
    var checked: Boolean = false,
    val inline: MutableList<Inline> = mutableListOf(),
) : ItemBuilder {
    override fun build(): Item = Block(heading = heading, quote = quote, hr = hr, list = list, checked = checked, inline = normalized(inline))
}

/** An item being read: a block or a frame. */
private sealed interface ItemBuilder {
    fun build(): Item
}

/** A code block being read. */
private class CodeBuilder(val language: String, val title: String, val text: StringBuilder = StringBuilder()) : ItemBuilder {
    override fun build(): Item = CodeBlock(language = language, title = title, text = text.toString())
}

/** A table being read: its cells hold blocks like the root. */
private class TableBuilder(val shape: TableShape, val cells: List<List<MutableList<ItemBuilder>>>) : ItemBuilder {
    override fun build(): Item = Table(
        headerRows = shape.headerRows,
        widths = setColumnWidths(shape.columns, shape.widths),
        width = shape.width,
        cells = cells.map { row -> row.map { cell -> cell.map { it.build() as Block } } },
    )
}

/** A list opened by `<ul>` or `<ol>`: its format, and the list its items share once one is read. */
private class ListContext(val kind: ListKind, val indent: Int, val style: String, val start: Int, var list: ListInfo? = null)

/** Builds the model block by block from tokens, as the desktop's reader builds its document. */
private class Reader(private val mode: Mode) {
    private val root: MutableList<ItemBuilder> = mutableListOf(BlockBuilder())

    // The items the cursor is in, and the block it is at the end of.
    private var container: MutableList<ItemBuilder> = root
    private var block: BlockBuilder = root[0] as BlockBuilder
    private var formats = mutableListOf(FormatBuilder())
    private var lists = mutableListOf<ListContext>()
    private var code: CodeBuilder? = null
    private var codeStart = false
    private var table: TableBuilder? = null
    private var afterFrame: BlockBuilder? = null
    private var nestedTables = 0
    private var row = -1
    private var column = -1
    private var attachment: AttachmentObject? = null
    private var attachmentName = StringBuilder()
    private var skip = 0
    private var reuse = true
    private var inBlock = false
    private var quote = false
    private var tokens: List<HtmlToken> = emptyList()
    private var index = 0

    /** Reads HTML into the model and returns the root items. */
    fun read(html: String): List<Item> {
        tokens = tokenizeHtml(html)
        index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.name in SkippedElements) {
                skip = maxOf(0, skip + if (token.kind == HtmlToken.Kind.Start) 1 else -1)
            } else if (skip == 0) {
                when (token.kind) {
                    HtmlToken.Kind.Start -> start(token)
                    HtmlToken.Kind.End -> end(token)
                    HtmlToken.Kind.Text -> text(token.text)
                }
            }
            index++
        }
        return root.map { it.build() }
    }

    /** Handles a start tag. */
    private fun start(token: HtmlToken) {
        val name = token.name
        if (code != null) return // A code block is plain text; elements inside it are dropped.
        if (attachment != null) return // An attachment chip shows its name only.
        if (name == "table") {
            startTable()
            return
        }
        val current = table
        if (current != null && nestedTables > 0) {
            // The structure of a nested table is dropped; its cells become paragraphs.
            if (name == "tr" || name == "td" || name == "th") {
                inBlock = false
                return
            }
        } else if (current != null) {
            if (name == "tr") {
                row++
                column = -1
                return
            }
            if (name == "td" || name == "th") {
                startCell()
                return
            }
        }
        // Cells hold paragraphs, inline formatting and lists only.
        val inCell = table != null
        when {
            name == "pre" && !inCell -> {
                // The frame goes after the current block, followed by an empty block the next block reuses.
                val frame = CodeBuilder(token.attribute("data-language").lowercase(), token.attribute("data-title"))
                code = frame
                val after = BlockBuilder()
                afterFrame = after
                container += frame
                container += after
                reuse = false
                codeStart = true
            }
            name == "p" || name == "div" || name == "pre" -> beginBlock(quote = quote)
            name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> beginBlock(heading = if (inCell) 0 else minOf(3, name[1] - '0'))
            name == "blockquote" -> quote = !inCell
            name == "ul" || name == "ol" -> {
                val kind = if (name == "ol") ListKind.Number else if (token.attribute("class") == "checklist") ListKind.Check else ListKind.Bullet
                val style = if (mode == Mode.Note && kind == ListKind.Number) token.attribute("data-style") else ""
                val first = if (kind == ListKind.Number) integerAttribute(token.attribute("start")) else 0
                lists += ListContext(kind, lists.size + 1, if (style == "decimal") "" else style, if (first > 1) first else 1)
            }
            name == "li" -> {
                val context = lists.lastOrNull()
                beginBlock(checked = context?.kind == ListKind.Check && token.attribute("data-checked") == "true")
                if (context != null) {
                    val list = context.list ?: ListInfo(newId(), context.kind, context.indent, context.style, context.start).also { context.list = it }
                    block.list = list
                }
            }
            name == "hr" -> {
                if (!inCell) beginBlock(hr = true)
                inBlock = false
            }
            name == "br" -> if (inBlock) insertText(LineSeparator.toString())
            name == "img" -> image(token)
            name == "a" && token.attribute("class") == "attachment" && mode == Mode.Note -> {
                val ref = parseAttachmentSource(token.attribute("href"))
                if (ref != null) {
                    attachment = AttachmentObject(ref.noteId, ref.fileName, sizeAttribute(token.attribute("data-size")), ref.fileName)
                    attachmentName = StringBuilder()
                } else {
                    formats += formats.last().copy()
                }
            }
            name in InlineElements -> {
                val format = formats.last().copy()
                applyInline(token, format, mode)
                formats += format
            }
        }
    }

    /** Handles an end tag. */
    private fun end(token: HtmlToken) {
        val name = token.name
        if (code != null) {
            if (name == "pre") {
                block = afterFrame!!
                code = null
                reuse = true
                inBlock = false
            }
            return
        }
        val chip = attachment
        if (chip != null) {
            if (name == "a") {
                attachment = null
                val shown = qtSimplified(attachmentName.toString())
                insertObject(if (shown.isNotEmpty()) chip.copy(name = shown) else chip)
            }
            return
        }
        if (name == "table") {
            endTable()
            return
        }
        if (table != null && (name == "td" || name == "th")) {
            lists = mutableListOf()
            inBlock = false
            return
        }
        when {
            name in InlineElements -> if (formats.size > 1) formats.removeAt(formats.lastIndex)
            name == "ul" || name == "ol" -> {
                if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex)
                inBlock = false
            }
            name == "blockquote" -> quote = false
            isBlockElement(name) -> inBlock = false
        }
    }

    /** Handles decoded text. */
    private fun text(raw: String) {
        val frame = code
        if (frame != null) {
            // A line feed right after `<pre>` only lays out the HTML, as in browsers.
            var text = raw.replace("\r", "")
            if (codeStart && text.startsWith("\n")) text = text.substring(1)
            codeStart = false
            frame.text.append(text)
            return
        }
        if (attachment != null) {
            attachmentName.append(raw)
            return
        }
        if (table != null && column < 0) return // Text between the rows of a table belongs to no cell.
        var text = raw
        if (mode == Mode.Paste) {
            // In pasted HTML line breaks and runs of white space are one space, as in a browser.
            text = text.replace(PastedSpace, " ")
            if (!inBlock || block.inline.isEmpty()) text = text.trimStart(' ')
        } else {
            // Line breaks in a note's source only lay out the HTML; `<br>` is the line break.
            text = text.replace("\r", "").replace("\n", "")
        }
        if (text.isEmpty()) return
        if (!inBlock) {
            if (qtTrimmed(text).isEmpty()) return
            beginBlock(quote = quote)
        }
        insertText(text)
    }

    /** Adds text to the block at the cursor in the current format, joining it to the run before when the format is the same. */
    private fun insertText(text: String) {
        val format = formats.last().build()
        val last = block.inline.lastOrNull()
        if (last is TextRun && last.format == format) block.inline[block.inline.lastIndex] = TextRun(last.text + text, format)
        else block.inline += TextRun(text, format)
    }

    /**
     * Reads an image: one of the note's files, or in pasted HTML a picture to store. A picture on
     * the web is not downloaded; its description stays as text.
     */
    private fun image(token: HtmlToken) {
        val source = token.attribute("src")
        if (mode == Mode.Note) {
            val ref = parseAttachmentSource(source) ?: return
            val wrap = token.attribute("data-wrap")
            insertObject(
                ImageObject(
                    ref.noteId, ref.fileName, validWidth(integerAttribute(token.attribute("data-width"))),
                    if (wrap == "square" || wrap == "top-bottom") wrap else "",
                ),
            )
            return
        }
        if (source.startsWith("data:image/") || source.startsWith("file:")) {
            insertObject(ImageObject("", "", pending = source))
        } else {
            val alt = qtSimplified(token.attribute("alt"))
            if (alt.isNotEmpty()) text(alt)
        }
    }

    /** Inserts an image or a chip at the cursor, starting a paragraph when outside one. */
    private fun insertObject(item: InlineObject) {
        if (table != null && column < 0) return
        if (!inBlock) beginBlock(quote = quote)
        block.inline += item
    }

    /** Starts a table: the frame goes after the current block, like a code block. */
    private fun startTable() {
        if (table != null) {
            nestedTables++
            return
        }
        val shape = measureTable(tokens, index, mode)
        if (shape.rows == 0 || shape.columns == 0) return
        lists = mutableListOf()
        quote = false
        val cells = List(shape.rows) { List(shape.columns) { mutableListOf<ItemBuilder>(BlockBuilder()) } }
        val frame = TableBuilder(shape, cells)
        table = frame
        val after = BlockBuilder()
        afterFrame = after
        container += frame
        container += after
        // Qt puts the cursor into the first cell.
        container = cells[0][0]
        block = container[0] as BlockBuilder
        row = -1
        column = -1
        inBlock = false
    }

    /** Moves the cursor into the next cell of the current row. */
    private fun startCell() {
        val frame = table ?: return
        column++
        if (row < 0 || row >= frame.shape.rows || column >= frame.shape.columns) {
            column = -1
            return
        }
        container = frame.cells[row][column]
        block = container[0] as BlockBuilder
        lists = mutableListOf()
        formats = mutableListOf(FormatBuilder())
        reuse = true
        inBlock = false
    }

    /** Ends a table, moving the cursor to the block after it. */
    private fun endTable() {
        if (nestedTables > 0) {
            nestedTables--
            inBlock = false
            return
        }
        if (table == null) return
        container = root
        block = afterFrame!!
        table = null
        lists = mutableListOf()
        reuse = true
        inBlock = false
    }

    /**
     * Starts a block. An empty block the document already has (its first block, the block after a
     * code block or a table, the first block of a cell) is reused rather than followed by another.
     */
    private fun beginBlock(heading: Int = 0, quote: Boolean = false, hr: Boolean = false, checked: Boolean = false) {
        if (reuse) {
            block.heading = heading
            block.quote = quote
            block.hr = hr
            block.list = null
            block.checked = checked
            reuse = false
        } else {
            block = BlockBuilder(heading, quote, hr, null, checked)
            container += block
        }
        formats = mutableListOf(FormatBuilder())
        inBlock = true
    }

    companion object {
        private val SkippedElements = setOf("head", "style", "script", "title", "template")
        private val PastedSpace = Regex("[ \\t\\r\\n]+")
    }
}

/** Reads an attachment's size as the desktop does: an integer, 0 for anything else. */
private fun sizeAttribute(value: String): Long = value.trim().toLongOrNull() ?: 0

/** Reads a note body into the model. */
fun parseBody(html: String): Doc = Doc(Reader(Mode.Note).read(html))

/**
 * Reads HTML pasted from another program: structure and basic formatting stay, fonts, sizes and
 * colours go, and links keep only web and mail addresses. Pictures in `data:` addresses stay as
 * images waiting to be stored, their address in `pending`; pictures of local files are left out,
 * as the application cannot read them.
 */
fun parsePastedHtml(html: String): Doc {
    val doc = Doc(Reader(Mode.Paste).read(html))
    fun keep(piece: Inline) = !(piece is ImageObject && piece.pending.isNotEmpty() && !piece.pending.startsWith("data:"))
    fun clean(block: Block) = if (block.inline.all(::keep)) block else block.withInline(block.inline.filter(::keep))
    return Doc(doc.items.map { item ->
        when (item) {
            is Block -> clean(item)
            is Table -> item.copy(cells = item.cells.map { row -> row.map { cell -> cell.map(::clean) } })
            is CodeBlock -> item
        }
    })
}

private val UrlScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

/**
 * Normalises a typed or pasted address: adds `https://` to a bare domain and `mailto:` to a bare
 * mail address, encodes the characters a URL holds only encoded, and refuses what
 * [isWebLink] refuses.
 *
 * @return The address to store, or an empty string when it is not acceptable.
 */
fun normaliseLink(address: String): String {
    var text = address.trim()
    if (text.isEmpty()) return ""
    if ("://" !in text && !text.startsWith("mailto:")) text = (if ('@' in text && '/' !in text) "mailto:" else "https://") + text
    val encoded = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val brokenEscape = c == '%' && !(i + 2 < text.length + 0 && i + 2 <= text.lastIndex &&
            text[i + 1].isHexDigit() && text[i + 2].isHexDigit())
        if (c.code !in 0x21..0x7e || c in "\"<>\\^`{|}" || brokenEscape) {
            val end = if (Character.isHighSurrogate(c) && i + 1 < text.length) i + 2 else i + 1
            for (byte in text.substring(i, end).toByteArray(Charsets.UTF_8)) encoded.append('%').append("%02X".format(byte.toInt() and 0xFF))
            i = end
        } else {
            encoded.append(c)
            i++
        }
    }
    val result = encoded.toString()
    return if (UrlScheme.containsMatchIn(result) && isWebLink(result)) result else ""
}

/** Tells whether a character is an ASCII hexadecimal digit. */
private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
