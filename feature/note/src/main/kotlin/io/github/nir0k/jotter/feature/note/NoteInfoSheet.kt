package io.github.nir0k.jotter.feature.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import io.github.nir0k.jotter.core.format.GeoLocation
import io.github.nir0k.jotter.core.index.NoteSummary
import io.github.nir0k.jotter.ui.dateTimeText
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import androidx.compose.ui.platform.LocalConfiguration
import io.github.nir0k.jotter.designsystem.components.CoordinateActions
import io.github.nir0k.jotter.designsystem.components.InlineAction
import io.github.nir0k.jotter.designsystem.components.NavigationRow
import io.github.nir0k.jotter.designsystem.components.SettingsSection
import io.github.nir0k.jotter.designsystem.components.ValueRow
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.data.R as DataR

/**
 * What the information sheet shows of a note.
 *
 * @param collections The names of the note's collections.
 * @param location The note's geotag, or null.
 * @param locationUnavailable Whether the position could not be found for the note since the
 *   application started.
 * @param counts The words, characters and attached files of the body, or null while the editor
 *   counts them.
 * @param backlinks The notes that link to this one, outside the trash, or null while they are read.
 */
data class NoteInfo(
    val created: Instant,
    val updated: Instant,
    val collections: List<String>,
    val tags: List<String>,
    val location: GeoLocation?,
    val locationUnavailable: Boolean,
    val counts: Triple<Int, Int, Int>?,
    val backlinks: List<NoteSummary>?,
)

/** Formats a coordinate in decimal degrees with a point, as maps take them. */
private fun coordinate(value: Double): String = String.format(Locale.US, "%.5f", value).trimEnd('0').trimEnd('.')

/**
 * NoteInfoSheet is the note's information as a bottom sheet: the dates, the collections and
 * tags, the counts of the body, the geotag with the map, Copy and Remove, and the backlinks,
 * each opening its note.
 *
 * @param onRemoveLocation Removes the geotag; null for a note that cannot be edited.
 * @param onOpenNote Opens a backlink's note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteInfoSheet(info: NoteInfo, title: String, onRemoveLocation: (() -> Unit)?, onOpenNote: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        NoteInfoContent(info, title, onRemoveLocation, onOpenNote)
    }
}

/** The sections of the information sheet; drawn without the sheet in screenshots. */
@Composable
fun NoteInfoContent(info: NoteInfo, title: String, onRemoveLocation: (() -> Unit)?, onOpenNote: (String) -> Unit) {
    val none = stringResource(R.string.info_none)
    val numbers = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val counting = stringResource(R.string.info_counting)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.ScreenPadding).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap),
    ) {
        Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleLarge)
        SettingsSection(stringResource(R.string.info_note)) {
            ValueRow(stringResource(R.string.info_created), dateTimeText(info.created))
            ValueRow(stringResource(R.string.info_updated), dateTimeText(info.updated))
            ValueRow(stringResource(R.string.info_collections), info.collections.joinToString(", ").ifEmpty { none }, muted = info.collections.isEmpty())
            ValueRow(stringResource(R.string.info_tags), info.tags.joinToString(" ") { "#$it" }.ifEmpty { none }, muted = info.tags.isEmpty())
        }
        SettingsSection(stringResource(R.string.info_text)) {
            val (words, characters, files) = info.counts ?: Triple(null, null, null)
            ValueRow(stringResource(R.string.info_words), words?.let(numbers::format) ?: counting, muted = words == null)
            ValueRow(stringResource(R.string.info_characters), characters?.let(numbers::format) ?: counting, muted = characters == null)
            ValueRow(stringResource(R.string.info_attachments), files?.let(numbers::format) ?: counting, muted = files == null)
        }
        SettingsSection(stringResource(R.string.info_location)) {
            val location = info.location
            if (location == null) {
                Text(
                    stringResource(if (info.locationUnavailable) R.string.info_location_unavailable else R.string.info_location_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${coordinate(location.latitude)}, ${coordinate(location.longitude)}", style = MaterialTheme.typography.bodyLarge)
                        location.accuracy?.let {
                            Text(
                                stringResource(R.string.info_location_accuracy, numbers.format(Math.round(it))),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    CoordinateActions(location.latitude, location.longitude, title)
                }
                onRemoveLocation?.let { InlineAction(AppIcons.Delete, stringResource(R.string.info_location_remove), it) }
            }
        }
        SettingsSection(stringResource(R.string.info_backlinks)) {
            val backlinks = info.backlinks
            if (backlinks.isNullOrEmpty()) {
                Text(
                    if (backlinks == null) counting else stringResource(R.string.info_backlinks_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            backlinks?.forEach { note ->
                NavigationRow(note.title.ifEmpty { stringResource(DataR.string.note_untitled) }, { onOpenNote(note.id) }, icon = AppIcons.Link)
            }
        }
    }
}
