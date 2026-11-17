package io.github.nir0k.jotter.editor

import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.nir0k.jotter.core.body.AttachmentObject
import io.github.nir0k.jotter.core.body.Block
import io.github.nir0k.jotter.core.body.ImageObject
import io.github.nir0k.jotter.core.body.InlineObject
import io.github.nir0k.jotter.core.body.ListKind
import io.github.nir0k.jotter.core.body.Match
import io.github.nir0k.jotter.core.body.ObjectCharacter
import io.github.nir0k.jotter.core.body.Run
import io.github.nir0k.jotter.core.body.TextFormat
import io.github.nir0k.jotter.core.body.TextRun
import io.github.nir0k.jotter.core.body.VAlign
import io.github.nir0k.jotter.core.body.listMarkers
import io.github.nir0k.jotter.core.format.noteLinkTarget

// How a run of blocks is drawn: the note's own formatting — heading sizes, fonts, colours — as
// the desktop draws it, not the design system's type roles. The text field takes the styles;
// what is drawn around the text (list markers, checkboxes, quote lines, dividers, images and
// chips) is drawn from the text's layout.

/** The size of a note's text, as the desktop's style sheet sets it. */
internal val BodyFontSize = 16.sp

/** The height of a line of the note's text, in multiples of its size. */
internal const val BodyLineHeight = 1.5f

/**
 * Returns the text style of a note's body in a palette. The height of its lines is set per
 * paragraph, as a paragraph with images keeps the height its lines need.
 */
internal fun bodyTextStyle(colors: NoteColors) = TextStyle(color = rgb(colors.text), fontSize = BodyFontSize)

/** The height of a line of text, which paragraphs without images and code blocks take. */
internal val TextLineHeight = (BodyFontSize.value * BodyLineHeight).sp

/** How the height of a line of text is shared above and below it. */
internal val TextLineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None, LineHeightStyle.Mode.Minimum)

/** The sizes of headings 1 to 3, in multiples of the body's size. */
private val HeadingSizes = floatArrayOf(1.6f, 1.35f, 1.15f)

/** The indentation of one level of a bulleted list or checklist, and of a numbered list, in multiples of the body's size. */
private const val ListIndent = 1.75f
private const val NumberedIndent = 2.25f

/** The indentation of a quote, in multiples of the body's size. */
internal const val QuoteIndent = 1.1f

/** The style of the character between two blocks: transparent and narrow; the line keeps its height from the paragraph. */
private val Separator = SpanStyle(color = Color.Transparent, fontSize = 1.sp)

/** A style over a range of a run's text. */
internal class Styled<T>(val item: T, val start: Int, val end: Int)

/** Something drawn around a run's text. */
internal sealed interface Decoration

/**
 * The marker of a list item: a number, a bullet or a checkbox, drawn before the item's first line.
 *
 * @param offset Where the item starts in the run's text.
 * @param indent The item's indentation in multiples of the body's size.
 * @param level The nesting level of its list, which picks the bullet.
 */
internal class Marker(val offset: Int, val block: Long, val kind: ListKind, val text: String?, val level: Int, val checked: Boolean, val indent: Float) : Decoration

/** The line beside a quoted block, between two offsets. */
internal class QuoteLine(val start: Int, val end: Int) : Decoration

/** A divider, on the line of a block. */
internal class Divider(val offset: Int) : Decoration

/**
 * An image or a chip drawn where its object character stands, or an image wrapped square at the
 * left of the column, which text flows beside.
 *
 * @param width Its width in pixels.
 * @param height Its height in pixels.
 * @param float Whether it is wrapped square with text beside it.
 * @param top Where a wrapped image's top stands in the text, in pixels.
 */
internal data class ObjectBox(val offset: Int, val item: InlineObject, val width: Float, val height: Float, val float: Boolean = false, val top: Float = 0f) : Decoration

