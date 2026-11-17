package io.github.nir0k.jotter.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.editor.EditorStrings
import io.github.nir0k.jotter.data.export.ExportFormat
import io.github.nir0k.jotter.data.export.PageSize
import io.github.nir0k.jotter.data.export.defaultPageSize
import io.github.nir0k.jotter.data.export.ExportState
import io.github.nir0k.jotter.data.export.ExportTexts
import io.github.nir0k.jotter.data.library.LibrarySession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.FormDialog
import io.github.nir0k.jotter.designsystem.components.SecondaryButton
import io.github.nir0k.jotter.designsystem.components.SegmentedChoice
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.data.R as DataR

/** Offers a file for saving under its own media type, chosen when the picker opens from its name. */
private class CreateExportDocument : ActivityResultContracts.CreateDocument("*/*") {
    /** Builds the picker's request for a file name. */
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).setType(mediaType(File(input)))
}

/** What the export dialog shows besides the choice of format. */
sealed interface ExportDialogStep {
    /** The format is being chosen. */
    data object Choosing : ExportDialogStep

    /**
     * The export runs.
     *
     * @param done The notes finished; before the first, how long it takes is unknown.
     * @param total The notes of the export.
     */
    data class Running(val done: Int, val total: Int) : ExportDialogStep

    /**
     * The export is ready to save or share.
     *
     * @param name The file's name.
     */
    data class Ready(val name: String) : ExportDialogStep
}

/**
 * The export dialog's form, a form dialog of the design system: the format, then the progress of
 * the export, then the finished file. A PDF chooses its page size.
 *
 * @param count The notes to export.
 * @param step What the dialog shows.
 * @param format The chosen format.
 * @param page The chosen page size of a PDF.
 * @param error A failure of the last attempt, with the choices kept.
 * @param onFormat Chooses a format.
 * @param onPage Chooses a page size.
 * @param onConfirm Starts the export, or saves the finished file.
 * @param onShare Offers the finished file for sharing.
 * @param onDismiss Cancels a running export, which keeps the dialog open; closes it otherwise.
 */
@Composable
fun ExportForm(
    count: Int,
    step: ExportDialogStep,
    format: ExportFormat,
    page: PageSize,
    error: String?,
    onFormat: (ExportFormat) -> Unit,
    onPage: (PageSize) -> Unit,
    onConfirm: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    FormDialog(
        title = pluralStringResource(R.plurals.export_title, count, count),
        confirmText = stringResource(if (step is ExportDialogStep.Ready) R.string.export_save else R.string.export_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmEnabled = step !is ExportDialogStep.Running,
        error = error,
    ) {
        when (step) {
            is ExportDialogStep.Ready -> {
                Text(stringResource(R.string.export_ready, step.name), style = MaterialTheme.typography.bodyMedium)
                SecondaryButton(stringResource(R.string.export_share), onShare, icon = AppIcons.Share)
            }
            else -> {
                val running = step is ExportDialogStep.Running
                SegmentedChoice(
                    stringResource(R.string.export_format),
                    listOfNotNull(
                        Choice(ExportFormat.Pdf, stringResource(R.string.export_pdf)),
                        Choice(ExportFormat.Html, stringResource(R.string.export_html)),
                        Choice(ExportFormat.Jpg, stringResource(R.string.export_jpg)),
                    ),
                    format,
                    onFormat,
                    enabled = !running,
                )
                if (format == ExportFormat.Pdf) {
                    SegmentedChoice(
                        stringResource(R.string.export_page_size),
                        listOf(Choice(PageSize.A4, stringResource(R.string.export_page_a4)), Choice(PageSize.Letter, stringResource(R.string.export_page_letter))),
                        page,
                        onPage,
                        enabled = !running,
                    )
                }
                if (step is ExportDialogStep.Running) {
                    if (step.done == 0) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { step.done.toFloat() / step.total }, modifier = Modifier.fillMaxWidth())
                    }
                    if (step.total > 1) Text(stringResource(R.string.export_progress, step.done, step.total), style = MaterialTheme.typography.bodySmall)
                } else if (count > 1) {
                    Text(stringResource(R.string.export_several_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Exports notes: chooses the format, runs the export with its progress and Cancel, then offers the
 * file for saving through the system's document picker or for sharing.
 *
 * @param session The library session, whose exporter outlives this dialog's screen.
 * @param library The library holding the notes.
 * @param ids The notes, in their list order.
 * @param onMessage Shows a confirmation once this dialog has closed.
 * @param onClose Closes the dialog.
 */
@Composable
fun ExportDialog(session: LibrarySession, library: Library, ids: List<String>, onMessage: (String) -> Unit, onClose: () -> Unit) {
    val exporter = session.exporter
    val state by exporter.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    var format by rememberSaveable { mutableStateOf(if (ids.size == 1) ExportFormat.Pdf else ExportFormat.Html) }
    var page by rememberSaveable { mutableStateOf(defaultPageSize(locale.country)) }
    var failure by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current

    LaunchedEffect(state) { if (state is ExportState.Failed) failure = R.string.export_failed }
    val close = {
        exporter.clear()
        onClose()
    }
    val save = rememberLauncherForActivityResult(remember { CreateExportDocument() }) { target ->
        val file = (exporter.state.value as? ExportState.Finished)?.file
        if (target != null && file != null) {
            scope.launch {
                if (withContext(Dispatchers.IO) { saveFileCopy(context, file, target) }) {
                    onMessage(resources.getString(R.string.export_saved, file.name))
                    close()
                } else {
                    failure = R.string.export_save_failed
                }
            }
        }
    }
    val finished = (state as? ExportState.Finished)?.file
    val running = state as? ExportState.Running
    ExportForm(
        count = ids.size,
        step = when {
            finished != null -> ExportDialogStep.Ready(finished.name)
            running != null -> ExportDialogStep.Running(running.done, running.total)
            else -> ExportDialogStep.Choosing
        },
        format = format,
        page = page,
        error = failure?.let { stringResource(it) },
        onFormat = { format = it },
        onPage = { page = it },
        onConfirm = {
            failure = null
            val texts = ExportTexts(
                untitled = resources.getString(DataR.string.note_untitled),
                archiveName = resources.getString(R.string.export_archive_name),
                subtitle = { time -> resources.getString(R.string.export_updated, dateTimeText(time, locale)) },
                strings = EditorStrings.from(context),
                locale = locale,
            )
            if (finished != null) save.launch(finished.name) else exporter.start(library, ids, format, texts, page)
        },
        onShare = { finished?.let { if (!shareFile(context, it)) failure = R.string.export_no_application } },
        onDismiss = {
            if (running != null) exporter.cancel() else close()
        },
    )
}
