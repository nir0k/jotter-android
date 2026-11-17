package io.github.nir0k.jotter.core.body

import java.util.concurrent.atomic.AtomicLong

// The model of a note body: the desktop's document as its body codec reads and writes it
// (editor/HtmlCodec.cpp). The root and every table cell hold blocks; the root also holds frames
// (code blocks and tables), each with a block before and after it, as in Qt. A block is a
// paragraph, a heading, a divider, a quoted paragraph or a list item; list items name their list,
// which has a kind and a nesting level, and nesting is rebuilt when writing. Inline content is
// text with a character format (line breaks as U+2028), images and attachment chips. The editor
// edits this model directly, so a body reads and writes back byte for byte.

/** The line separator a `<br>` is kept as, as Qt does. */
const val LineSeparator = ' '

/** The character Qt holds an object in; text never writes it. */
const val ObjectCharacter = '￼'

private val nextId = AtomicLong(1)

/** Returns a new identifier for a block, a list or a frame, unique while the application runs. */
fun newId(): Long = nextId.getAndIncrement()

/** The vertical alignment of a run of text. */
enum class VAlign { Sup, Sub }

/**
 * The character format of a run of text: the elements it is written in.
 *
 * @param href The link's address.
 * @param family The font family.
 * @param color A text colour: a tag colour key or `#rrggbb`.
 * @param highlight A highlight colour, as [color].
 */
data class TextFormat(
    val href: String? = null,
    val family: String? = null,
    val color: String? = null,
    val highlight: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val valign: VAlign? = null,
    val code: Boolean = false,
) {
    companion object {
        /** Text without any format. */
        val Plain = TextFormat()
    }
}

/** A piece of a block's inline content. */
sealed interface Inline {
    /** How many characters it takes in the block's text: an object takes one. */
    val length: Int
}

/** A run of text in one format; line breaks are [LineSeparator]. */
data class TextRun(val text: String, val format: TextFormat) : Inline {
    override val length: Int get() = text.length
}

/** An image or an attachment chip. */
sealed interface InlineObject : Inline {
    /** The note whose attachment folder holds the file. */
    val note: String

    /** The file's name. */
    val file: String

    /**
     * Names a pasted object not stored in the note's folder yet: a `data:` address or the address
     * of another note's file. Such an object writes nothing until it is stored.
     */
    val pending: String

    override val length: Int get() = 1
}

/**
 * An image of an attachment folder.
 *
 * @param width The percent of the text column (10 to 99), 0 for full width.
 * @param wrap Empty for in line with text, `square` or `top-bottom`.
 */
data class ImageObject(
    override val note: String,
    override val file: String,
    val width: Int = 0,
    val wrap: String = "",
    override val pending: String = "",
) : InlineObject

/**
 * An attachment chip.
 *
 * @param size The file's size in bytes.
 * @param name The name the chip shows.
 */
data class AttachmentObject(
    override val note: String,
    override val file: String,
    val size: Long = 0,
    val name: String = file,
    override val pending: String = "",
) : InlineObject

/** The kind of a list. */
enum class ListKind { Bullet, Number, Check }

/**
 * A list of the model: list items of one list carry the same [id].
 *
 * @param indent The nesting level, from 1.
 * @param style The numbering style identifier, empty for `1.`; an unknown one is kept.
 * @param start The first number of a numbered list.
 */
data class ListInfo(val id: Long, val kind: ListKind, val indent: Int, val style: String = "", val start: Int = 1)

/** An item of the root: a block or a frame. */
sealed interface Item {
    /** Identifies the item while the document is edited; never written. */
    val id: Long
}

/**
 * A block of text.
 *
 * @param heading 1 to 3 for a heading, 0 otherwise.
 * @param quote Whether a paragraph is quoted.
 * @param hr Whether the block is a divider.
 * @param list The list the block is an item of.
 * @param checked Whether a checklist item is checked.
 */
data class Block(
    override val id: Long = newId(),
    val heading: Int = 0,
    val quote: Boolean = false,
    val hr: Boolean = false,
    val list: ListInfo? = null,
    val checked: Boolean = false,
    val inline: List<Inline> = emptyList(),
) : Item {
    /** The block's text: line breaks as [LineSeparator] and objects as [ObjectCharacter]. */
    val text: String get() = inline.joinToString("") { if (it is TextRun) it.text else ObjectCharacter.toString() }

    /** The number of characters of the block's text. */
    val length: Int get() = inline.sumOf { it.length }

    /** Tells whether the block is an empty paragraph: no content, no list, heading, quote or divider. */
    val isEmptyParagraph: Boolean get() = inline.isEmpty() && list == null && heading == 0 && !quote && !hr

    /** Returns the block with the same format and other content. */
    fun withInline(content: List<Inline>): Block = copy(inline = normalized(content))
}

/** A frame: a code block or a table. */
sealed interface Frame : Item

/**
 * A code block of plain text.
 *
 * @param language The lower-case name of a KDE SyntaxHighlighting definition, empty for plain text.
 */
data class CodeBlock(override val id: Long = newId(), val language: String = "", val title: String = "", val text: String = "") : Frame

/**
 * A table whose cells hold blocks.
 *
 * @param headerRows How many rows at the top are header rows.
 * @param widths The column widths in whole percents adding up to 100, or null for equal columns.
 * @param width The table's width in percent of the text column (10 to 99), 0 for full width.
 * @param cells The cells by row, each with at least one block.
 */
