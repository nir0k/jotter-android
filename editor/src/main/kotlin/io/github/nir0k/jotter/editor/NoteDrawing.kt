package io.github.nir0k.jotter.editor

import android.content.Context
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.nir0k.jotter.core.body.CellContainer
import io.github.nir0k.jotter.core.body.CodeBlock
import io.github.nir0k.jotter.core.body.Block
import io.github.nir0k.jotter.core.body.ObjectCharacter
import io.github.nir0k.jotter.core.body.RootContainer
import io.github.nir0k.jotter.core.body.Run
import io.github.nir0k.jotter.core.body.Table
import io.github.nir0k.jotter.core.body.columnWidths
import io.github.nir0k.jotter.core.body.noteImages
import io.github.nir0k.jotter.core.body.parseBody
import io.github.nir0k.jotter.core.body.runsIn
import io.github.nir0k.jotter.core.format.ExportBlock
import io.github.nir0k.jotter.core.highlight.CodeHighlighter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A note drawn for an export: the editor's own drawing of the body, laid out on a column of a fixed
// width under the title and the updated date, so a PDF and a JPG look as the note does in the
// editor. The drawing is split into parts at the top-level blocks, and inside them at their lines,
// so a page or an image part never cuts a line.

/**
 * A part of a drawn note: a top-level block or the heading.
 *
 * @param top Where it starts in the note.
 * @param height Its height.
 * @param breaks Where inside it a page may start, measured from its top; the lines of its text.
 */
internal class Part(val top: Float, val height: Float, val breaks: List<Float>, val draw: DrawScope.() -> Unit)

/**
 * A note laid out for an export.
 *
 * @param width The column's width in the drawing's pixels.
 * @param height The note's height.
 */
class DrawnNote internal constructor(val width: Float, val height: Float, private val density: Density, private val parts: List<Part>) {
    /** The heading and the top-level blocks, where the parts of a tall JPG start. */
    val blocks: List<ExportBlock> get() = parts.map { ExportBlock(it.top.toDouble(), (it.top + it.height).toDouble()) }

    /**
     * Returns where the pages of a given height start: at a block or a line that fits, and inside
     * a line only when the line is taller than a page.
     */
    fun pageStarts(pageHeight: Float): List<Float> {
        val candidates = (parts.flatMap { part -> listOf(part.top) + part.breaks.map { part.top + it } } + height).distinct().sorted()
        val starts = mutableListOf(0f)
        while (true) {
            val start = starts.last()
            if (height - start <= pageHeight) break
            val next = candidates.lastOrNull { it > start && it <= start + pageHeight } ?: (start + pageHeight)
            starts += next
        }
        return starts
    }

    /**
     * Draws the part of the note between two heights onto a canvas, at its origin.
     *
     * @param canvas An Android canvas: a PDF page's or a bitmap's.
     */
    fun drawRange(canvas: android.graphics.Canvas, top: Float, bottom: Float) {
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(canvas), Size(width, bottom - top)) {
            clipRect(0f, 0f, width, bottom - top) {
                translate(0f, -top) {
                    for (part in parts) {
                        if (part.top + part.height < top || part.top > bottom) continue
                        translate(0f, part.top) { part.draw(this) }
                    }
                }
            }
        }
    }
}

/**
 * Lays out a note for an export, its images decoded first.
 *
 * @param density The drawing's density: its pixels per density-independent pixel, the text not
 *   following the system's font scale, as an export is not for this device's screen.
 * @param column The width of the text column in the drawing's pixels.
 * @param subtitle The line under the title, such as the updated date.
 */
