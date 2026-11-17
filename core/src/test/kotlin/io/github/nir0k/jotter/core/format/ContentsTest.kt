package io.github.nir0k.jotter.core.format

import io.github.nir0k.jotter.core.index.Index
import java.nio.file.Path
import java.time.ZoneOffset
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.getLastModifiedTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the generated table of contents (desktop spec section 3.6), as the desktop's `tst_links` does. */
@OptIn(ExperimentalPathApi::class)
class ContentsTest {
    private val dir: Path = createTempDirectory()
    private val library = Library.create(dir.resolve("library"))
    private val index = Index.open(dir.resolve("index.sqlite"), String.CASE_INSENSITIVE_ORDER)
    private val text = ContentsText("Jotter library", "Untitled", "Notes without a collection", "Archive", "This library has no notes yet.")

    @After
    fun cleanup() {
        index.close()
        dir.deleteRecursively()
    }

    private fun collection(name: String, parent: String? = null): Collection {
        val time = now()
        return Collection(newId(), parent, name, created = time, updated = time)
    }

    /** Writes a note with a change of its metadata. */
    private fun note(title: String, change: (NoteMeta) -> NoteMeta = { it }): String {
        val fresh = newNote()
        library.writeNote(fresh.copy(meta = change(fresh.meta.copy(title = title))))
        return fresh.meta.id
    }

    /**
     * Notes are listed under each of their collections, nested collections as nested headings,
     * then the notes without a collection and the archive, leaving out the trash and empty
     * collections; the index gives them with their tags and collections.
     */
    @Test
    fun contents() {
        val work = collection("Work")
        val meetings = collection("Meetings", work.id)
        val empty = collection("Empty")
        library.changeCollections { listOf(work, meetings, empty) }
        val standup = note("Standup") { it.copy(collections = listOf(meetings.id, work.id), tags = listOf("daily")) }
        note("Loose")
        note("Old") { it.copy(archived = now()) }
        note("Gone") { it.copy(trashed = now()) }
        index.scan(library)

        val page = tableOfContents(index.contentsNotes(), library.readCollections(), text, Locale.ENGLISH, ZoneOffset.UTC).decodeToString()
        assertEquals(2, Regex.escape("notes/${noteLinkAddress(standup)}").toRegex().findAll(page).count())
        assertTrue(page.indexOf("<h2>Work</h2>") < page.indexOf("<h3>Meetings</h3>"))
        assertTrue("#daily" in page)
        assertTrue(page.indexOf("Loose") < page.indexOf("<h2>Archive</h2>") && page.indexOf("<h2>Archive</h2>") < page.indexOf("Old"))
        assertFalse("Gone" in page)
        assertFalse("Empty" in page)
    }

    /** The file is written only when its contents changed, so synchronisation sees no change. */
    @Test
    fun writesOnlyChanges() {
        val page = tableOfContents(emptyList(), emptyList(), text, Locale.ENGLISH, ZoneOffset.UTC)
        assertTrue("This library has no notes yet." in page.decodeToString())
        assertTrue(library.writeTableOfContents(page))
        val written = library.root.resolve("index.html").getLastModifiedTime()
        assertFalse(library.writeTableOfContents(page))
        assertEquals(written, library.root.resolve("index.html").getLastModifiedTime())
    }
}
