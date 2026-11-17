package io.github.nir0k.jotter.data.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import io.github.nir0k.jotter.core.format.exportPartStarts
import io.github.nir0k.jotter.editor.DrawnNote
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The width of a JPG's text column, in CSS pixels. */
const val JpgColumn = 760

/** The scale a JPG is drawn at. */
const val JpgScale = 2

/** The margin around a JPG's text column, in CSS pixels. */
private const val JpgMargin = 32

/** The tallest JPG part in pixels. */
private const val JpgMaxHeight = 16_000

/** The quality of a JPG. */
private const val JpgQuality = 90

/** A PDF's page sizes in points. */
enum class PageSize(val width: Int, val height: Int) { A4(595, 842), Letter(612, 792) }

/** The margins of a PDF page: 20 mm in points. */
const val PdfMargin = 56.69f

/** The points of a CSS pixel, as the desktop draws a PDF at 96 pixels per inch. */
const val PdfScale = 0.75f

/** The regions whose paper is Letter; the others use A4. */
private val LetterRegions = setOf("US", "CA", "MX", "GT", "BZ", "SV", "HN", "NI", "CR", "PA", "PR", "DO", "CL", "CO", "VE", "PH")

/** Returns the page size a PDF starts with in a region. */
fun defaultPageSize(region: String): PageSize = if (region.uppercase() in LetterRegions) PageSize.Letter else PageSize.A4

/**
 * Draws a note as JPG images on a 760-pixel column at twice its size, split into parts no taller
 * than 16,000 pixels at block boundaries.
 *
 * @param note The note drawn at [JpgScale] on a column of [JpgColumn] CSS pixels.
 * @param background The colour of the margin around the text.
 * @return The images in order.
 */
suspend fun jpgParts(note: DrawnNote, background: Int): List<ByteArray> {
    val margin = JpgMargin * JpgScale
    val starts = exportPartStarts(note.blocks, (JpgMaxHeight - 2 * margin).toDouble())
    return starts.mapIndexed { index, top ->
        currentCoroutineContext().ensureActive()
        val bottom = maxOf(starts.getOrNull(index + 1) ?: note.height.toDouble(), top + 1)
        val height = min(ceil(bottom - top).toInt() + 2 * margin, JpgMaxHeight)
        val bitmap = createBitmap(ceil(note.width).toInt() + 2 * margin, height)
        try {
            Canvas(bitmap).apply {
                drawColor(background)
                translate(margin.toFloat(), margin.toFloat())
                note.drawRange(this, top.toFloat(), bottom.toFloat())
            }
            withContext(Dispatchers.Default) {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JpgQuality, it) }.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * Draws a note as a PDF of pages of a size with margins of 20 mm; a line never crosses a page.
 *
 * @param note The note drawn at [PdfScale] on the page's column.
 * @param background The colour of the pages.
 */
suspend fun pdfDocument(note: DrawnNote, page: PageSize, background: Int): ByteArray {
    val document = PdfDocument()
    try {
        val starts = note.pageStarts(page.height - 2 * PdfMargin)
        starts.forEachIndexed { index, top ->
            currentCoroutineContext().ensureActive()
            val bottom = starts.getOrNull(index + 1) ?: note.height
            val pdfPage = document.startPage(PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create())
            pdfPage.canvas.apply {
                drawColor(background)
                translate(PdfMargin, PdfMargin)
                note.drawRange(this, top, bottom)
            }
            document.finishPage(pdfPage)
        }
        return withContext(Dispatchers.IO) { ByteArrayOutputStream().also(document::writeTo).toByteArray() }
    } finally {
        document.close()
    }
}
