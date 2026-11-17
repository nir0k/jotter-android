package io.github.nir0k.jotter.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.nir0k.jotter.core.format.tagColor
import io.github.nir0k.jotter.core.index.NoteSort
import io.github.nir0k.jotter.core.index.NoteSummary
import io.github.nir0k.jotter.ui.theme.ItemIcons
import io.github.nir0k.jotter.ui.theme.itemTextColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import io.github.nir0k.jotter.designsystem.components.AddButton
import io.github.nir0k.jotter.designsystem.components.AppTopBar
import io.github.nir0k.jotter.designsystem.components.Choice
import io.github.nir0k.jotter.designsystem.components.ChoiceDialog
import io.github.nir0k.jotter.designsystem.components.CompactTagRow
import io.github.nir0k.jotter.designsystem.components.EmptyState
import io.github.nir0k.jotter.designsystem.components.ErrorState
import io.github.nir0k.jotter.designsystem.components.FilterButton
import io.github.nir0k.jotter.designsystem.components.LoadingState
import io.github.nir0k.jotter.designsystem.components.ListRowDivider
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.components.SortDirectionButton
import io.github.nir0k.jotter.designsystem.components.StatusBanner
import io.github.nir0k.jotter.designsystem.components.Tag
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.ChosenColor
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes
import io.github.nir0k.jotter.ui.Refresh
import io.github.nir0k.jotter.data.R as DataR

/** NotesLoad is what a note list has read from the index. */
sealed interface NotesLoad {
    /** The first read is running. */
    data object Loading : NotesLoad

    /** The notes in the list's order. */
    data class Loaded(val notes: List<NoteSummary>) : NotesLoad

    /** The index could not be read; Retry reads it again. */
    data object Failed : NotesLoad
}


/**
 * SyncBanner is the status banner under the top bar while synchronisation waits or failed; it
 * opens the Sync tab of Settings.
 *
 * @param text What happened, in one line.
 * @param failed Whether the run failed rather than waits.
 */
class SyncBanner(val text: String, val failed: Boolean, val onOpen: () -> Unit)

/** Shows the sync status banner. */
@Composable
fun SyncBannerStrip(banner: SyncBanner) = StatusBanner(
    text = banner.text,
    icon = if (banner.failed) AppIcons.StatusError else AppIcons.StatusWarning,
    actionText = stringResource(R.string.sync_banner_open),
    onAction = banner.onOpen,
)

/**
 * NoteSelection is the selection of a note list: a long press on a row starts it, taps then
 * add or remove notes, Back clears it, and the selection bar replaces the list's Add button
 * while notes are selected.
 *
 * @param ids The selected notes.
 * @param onChange Changes the selection; an empty set ends it.
 * @param bar The selection bar with its actions and dialogs.
 */
class NoteSelection(val ids: Set<String>, val onChange: (Set<String>) -> Unit, val bar: @Composable () -> Unit) {
    /** Whether notes are selected. */
    val active: Boolean get() = ids.isNotEmpty()
}

/**
 * LeadingRow is a row scrolled with the notes above them, such as a favourite collection.
 *
 * @param key Its key in the list, distinct from note identifiers.
 * @param content Draws it with the trailing content it is given, such as a drag handle.
 */
class LeadingRow(val key: String, val content: @Composable (trailing: (@Composable () -> Unit)?) -> Unit)

/**
 * Reorder lets rows be moved by dragging their handle: a row moves among the rows of its own group,
 * the leading rows or the notes, and the order is stored when it is dropped.
 *
 * @param onMove Receives the moved row's key and the key of the row it goes before, or null for
 *   the end of its group.
 */
class Reorder(val onMove: (String, String?) -> Unit)

/** The row being dragged by its handle and how far it moved. */
private class DragState {
    var key by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
}

/**
 * Draws the drag handle of a row and moves the row while it is dragged; on release the row goes
 * to the place of the row under its centre.
 *
 * @param group The keys of the rows it moves among, in their order.
 */