suspend fun drawNote(
    context: Context,
    noteId: String,
    body: String,
    title: String,
    subtitle: String,
    colors: NoteColors,
    strings: EditorStrings,
    locale: Locale,
    assets: NoteAssets,
    density: Density,
    column: Float,
): DrawnNote = withContext(Dispatchers.Main) {
    val doc = parseBody(body)
    val images = NoteImages(assets, CoroutineScope(Dispatchers.Main))
    images.preload(doc.noteImages(noteId))
    // Code blocks need their definitions read before they are drawn.
    withContext(Dispatchers.Default) { doc.items.filterIsInstance<CodeBlock>().map { it.language }.distinct().forEach(CodeHighlighter::prepare) }
    val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
    val style = bodyTextStyle(colors)
    val decorations = DecorationContext(colors, measurer, style, images, { null }, strings, locale)
    val glyph = measureObjectGlyph(measurer, style, density)
    val fontPx = with(density) { style.fontSize.toPx() }
    fun metrics(width: Float) = ObjectMetrics(
        column = width,
        glyphHeight = glyph.height,
        glyphWidth = glyph.width,
        glyphDescent = glyph.descent,
        lineHeight = fontPx * 1.4f,
        density = density,
        imageSize = { images.size(it.file) },
        chipWidth = { chipWidth(it, measurer, style, strings, locale, density, glyph = false) },
    )
    val parts = ArrayList<Part>()
    var y = 0f

    /** Adds a part at the current height. */
    fun add(height: Float, breaks: List<Float>, draw: DrawScope.() -> Unit) {
        parts += Part(y, height, breaks, draw)
        y += height
    }

    // The title and the updated date above the note.
    val titleLayout = measurer.measure(title, style.copy(fontSize = style.fontSize * 1.5f, fontWeight = FontWeight.SemiBold), constraints = Constraints(maxWidth = column.toInt()))
    val subtitleLayout = measurer.measure(subtitle, style.copy(color = rgb(colors.muted), fontSize = style.fontSize * 0.875f), constraints = Constraints(maxWidth = column.toInt()))
    val gap = fontPx * 0.25f
    add(titleLayout.size.height + gap + subtitleLayout.size.height + fontPx * 1.25f, emptyList()) {
        drawText(titleLayout)
        drawText(subtitleLayout, topLeft = Offset(0f, titleLayout.size.height + gap))
    }
    val runs = doc.runsIn(RootContainer).associateBy { it.start }
    for ((index, item) in doc.items.withIndex()) {
        when (item) {
            is Block -> runs[index]?.let { run ->
                val (layout, shown) = layoutRun(run, colors, metrics(column), measurer, style, column)
                add(runHeight(layout, shown), lineTops(layout)) {
                    drawDecorations(layout, shown, decorations, -1)
                    drawText(layout)
                }
            }
            is CodeBlock -> drawCode(item, colors, strings, measurer, style, column, fontPx, ::add)
            is Table -> drawTable(item, colors, measurer, style, decorations, column, fontPx, ::metrics, ::add)
        }
    }
    DrawnNote(column, y, density, parts)
}

/** Returns where the lines of a text start, below its first. */
private fun lineTops(layout: TextLayoutResult): List<Float> = (1 until layout.lineCount).map { layout.getLineTop(it) }

/** Returns a presentation's text with its styles. */
/** Lays out a run of blocks on a column, with text beside its images wrapped square. */
private fun layoutRun(run: Run, colors: NoteColors, metrics: ObjectMetrics, measurer: TextMeasurer, style: TextStyle, width: Float): Pair<TextLayoutResult, RunPresentation> {
    val shown = wrapImages(presentation(run, colors, metrics, emptyList(), null, emptySet()), measurer, style, metrics)
    return measurer.measure(shown.annotated(), style, constraints = Constraints(maxWidth = width.toInt().coerceAtLeast(1))) to shown
}

/** Returns the height a laid out run takes: its text's, or its wrapped images' when they reach lower. */
private fun runHeight(layout: TextLayoutResult, shown: RunPresentation): Float = maxOf(layout.size.height.toFloat(), shown.bottom)

