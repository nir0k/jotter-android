package io.github.nir0k.jotter.feature.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.index.NoteFilter
import io.github.nir0k.jotter.core.index.NoteSort
import io.github.nir0k.jotter.data.library.LibraryState
import io.github.nir0k.jotter.feature.notes.trees.CollectionNode
import io.github.nir0k.jotter.feature.notes.trees.TagNode
import io.github.nir0k.jotter.feature.notes.trees.collectionTree
import io.github.nir0k.jotter.feature.notes.trees.tagTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * NotesQuery is a loaded note list and the way to read it again after a failure.
 *
 * @param load What the list has read.
 * @param retry Reads the index again.
 */
class NotesQuery(val load: NotesLoad, val retry: () -> Unit)

/** The pause after the last change of a search before it runs. */
private const val SearchDelayMillis = 150L

/**
 * Reads a note list from the index off the main thread, again whenever the index changes, the
 * view, the order or the search changes, or Retry is chosen. An active search runs 150 ms after
 * its last change, ranked by relevance, over all notes when it searches everywhere. The previous
 * list stays shown while the next one is read, so typing or changing the order does not flash a
 * loading state.
 *
 * @param open The open library.
 * @param filter The view.
 * @param sort The order; ignored when [order] is given and nothing is searched.
 * @param search The list's search.
 * @param collections The collection tree, for collection names and the collection filter.
 * @param order Identifiers in the order the list keeps, such as the favourites'.
 */
@Composable
fun rememberNotes(
    open: LibraryState.Open,
    filter: NoteFilter,
    sort: NoteSort,
    search: NoteSearch,
    collections: List<Collection>,
    order: List<String>? = null,
): NotesQuery {
    var attempt by remember { mutableIntStateOf(0) }
    val load by produceState<NotesLoad>(NotesLoad.Loading, open.index, open.revision, filter, sort, search, collections, order, attempt) {
        if (search.isActive) delay(SearchDelayMillis)
        value = withContext(Dispatchers.IO) {
            try {
                if (search.isActive) {
                    NotesLoad.Loaded(open.index.search(if (search.everywhere) NoteFilter() else filter, search.toSearch(), collections, sort))
                } else {
                    val notes = open.index.notes(filter, sort)
                    NotesLoad.Loaded(order?.let { ids -> notes.sortedBy { ids.indexOf(it.id) } } ?: notes)
                }
            } catch (_: FormatException) {
                NotesLoad.Failed
            }
        }
    }
    return NotesQuery(load) { attempt++ }
}

/**
 * LibraryLists are the collection tree and the tags of the open library with their counts, which
 * the home screen shows and the search windows offer.
 *
 * @param tags The tags of active notes, each in the spelling of its first use.
 * @param collectionTree The collections with the number of active notes of each, descendants included.
 * @param tagTree The tag tree with the number of active notes of each path, children included.
 */
class LibraryLists(
    val collections: List<Collection>,
    val tags: List<String>,
    val collectionTree: List<CollectionNode>,
    val tagTree: List<TagNode>,
)

private val NoLists = LibraryLists(emptyList(), emptyList(), emptyList(), emptyList())

/**
 * Reads the collection tree, the tags and their counts off the main thread, again whenever the
 * index changes. A failure leaves them empty: the filters then offer nothing to choose and the
 * trees show their empty states, while the lists still show their own errors.
 */
@Composable
fun rememberLibraryLists(open: LibraryState.Open): LibraryLists {
    val lists by produceState(NoLists, open.library, open.index, open.revision) {
        value = withContext(Dispatchers.IO) {
            try {
                val collections = open.library.readCollections()
                val tags = open.index.tags()
                LibraryLists(collections, tags.names, collectionTree(collections, open.index.collectionCounts(collections)), tagTree(tags.names, tags.counts))
            } catch (_: FormatException) {
                NoLists
            }
        }
    }
    return lists
}
