package io.github.nir0k.jotter.core.organize

import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FavoriteRef
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.format.attachmentSource
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.format.serializeNote
import io.github.nir0k.jotter.core.index.Index
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks organising notes: undo, permanent deletion, collections, tags and the removal of what the library keeps no longer. */
@OptIn(ExperimentalPathApi::class)
class OrganizerTest {
    private val dir: Path = createTempDirectory()
    private val library = Library.create(dir.resolve("library"))
    private val index = Index.open(dir.resolve("index.sqlite"), String.CASE_INSENSITIVE_ORDER)
    private val organizer = Organizer(library, index)

    @After
    fun cleanup() {
        index.close()
        dir.deleteRecursively()
    }

    /** Writes a note and indexes the library. */
    private fun note(title: String, body: String = "<p></p>", edit: (NoteMeta) -> NoteMeta = { it }): String {
        val time = now()
        val note = Note(edit(NoteMeta(id = newId(), title = title, created = time, updated = time)), body)
        library.writeNote(note)
        index.scan(library)
        return note.meta.id
    }

    private fun meta(id: String) = library.readNote(id).meta

    /** Undo gives back the organisation, keeping a title changed since; a restored note returns to the archive it was in. */
    @Test
    fun undoAndRestore() {
        val id = note("A") { it.copy(collections = listOf("c1")) }
        val before = organizer.changeCollections(listOf(id), add = listOf("c2"))
        assertEquals(listOf("c1", "c2"), meta(id).collections)
        library.writeNote(library.readNote(id).let { it.copy(meta = it.meta.copy(title = "B")) })
        organizer.restore(before)
        assertEquals(listOf("c1"), meta(id).collections)
        assertEquals("B", meta(id).title)

        organizer.setArchived(listOf(id), true)
        organizer.setTrashed(listOf(id), true)
        organizer.setTrashed(listOf(id), false)
        assertNotNull(meta(id).archived)
        assertNull(meta(id).trashed)
    }

    /** A permanent deletion removes the files, leaves a tombstone and drops the favourite. */
    @Test
    fun deletesPermanently() {
        val id = note("A")
        Files.createDirectories(library.attachmentsDir(id)).resolve("a.png").writeText("x")
        organizer.setFavorite(listOf(FavoriteRef(FavoriteRef.Kind.Note, id)), true)
        assertEquals(1, organizer.deleteNotes(listOf(id, "not an id")))
        assertFalse(Files.exists(library.notePath(id)))
        assertFalse(Files.exists(library.attachmentsDir(id)))
        assertEquals(listOf(id), library.info.tombstones.map { it.id })
        assertTrue(library.info.favorites.isEmpty())
    }

    /** Moving a favourite keeps the others in their order. */
    @Test
    fun movesFavorites() {
        val (a, b, c) = listOf("a", "b", "c").map { FavoriteRef(FavoriteRef.Kind.Note, note(it)) }
        val folder = FavoriteRef(FavoriteRef.Kind.Collection, newId())
        organizer.setFavorite(listOf(a, folder, b, c), true)
        organizer.moveFavorite(c, before = a)
        assertEquals(listOf(c, a, folder, b), library.info.favorites)
        organizer.moveFavorite(c, before = null)
        assertEquals(listOf(a, folder, b, c), library.info.favorites)
    }

    /** Only notes trashed longer than the retention period are deleted. */
    @Test
    fun purgesTrashAfterRetention() {
        val old = note("Old") { it.copy(trashed = now().minus(Duration.ofDays(31))) }
        val recent = note("Recent") { it.copy(trashed = now().minus(Duration.ofDays(29))) }
        assertEquals(1, organizer.purgeTrash())
        assertFalse(Files.exists(library.notePath(old)))
        assertTrue(Files.exists(library.notePath(recent)))
    }

    /** Deleting a collection removes its subtree and memberships, and trashes the notes left in none when asked. */
    @Test
    fun deletesCollections() {
        val parent = organizer.createCollection("Work", null)
        val child = organizer.createCollection("Old", parent.id)
        val other = organizer.createCollection("Home", null)
        val only = note("Only") { it.copy(collections = listOf(child.id)) }
        val both = note("Both") { it.copy(collections = listOf(child.id, other.id)) }
        organizer.setFavorite(listOf(FavoriteRef(FavoriteRef.Kind.Collection, child.id)), true)
        assertEquals(Organizer.CollectionDeletion(collections = 1, notes = 2), organizer.collectionDeletion(parent.id))

        organizer.deleteCollection(parent.id, trashOrphans = true)
        assertEquals(listOf(other.id), library.readCollections().map { it.id })
        assertNotNull(meta(only).trashed)
        assertEquals(listOf(other.id), meta(both).collections)
        assertNull(meta(both).trashed)
        assertTrue(library.info.favorites.isEmpty())
    }

