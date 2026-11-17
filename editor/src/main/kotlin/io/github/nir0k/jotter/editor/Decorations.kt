package io.github.nir0k.jotter.editor

import android.content.Context
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.core.body.AttachmentObject
import io.github.nir0k.jotter.core.body.ImageObject
import io.github.nir0k.jotter.core.body.ListKind
import java.text.NumberFormat
import java.util.Locale

/**
 * The texts the editor draws in the interface language.
 *
 * @param copy The Copy button of code blocks.
 * @param plainText The language of a code block without one.
 * @param codeTitle The placeholder of a code block's title.
 * @param sizeUnits The units of file sizes, from bytes to gigabytes, separated by spaces.
 * @param placeholder The hint of an empty body.
 */
data class EditorStrings(val copy: String, val plainText: String, val codeTitle: String, val sizeUnits: String, val placeholder: String) {
    companion object {
        /** Reads the texts from the editor's resources in the context's language. */
        fun from(context: Context) = EditorStrings(
            context.getString(R.string.editor_copy),
            context.getString(R.string.editor_plain_text),
            context.getString(R.string.editor_code_title),
            context.getString(R.string.editor_size_units),
            context.getString(R.string.editor_placeholder),
        )
    }
}

/** Formats a file size in the interface language: bytes, then KB, MB and GB by 1024. */
internal fun formatSize(size: Long, units: String, locale: Locale): String {
    val names = units.split(' ')
    var value = size.toDouble()
    var unit = 0
    while (value >= 1024 && unit < names.size - 1) {
        value /= 1024
        unit++
    }
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (unit == 0 || value >= 10) 0 else 1 }
    return "${format.format(value)} ${names[unit]}"
}

/** The size of a checkbox and a bullet, in multiples of the body's size. */
private const val CheckboxSize = 1f
private const val BulletSize = 0.36f

/** The radius of a selected image's corner handle as drawn; its touch reaches further. */
private val HandleRadius = 7.dp

/** Returns where a list item's checkbox is drawn, in the text's coordinates, beside a wrapped image when its line is. */
internal fun checkboxRect(layout: TextLayoutResult, marker: Marker, fontPx: Float, shown: RunPresentation): Rect {
    val line = layout.getLineForOffset(marker.offset.coerceAtMost(layout.layoutInput.text.length))
    val top = layout.getLineTop(line)
    val bottom = layout.getLineBottom(line)
    val side = CheckboxSize * fontPx
    val left = (marker.indent - 1.5f) * fontPx + shown.shiftAt(top)
    val y = top + (bottom - top - side) / 2
    return Rect(left, y, left + side, y + side)
}

/**
 * Returns where an object's box is drawn, in the text's coordinates: at its character, centred on
 * its line, which its character makes as tall as it; an image text flows beside stands at the left
 * of the column from the top of its band.
 */
internal fun objectRect(layout: TextLayoutResult, box: ObjectBox): Rect {
    if (box.float) return Rect(0f, box.top, box.width, box.top + box.height)
    val bounds = layout.getBoundingBox(box.offset.coerceAtMost(maxOf(0, layout.layoutInput.text.length - 1)))
    val top = bounds.top + maxOf(0f, (bounds.height - box.height) / 2)
    return Rect(bounds.left, top, bounds.left + box.width, top + box.height)
}

/** Returns the corners of an object's box, where a selected image's handles stand. */
internal fun Rect.corners(): List<Offset> = listOf(topLeft, topRight, bottomLeft, bottomRight)

/** What decorations are drawn with. */
internal class DecorationContext(
    val colors: NoteColors,
    val measurer: TextMeasurer,
    val textStyle: TextStyle,
    val images: NoteImages,
    val glyph: (String) -> Painter?,
    val strings: EditorStrings,
    val locale: Locale,
)

/**
 * Draws what stands around a run's text: list markers, checkboxes, quote lines, dividers, images
 * and chips; the selected object gets an outline, and a selected image that can be resized gets
 * handles at its corners.
 *
 * @param selected The offset of the selected object, or -1.
 * @param handles Whether the selected image shows its handles.
 * @param resized The size the selected image takes at the width being dragged, outlined; null when no handle is dragged.
 */
