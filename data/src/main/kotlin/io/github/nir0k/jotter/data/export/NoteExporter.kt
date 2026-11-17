package io.github.nir0k.jotter.data.export

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.nir0k.jotter.core.format.ExportFile
import io.github.nir0k.jotter.core.format.ExportNames
import io.github.nir0k.jotter.core.format.ExportPackage
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.exportBaseName
import io.github.nir0k.jotter.core.format.exportHtml
import androidx.compose.ui.unit.Density
import io.github.nir0k.jotter.editor.DrawnNote
import io.github.nir0k.jotter.editor.EditorStrings
import io.github.nir0k.jotter.editor.NoteAssets
import io.github.nir0k.jotter.editor.NoteColors
import io.github.nir0k.jotter.editor.drawNote
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.designsystem.theme.WarmLightColors

/** The cache folder of exports, which the application's `FileProvider` offers for sharing. */
private const val ExportFolder = "export"

/** The formats an export can be written in. */
enum class ExportFormat { Pdf, Html, Jpg }

/** How far an export of files is. */
sealed interface ExportState {
    /** No export is running or waiting. */
    data object Idle : ExportState

    /**
     * Notes are being exported.
     *
     * @param done The notes finished.
     * @param total The notes of the export.
     */
    data class Running(val done: Int, val total: Int) : ExportState

    /**
     * The export is ready to be saved or shared.
     *
     * @param file The temporary file, named as it is offered.
     */
    data class Finished(val file: File) : ExportState

    /** The last attempt failed; the choices can be tried again. */
    data object Failed : ExportState
}

/**
 * What an export writes in the interface language, which screens know and the application's context
 * may not.
 *
 * @param untitled The name of a note without a title.
 * @param archiveName The name of the archive of several notes, without its extension.
 * @param subtitle Returns the line under a note's title, from its updated time.
 * @param strings The editor's texts.
 * @param locale The interface language.
 */
class ExportTexts(
    val untitled: String,
    val archiveName: String,
    val subtitle: (Instant) -> String,
    val strings: EditorStrings,
    val locale: Locale,
)

/** The light Warm palette exports are drawn in, the palette of the note files' style sheet. */
private val ExportColors: NoteColors = with(WarmLightColors) {
    fun rgb(color: Color) = color.toArgb()
    NoteColors(
        background = rgb(surface),
        text = rgb(onSurface),
        muted = rgb(onSurfaceVariant),
        link = rgb(primary),
        raised = rgb(surfaceContainerHighest),
        outline = rgb(outline),
        edge = rgb(outlineVariant),
        primary = rgb(primary),
        onPrimary = rgb(onPrimary),
        dark = false,
        codeTheme = "breeze-light",
    )
}

/**
 * NoteExporter runs exports in the application's scope and leaves one temporary file, so an export
 * survives its screen being recreated. PDF and JPG are drawn by the editor's own drawing of the
 * note.
 *
 * @param context The application's context, for the off-screen pages and the cache folder.
 * @param scope The application's scope.
 */
class NoteExporter(private val context: Context, private val scope: CoroutineScope) {
    private val folder = File(context.cacheDir, ExportFolder)
    private val progress = MutableStateFlow<ExportState>(ExportState.Idle)
    private var running: Job? = null

    /** How far the current export is. */
    val state: StateFlow<ExportState> = progress.asStateFlow()

    /**
     * Starts exporting notes to a file, replacing a finished export.
     *
     * @param library The library holding the notes.
     * @param ids The notes, in their list order.
     * @param format The format.
     * @param texts The export's texts.
     * @param page The page size of a PDF.
     */
    fun start(library: Library, ids: List<String>, format: ExportFormat, texts: ExportTexts, page: PageSize) {
        if (progress.value is ExportState.Running) return
        progress.value = ExportState.Running(0, ids.size)
        running = scope.launch(Dispatchers.Main) {
            val directory = File(folder, System.nanoTime().toString())
            try {
                progress.value = ExportState.Finished(writeExport(library, ids, format, texts, page, directory))
            } catch (e: CancellationException) {
                progress.value = ExportState.Idle
                directory.deleteRecursively()
                throw e
            } catch (_: IOException) {
                fail(directory)
            } catch (_: FormatException) {
                fail(directory)
            } catch (_: OutOfMemoryError) {
                // A note with many large images does not fit in memory as one document or image.
                fail(directory)
            }
        }
    }