/**
 * The band of a run that an image wrapped square takes: lines whose tops fall in it stand to the
 * right of the image.
 *
 * @param top The band's top in pixels.
 * @param bottom Its bottom, below the image's room under it.
 * @param shift How far its lines move right: the image's width and its room beside it.
 */
internal class FloatArea(val top: Float, val bottom: Float, val shift: Float)

/**
 * How a run is drawn: its text, the styles of its text and paragraphs, what is drawn around it and
 * the bands of its images wrapped square.
 */
internal class RunPresentation(
    val text: String,
    val spans: List<Styled<SpanStyle>>,
    val paragraphs: List<Styled<ParagraphStyle>>,
    val decorations: List<Decoration>,
    val floats: List<FloatArea> = emptyList(),
) {
    /** The height the run needs for its wrapped images, which may reach below its text. */
    val bottom: Float get() = floats.maxOfOrNull { it.bottom } ?: 0f

    /** Returns how far a line whose top is at a height moves right beside a wrapped image. */
    fun shiftAt(y: Float): Float = floats.firstOrNull { y >= it.top && y < it.bottom }?.shift ?: 0f
}

/**
 * The sizes of the objects of a run, which their characters reserve.
 *
 * @param column The width of the text column in pixels.
 * @param glyphHeight The line height of an object character at 100 sp, in pixels.
 * @param glyphWidth Its width at 100 sp, in pixels.
 * @param glyphDescent The part of its line height below the baseline, in multiples of its size.
 * @param imageSize Returns the size of an image's file in pixels, or null while it is unknown.
 * @param chipWidth Returns the width of a chip in pixels.
 */
internal class ObjectMetrics(
    val column: Float,
    val glyphHeight: Float,
    val glyphWidth: Float,
    val glyphDescent: Float,
    val lineHeight: Float,
    val density: Density,
    val imageSize: (ImageObject) -> Pair<Int, Int>?,
    val chipWidth: (AttachmentObject) -> Float,
) {
    /** The room a wrapped image keeps on its right and below it, as on the desktop: 0.75 rem. */
    val wrapGap: Float get() = with(density) { BodyFontSize.toPx() * 0.75f }

    /**
     * Returns whether text flows beside an object: an image wrapped square that leaves room for
     * four body sizes of text in the column; a wider one stands on a line of its own.
     */
    fun floats(item: InlineObject): Boolean =
        item is ImageObject && item.wrap == "square" && column - sizeOf(item).first - wrapGap >= with(density) { BodyFontSize.toPx() * 4 }

    /** Returns the size an object is drawn at, in pixels. */
    fun sizeOf(item: InlineObject): Pair<Float, Float> = when (item) {
        is AttachmentObject -> chipWidth(item) to lineHeight
        is ImageObject -> {
            val natural = imageSize(item)
            if (natural == null || item.pending.isNotEmpty()) {
                with(density) { (4 * BodyFontSize.toPx()) to (3 * BodyFontSize.toPx()) }
            } else {
                // An image takes its own size in density-independent pixels, at most its share of the column.
                val limit = column * (if (item.width > 0) item.width / 100f else 1f)
                val width = minOf(with(density) { natural.first.toFloat() * this.density }, limit).coerceAtLeast(1f)
                width to width * natural.second / natural.first.coerceAtLeast(1)
            }
        }
    }

    /**
     * Returns the style of an object character that reserves a box: its font size gives the line its
     * height and its horizontal scale the box's width; it is drawn transparent. Letter spacing would
     * not do for the width: Android 15 and later trim it at the edges of a line and count the rest
     * to the next character, so objects overlapped what followed them. The character of an image
     * that text flows beside takes no room: the image is drawn at the left of its band.
     */
    fun placeholder(item: InlineObject): SpanStyle {
        if (floats(item)) return SpanStyle(color = Color.Transparent, textGeometricTransform = TextGeometricTransform(scaleX = 0.01f))
        val (width, height) = sizeOf(item)
        // Images that stand on lines of their own take the column's width.
        val reserved = if (item is ImageObject && item.wrap.isNotEmpty()) column else width
        // The glyph was measured at 100 sp; its line height and advance grow with the font size.
        val fontSize = (100f * height / glyphHeight).coerceAtLeast(1f)
        val advance = (glyphWidth * fontSize / 100f).coerceAtLeast(0.01f)
        return SpanStyle(color = Color.Transparent, fontSize = fontSize.sp, textGeometricTransform = TextGeometricTransform(scaleX = reserved / advance))
    }
}

