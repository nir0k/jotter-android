package io.github.nir0k.jotter.core.index

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.GeoLocation
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.search.Search
import io.github.nir0k.jotter.core.search.SearchScope
import io.github.nir0k.jotter.core.search.TermMatcher
import io.github.nir0k.jotter.core.search.parseQuery
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the search index: incremental scans, problems, the views of the note list, counts and search. */
@OptIn(ExperimentalPathApi::class)
class IndexTest {
    private val dir: Path = createTempDirectory()
    private val library = Library.create(dir.resolve("library"))
    private val indexPath = dir.resolve("index.sqlite")
    private val index = Index.open(indexPath, String.CASE_INSENSITIVE_ORDER)
    private val time = Instant.parse("2026-10-20T10:00:00Z")

    @After
    fun cleanup() {
        index.close()
        dir.deleteRecursively()
    }

    /** Writes a note with the given fields; its times grow with [minutes]. */
    private fun note(title: String, body: String = "<p></p>", minutes: Long = 0, edit: (NoteMeta) -> NoteMeta = { it }): Note {
        val at = time.plusSeconds(minutes * 60)
        return Note(edit(NoteMeta(id = newId(), title = title, created = at, updated = at)), body).also(library::writeNote)
    }

    private fun titles(list: List<NoteSummary>) = list.map { it.title }

    /** A scan reads only changed files, records unreadable ones as problems and forgets vanished ones. */
    @Test
    fun scansIncrementally() {
        val a = note("A")
        val b = note("B")
        assertEquals(ScanReport(added = 2, updated = 0, removed = 0), index.scan(library))
        assertEquals(ScanReport(0, 0, 0), index.scan(library))

        library.writeNote(a.copy(meta = a.meta.copy(title = "A changed")))
        library.notePath(b.meta.id).writeText("not a note")
        assertEquals(ScanReport(added = 0, updated = 1, removed = 1), index.scan(library))
        assertEquals(listOf("A changed"), titles(index.notes(NoteFilter(), NoteSort())))
        assertEquals(listOf(ErrorCode.Malformed), index.problems().map { it.code })

        Files.delete(library.notePath(b.meta.id))
        Files.delete(library.notePath(a.meta.id))
        assertEquals(ScanReport(0, 0, removed = 2), index.scan(library))
        assertTrue(index.problems().isEmpty())
        assertTrue(index.search(NoteFilter(), Search("changed"), emptyList(), NoteSort()).isEmpty())
    }

    /** Each view lists its notes; counts include descendants once and tags keep their first spelling. */
    @Test
    fun tellsWhenTheLibraryLastChanged() {
        assertEquals(null, index.lastUpdated())
        note("Old", minutes = 1)
        val newest = note("New", minutes = 5)
        index.scan(library)
        assertEquals(newest.meta.updated, index.lastUpdated())
    }

    @Test
    fun listsViews() {
        val parent = Collection(newId(), null, "Work", created = time, updated = time)
        val child = Collection(newId(), parent.id, "Meetings", created = time, updated = time)
        val tree = listOf(parent, child)
        val plan = note("Plan 10", minutes = 1) { it.copy(collections = listOf(parent.id, child.id), tags = listOf("Work")) }
        note("plan 2", minutes = 2) { it.copy(collections = listOf(child.id), tags = listOf("work/urgent")) }
        val unfiled = note("Unfiled", minutes = 3) { it.copy(tags = listOf("home")) }
        note("Archived", minutes = 4) { it.copy(archived = time, tags = listOf("work")) }
        note("Trashed", minutes = 5) { it.copy(archived = time, trashed = time) }
        index.scan(library)

        assertEquals(listOf("Unfiled", "plan 2", "Plan 10"), titles(index.notes(NoteFilter(), NoteSort())))
        assertEquals(listOf("Plan 10", "plan 2", "Unfiled"), titles(index.notes(NoteFilter(), NoteSort(NoteSort.Key.Title, descending = false))))
        assertEquals(listOf("Unfiled"), titles(index.notes(NoteFilter(NoteFilter.View.Unfiled), NoteSort())))
        assertEquals(listOf("Archived"), titles(index.notes(NoteFilter(NoteFilter.View.Archive), NoteSort())))
        assertEquals(listOf("Trashed"), titles(index.notes(NoteFilter(NoteFilter.View.Trash), NoteSort())))
        assertEquals(listOf("plan 2", "Plan 10"), titles(index.notes(NoteFilter(NoteFilter.View.Tag, tag = "WORK"), NoteSort())))
        assertEquals(listOf("Unfiled", "Plan 10"), titles(index.notes(NoteFilter(NoteFilter.View.Notes, listOf(plan.meta.id, unfiled.meta.id)), NoteSort())))
        assertEquals(1, index.count(NoteFilter(NoteFilter.View.Collections, listOf(parent.id))))
        assertEquals(mapOf(parent.id to 2, child.id to 2), index.collectionCounts(tree))
        // A parent path counts the notes of its children once, as the Tag view lists them.
        assertEquals(TagList(listOf("home", "Work", "work/urgent"), mapOf("home" to 1, "work" to 2, "work/urgent" to 1)), index.tags())
        assertEquals(listOf("Work"), index.notes(NoteFilter(NoteFilter.View.Notes, listOf(plan.meta.id)), NoteSort()).single().tags)
    }

