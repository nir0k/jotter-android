package io.github.nir0k.jotter.feature.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.nir0k.jotter.core.format.noteLinkAddress
import io.github.nir0k.jotter.core.format.noteLinkTarget
import io.github.nir0k.jotter.core.index.NoteSummary
import io.github.nir0k.jotter.editor.CodeLanguages
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.delay
import io.github.nir0k.jotter.designsystem.components.AppTextField
import io.github.nir0k.jotter.designsystem.components.FormDialog
import io.github.nir0k.jotter.designsystem.components.SearchField
import io.github.nir0k.jotter.designsystem.components.SecondaryButton
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes
import io.github.nir0k.jotter.data.R as DataR

/** The schemes a link of a note may have. */
private val LinkSchemes = setOf("http", "https", "mailto")

/**
 * Turns a typed address into the one a link stores, as the desktop does: a bare domain gets
 * `https://`, a bare mail address `mailto:`, and only web and mail addresses and the addresses
 * of notes the note picker puts in are accepted.
 *
 * @param typed The address as typed.
 * @return The address, or null when it is not a web, mail or note address.
 */
fun normalizedLink(typed: String): String? {
    var text = typed.trim()
    if (text.isEmpty()) return null
    if (noteLinkTarget(text) != null) return text
    if ("://" !in text && !text.startsWith("mailto:")) text = (if ('@' in text && '/' !in text) "mailto:" else "https://") + text
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in LinkSchemes || (scheme != "mailto" && uri.host.isNullOrEmpty())) return null
    return uri.toASCIIString()
}

/**
 * Inserts a link or changes the one at the cursor: the text it shows and its address. Note…
 * opens the note picker: choosing a note replaces the address, and gives the link the note's
 * title when it has no text yet; typing an address forgets the note.
 *
 * @param text The selected text or the link's text.
 * @param href The link's address, empty for a new link.
 * @param onSave Receives the text and the accepted address.
 * @param notes Lists the notes the picker offers for a query.
 */
@Composable
fun LinkDialog(text: String, href: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit, notes: suspend (String) -> List<NoteSummary>) {
    var shown by rememberSaveable { mutableStateOf(text) }
    var address by rememberSaveable { mutableStateOf(href) }
    var tried by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val untitled = stringResource(DataR.string.note_untitled)
    val accepted = normalizedLink(address)
    FormDialog(
        title = stringResource(R.string.link_title),
        confirmText = stringResource(io.github.nir0k.jotter.designsystem.R.string.ds_save),
        onConfirm = {
            tried = true
            if (accepted != null) {
                onSave(shown.trim(), accepted)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
        confirmEnabled = address.isNotBlank(),
    ) {
        AppTextField(shown, { shown = it }, stringResource(R.string.link_text))
        AppTextField(
            address,
            { address = it },
            stringResource(R.string.link_address),
            error = stringResource(R.string.link_invalid).takeIf { tried && accepted == null },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        SecondaryButton(stringResource(R.string.link_note), { picking = true }, icon = AppIcons.Note)
    }
    if (picking) {
        NotePickerDialog(notes, { note ->
            address = noteLinkAddress(note.id)
            if (shown.isBlank()) shown = note.title.ifEmpty { untitled }
        }) { picking = false }
    }
}

/** How long the note picker waits after typing before it searches, as note lists do. */
private const val PickerDelayMs = 150L

/**
 * NotePickerDialog chooses a note for a link: it searches titles as the person types, in every
 * word form; with nothing typed it offers the recently updated notes. The notes of the trash and
 * the note being edited are never offered.
 *
 * @param notes Lists the notes for a query.
 * @param onSelect Receives the chosen note; the dialog closes.
 */
@Composable
fun NotePickerDialog(notes: suspend (String) -> List<NoteSummary>, onSelect: (NoteSummary) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val found by produceState<List<NoteSummary>?>(null, query) {
        if (query.isNotEmpty()) delay(PickerDelayMs)
        value = notes(query)
    }
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = Shapes.Dialog, color = scheme.background) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(stringResource(R.string.note_picker_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                SearchField(query, { query = it }, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                val list = found
                if (list != null && list.isEmpty()) {
                    Text(
                        stringResource(R.string.note_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = Dimens.ChoiceListMaxHeight)) {
                    items(list.orEmpty(), key = { it.id }) { note ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    onSelect(note)
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        ) {
                            Text(
                                note.title.ifEmpty { stringResource(DataR.string.note_untitled) },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (note.snippet.isNotEmpty()) {
                                Text(note.snippet, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chooses the language of a code block from a list with search: Plain text first, then the
 * offered languages; a language the block has from a file that the list lacks stays offered
 * while it is the block's language.
 *
 * @param current The block's language.
 * @param name Returns the name a language is shown with.
 * @param onSelect Receives the chosen language, empty for plain text.
 */
@Composable
fun LanguageDialog(current: String, name: (String) -> String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val keys = buildList {
        add("")
        addAll(CodeLanguages.map { it.key })
        if (current.isNotEmpty() && current !in this) add(current)
    }
    val shown = keys.filter { query.isBlank() || name(it).contains(query.trim(), ignoreCase = true) }
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = Shapes.Dialog, color = scheme.background) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(stringResource(R.string.code_language_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                SearchField(query, { query = it }, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                LazyColumn(Modifier.heightIn(max = Dimens.ChoiceListMaxHeight)) {
                    items(shown, key = { it }) { key ->
                        val selected = key == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = selected, role = Role.RadioButton) {
                                    onDismiss()
                                    onSelect(key)
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name(key), style = MaterialTheme.typography.bodyLarge, color = if (selected) scheme.primary else scheme.onSurface, modifier = Modifier.weight(1f))
                            if (selected) Icon(AppIcons.Check, null, tint = scheme.primary, modifier = Modifier.size(Dimens.IconSecondary))
                        }
                    }
                }
            }
        }
    }
}

/** Edits the title of a code block; an empty title removes it. */
@Composable
fun CodeTitleDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(current) }
    FormDialog(
        title = stringResource(R.string.code_title_title),
        confirmText = stringResource(io.github.nir0k.jotter.designsystem.R.string.ds_save),
        onConfirm = {
            onSave(title.trim())
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        AppTextField(title, { title = it }, stringResource(R.string.code_title_field))
    }
}