internal fun DrawScope.drawDecorations(
    layout: TextLayoutResult,
    presentation: RunPresentation,
    context: DecorationContext,
    selected: Int,
    handles: Boolean = false,
    resized: Size? = null,
) {
    val colors = context.colors
    val fontPx = context.textStyle.fontSize.toPx()
    val length = layout.layoutInput.text.length
    for (decoration in presentation.decorations) {
        when (decoration) {
            is Marker -> drawMarker(layout, decoration, presentation, context, fontPx)
            is QuoteLine -> {
                // Each line's piece stands where its line starts, beside a wrapped image or not.
                val first = layout.getLineForOffset(decoration.start.coerceAtMost(length))
                val last = layout.getLineForOffset(decoration.end.coerceAtMost(length))
                for (line in first..last) {
                    val top = layout.getLineTop(line)
                    drawRect(rgb(colors.outline), Offset(presentation.shiftAt(top), top), Size(3.dp.toPx(), layout.getLineBottom(line) - top))
                }
            }
            is Divider -> {
                val line = layout.getLineForOffset(decoration.offset.coerceAtMost(length))
                val top = layout.getLineTop(line)
                val y = (top + layout.getLineBottom(line)) / 2
                drawLine(rgb(colors.outline), Offset(presentation.shiftAt(top), y), Offset(size.width, y), 1.dp.toPx())
            }
            is ObjectBox -> {
                val rect = objectRect(layout, decoration)
                when (val item = decoration.item) {
                    is ImageObject -> drawImageBox(rect, item, context)
                    is AttachmentObject -> drawChip(rect, item, context, fontPx)
                }
                if (decoration.offset == selected) {
                    drawRect(rgb(colors.primary), rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
                    if (handles && decoration.item is ImageObject) drawHandles(rect, resized, context)
                }
            }
        }
    }
}

/** Draws a selected image's corner handles and, while one is dragged, the outline of the size it takes. */
private fun DrawScope.drawHandles(rect: Rect, resized: Size?, context: DecorationContext) {
    val primary = rgb(context.colors.primary)
    resized?.let { drawRect(primary, rect.topLeft, it, style = Stroke(1.dp.toPx())) }
    val radius = HandleRadius.toPx()
    for (corner in rect.corners()) {
        drawCircle(primary, radius, corner)
        drawCircle(rgb(context.colors.onPrimary), radius, corner, style = Stroke(1.5.dp.toPx()))
    }
}

/** Draws a list item's marker: its number, its level's bullet or its checkbox, beside a wrapped image when its line is. */
private fun DrawScope.drawMarker(layout: TextLayoutResult, marker: Marker, presentation: RunPresentation, context: DecorationContext, fontPx: Float) {
    val colors = context.colors
    val line = layout.getLineForOffset(marker.offset.coerceAtMost(layout.layoutInput.text.length))
    val baseline = layout.getLineBaseline(line)
    val indent = marker.indent * fontPx + presentation.shiftAt(layout.getLineTop(line))
    when (marker.kind) {
        ListKind.Number -> {
            val text = context.measurer.measure(marker.text.orEmpty(), context.textStyle)
            drawText(text, topLeft = Offset(indent - 0.4f * fontPx - text.size.width, baseline - text.firstBaseline))
        }
        ListKind.Bullet -> {
            val radius = BulletSize * fontPx / 2
            val center = Offset(indent - 1f * fontPx, baseline - 0.33f * fontPx)
            val color = rgb(colors.text)
            when ((marker.level - 1) % 3) {
                0 -> drawCircle(color, radius, center)
                1 -> drawCircle(color, radius, center, style = Stroke(1.2.dp.toPx()))
                else -> drawRect(color, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
            }
        }
        ListKind.Check -> {
            val box = checkboxRect(layout, marker, fontPx, presentation)
            val corner = CornerRadius(4.dp.toPx())
            if (marker.checked) {
                drawRoundRect(rgb(colors.primary), box.topLeft, box.size, corner)
                val path = Path().apply {
                    moveTo(box.left + box.width * 0.22f, box.top + box.height * 0.52f)
                    lineTo(box.left + box.width * 0.42f, box.top + box.height * 0.72f)
                    lineTo(box.left + box.width * 0.78f, box.top + box.height * 0.3f)
                }
                drawPath(path, rgb(colors.onPrimary), style = Stroke(2.dp.toPx()))
            } else {
                val stroke = 2.dp.toPx()
                drawRoundRect(rgb(colors.muted), box.topLeft + Offset(stroke / 2, stroke / 2), Size(box.width - stroke, box.height - stroke), corner, style = Stroke(stroke))
            }
        }
    }
}

/** Draws an image in its box; while it decodes, or when it cannot be shown, its place is a tinted box. */
private fun DrawScope.drawImageBox(rect: Rect, item: ImageObject, context: DecorationContext) {
    val picture = if (item.pending.isEmpty()) context.images.picture(item.file) else null
    if (picture == null) {
        drawRoundRect(rgb(context.colors.raised), rect.topLeft, rect.size, CornerRadius(4.dp.toPx()))
        return
    }
    drawImage(
        picture,
        dstOffset = IntOffset(rect.left.toInt(), rect.top.toInt()),
        dstSize = IntSize(rect.width.toInt().coerceAtLeast(1), rect.height.toInt().coerceAtLeast(1)),
    )
}

/**
 * Returns the width of an attachment chip: its glyph, name and size inside its padding.
 *
 * @param glyph Whether the chip draws its file type's glyph; an export draws none.
 */
internal fun chipWidth(item: AttachmentObject, measurer: TextMeasurer, style: TextStyle, strings: EditorStrings, locale: Locale, density: Density, glyph: Boolean): Float {
    val font = with(density) { style.fontSize.toPx() }
    val name = measurer.measure(item.name, style).size.width
    val size = measurer.measure(formatSize(item.size, strings.sizeUnits, locale), style.copy(fontSize = style.fontSize * 0.9f)).size.width
    return font * (0.5f + (if (glyph) 1.3f else 0f) + 0.4f + 0.5f) + name + size
}

/** Draws an attachment chip: the file type's glyph, its name and its size. */
private fun DrawScope.drawChip(rect: Rect, item: AttachmentObject, context: DecorationContext, fontPx: Float) {
    val colors = context.colors
    val corner = CornerRadius(6.dp.toPx())
    drawRoundRect(rgb(colors.raised), rect.topLeft, rect.size, corner)
    drawRoundRect(rgb(colors.edge), rect.topLeft, rect.size, corner, style = Stroke(1.dp.toPx()))
    var x = rect.left + 0.5f * fontPx
    context.glyph(item.file)?.let { glyph ->
        translate(x, rect.top + (rect.height - fontPx) / 2) {
            with(glyph) { draw(Size(fontPx, fontPx), colorFilter = ColorFilter.tint(rgb(colors.text))) }
        }
        x += 1.3f * fontPx
    }
    val name = context.measurer.measure(item.name, context.textStyle)
    val top = rect.top + (rect.height - name.size.height) / 2
    drawText(name, topLeft = Offset(x, top))
    x += name.size.width + 0.4f * fontPx
    val size = context.measurer.measure(formatSize(item.size, context.strings.sizeUnits, context.locale), context.textStyle.copy(color = rgb(colors.muted), fontSize = context.textStyle.fontSize * 0.9f))
    drawText(size, topLeft = Offset(x, rect.top + (rect.height - size.size.height) / 2))
}

/** The glyph of each file extension, as the desktop chooses a chip's glyph by the file's type; any other file shows File. */
private val Extensions: Map<String, ImageVector> = buildMap {
    fun add(glyph: ImageVector, list: String) = list.split(' ').forEach { put(it, glyph) }
    add(AppIcons.FileImage, "png jpg jpeg gif webp bmp svg tif tiff heic heif avif ico")
    add(AppIcons.FileAudio, "mp3 m4a aac ogg oga opus flac wav wma mid midi amr")
    add(AppIcons.FileVideo, "mp4 m4v mkv webm avi mov wmv mpg mpeg 3gp")
    add(AppIcons.FilePdf, "pdf")
    add(AppIcons.FileZip, "zip tar gz tgz 7z rar xz txz bz2 tbz2 zst")
    add(AppIcons.FileSpreadsheet, "csv ods xls xlsx")
    add(AppIcons.FileCode, "json xml sh bash zsh c h cpp hpp cc cs java kt kts js mjs ts py rb go rs php pl lua swift sql yaml yml toml ini css scss html htm")
    add(AppIcons.Document, "txt md rtf odt doc docx")
}

/** Returns the glyph of an attachment chip for a file name. */
internal fun fileGlyph(name: String): ImageVector {
    val dot = name.lastIndexOf('.')
    return Extensions[if (dot > 0) name.substring(dot + 1).lowercase() else ""] ?: AppIcons.File
}
