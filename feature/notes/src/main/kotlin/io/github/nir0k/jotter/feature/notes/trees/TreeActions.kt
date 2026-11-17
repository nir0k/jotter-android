package io.github.nir0k.jotter.feature.notes.trees

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.FavoriteRef
import io.github.nir0k.jotter.core.format.normalizedTag
import io.github.nir0k.jotter.core.format.tagKey
import io.github.nir0k.jotter.core.organize.Organizer
import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.data.library.LibraryState
import io.github.nir0k.jotter.ui.DeleteCollectionDialog
import io.github.nir0k.jotter.ui.MoveCollectionDialog
import io.github.nir0k.jotter.ui.NameDialog
import io.github.nir0k.jotter.ui.theme.itemTextColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.designsystem.components.ColourPickerDialog
import io.github.nir0k.jotter.designsystem.components.ConfirmDialog
import io.github.nir0k.jotter.designsystem.components.IconPickerDialog
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.feature.notes.R
import io.github.nir0k.jotter.ui.R as UiR

/**
 * What the home screen offers to change in the collections and tags.
 *
 * @param collectionMenu Returns a collection's ⋮ entries.
 * @param tagMenu Returns a tag path's ⋮ entries.
 * @param onNewCollection Creates a top-level collection, from the + of Collections.
 */
class TreeActions(
    val collectionMenu: @Composable (Collection) -> List<MenuAction>,
    val tagMenu: @Composable (String) -> List<MenuAction>,
    val onNewCollection: () -> Unit,
)

/** A dialog of the trees: its kind and the collection identifier or tag path it is for. */
private enum class TreeDialog { NewCollection, NewChild, Rename, Icon, Color, Move, Delete, RenameTag, TagColor, DeleteTag }

/**
 * Remembers the changes of the collection tree and the tags with their dialogs: a collection's New
 * subcollection, Rename, Icon, Colour, Move, Favourite, New rule and Delete; a tag's Rename, Colour and
 * Delete. Names are saved through form dialogs that keep their error; icons, colours and favourites apply
 * at once; deletions are confirmed with their counts.
 *
 * @param open The open library.
 * @param collections The collection tree's entries.
 * @param snackbar The home screen's transient messages.
 * @param onNewRule Opens the form of a new rule filing into the collection.
 * @return The actions, or null in a read-only library.
 */