    /** Search finds word forms, phrases, exclusions, tags, collection names and filters, ranking the title first. */
    @Test
    fun searches() {
        val trips = Collection(newId(), null, "Поездки", created = time, updated = time)
        val tree = listOf(trips)
        note("Ёлка", "<p>Купили ёлку на рынке.</p>", minutes = 1) { it.copy(tags = listOf("дом/праздник")) }
        note(
            "Заметки",
            "<p>Мы долго выбирали, какую ёлку взять, и в итоге купили большую ёлку.</p>" +
                "<p><img src=\"../attachments/x/a.png\"></p>",
            minutes = 2,
        ) { it.copy(collections = listOf(trips.id), location = GeoLocation(1.0, 2.0, null, time)) }
        note("Running notes", "<p>Shoes and socks</p>", minutes = 3) { it.copy(created = time.minusSeconds(86_400)) }
        index.scan(library)
        fun found(search: Search) = titles(index.search(NoteFilter(), search, tree, NoteSort()))

        assertEquals(listOf("Ёлка", "Заметки"), found(Search("елки")))
        assertEquals(listOf("Заметки"), found(Search("\"купили большую\"")))
        assertEquals(listOf("Ёлка"), found(Search("ёлку -выбирали")))
        assertEquals(listOf("Running notes"), found(Search("note ru")))
        assertEquals(listOf("Ёлка"), found(Search("#дом")))
        assertEquals(listOf("Ёлка"), found(Search("#до")))
        assertEquals(listOf("Заметки"), found(Search("поездкам")))
        assertEquals(emptyList<String>(), found(Search("поездкам", scopes = setOf(SearchScope.Title, SearchScope.Text))))
        assertEquals(listOf("Ёлка"), found(Search("ёлка", scopes = setOf(SearchScope.Title))))
        assertEquals(listOf("Заметки"), found(Search(withAttachments = true, withLocation = true)))
        assertEquals(listOf("Running notes"), found(Search(createdUntil = time)))

        val result = index.search(NoteFilter(), Search("ёлку"), tree, NoteSort()).last()
        assertEquals("Мы долго выбирали, какую ёлку взять, и в итоге купили большую ёлку.", result.snippet)
        assertEquals(listOf("ёлку", "ёлку"), result.matches.map { result.snippet.substring(it.start, it.end) })
    }

    /** A long text gives a window of words around the match, with an ellipsis where the text goes on. */
    @Test
    fun cutsLongFragments() {
        val text = (1..40).joinToString(" ") { "w$it" }
        val (fragment, matches) = fragment(text, TermMatcher(parseQuery("w30 ")))!!
        assertEquals("…" + (17..40).joinToString(" ") { "w$it" }, fragment)
        assertEquals(listOf("w30"), matches.map { fragment.substring(it.start, it.end) })
        val start = fragment((1..30).joinToString(" ") { "w$it" }, TermMatcher(parseQuery("w2 ")))!!.first
        assertEquals((1..24).joinToString(" ") { "w$it" } + "…", start)
    }

    /** An index of another schema version is dropped and built again by the next scan. */
    @Test
    fun rebuildsOutdatedIndex() {
        note("Kept")
        index.scan(library)
        index.close()
        BundledSQLiteDriver().open(indexPath.toString()).use { it.execSQL("PRAGMA user_version = 99") }
        Index.open(indexPath, String.CASE_INSENSITIVE_ORDER).use { reopened ->
            assertTrue(reopened.notes(NoteFilter(), NoteSort()).isEmpty())
            reopened.scan(library)
            assertEquals(listOf("Kept"), titles(reopened.notes(NoteFilter(), NoteSort())))
        }
    }

    /** Backlinks leave out the note itself and, unless asked, the trash; the picker offers recent or matching titles. */
    @Test
    fun links() {
        val target = note("Target", minutes = 1)
        val link = """<p><a class="note-link" href="${target.meta.id}.html">Target</a></p>"""
        note("Active", link, minutes = 2)
        note("Trashed", link, minutes = 3) { it.copy(trashed = time) }
        library.writeNote(target.copy(body = link))
        index.scan(library)
        assertEquals(listOf("Active"), titles(index.backlinks(target.meta.id, includeTrashed = false)))
        assertEquals(listOf("Trashed", "Active"), titles(index.backlinks(target.meta.id, includeTrashed = true)))
        assertEquals(setOf(target.meta.id), index.existing(setOf(target.meta.id, newId())))

        assertEquals(listOf("Active"), titles(index.linkCandidates("", exclude = target.meta.id, limit = 30)))
        assertEquals(listOf("Target"), titles(index.linkCandidates("targets", exclude = "", limit = 30)))
    }
}