@Composable
private fun DragHandle(key: String, group: List<String>, drag: DragState, list: LazyListState, reorder: Reorder) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Dimens.TouchTarget)
            .pointerInput(key, group) {
                detectDragGestures(
                    onDragStart = {
                        drag.key = key
                        drag.offset = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.offset += amount.y
                    },
                    onDragEnd = {
                        val items = list.layoutInfo.visibleItemsInfo
                        val dragged = items.firstOrNull { it.key == key }
                        val centre = dragged?.let { it.offset + it.size / 2 + drag.offset }
                        val target = centre?.let { c -> items.firstOrNull { it.key != key && it.key in group && c.toInt() in it.offset until it.offset + it.size } }
                        if (dragged != null && target != null) {
                            val rest = group - key
                            // Moving down puts the row after the target, moving up before it.
                            val before = if (target.index > dragged.index) rest.getOrNull(rest.indexOf(target.key) + 1) else target.key as String
                            reorder.onMove(key, before)
                        }
                        drag.key = null
                        drag.offset = 0f
                    },
                    onDragCancel = {
                        drag.key = null
                        drag.offset = 0f
                    },
                )
            },
    ) {
        Icon(AppIcons.DragHandle, stringResource(R.string.reorder_handle), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimens.IconSecondary))
    }
}

/** Moves the dragged row with the finger, above the others. */
private fun Modifier.dragged(key: String, drag: DragState): Modifier =
    if (drag.key != key) this else zIndex(1f).graphicsLayer { translationY = drag.offset }

/** EmptyContent is the empty state of one note list: its glyph, title and explanation. */
data class EmptyContent(val icon: ImageVector, val title: String, val text: String)

/**
 * ListSearch is the search of one note list: its query and filters, how they change and what the
 * search window offers.
 */
class ListSearch(val search: NoteSearch, val onSearch: (NoteSearch) -> Unit, val options: SearchOptions)

/** Returns the localized name of a sort key. */
@Composable
private fun sortName(key: NoteSort.Key): String = stringResource(
    when (key) {
        NoteSort.Key.Updated -> R.string.sort_updated
        NoteSort.Key.Created -> R.string.sort_created
        NoteSort.Key.Title -> R.string.sort_title
    },
)

/**
 * Formats the date a row shows: the time for today, the date otherwise, in the interface language.
 *
 * @param time The note's updated or created time.
 */
@Composable
private fun rowDate(time: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val local = time.atZone(ZoneId.systemDefault())
    val style = if (local.toLocalDate() == LocalDate.now()) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    return local.format(style.withLocale(locale))
}

