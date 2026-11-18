package io.github.nir0k.jotter.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.feature.notes.trees.CollectionNode
import io.github.nir0k.jotter.feature.notes.trees.TagNode
import io.github.nir0k.jotter.feature.notes.trees.TreeActions
import io.github.nir0k.jotter.feature.notes.trees.collectionTreeRows
import io.github.nir0k.jotter.feature.notes.trees.tagTreeRows
import io.github.nir0k.jotter.designsystem.components.AddButton
import io.github.nir0k.jotter.designsystem.components.AppTopBar
import io.github.nir0k.jotter.designsystem.components.IconAction
import io.github.nir0k.jotter.designsystem.components.StatusBanner
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.ui.Refresh
import io.github.nir0k.jotter.ui.R as UiR

/**
 * HomeTrees is what the home screen shows of the collections and the tags.
 *
 * @param collections The collection tree with its counts.
 * @param tags The tag tree with its counts.
 * @param tagColors Tag colour keys by tag key, from the library.
 * @param onOpenCollection Opens a collection's notes.
 * @param onOpenTag Opens the notes of a tag path.
 * @param actions What can be changed in the trees, or null in a read-only library.
 */
class HomeTrees(
    val collections: List<CollectionNode>,
    val tags: List<TagNode>,
    val tagColors: Map<String, String>,
    val onOpenCollection: (Collection) -> Unit,
    val onOpenTag: (String) -> Unit,
    val actions: TreeActions? = null,
)

/**
 * HomeDestination is a note list the home screen opens.
 *
 * @param label The list's name.
 * @param icon The list's glyph.
 * @param onOpen Opens the list.
 */
class HomeDestination(val label: String, val icon: ImageVector, val onOpen: () -> Unit)

// The expanded nodes of a tree survive rotation as a list of their keys.
private val ExpandedSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** The space under the last row, so the round Add button covers none of its actions. */
private val AddButtonClearance = Dimens.TouchTarget * 2

/** Counts the paths of a tag tree, the rows it shows when every node is expanded. */
private fun tagCount(nodes: List<TagNode>): Int = nodes.sumOf { 1 + tagCount(it.children) }