    /** Reports a failed export and removes what it wrote. */
    private fun fail(directory: File) {
        directory.deleteRecursively()
        progress.value = ExportState.Failed
    }

    /** Writes the export into a new folder and returns the finished file. */
    private suspend fun writeExport(library: Library, ids: List<String>, format: ExportFormat, texts: ExportTexts, page: PageSize, directory: File): File {
        val notes = withContext(Dispatchers.IO) { ids.map { titled(library.readNote(it), texts) } }
        // Names first: an HTML note links to the files of the other notes of the export.
        val names = ExportNames()
        val bases = notes.associate { it.meta.id to names.take(exportBaseName(it.meta.title, texts.untitled)) }
        val work = File(directory, "export.tmp")
        val pack = withContext(Dispatchers.IO) {
            directory.mkdirs() || throw IOException("No folder for the export")
            ExportPackage(work.toPath(), notes.size > 1)
        }
        try {
            notes.forEachIndexed { index, note ->
                val base = bases.getValue(note.meta.id)
                val files = when (format) {
                    ExportFormat.Jpg -> jpgFiles(library, note, base, texts)
                    ExportFormat.Pdf -> listOf(ExportFile("$base.pdf", pdfDocument(drawn(library, note, texts, PdfScale, page.width - 2 * PdfMargin), page, ExportColors.background)))
                    ExportFormat.Html -> withContext(Dispatchers.IO) { exportHtml(library, note, base, bases, texts.subtitle(note.meta.updated)) }
                }
                withContext(Dispatchers.IO) { pack.add(files) }
                progress.value = ExportState.Running(index + 1, notes.size)
            }
            return withContext(Dispatchers.IO) {
                val name = pack.finish(if (notes.size > 1) texts.archiveName else bases.getValue(notes.single().meta.id))
                File(directory, name).also { if (!work.renameTo(it)) throw IOException("The export could not be named") }
            }
        } finally {
            pack.abandon()
        }
    }

    /** Draws a note as its JPG files: `<base>.jpg`, or numbered parts when it is tall. */
    private suspend fun jpgFiles(library: Library, note: Note, base: String, texts: ExportTexts): List<ExportFile> {
        val parts = jpgParts(drawn(library, note, texts, JpgScale.toFloat(), (JpgColumn * JpgScale).toFloat()), ExportColors.background)
        return parts.mapIndexed { index, data -> ExportFile(if (parts.size == 1) "$base.jpg" else "$base-${index + 1}.jpg", data) }
    }

    /**
     * Lays out a note for drawing, in the light Warm palette under its title and updated date.
     *
     * @param scale The drawing's pixels per CSS pixel.
     * @param column The text column's width in the drawing's pixels.
     */
    private suspend fun drawn(library: Library, note: Note, texts: ExportTexts, scale: Float, column: Float): DrawnNote = drawNote(
        context, note.meta.id, note.body, note.meta.title, texts.subtitle(note.meta.updated), ExportColors, texts.strings, texts.locale,
        NoteAssets(library.root.toFile(), note.meta.id), Density(scale, 1f), column,
    )

    /** Returns a note with a title to show. */
    private fun titled(note: Note, texts: ExportTexts): Note =
        if (note.meta.title.isBlank()) note.copy(meta = note.meta.copy(title = texts.untitled)) else note

    /** Stops a running export, whose state returns to [ExportState.Idle]. */
    fun cancel() {
        running?.cancel()
    }

    /** Forgets a finished or failed export and removes its file. */
    fun clear() {
        if (progress.value is ExportState.Running) return
        progress.value = ExportState.Idle
        scope.launch(Dispatchers.IO) { removeFiles() }
    }

    /** Removes every export's temporary files; one the system stopped half way is left otherwise. */
    fun removeFiles() {
        folder.deleteRecursively()
    }
}