/**
 * NoteRow is one dense row of a note list, laid out as the design system's list row: the note's
 * icon and its title in the note's colour with the date on the first line, up to two lines of the
 * start of the text and the tags in their colours. A selected row takes the soft primary container,
 * inset so the lines between rows stay visible. Unlike the design system's row it shows the
 * matching words of a search result in bold and has a trailing slot for drag handles.
 *
 * @param note The note.
 * @param sort The list's order, which decides whether the updated or the created date shows.
 * @param tagColors Tag colour keys by tag key, from the library.
 * @param onOpen Opens the note, or toggles it while notes are selected.
 * @param selected Whether the note is selected; null while nothing is selected.
 * @param onSelect Starts the selection with this note on a long press; null where notes are not selected.
 * @param trailing Content at the row's end, such as a drag handle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteRow(
    note: NoteSummary,
    sort: NoteSort,
    tagColors: Map<String, String>,
    onOpen: () -> Unit,
    selected: Boolean? = null,
    onSelect: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val ink = itemTextColor(note.color)?.takeIf { selected != true } ?: scheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.DenseListGap, vertical = Dimens.CardInnerGap / 2)
            .clip(Shapes.Trigger)
            .background(if (selected == true) scheme.primaryContainer else Color.Transparent)
            .semantics { if (selected != null) this.selected = selected }
            .combinedClickable(onClick = onOpen, onLongClick = onSelect)
            .padding(horizontal = Dimens.SectionFieldGap, vertical = Dimens.DenseListVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.CardInnerGap / 2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.DenseListGap)) {
                ItemIcons[note.icon]?.let { Icon(it, null, tint = ink, modifier = Modifier.size(Dimens.IconInline)) }
                Text(
                    note.title.ifEmpty { stringResource(DataR.string.note_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (note.title.isEmpty()) muted else ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(rowDate(if (sort.key == NoteSort.Key.Created) note.created else note.updated), style = MaterialTheme.typography.labelSmall, color = muted)
            }
            if (note.snippet.isNotEmpty()) {
                // Search results show the matching words of their fragment in bold.
                val snippet = buildAnnotatedString {
                    append(note.snippet)
                    for (match in note.matches) {
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = scheme.onSurface), match.start, match.end)
                    }
                }
                Text(snippet, style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (note.tags.isNotEmpty()) {
                CompactTagRow(note.tags.map { Tag(it, ChosenColor.resolve(tagColor(tagColors, it)) ?: muted) })
            }
        }
        trailing?.invoke()
    }
}

/**
 * NoteListBody is the content of every note list: the order of the list with the list's own
 * controls, then the rows separated by lines or the list's empty, loading or error state.
 *
 * @param load What the list has read.
 * @param sort The order of the lists.
 * @param onSort Stores a new order; null for a list that keeps an order of its own, as Favourites.
 * @param tagColors Tag colour keys by tag key, from the library.
 * @param empty The state of an empty list.
 * @param onRetry Reads the index again after a failure.
 * @param onOpen Opens a note.
 * @param controls Controls beside the order, such as a collection's Only its own notes.
 * @param leading Rows scrolled with the notes above them, such as favourite collections; a list
 *   with leading rows is not empty.
 * @param reorder Moves rows by their drag handles, as in Favourites; null for lists in their order.
 * @param refresh Pull to refresh, or null for a library without a sync storage.
 * @param selection The list's selection, or null where notes are not selected.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteListBody(
    load: NotesLoad,
    sort: NoteSort,
    onSort: ((NoteSort) -> Unit)?,
    tagColors: Map<String, String>,
    empty: EmptyContent,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    controls: (@Composable () -> Unit)? = null,
    leading: List<LeadingRow> = emptyList(),
    refresh: Refresh? = null,
    selection: NoteSelection? = null,
    reorder: Reorder? = null,
) {
    val list = rememberLazyListState()
    val drag = remember { DragState() }
    var choosingSort by remember { mutableStateOf(false) }
    BackHandler(enabled = selection?.active == true) { selection?.onChange(emptySet()) }
    Column(modifier.fillMaxSize()) {
        if (onSort != null || controls != null) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimens.CardInnerGap),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSort != null) {
                    FilterButton(stringResource(R.string.sort_label, sortName(sort.key)), active = false, onClick = { choosingSort = true })
                    SortDirectionButton(sort.descending) { onSort(sort.copy(descending = !sort.descending)) }
                }
                controls?.invoke()
            }
        }
        val content: @Composable () -> Unit = {
            when (load) {
                NotesLoad.Loading -> LoadingState()
                NotesLoad.Failed -> ErrorState(stringResource(R.string.notes_failed), onRetry)
                is NotesLoad.Loaded -> when {
                    load.notes.isNotEmpty() || leading.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize(), state = list) {
                        val leadingKeys = leading.map { it.key }
                        val noteKeys = load.notes.map { it.id }
                        items(leading, key = { it.key }) { row ->
                            Box(Modifier.dragged(row.key, drag)) {
                                row.content(reorder?.let { { DragHandle(row.key, leadingKeys, drag, list, it) } })
                            }
                        }
                        itemsIndexed(load.notes, key = { _, note -> note.id }) { i, note ->
                            Column(Modifier.dragged(note.id, drag)) {
                                if (i > 0) ListRowDivider(Modifier.padding(horizontal = Dimens.DenseListHorizontal))
                                val selecting = selection?.active == true
                                NoteRow(
                                    note, sort, tagColors,
                                    onOpen = {
                                        if (selecting) selection.onChange(selection.ids.let { if (note.id in it) it - note.id else it + note.id })
                                        else onOpen(note.id)
                                    },
                                    selected = (note.id in selection?.ids.orEmpty()).takeIf { selecting },
                                    onSelect = selection?.let { { it.onChange(it.ids + note.id) } },
                                    trailing = reorder?.takeIf { !selecting }?.let { { DragHandle(note.id, noteKeys, drag, list, it) } },
                                )
                            }
                        }
                    }
                    else -> EmptyState(empty.icon, empty.title, empty.text)
                }
            }
        }
        if (refresh == null) {
            content()
        } else {
            PullToRefreshBox(isRefreshing = refresh.refreshing, onRefresh = refresh.onRefresh, modifier = Modifier.fillMaxSize()) {
                // States without a list of their own scroll, so they can be pulled too.
                if (load is NotesLoad.Loaded && (load.notes.isNotEmpty() || leading.isNotEmpty())) content()
                else Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
            }
        }
    }
    if (choosingSort && onSort != null) {
        ChoiceDialog(
            stringResource(R.string.sort_title_dialog),
            NoteSort.Key.entries.map { Choice(it, sortName(it)) },
            sort.key,
            { onSort(sort.copy(key = it)) },
            onDismiss = { choosingSort = false },
        )
    }
}

/**
 * NoteListScreen is a note list opened from the home screen or from a list of it: a top bar with
 * Back, the title and the search button, then the list.
 *
 * @param title The list's name.
 * @param onSort Stores a new order; null for a list that keeps an order of its own.
 * @param onSearch Opens the list's search window.
 * @param onOpen Opens a note.
 * @param onBack Closes the screen.
 * @param menu The list's own ⋮ entries, such as the trash's Empty trash.
 * @param detail The open note beside the list in a wide window, or null.
 * @param refresh Pull to refresh, or null for a library without a sync storage.
 * @param banner The sync status banner, or null.
 * @param snackbar Transient messages of synchronisation, or null.
 * @param onAdd Creates a note in this list, shown as the round Add button; null where notes are
 *   not created, as in the archive and the trash.
 * @param selection The list's selection, whose bar replaces the Add button while notes are selected.
 * @param controls Controls beside the order, such as a collection's Only its own notes.
 * @param leading Rows scrolled with the notes above them, such as favourite collections.
 * @param reorder Moves rows by their drag handles, as in Favourites.
 */