private val families = HashMap<String, FontFamily>()

/**
 * The box of the object character at 100 sp, which images and chips reserve their boxes with: its
 * line height and width in pixels, and its descent in multiples of its size.
 */
internal data class GlyphBox(val height: Float, val width: Float, val descent: Float)

/** Measures the object character at 100 sp. */
internal fun measureObjectGlyph(measurer: TextMeasurer, style: TextStyle, density: Density): GlyphBox {
    val result = measurer.measure(ObjectCharacter.toString(), style.copy(fontSize = 100.sp, lineHeight = TextUnit.Unspecified))
    val size = with(density) { 100.sp.toPx() }
    return GlyphBox(result.size.height.toFloat(), result.size.width.toFloat(), (result.size.height - result.firstBaseline) / size)
}

/** Returns the font family a stored family name draws in: the phone's family of that name, otherwise the default. */
private fun fontFamily(name: String): FontFamily = synchronized(families) {
    families.getOrPut(name) { FontFamily(Typeface.create(name, Typeface.NORMAL)) }
}

/**
 * Builds how a run is drawn.
 *
 * @param matches The matches of find in the whole note, and [current] the selected one.
 * @param missing The notes that links point to that no longer exist.
 */
internal fun presentation(
    run: Run,
    colors: NoteColors,
    metrics: ObjectMetrics,
    matches: List<Match>,
    current: Match?,
    missing: Set<String>,
): RunPresentation {
    val spans = ArrayList<Styled<SpanStyle>>()
    val paragraphs = ArrayList<Styled<ParagraphStyle>>()
    val decorations = ArrayList<Decoration>()
    val markers = listMarkers(run.blocks)
    var offset = 0
    for ((i, block) in run.blocks.withIndex()) {
        val start = offset
        val end = start + block.length
        val paragraphEnd = if (i < run.blocks.lastIndex) end + 1 else end
        val (marker, level) = markers[i]
        val indent = indentOf(block, level)
        paragraphs += Styled(paragraphStyle(block, indent, block.inline.any { it is ImageObject && !metrics.floats(it) }), start, paragraphEnd)
        blockSpan(block, colors)?.let { spans += Styled(it, start, maxOf(end, start)) }
        var position = start
        for (piece in block.inline) {
            when (piece) {
                is TextRun -> formatSpan(piece.format, colors, missing)?.let { spans += Styled(it, position, position + piece.length) }
                is InlineObject -> {
                    spans += Styled(metrics.placeholder(piece), position, position + 1)
                    val (width, height) = metrics.sizeOf(piece)
                    decorations += ObjectBox(position, piece, width, height, metrics.floats(piece))
                }
            }
            position += piece.length
        }
        block.list?.let { list -> decorations += Marker(start, block.id, list.kind, marker, level, block.checked, indent) }
        if (block.quote && block.list == null) decorations += QuoteLine(start, end)
        if (block.hr) decorations += Divider(start)
        // The character between blocks breaks the line and is never seen.
        if (paragraphEnd > end) spans += Styled(Separator, end, paragraphEnd)
        offset = paragraphEnd
    }
    // Find highlights the matches of the run, the current one more strongly.
    val find = rgb(colors.primary)
    for (match in matches) {
        if (match.container != run.container) continue
        val index = run.indexOf(match.item)
        if (index < 0) continue
        val blockStart = run.startOf(index)
        val alpha = if (match == current) 0.55f else 0.25f
        spans += Styled(SpanStyle(background = find.copy(alpha = alpha)), blockStart + match.from, blockStart + match.to)
    }
    return RunPresentation(run.text, spans, paragraphs, decorations)
}