data class Table(
    override val id: Long = newId(),
    val headerRows: Int = 0,
    val widths: List<Int>? = null,
    val width: Int = 0,
    val cells: List<List<List<Block>>>,
) : Frame {
    /** The number of rows. */
    val rows: Int get() = cells.size

    /** The number of columns. */
    val columns: Int get() = cells.firstOrNull()?.size ?: 0
}

/** A note body: the root items, starting and ending with a block, no two frames adjacent. */
data class Doc(val items: List<Item>) {
    companion object {
        /** An empty body: one empty paragraph. */
        fun empty(): Doc = Doc(listOf(Block()))
    }
}

/**
 * Returns inline content in its normal form: empty text runs dropped and adjacent runs of the same
 * format joined, as the desktop's document keeps it.
 */
fun normalized(content: List<Inline>): List<Inline> {
    val out = ArrayList<Inline>(content.size)
    for (piece in content) {
        if (piece is TextRun) {
            if (piece.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last is TextRun && last.format == piece.format) {
                out[out.lastIndex] = TextRun(last.text + piece.text, last.format)
                continue
            }
        }
        out += piece
    }
    return out
}

/**
 * Returns the part of inline content between two character offsets.
 *
 * @param from The first character.
 * @param to The end, exclusive.
 */
fun slice(content: List<Inline>, from: Int, to: Int): List<Inline> {
    if (from >= to) return emptyList()
    val out = ArrayList<Inline>()
    var pos = 0
    for (piece in content) {
        val end = pos + piece.length
        if (end > from && pos < to) {
            if (piece is TextRun) {
                out += TextRun(piece.text.substring(maxOf(0, from - pos), minOf(piece.length, to - pos)), piece.format)
            } else {
                out += piece
            }
        }
        pos = end
        if (pos >= to) break
    }
    return out
}

/**
 * Returns the format of the text at an offset, as text typed there takes it: the format of the
 * character before, or of the character after at the start of a block. A link ends where its text
 * does, so text typed right after a link is not part of it.
 *
 * @return The format; plain text in an empty block or next to objects only.
 */
fun formatAt(content: List<Inline>, offset: Int): TextFormat {
    var pos = 0
    var before: TextFormat? = null
    var after: TextFormat? = null
    for (piece in content) {
        val end = pos + piece.length
        if (piece is TextRun) {
            if (end >= offset && pos < offset) before = piece.format
            if (pos <= offset && end > offset && after == null) after = piece.format
        }
        pos = end
    }
    val format = before ?: after ?: return TextFormat.Plain
    if (before != null && format.href != null && after?.href != format.href) return format.copy(href = null)
    return format
}

/** Returns the formats of the text runs between two offsets. */
fun formatsIn(content: List<Inline>, from: Int, to: Int): List<TextFormat> =
    slice(content, from, to).filterIsInstance<TextRun>().map { it.format }

/**
 * Returns the column widths a table stores: whole percents adding up to 100, none narrower than
 * [MinColumnWidth] (or an equal share when that is less). Widths that do not fit the columns give
 * equal columns.
 *
 * @param columns The number of columns.
 * @param widths The widths in any unit.
 */
fun setColumnWidths(columns: Int, widths: List<Double>): List<Int> {
    val count = maxOf(1, columns)
    val given = if (widths.size != count || widths.any { it <= 0 }) List(count) { 1.0 } else widths
    val minimum = minOf(MinColumnWidth.toDouble(), 100.0 / count)
    // Scale to 100, raise the narrow columns to the minimum, then take the excess from the others
    // in their proportions.
    var total = given.sum()
    val scaled = given.map { maxOf(minimum, it * 100 / total) }
    total = scaled.sum()
    val excess = total - 100
    val flexible = scaled.sumOf { it - minimum }
    val whole = scaled.map { jsRound(if (flexible > 0) it - excess * (it - minimum) / flexible else it) }.toMutableList()
    // Rounding may leave a percent over or under; the widest column takes it.
    val sum = whole.sum()
    val widest = whole.indexOf(whole.max())
    whole[widest] += 100 - sum
    return whole
}

/** Rounds as JavaScript's `Math.round`: halves go up. */
private fun jsRound(value: Double): Int = kotlin.math.floor(value + 0.5).toInt()

/** Returns the widths a table's columns are drawn with: the stored ones, or equal shares when they do not fit. */
fun columnWidths(widths: List<Int>?, columns: Int): List<Double> {
    if (widths == null || widths.size != columns || widths.any { it <= 0 }) return List(columns) { 100.0 / maxOf(1, columns) }
    return widths.map { it.toDouble() }
}

/**
 * Tells whether a table's columns share its width equally; whole percents cannot split 100 among
 * three columns, so 33, 33 and 34 are equal.
 */
fun hasEqualColumns(widths: List<Int>?, columns: Int): Boolean =
    columnWidths(widths, columns).all { kotlin.math.abs(it - 100.0 / maxOf(1, columns)) < 1 }

/** The narrowest a table column is made, in percent of the table. */
const val MinColumnWidth = 5

/** The narrowest an image or a table is made, in percent of its column. */
const val MinWidth = 10

/** Returns the width of an image or a table read from a note: 10 to 99 percent, or 0 for full width. */
fun validWidth(width: Int): Int = if (width in MinWidth..99) width else 0