@Composable
fun NoteListScreen(
    title: String,
    load: NotesLoad,
    sort: NoteSort,
    onSort: ((NoteSort) -> Unit)?,
    onSearch: () -> Unit,
    tagColors: Map<String, String>,
    empty: EmptyContent,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    menu: List<MenuAction> = emptyList(),
    detail: (@Composable () -> Unit)? = null,
    refresh: Refresh? = null,
    banner: SyncBanner? = null,
    snackbar: SnackbarHostState? = null,
    onAdd: (() -> Unit)? = null,
    selection: NoteSelection? = null,
    controls: (@Composable () -> Unit)? = null,
    leading: List<LeadingRow> = emptyList(),
    reorder: Reorder? = null,
) {
    Scaffold(
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        floatingActionButton = { if (selection?.active != true) onAdd?.let { AddButton(stringResource(R.string.note_new), it) } },
        bottomBar = { if (selection?.active == true) selection.bar() },
        topBar = { AppTopBar(title, onBack = onBack, menu = menu, actions = { SearchButton(onSearch) }) },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            banner?.let { SyncBannerStrip(it) }
            ListAndDetail(Modifier, detail) {
                NoteListBody(load, sort, onSort, tagColors, empty, onRetry, onOpen, controls = controls, leading = leading, refresh = refresh, selection = selection, reorder = reorder)
            }
        }
    }
}

/**
 * ListAndDetail lays a list out alone or, in a wide window with a note open, beside the note, as
 * the desktop's panes.
 *
 * @param detail The open note, or null for the list alone.
 * @param list The list.
 */
@Composable
fun ListAndDetail(modifier: Modifier, detail: (@Composable () -> Unit)?, list: @Composable () -> Unit) {
    if (detail == null) {
        Column(modifier.fillMaxSize()) { list() }
        return
    }
    Row(modifier.fillMaxSize()) {
        Column(Modifier.weight(Dimens.ListDetailListShare).fillMaxHeight()) { list() }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1 - Dimens.ListDetailListShare).fillMaxHeight()) { detail() }
    }
}