/** Returns the indentation of a block in multiples of the body's size: a list item's level, a quote's line. */
private fun indentOf(block: Block, level: Int): Float {
    val list = block.list
    if (list != null) return ListIndent * (level - 1) + if (list.kind == ListKind.Number) NumberedIndent else ListIndent
    return if (block.quote) QuoteIndent else 0f
}

/**
 * Returns the paragraph style of a block: its indentation, the height of its lines and, for
 * headings, room above. A block with images keeps the natural height of its lines: Compose takes
 * the line height style's measures from a paragraph's first line, so an image's tall line would
 * make every line after it as tall.
 */
private fun paragraphStyle(block: Block, indent: Float, images: Boolean): ParagraphStyle {
    val textIndent = if (indent > 0) TextIndent(indent.em, indent.em) else null
    val heading = block.heading
    return if (heading in 1..3) {
        // A heading's line keeps the room above it that the desktop's margins give; the height is
        // in sp, as em here would count in the body's size.
        val size = HeadingSizes[heading - 1]
        ParagraphStyle(
            textIndent = textIndent,
            lineHeight = (BodyFontSize.value * size * 1.85f).sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None, LineHeightStyle.Mode.Minimum),
        )
    } else if (images) {
        ParagraphStyle(textIndent = textIndent)
    } else {
        ParagraphStyle(textIndent = textIndent, lineHeight = TextLineHeight, lineHeightStyle = TextLineHeightStyle)
    }
}

/** Returns the style of a whole block: a heading's size, a quote's muted text, a checked item's line. */
private fun blockSpan(block: Block, colors: NoteColors): SpanStyle? = when {
    block.heading in 1..3 -> SpanStyle(fontSize = HeadingSizes[block.heading - 1].em, fontWeight = FontWeight.SemiBold)
    block.list?.kind == ListKind.Check && block.checked -> SpanStyle(color = rgb(colors.muted), textDecoration = TextDecoration.LineThrough)
    block.quote && block.list == null -> SpanStyle(color = rgb(colors.muted))
    else -> null
}

/** Returns the style of a run of text in a format, or null for plain text. */
internal fun formatSpan(format: TextFormat, colors: NoteColors, missing: Set<String>): SpanStyle? {
    if (format == TextFormat.Plain) return null
    val decorations = ArrayList<TextDecoration>()
    var color: Color = Color.Unspecified
    format.href?.let { href ->
        val target = noteLinkTarget(href)
        if (target != null && target in missing) {
            color = rgb(colors.muted)
            decorations += TextDecoration.LineThrough
        } else {
            color = rgb(colors.link)
            decorations += TextDecoration.Underline
        }
    }
    format.color?.let { key -> colors.textShade(key)?.let { color = it } }
    if (format.underline) decorations += TextDecoration.Underline
    if (format.strike) decorations += TextDecoration.LineThrough
    val highlight = format.highlight?.let { colors.highlightShade(it) }
    return SpanStyle(
        color = color,
        fontWeight = if (format.bold) FontWeight.Bold else null,
        fontStyle = if (format.italic) FontStyle.Italic else null,
        fontFamily = when {
            format.code -> FontFamily.Monospace
            format.family != null -> fontFamily(format.family!!)
            else -> null
        },
        fontSize = when {
            format.code -> 0.92.em
            format.valign != null -> 0.83.em
            else -> TextUnit.Unspecified
        },
        baselineShift = when (format.valign) {
            VAlign.Sup -> BaselineShift.Superscript
            VAlign.Sub -> BaselineShift.Subscript
            null -> null
        },
        background = highlight ?: if (format.code) rgb(colors.raised) else Color.Unspecified,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}