/** Lays out and adds a code block: its header with the language and title, the highlighted code and its line numbers. */
private fun drawCode(
    code: CodeBlock,
    colors: NoteColors,
    strings: EditorStrings,
    measurer: TextMeasurer,
    style: TextStyle,
    column: Float,
    fontPx: Float,
    add: (Float, List<Float>, DrawScope.() -> Unit) -> Unit,
) {
    val codeStyle = style.copy(fontFamily = FontFamily.Monospace, fontSize = style.fontSize * 0.9f, lineHeight = TextLineHeight * 0.9f, lineHeightStyle = TextLineHeightStyle)
    val headerStyle = style.copy(color = rgb(colors.muted), fontSize = style.fontSize * 0.8f, fontWeight = FontWeight.SemiBold)
    val headerText = listOf(if (code.language.isEmpty()) strings.plainText else CodeLanguages.firstOrNull { it.key == code.language }?.name ?: code.language, code.title)
        .filter { it.isNotEmpty() }.joinToString("   ")
    val padding = fontPx * 0.75f
    val gutter = codeStyle.fontSize.value * fontPx / style.fontSize.value * 2.5f
    val header = measurer.measure(headerText, headerStyle, constraints = Constraints(maxWidth = (column - 2 * padding).toInt().coerceAtLeast(1)))
    val text = buildAnnotatedString {
        append(code.text)
        for (span in CodeHighlighter.highlight(code.text, code.language, colors.theme)) {
            val s = span.style
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = s.color?.let(::rgb) ?: androidx.compose.ui.graphics.Color.Unspecified,
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                ),
                span.start, span.end,
            )
        }
    }
    val body = measurer.measure(text, codeStyle, constraints = Constraints(maxWidth = (column - gutter - padding).toInt().coerceAtLeast(1)))
    val top = fontPx * 0.5f
    val headerHeight = header.size.height + padding
    val height = top * 2 + headerHeight + body.size.height + padding
    val breaks = lineTops(body).map { top + headerHeight + it }
    add(height, breaks) {
        val box = Size(column, height - 2 * top)
        drawRoundRect(rgb(colors.raised), Offset(0f, top), box, CornerRadius(fontPx * 0.4f))
        drawRoundRect(rgb(colors.edge), Offset(0f, top), box, CornerRadius(fontPx * 0.4f), style = Stroke(1f * density))
        drawText(header, topLeft = Offset(padding, top + padding / 2))
        drawText(body, topLeft = Offset(gutter, top + headerHeight))
        var offset = 0
        var number = 1
        while (true) {
            val line = body.getLineForOffset(offset)
            val label = measurer.measure(number.toString(), codeStyle.copy(color = rgb(colors.muted)))
            drawText(label, topLeft = Offset(gutter - padding - label.size.width, top + headerHeight + body.getLineBaseline(line) - label.firstBaseline))
            val next = code.text.indexOf('\n', offset)
            if (next < 0) break
            offset = next + 1
            number++
        }
    }
}

/** Lays out and adds a table: its rows with their cells' text, the header row tinted, the lines between them. */
private fun drawTable(
    table: Table,
    colors: NoteColors,
    measurer: TextMeasurer,
    style: TextStyle,
    decorations: DecorationContext,
    column: Float,
    fontPx: Float,
    metrics: (Float) -> ObjectMetrics,
    add: (Float, List<Float>, DrawScope.() -> Unit) -> Unit,
) {
    val width = column * (if (table.width > 0) table.width / 100f else 1f)
    val widths = columnWidths(table.widths, table.columns).map { (it / 100 * width).toFloat() }
    val padX = fontPx * 0.5f
    val padY = fontPx * 0.25f
    val headerStyle = style.copy(fontWeight = FontWeight.Bold)
    val rows = table.cells.mapIndexed { r, row ->
        row.mapIndexed { c, cell ->
            val cellWidth = (widths[c] - 2 * padX).coerceAtLeast(1f)
            layoutRun(Run(CellContainer(table.id, r, c), 0, cell), colors, metrics(cellWidth), measurer, if (r < table.headerRows) headerStyle else style, cellWidth)
        }
    }
    val heights = rows.map { row -> row.maxOf { (layout, shown) -> runHeight(layout, shown) } + 2 * padY }
    val margin = fontPx * 0.5f
    val total = heights.sum() + 2 * margin
    val tops = heights.runningFold(margin) { acc, h -> acc + h }
    add(total, tops.drop(1).dropLast(1)) {
        val line = rgb(colors.outline)
        val stroke = 1f * density
        rows.forEachIndexed { r, row ->
            val top = tops[r]
            if (r < table.headerRows) drawRect(rgb(colors.raised), Offset(0f, top), Size(width, heights[r]))
            var x = 0f
            row.forEachIndexed { c, (layout, shown) ->
                translate(x + padX, top + padY) {
                    drawDecorations(layout, shown, decorations, -1)
                    drawText(layout)
                }
                x += widths[c]
                if (c < row.lastIndex) drawLine(line, Offset(x, top), Offset(x, top + heights[r]), stroke)
            }
            if (r > 0) drawLine(line, Offset(0f, top), Offset(width, top), stroke)
        }
        drawRect(line, Offset(0f, margin), Size(width, heights.sum()), style = Stroke(stroke))
    }
}
