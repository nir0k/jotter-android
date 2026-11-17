package io.github.nir0k.jotter.feature.notes

import androidx.annotation.PluralsRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.nir0k.jotter.core.format.FavoriteRef
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.organize.Organizer
import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.ui.ExportDialog
import io.github.nir0k.jotter.ui.TagsDialog
import kotlinx.coroutines.launch
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ConfirmDialog
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.components.MultiChoiceDialog
import io.github.nir0k.jotter.designsystem.components.SelectionBar
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.ui.Refresh
import io.github.nir0k.jotter.ui.collectionChoices
import io.github.nir0k.jotter.ui.R as UiR

/** Which actions a list's selection offers. */
enum class SelectionKind {
    /** Notes neither archived nor trashed: collections, tags, archive and trash. */
    Active,
    Archive,
    Trash,
}

/** The dialogs of the selection bar. */
private enum class SelectionDialog { Collections, Tags, Export, Delete }

// The selection survives rotation as a list of identifiers.
private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * What the selection bar needs from the library.
 *
 * @param library The open library, which exports read.
 * @param favorites The library's favourites, for the bar's favourite toggle.
 * @param collectionChoices The collections notes can join, with their paths.
 * @param knownTags The library's tags, for Add tag.
 */
class SelectionLibrary(
    val session: LibrarySession,
    val library: Library,
    val favorites: List<FavoriteRef>,
    val collectionChoices: List<Choice<String>>,
    val knownTags: List<String>,
)

/**
 * Remembers the selection of one note list and builds its bar: Clear, the count, Select all and the
 * favourite toggle, then the list's actions. Active notes can be added to collections, removed from the
 * collection being viewed, given tags, exported, archived and trashed; archived notes unarchived,
 * exported or trashed; trashed notes restored or deleted permanently after a confirmation. Archive,
 * trash and restore offer Undo; selected notes that leave the list leave the selection.
 *
 * @param key Tells the lists apart, so each keeps its own selection.
 * @param load The list's notes.
 * @param collection The collection being viewed, which Remove from this collection leaves.
 * @param snackbar The list's transient messages.
 * @return The selection, or null where notes cannot be changed, as in a read-only library.
 */