    /** A collection moves among its new siblings, never into its own subtree. */
    @Test
    fun movesCollections() {
        val a = organizer.createCollection("A", null)
        val b = organizer.createCollection("B", null)
        val child = organizer.createCollection("Child", a.id)
        organizer.moveCollection(b.id, a.id, before = child.id)
        assertEquals(listOf(a.id to null, b.id to a.id, child.id to a.id), library.readCollections().map { it.id to it.parent })
        val error = assertThrows(FormatException::class.java) { organizer.moveCollection(a.id, child.id, null) }
        assertEquals(ErrorCode.Malformed, error.code)
    }

    /** Renaming a tag renames its children, merges with an existing tag and moves colours, keeping the target's own. */
    @Test
    fun renamesTags() {
        val id = note("A") { it.copy(tags = listOf("work/urgent", "Projects", "work")) }
        organizer.setTagColor("work", "red")
        organizer.setTagColor("work/urgent", "blue")
        organizer.setTagColor("projects", "green")
        assertEquals(1, organizer.renameTag("work", "projects"))
        assertEquals(listOf("projects/urgent", "Projects"), meta(id).tags)
        assertEquals(mapOf("projects" to "green", "projects/urgent" to "blue"), library.info.tagColors)

        assertEquals(1, organizer.deleteTag("projects"))
        assertTrue(meta(id).tags.isEmpty())
        assertTrue(library.info.tagColors.isEmpty())
    }

    /** An unreferenced file goes only after 30 days; one referenced again is kept. */
    @Test
    fun purgesUnreferencedAttachments() {
        val id = note("A")
        val folder = Files.createDirectories(library.attachmentsDir(id))
        library.writeNote(library.readNote(id).copy(body = """<p><img src="../attachments/$id/kept.png"></p>"""))
        index.scan(library)
        folder.resolve("kept.png").writeText("x")
        folder.resolve("gone.png").writeText("x")
        val start = now()
        assertEquals(0, organizer.purgeAttachments(start))
        assertEquals(0, organizer.purgeAttachments(start.plus(Duration.ofDays(29))))
        assertEquals(1, organizer.purgeAttachments(start.plus(Duration.ofDays(31))))
        assertTrue(Files.exists(folder.resolve("kept.png")))
        assertFalse(Files.exists(folder.resolve("gone.png")))
    }

    /** A copy owns duplicates of its files and leaves the source as it was; when it cannot be written, nothing of it stays. */
    @Test
    fun copiesOwnTheirFiles() {
        val source = note("Source")
        library.attachmentsDir(source).let {
            Files.createDirectories(it)
            it.resolve("a.pdf").writeText("file")
        }
        val address = attachmentSource(source, "a.pdf")
        val sourceBytes = Files.readAllBytes(library.notePath(source))
        val time = now()
        val copy = Note(NoteMeta(id = newId(), created = time, updated = time), "<p><a class=\"attachment\" href=\"$address\">a.pdf</a></p>")
        val written = organizer.writeCopy(copy, null)
        assertEquals("<p><a class=\"attachment\" href=\"${attachmentSource(copy.meta.id, "a.pdf")}\">a.pdf</a></p>", written.body)
        assertTrue(Files.exists(library.attachmentsDir(copy.meta.id).resolve("a.pdf")))
        assertTrue(Files.exists(library.attachmentsDir(source).resolve("a.pdf")))
        assertTrue(sourceBytes.contentEquals(Files.readAllBytes(library.notePath(source))))

        val refused = Note(NoteMeta(id = newId(), created = time, updated = time), "<a class=\"attachment\" href=\"$address\">a.pdf</a>")
        assertThrows(FormatException::class.java) { organizer.writeCopy(refused, null) }
        assertFalse(Files.exists(library.attachmentsDir(refused.meta.id)))
        assertFalse(Files.exists(library.notePath(refused.meta.id)))
    }

    /** A note outside the vocabulary can be deleted: its file and folder go, a tombstone follows, and the scan that finds it gone reports the change. */
    @Test
    fun deletesAProblemNote() {
        val time = now()
        val id = newId()
        val file = library.notePath(id)
        Files.write(file, serializeNote(Note(NoteMeta(id = id, created = time, updated = time), "<video></video>")))
        library.attachmentsDir(id).let {
            Files.createDirectories(it)
            it.resolve("a.pdf").writeText("file")
        }
        index.scan(library)
        assertEquals(listOf("$id.html"), index.problems().map { it.fileName })
        assertEquals(1, organizer.deleteNotes(listOf(id)))
        assertFalse(Files.exists(file))
        assertFalse(Files.exists(library.attachmentsDir(id)))
        assertEquals(listOf(id), library.info.tombstones.map { it.id })
        assertEquals(1, index.scan(library).removed)
        assertTrue(index.problems().isEmpty())
    }
}
