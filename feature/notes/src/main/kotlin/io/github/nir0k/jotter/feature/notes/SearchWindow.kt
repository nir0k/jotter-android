package io.github.nir0k.jotter.feature.notes

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.nir0k.jotter.core.index.NoteSort
import io.github.nir0k.jotter.designsystem.components.EmptyState
import io.github.nir0k.jotter.designsystem.components.ErrorState
import io.github.nir0k.jotter.designsystem.components.FilterToggle
import io.github.nir0k.jotter.designsystem.components.IconAction
import io.github.nir0k.jotter.designsystem.components.ListRowDivider
import io.github.nir0k.jotter.designsystem.components.SearchField
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes

/** The widest the search window grows in a wide window. */
private val SearchWindowMaxWidth = 600.dp

/** SearchButton opens a list's search window from its top bar. */
@Composable
fun SearchButton(onClick: () -> Unit) = IconAction(
    AppIcons.Search,
    stringResource(R.string.search_open),
    onClick,
    iconSize = Dimens.IconToolbar,
    tint = MaterialTheme.colorScheme.onSurface,
)

/**
 * SearchWindow searches one note list in a window over it: the search field with the filter toggle,
 * the filters wrapped on as many lines as they need, and the results. The field takes the focus
 * when nothing is typed yet, and tapping outside or Back closes the window, which keeps the query
 * and the filters for the next time it opens. The results are plain rows: notes are selected in the
 * lists themselves.
 *
 * @param search The list's search.
 * @param load The results of an active search; null while nothing is searched.
 * @param sort The list's order, which decides whether a row shows the updated or the created date.
 * @param tagColors Tag colour keys by tag key, from the library.
 * @param onRetry Reads the index again after a failure.
 * @param onOpen Opens a note.
 * @param onDismiss Closes the window.
 * @param initialFiltersShown Whether the filters start open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchWindow(
    search: ListSearch,
    load: NotesLoad?,
    sort: NoteSort,
    tagColors: Map<String, String>,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    initialFiltersShown: Boolean = false,
) {
    var filtersShown by rememberSaveable { mutableStateOf(initialFiltersShown) }
    val focus = remember { FocusRequester() }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The window draws behind the system bars and the keyboard, whose height comes as insets.
        LaunchedEffect(Unit) { if (search.search.query.isEmpty()) focus.requestFocus() }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
            Surface(
                shape = Shapes.Dialog,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(Dimens.DialogMargin)
                    .widthIn(max = SearchWindowMaxWidth)
                    .fillMaxWidth(),
            ) {
                Column {
                    Row(
                        Modifier.padding(start = Dimens.ScreenPadding, end = Dimens.CardInnerGap, top = Dimens.CardInnerGap, bottom = Dimens.CardInnerGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SearchField(
                            search.search.query,
                            { search.onSearch(search.search.copy(query = it)) },
                            Modifier.weight(1f).focusRequester(focus),
                            placeholder = stringResource(R.string.search_placeholder),
                        )
                        FilterToggle(filtersShown, search.search.hasFilters) { filtersShown = !filtersShown }
                    }
                    if (filtersShown) {
                        FlowRow(
                            // Scrolled in a window too short for them, as above the keyboard in landscape.
                            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth().padding(horizontal = Dimens.ScreenPadding),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.CardInnerGap * 2),
                        ) {
                            SearchFilters(search.search, search.onSearch, search.options)
                        }
                    }
                    Box(Modifier.weight(1f, fill = false)) {
                        SearchResults(search, load, sort, tagColors, onRetry, onOpen)
                    }
                }
            }
        }
    }
}

/**
 * Shows what the search window has found: a hint while nothing is searched, the rows, or the no
 * matches or error state. Nothing shows during the first read, which lasts a moment.
 */
@Composable
private fun SearchResults(
    search: ListSearch,
    load: NotesLoad?,
    sort: NoteSort,
    tagColors: Map<String, String>,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val stateHeight = Modifier.heightIn(max = Dimens.ChoiceListMaxHeight)
    when {
        load == null -> Text(
            stringResource(R.string.search_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(Dimens.DialogPadding),
        )
        load is NotesLoad.Failed -> ErrorState(stringResource(R.string.notes_failed), onRetry, stateHeight)
        load is NotesLoad.Loaded && load.notes.isEmpty() -> EmptyState(
            AppIcons.Search,
            stringResource(R.string.no_matches_title),
            stringResource(R.string.no_matches_text),
            stateHeight,
            actionText = stringResource(R.string.no_matches_clear),
            onAction = { search.onSearch(NoteSearch()) },
        )
        load is NotesLoad.Loaded -> LazyColumn {
            itemsIndexed(load.notes, key = { _, note -> note.id }) { i, note ->
                if (i > 0) ListRowDivider(Modifier.padding(horizontal = Dimens.DenseListHorizontal))
                NoteRow(note, sort, tagColors, onOpen = { onOpen(note.id) })
            }
        }
    }
}