@Composable
fun rememberNoteSelection(
    key: String,
    kind: SelectionKind,
    load: NotesLoad,
    library: SelectionLibrary?,
    snackbar: SnackbarHostState,
    collection: String? = null,
): NoteSelection? {
    var ids by rememberSaveable(key, stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    var dialog by rememberSaveable(key) { mutableStateOf<SelectionDialog?>(null) }
    val listed = (load as? NotesLoad.Loaded)?.notes?.map { it.id }.orEmpty()
    LaunchedEffect(listed) { if (load is NotesLoad.Loaded) ids = ids.intersect(listed.toSet()) }
    if (library == null) return null
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val session = library.session

    // Runs an action on the selected notes and ends the selection; one with a message offers Undo.
    fun act(@PluralsRes message: Int?, change: (Organizer, List<String>) -> List<NoteMeta>) {
        val chosen = ids.toList()
        ids = emptySet()
        scope.launch {
            val before = session.organize { change(it, chosen) }
            if (before == null) {
                snackbar.showSnackbar(resources.getString(UiR.string.organize_failed))
                return@launch
            }
            if (message == null || before.isEmpty()) return@launch
            val text = resources.getQuantityString(message, before.size, before.size)
            if (snackbar.showSnackbar(text, actionLabel = resources.getString(UiR.string.undo)) == SnackbarResult.ActionPerformed) {
                session.organize { it.restore(before) }
            }
        }
    }

    val refs = ids.map { FavoriteRef(FavoriteRef.Kind.Note, it) }
    val allFavorite = refs.isNotEmpty() && refs.all { it in library.favorites }
    val menu = when (kind) {
        SelectionKind.Active -> listOfNotNull(
            MenuAction(stringResource(R.string.selection_add_collection), AppIcons.Folder) { dialog = SelectionDialog.Collections },
            collection?.let { viewed ->
                MenuAction(stringResource(R.string.selection_remove_collection), AppIcons.Close) {
                    act(null) { organizer, notes -> organizer.changeCollections(notes, emptyList(), listOf(viewed)) }
                }
            },
            MenuAction(stringResource(R.string.selection_add_tag), AppIcons.Tag) { dialog = SelectionDialog.Tags },
            MenuAction(stringResource(UiR.string.note_export), AppIcons.Download) { dialog = SelectionDialog.Export },
            MenuAction(stringResource(UiR.string.note_archive), AppIcons.Archive) { act(R.plurals.selection_archived) { o, notes -> o.setArchived(notes, true) } },
            MenuAction(stringResource(UiR.string.note_trash), AppIcons.Delete, destructive = true) {
                act(R.plurals.selection_trashed) { o, notes -> o.setTrashed(notes, true) }
            },
        )
        SelectionKind.Archive -> listOf(
            MenuAction(stringResource(UiR.string.note_unarchive), AppIcons.Archive) { act(R.plurals.selection_unarchived) { o, notes -> o.setArchived(notes, false) } },
            MenuAction(stringResource(UiR.string.note_export), AppIcons.Download) { dialog = SelectionDialog.Export },
            MenuAction(stringResource(UiR.string.note_trash), AppIcons.Delete, destructive = true) {
                act(R.plurals.selection_trashed) { o, notes -> o.setTrashed(notes, true) }
            },
        )
        SelectionKind.Trash -> listOf(
            MenuAction(stringResource(UiR.string.note_restore), AppIcons.Refresh) { act(R.plurals.selection_restored) { o, notes -> o.setTrashed(notes, false) } },
            MenuAction(stringResource(UiR.string.note_delete), AppIcons.Delete, destructive = true) { dialog = SelectionDialog.Delete },
        )
    }
    return NoteSelection(ids, { ids = it }) {
        // The bar sits at the bottom of the screen, so it keeps clear of the system navigation bar.
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest).navigationBarsPadding()) {
            SelectionBar(
                count = ids.size,
                allFavorite = allFavorite,
                allPrivate = false,
                onClear = { ids = emptySet() },
                onSelectAll = { ids = listed.toSet() },
                onFavorite = { favorite ->
                    scope.launch { if (session.organize { it.setFavorite(refs, favorite) } == null) snackbar.showSnackbar(resources.getString(UiR.string.organize_failed)) }
                },
                onPrivate = null,
                menu = menu,
            )
        }
        when (dialog) {
            SelectionDialog.Collections -> MultiChoiceDialog(
                stringResource(R.string.selection_add_collection),
                library.collectionChoices,
                emptySet(),
                { chosen -> act(null) { o, notes -> o.changeCollections(notes, library.collectionChoices.map { it.key }.filter { it in chosen }) } },
                onDismiss = { dialog = null },
            )
            SelectionDialog.Tags -> TagsDialog(stringResource(R.string.selection_add_tag), library.knownTags, emptyList(), { tags ->
                act(null) { o, notes -> o.changeTags(notes, tags) }
            }) { dialog = null }
            SelectionDialog.Export -> ExportDialog(session, library.library, ids.toList(), { scope.launch { snackbar.showSnackbar(it) } }) { dialog = null }
            SelectionDialog.Delete -> ConfirmDialog(
                title = pluralStringResource(UiR.plurals.delete_notes_title, ids.size, ids.size),
                text = stringResource(UiR.string.delete_notes_text),
                confirmText = stringResource(UiR.string.delete),
                onConfirm = {
                    val chosen = ids.toList()
                    ids = emptySet()
                    scope.launch { if (session.organize { it.deleteNotes(chosen) } == null) snackbar.showSnackbar(resources.getString(UiR.string.organize_failed)) }
                },
                onDismiss = { dialog = null },
                destructive = true,
            )
            null -> Unit
        }
    }
}
