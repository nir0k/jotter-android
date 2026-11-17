package io.github.nir0k.jotter.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnitType
import kotlin.math.ceil

/**
 * Returns a presentation's text with its styles, up to an offset. A paragraph style that ends
 * inside a line starts a new line there, which is how text is moved beside a wrapped image without
 * a character of its own.
 */
internal fun RunPresentation.annotated(paragraphs: List<Styled<ParagraphStyle>> = this.paragraphs, limit: Int = text.length): AnnotatedString = buildAnnotatedString {
    append(text, 0, limit)
    for (paragraph in paragraphs) if (paragraph.end <= limit && paragraph.end >= paragraph.start) addStyle(paragraph.item, paragraph.start, paragraph.end)
    for (span in spans) if (span.end <= limit && span.end > span.start) addStyle(span.item, span.start, span.end)
}

/**
 * Lays a run's text out around its images wrapped square: each image stands at the left of the
 * column from the top of the line its character is on, and the lines whose tops fall beside it,
 * with its room below, move right by its width and its room beside it; the text after them takes
 * the full width again. An image whose top would fall beside an earlier one starts below it. The
 * lines are moved by ending paragraph styles where the band starts and ends, found by measuring
 * the text.
 *
 * @param shown The run's presentation, whose wrapped images have no place yet.
 * @param style The text style the run is drawn with.
 * @param metrics The sizes of the run's objects and the width of its column.
 * @return The presentation with the paragraphs beside the images indented and the images placed.
 */
internal fun wrapImages(shown: RunPresentation, measurer: TextMeasurer, style: TextStyle, metrics: ObjectMetrics): RunPresentation {
    val boxes = shown.decorations.filterIsInstance<ObjectBox>().filter { it.float }
    if (boxes.isEmpty()) return shown
    val length = shown.text.length
    val constraints = Constraints(maxWidth = metrics.column.toInt().coerceAtLeast(1))
    val bodyPx = with(metrics.density) { BodyFontSize.toPx() }
    // Indents in em count in the size of the run's text style.
    val emSp = style.fontSize.value
    var paragraphs = shown.paragraphs
    val placed = HashMap<Int, ObjectBox>()
    val floats = ArrayList<FloatArea>()
    var below = 0f

    /** Lays out the text up to an offset with the given paragraph styles. */
    fun measure(styles: List<Styled<ParagraphStyle>>, limit: Int): TextLayoutResult =
        measurer.measure(shown.annotated(styles, limit), style, constraints = constraints)

    for (box in boxes) {
        val shift = box.width + metrics.wrapGap
        val shiftSp = with(metrics.density) { shift.toSp().value }
        // Only the text that could stand beside the image is measured, a paragraph at a time, and
        // more when it falls short.
        var budget = box.offset + estimateBeside(box.height + metrics.wrapGap + below, metrics.column, bodyPx)
        while (true) {
            val limit = paragraphEndAfter(paragraphs, budget, length)
            val layout = measure(paragraphs, limit)
            var line = layout.getLineForOffset(box.offset)
            while (line < layout.lineCount && layout.getLineTop(line) < below) line++
            val located = line < layout.lineCount
            if (!located && limit < length) {
                budget = limit + (limit - box.offset).coerceAtLeast(1)
                continue
            }
            val top = if (located) layout.getLineTop(line) else maxOf(below, layout.size.height.toFloat())
            val bottom = top + box.height + metrics.wrapGap
            var to = length
            if (located) {
                val from = layout.getLineStart(line)
                val beside = measure(paragraphs.indented(from, length, shiftSp, emSp), limit)
                var found = false
                for (next in beside.getLineForOffset(from) until beside.lineCount) {
                    if (beside.getLineTop(next) >= bottom) {
                        to = beside.getLineStart(next)
                        found = true
                        break
                    }
                }
                if (!found && limit < length) {
                    budget = limit + (limit - box.offset).coerceAtLeast(1)
                    continue
                }
                paragraphs = paragraphs.indented(from, to, shiftSp, emSp)
            }
            placed[box.offset] = box.copy(top = top)
            floats += FloatArea(top, bottom, shift)
            below = bottom
            break
        }
    }
    val decorations = shown.decorations.map { if (it is ObjectBox) placed[it.offset] ?: it else it }
    return RunPresentation(shown.text, shown.spans, paragraphs, decorations, floats)
}

/**
 * Returns a generous count of characters that fit in a height of the column: lines no shorter than
 * 0.8 of the body's size and characters no narrower than 0.2 of it.
 */
private fun estimateBeside(height: Float, column: Float, bodyPx: Float): Int =
    ceil(height / (bodyPx * 0.8f)).toInt() * ceil(column / (bodyPx * 0.2f)).toInt()

/** Returns the end of the paragraph that holds an offset, so a measured part never ends inside a paragraph. */
private fun paragraphEndAfter(paragraphs: List<Styled<ParagraphStyle>>, offset: Int, length: Int): Int {
    if (offset >= length) return length
    return paragraphs.firstOrNull { it.end > offset }?.end?.coerceAtMost(length) ?: length
}

/**
 * Returns paragraph styles with the text between two offsets moved right: paragraphs are split at
 * the offsets, and the parts after a split start as continuations, with the indent of their
 * paragraph's later lines.
 *
 * @param shiftSp How far the text moves, in sp.
 * @param emSp The size that indents in em count in, in sp.
 */
internal fun List<Styled<ParagraphStyle>>.indented(from: Int, to: Int, shiftSp: Float, emSp: Float): List<Styled<ParagraphStyle>> {
    val result = ArrayList<Styled<ParagraphStyle>>(size + 2)
    for (paragraph in this) {
        val inside = if (paragraph.start == paragraph.end) paragraph.start in from until to else paragraph.start < to && paragraph.end > from
        if (!inside) {
            result += paragraph
            continue
        }
        if (paragraph.start < from) result += Styled(paragraph.item, paragraph.start, from)
        val start = maxOf(paragraph.start, from)
        val end = minOf(paragraph.end, to)
        result += Styled(paragraph.item.shifted(shiftSp, emSp, start > paragraph.start), start, end)
        if (end < paragraph.end) result += Styled(paragraph.item.shifted(0f, emSp, true), end, paragraph.end)
    }
    return result
}

/**
 * Returns a paragraph style with its indent moved right; a continuation's first line takes the
 * indent of the paragraph's later lines.
 */
private fun ParagraphStyle.shifted(shiftSp: Float, emSp: Float, continuation: Boolean): ParagraphStyle {
    /** Returns an indent in sp. */
    fun sp(unit: TextUnit?): Float = when {
        unit == null || !unit.isSpecified -> 0f
        unit.type == TextUnitType.Em -> unit.value * emSp
        else -> unit.value
    }
    val rest = sp(textIndent?.restLine) + shiftSp
    val first = if (continuation) rest else sp(textIndent?.firstLine) + shiftSp
    return copy(textIndent = TextIndent(first.sp, rest.sp))
}