@Composable
fun rememberTreeActions(
    open: LibraryState.Open,
    session: LibrarySession,
    collections: List<Collection>,
    snackbar: SnackbarHostState,
    onNewRule: (String) -> Unit,
): TreeActions? {
    var dialog by rememberSaveable { mutableStateOf<Pair<TreeDialog, String>?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    if (open.library.isReadOnly) return null
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val failed = stringResource(UiR.string.organize_failed)
    val info = open.library.info
    val show: (TreeDialog, String) -> Unit = { kind, arg ->
        error = null
        dialog = kind to arg
    }
    val close = { dialog = null }

    // Applies a change at once; a failure is a transient message.
    fun apply(change: (Organizer) -> Unit) {
        scope.launch { if (session.organize(change) == null) snackbar.showSnackbar(failed) }
    }

    // Saves a form; the dialog closes once saved and keeps the error otherwise.
    fun save(change: (Organizer) -> Unit) {
        scope.launch { if (session.organize(change) == null) error = failed else dialog = null }
    }

    val collectionMenu: @Composable (Collection) -> List<MenuAction> = { collection ->
        val ref = FavoriteRef(FavoriteRef.Kind.Collection, collection.id)
        val favorite = ref in info.favorites
        listOf(
            MenuAction(stringResource(R.string.collection_new_child), AppIcons.Add) { show(TreeDialog.NewChild, collection.id) },
            MenuAction(stringResource(R.string.collection_rename), AppIcons.Edit) { show(TreeDialog.Rename, collection.id) },
            MenuAction(stringResource(UiR.string.item_icon), AppIcons.Image) { show(TreeDialog.Icon, collection.id) },
            MenuAction(stringResource(UiR.string.item_color), AppIcons.Palette) { show(TreeDialog.Color, collection.id) },
            MenuAction(stringResource(UiR.string.collection_move), AppIcons.Folder) { show(TreeDialog.Move, collection.id) },
            MenuAction(stringResource(if (favorite) UiR.string.note_unfavorite else UiR.string.note_favorite), if (favorite) AppIcons.FavoriteFilled else AppIcons.Favorite) {
                apply { it.setFavorite(listOf(ref), !favorite) }
            },
            MenuAction(stringResource(R.string.collection_new_rule), AppIcons.Rules) { onNewRule(collection.id) },
            MenuAction(stringResource(UiR.string.delete), AppIcons.Delete, destructive = true) { show(TreeDialog.Delete, collection.id) },
        )
    }
    val tagMenu: @Composable (String) -> List<MenuAction> = { path ->
        listOf(
            MenuAction(stringResource(R.string.collection_rename), AppIcons.Edit) { show(TreeDialog.RenameTag, path) },
            MenuAction(stringResource(UiR.string.item_color), AppIcons.Palette) { show(TreeDialog.TagColor, path) },
            MenuAction(stringResource(UiR.string.delete), AppIcons.Delete, destructive = true) { show(TreeDialog.DeleteTag, path) },
        )
    }

    dialog?.let { (kind, arg) ->
        val collection = collections.firstOrNull { it.id == arg }
        when (kind) {
            TreeDialog.NewCollection, TreeDialog.NewChild -> NameDialog(
                stringResource(if (kind == TreeDialog.NewChild) R.string.collection_new_child else R.string.collection_new),
                "",
                stringResource(R.string.collection_create),
                error,
                { name -> save { it.createCollection(name, arg.ifEmpty { null }) } },
                close,
            )
            TreeDialog.Rename -> collection?.let { shown ->
                NameDialog(stringResource(R.string.collection_rename), shown.name, stringResource(io.github.nir0k.jotter.designsystem.R.string.ds_save), error, { name ->
                    save { it.updateCollection(shown.id) { c -> c.copy(name = name) } }
                }, close)
            }
            TreeDialog.Icon -> collection?.let { shown ->
                IconPickerDialog(
                    stringResource(UiR.string.item_icon),
                    shown.icon,
                    { icon -> apply { it.updateCollection(shown.id) { c -> c.copy(icon = icon ?: "Folder") } } },
                    close,
                    allowNone = false,
                    tint = itemTextColor(shown.color),
                )
            }
            TreeDialog.Color -> collection?.let { shown ->
                ColourPickerDialog(stringResource(UiR.string.item_color), shown.color, { color -> apply { it.updateCollection(shown.id) { c -> c.copy(color = color) } } }, close)
            }
            TreeDialog.Move -> collection?.let { shown ->
                MoveCollectionDialog(collections, shown, { parent, before -> apply { it.moveCollection(shown.id, parent, before) } }, close)
            }
            TreeDialog.Delete -> collection?.let { shown ->
                val counts by produceState<Organizer.CollectionDeletion?>(null, shown.id) {
                    value = withContext(Dispatchers.IO) { runCatching { Organizer(open.library, open.index).collectionDeletion(shown.id) }.getOrNull() }
                }
                DeleteCollectionDialog(shown.name, counts, { trash -> apply { it.deleteCollection(shown.id, trash) } }, close)
            }
            TreeDialog.RenameTag -> NameDialog(stringResource(R.string.tag_rename), arg.substringAfterLast('/'), stringResource(io.github.nir0k.jotter.designsystem.R.string.ds_save), error, { typed ->
                // The new name takes the old one's place under the same parent; a typed `/` nests it deeper.
                val parent = arg.substringBeforeLast('/', "")
                val target = normalizedTag(if (parent.isEmpty()) typed else "$parent/$typed")
                if (target.isEmpty() || target == arg) {
                    dialog = null
                } else {
                    scope.launch {
                        val count = session.organize { it.renameTag(arg, target) }
                        if (count == null) {
                            error = failed
                        } else {
                            dialog = null
                            snackbar.showSnackbar(resources.getQuantityString(R.plurals.tag_renamed, count, count))
                        }
                    }
                }
            }, close)
            TreeDialog.TagColor -> ColourPickerDialog(stringResource(UiR.string.item_color), info.tagColors[tagKey(arg)], { color ->
                apply { it.setTagColor(arg, color) }
            }, close)
            TreeDialog.DeleteTag -> {
                val count by produceState<Int?>(null, arg) {
                    value = withContext(Dispatchers.IO) { runCatching { open.index.notesTagged(arg).size }.getOrNull() }
                }
                ConfirmDialog(
                    title = stringResource(R.string.tag_delete_title, arg),
                    text = count?.let { pluralStringResource(R.plurals.tag_delete_text, it, it) } ?: "",
                    confirmText = stringResource(UiR.string.delete),
                    onConfirm = { apply { it.deleteTag(arg) } },
                    onDismiss = close,
                    destructive = true,
                )
            }
        }
    }
    return TreeActions(collectionMenu, tagMenu) { show(TreeDialog.NewCollection, "") }
}
