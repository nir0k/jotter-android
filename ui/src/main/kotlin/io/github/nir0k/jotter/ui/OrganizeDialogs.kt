package io.github.nir0k.jotter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.collectionSubtree
import io.github.nir0k.jotter.core.format.normalizedTag
import io.github.nir0k.jotter.core.organize.Organizer
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import io.github.nir0k.jotter.designsystem.components.AppTextField
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ChoiceDialog
import io.github.nir0k.jotter.designsystem.components.FormDialog
import io.github.nir0k.jotter.designsystem.components.PickerField
import io.github.nir0k.jotter.designsystem.components.SwitchRow
import io.github.nir0k.jotter.core.format.tagKey
import io.github.nir0k.jotter.designsystem.components.SearchField
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes

// Dialogs that organise notes: their tags, and the forms of collections and tags.

/**
 * TagsDialog chooses tags: the library's tags with a search field that matches anywhere in a
 * name, and the typed tag offered as a new one when no tag has it. Apply hands over the ticked
 * tags.
 *
 * @param title What the tags are for, such as Tags or Add tag.
 * @param known The library's tags.
 * @param initial The tags ticked when the dialog opens.
 * @param onApply Receives the ticked tags, in the library's spelling, new ones last.
 */
@Composable
fun TagsDialog(title: String, known: List<String>, initial: List<String>, onApply: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var chosen by rememberSaveable { mutableStateOf(initial) }
    val typed = normalizedTag(query)
    val options = (known + chosen.filter { tag -> known.none { tagKey(it) == tagKey(tag) } }).filter { query.isBlank() || it.contains(query.trim().trimStart('#'), ignoreCase = true) }
    val offerNew = typed.isNotEmpty() && (known + chosen).none { tagKey(it) == tagKey(typed) }
    fun toggle(tag: String) {
        chosen = if (chosen.any { tagKey(it) == tagKey(tag) }) chosen.filterNot { tagKey(it) == tagKey(tag) } else chosen + tag
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = Shapes.Dialog, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                SearchField(query, { query = it }, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                LazyColumn(Modifier.heightIn(max = Dimens.ChoiceListMaxHeight)) {
                    if (offerNew) {
                        item(key = "#new") {
                            TagRow(AppIcons.Add, stringResource(R.string.tags_create, typed), checked = null) {
                                toggle(typed)
                                query = ""
                            }
                        }
                    }
                    items(options, key = { tagKey(it) }) { tag ->
                        TagRow(AppIcons.Tag, tag, checked = chosen.any { tagKey(it) == tagKey(tag) }) { toggle(tag) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(io.github.nir0k.jotter.designsystem.R.string.ds_cancel)) }
                    TextButton(onClick = {
                        onDismiss()
                        onApply(chosen)
                    }) { Text(stringResource(io.github.nir0k.jotter.designsystem.R.string.ds_apply)) }
                }
            }
        }
    }
}

/**
 * One row of the tags dialog: a checkbox with the tag, or the action creating a typed tag.
 *
 * @param checked Whether the tag is ticked; null for the action.
 */
@Composable
private fun TagRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, checked: Boolean?, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked == true, role = if (checked == null) Role.Button else Role.Checkbox, onValueChange = { onToggle() })
            .padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked != null) Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(Dimens.TouchTarget))
        else Spacer(Modifier.size(Dimens.TouchTarget))
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimens.IconMenu))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/**
 * Names a new collection or renames a collection or tag; the error of a failed save stays in the
 * dialog.
 *
 * @param initial The name the field starts with.
 * @param error Why the last save failed, or null.
 * @param onSave Receives the trimmed name; the caller closes the dialog once it is saved.
 */
@Composable
fun NameDialog(title: String, initial: String, confirmText: String, error: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    FormDialog(
        title = title,
        confirmText = confirmText,
        onConfirm = { onSave(name.trim()) },
        onDismiss = onDismiss,
        error = error,
        confirmEnabled = name.isNotBlank(),
    ) {
        AppTextField(name, { name = it }, stringResource(R.string.collection_name))
    }
}

/**
 * Moves a collection: the new parent, the top level or any collection outside the moved one's
 * subtree, and the position among the new siblings, as dragging a tree is unreliable on a
 * touch screen.
 *
 * @param collections The collection tree.
 * @param moving The collection moved.
 * @param onMove Receives the new parent (null for the top level) and the sibling it goes before
 *   (null for the end).
 */
@Composable
fun MoveCollectionDialog(collections: List<Collection>, moving: Collection, onMove: (String?, String?) -> Unit, onDismiss: () -> Unit) {
    val subtree = remember(collections, moving) { collectionSubtree(collections, moving.id).toSet() }
    var parent by rememberSaveable { mutableStateOf(moving.parent) }
    val siblings = collections.filter { it.parent == parent && it.id != moving.id }
    val current = collections.filter { it.parent == moving.parent }.let { list -> list.getOrNull(list.indexOfFirst { it.id == moving.id } + 1)?.id }
    var before by rememberSaveable { mutableStateOf(current) }
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }
    val top = stringResource(R.string.collection_top_level)
    val end = stringResource(R.string.collection_at_end)
    val parents = listOf(Choice<String?>(null, top)) + collectionChoices(collections).filter { it.key !in subtree }.map { Choice<String?>(it.key, it.label) }
    val positions = listOf(Choice<String?>(null, end)) + siblings.map { Choice<String?>(it.id, stringResource(R.string.collection_before, it.name)) }
    FormDialog(
        title = stringResource(R.string.collection_move_title, moving.name),
        confirmText = stringResource(R.string.collection_move),
        onConfirm = {
            onMove(parent, before)
            onDismiss()
        },
        onDismiss = onDismiss,
    ) {
        PickerField(stringResource(R.string.collection_parent), parents.firstOrNull { it.key == parent }?.label ?: top, { choosing = "parent" })
        PickerField(stringResource(R.string.collection_position), positions.firstOrNull { it.key == before }?.label ?: end, { choosing = "position" })
    }
    when (choosing) {
        "parent" -> ChoiceDialog(stringResource(R.string.collection_parent), parents, parent, {
            parent = it
            before = null
        }) { choosing = null }
        "position" -> ChoiceDialog(stringResource(R.string.collection_position), positions, before, { before = it }) { choosing = null }
    }
}

/**
 * Confirms deleting a collection with what it touches: its descendant collections go with it,
 * and its notes stay in their other collections or in All notes, unless the option sends those
 * left in no collection to the trash.
 *
 * @param counts The collection's descendants and notes, or null while they are counted.
 * @param onDelete Receives whether notes left in no collection go to the trash.
 */
@Composable
fun DeleteCollectionDialog(name: String, counts: Organizer.CollectionDeletion?, onDelete: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var trashOrphans by rememberSaveable { mutableStateOf(false) }
    FormDialog(
        title = stringResource(R.string.collection_delete_title, name),
        confirmText = stringResource(R.string.delete),
        onConfirm = {
            onDelete(trashOrphans)
            onDismiss()
        },
        onDismiss = onDismiss,
        confirmEnabled = counts != null,
        destructive = true,
    ) {
        counts?.let {
            Text(
                pluralStringResource(R.plurals.collection_delete_collections, it.collections, it.collections) + " " +
                    pluralStringResource(R.plurals.collection_delete_notes, it.notes, it.notes),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        SwitchRow(stringResource(R.string.collection_delete_orphans), trashOrphans, { trashOrphans = it })
    }
}