/**
 * HomeScreen is what the application opens with: the note lists, then the collections with Add,
 * then the tags with their number, folded until they are asked for. A library written by a newer
 * Jotter shows a banner, and so does synchronisation while it waits or failed.
 *
 * @param destinations The note lists.
 * @param trees The collections and the tags.
 * @param onSearch Opens the search window over all notes.
 * @param onSettings Opens Settings.
 * @param readOnly Whether the library is read-only.
 * @param initialTagsShown Whether the tags start unfolded.
 * @param initialExpanded The collection identifiers and tag keys expanded at first.
 * @param detail The open note beside the list in a wide window, or null.
 * @param banner The sync status banner, or null.
 * @param refresh Pull to refresh, or null for a library without a sync storage.
 * @param snackbar Transient messages of the lists and of synchronisation, or null.
 * @param onAdd Creates a note; null in a read-only library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    destinations: List<HomeDestination>,
    trees: HomeTrees,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    readOnly: Boolean = false,
    initialTagsShown: Boolean = false,
    initialExpanded: Set<String> = emptySet(),
    detail: (@Composable () -> Unit)? = null,
    banner: SyncBanner? = null,
    refresh: Refresh? = null,
    snackbar: SnackbarHostState? = null,
    onAdd: (() -> Unit)? = null,
) {
    var tagsShown by rememberSaveable { mutableStateOf(initialTagsShown) }
    var expandedCollections by rememberSaveable(stateSaver = ExpandedSaver) { mutableStateOf(initialExpanded) }
    var expandedTags by rememberSaveable(stateSaver = ExpandedSaver) { mutableStateOf(initialExpanded) }
    val add: (@Composable () -> Unit)? = onAdd?.let { { AddButton(stringResource(R.string.note_new), it) } }
    Scaffold(
        topBar = {
            AppTopBar(
                stringResource(UiR.string.app_name),
                actions = {
                    SearchButton(onSearch)
                    IconAction(AppIcons.Settings, stringResource(UiR.string.settings_title), onSettings, iconSize = Dimens.IconToolbar, tint = MaterialTheme.colorScheme.onSurface)
                },
            )
        },
        floatingActionButton = { if (detail == null) add?.invoke() },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            if (readOnly) StatusBanner(text = stringResource(UiR.string.library_read_only), icon = AppIcons.Lock)
            banner?.let { SyncBannerStrip(it) }
            ListAndDetail(Modifier, detail, add) {
                val rows: @Composable () -> Unit = {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = AddButtonClearance)) {
                        items(destinations, key = { "list:${it.label}" }) { DestinationRow(it) }
                        item(key = "collections") {
                            SectionHeader(stringResource(R.string.home_collections)) {
                                trees.actions?.let { IconAction(AppIcons.Add, stringResource(R.string.collection_new), it.onNewCollection) }
                            }
                        }
                        if (trees.collections.isEmpty()) {
                            hint(R.string.collections_empty_text)
                        } else {
                            collectionTreeRows(
                                trees.collections,
                                expandedCollections,
                                { id -> expandedCollections = if (id in expandedCollections) expandedCollections - id else expandedCollections + id },
                                trees.onOpenCollection,
                                menu = trees.actions?.collectionMenu ?: { emptyList() },
                            )
                        }
                        item(key = "tags") {
                            val title = stringResource(R.string.home_tags)
                            SectionHeader(title, tagCount(trees.tags), onClick = { tagsShown = !tagsShown }) {
                                IconAction(
                                    if (tagsShown) AppIcons.ChevronDown else AppIcons.ChevronRight,
                                    stringResource(if (tagsShown) io.github.nir0k.jotter.designsystem.R.string.ds_tree_collapse else io.github.nir0k.jotter.designsystem.R.string.ds_tree_expand, title),
                                    { tagsShown = !tagsShown },
                                    iconSize = Dimens.IconChevron,
                                )
                            }
                        }
                        if (tagsShown) {
                            if (trees.tags.isEmpty()) {
                                hint(R.string.tags_empty_text)
                            } else {
                                tagTreeRows(
                                    trees.tags,
                                    trees.tagColors,
                                    expandedTags,
                                    { key -> expandedTags = if (key in expandedTags) expandedTags - key else expandedTags + key },
                                    trees.onOpenTag,
                                    menu = trees.actions?.tagMenu ?: { emptyList() },
                                )
                            }
                        }
                    }
                }
                if (refresh == null) rows() else PullToRefreshBox(refresh.refreshing, refresh.onRefresh, Modifier.fillMaxSize()) { rows() }
            }
        }
    }
}

/** A note list as a row of the home screen: its glyph and name. */
@Composable
private fun DestinationRow(destination: HomeDestination) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = destination.onOpen).heightIn(min = Dimens.TouchTarget).padding(horizontal = Dimens.DenseListHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SectionFieldGap),
    ) {
        Icon(destination.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimens.IconSecondary))
        Text(destination.label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The header of a part of the home screen.
 *
 * @param count The number of items, or null when the header shows none.
 * @param onClick What tapping the header does, or null when it does nothing.
 * @param action The control at its end, or nothing.
 */
@Composable
private fun SectionHeader(title: String, count: Int? = null, onClick: (() -> Unit)? = null, action: @Composable () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Dimens.TouchTarget).padding(start = Dimens.DenseListHorizontal, end = Dimens.CardInnerGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SectionFieldGap),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = muted, modifier = Modifier.weight(1f))
        count?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = muted) }
        action()
    }
}

/** Adds a muted line explaining an empty part of the home screen. */
private fun LazyListScope.hint(text: Int) = item {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.DenseListHorizontal, vertical = Dimens.CardInnerGap),
    )
}
