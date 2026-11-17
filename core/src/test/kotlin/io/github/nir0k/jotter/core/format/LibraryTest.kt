package io.github.nir0k.jotter.core.format

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks creating and opening libraries and the atomic writes of their files. */
@OptIn(ExperimentalPathApi::class)
class LibraryTest {
    private val dir: Path = createTempDirectory()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** A created library has the desktop's layout and opens again with the same identity. */
    @Test
    fun createAndOpen() {
        val root = dir.resolve("Jotter")
        val created = Library.create(root)
        for (name in listOf("library.json", "collections.json", "rules.json", "notes", "attachments")) {
            assertTrue(name, Files.exists(root.resolve(name)))
        }
        val opened = Library.open(root)
        assertEquals(created.info, opened.info)
        assertFalse(opened.isReadOnly)
        assertEquals(30, opened.info.trashRetentionDays)
        assertTrue(opened.readCollections().isEmpty())
        assertTrue(opened.readRules().isEmpty())
    }

    /** A new library is never created over existing files. */
    @Test
    fun createRefusesNonEmptyFolder() {
        dir.resolve("notes.txt").writeText("")
        val error = assertThrows(FormatException::class.java) { Library.create(dir) }
        assertEquals(ErrorCode.NotEmpty, error.code)
    }

    /** Rewriting a note replaces the file and leaves no temporary file behind. */
    @Test
    fun writesAreAtomic() {
        val library = Library.create(dir.resolve("Jotter"))
        val note = Note(NoteMeta(id = newId(), title = "Plan", created = now(), updated = now()), "<p>one</p>")
        library.writeNote(note)
        library.writeNote(note.copy(body = "<p>two</p>"))
        assertEquals(listOf("${note.meta.id}.html"), library.notesDir.listDirectoryEntries().map { it.name })
        assertEquals("<p>two</p>", library.readNote(note.meta.id).body)
    }

    /** A tag without a colour of its own takes its nearest coloured parent's, comparing case-folded. */
    @Test
    fun tagsInheritColours() {
        val colors = mapOf("work" to "sky", "work/urgent" to "red")
        assertEquals("red", tagColor(colors, "Work/Urgent/Today"))
        assertEquals("sky", tagColor(colors, "WORK/later"))
        assertEquals(null, tagColor(colors, "workshop"))
    }

    /** A present value of the wrong type is malformed in every library file, never an empty list or default settings. */
    @Test
    fun wrongTypesAreMalformed() {
        val id = newId()
        val time = "2026-11-04T00:00:00.000Z"
        val info = """"format": 1, "id": "$id", "created": "$time""""
        for (text in listOf(
            "{$info, \"favorites\": 1}",
            "{$info, \"tombstones\": {}}",
            "{$info, \"tagColors\": []}",
            "{$info, \"settings\": \"x\"}",
        )) {
            assertEquals(text, ErrorCode.Malformed, assertThrows(FormatException::class.java) { parseLibraryInfo(text.toByteArray(), "library.json") }.code)
        }
        for (text in listOf("{}", "{\"collections\": []}", "{\"format\": 0, \"collections\": []}", "{\"format\": \"1\", \"collections\": []}", "{\"format\": 1}", "{\"format\": 1, \"collections\": {}}")) {
            assertEquals(text, ErrorCode.Malformed, assertThrows(FormatException::class.java) { parseCollections(text.toByteArray(), "collections.json") }.code)
        }
        val rule = """"id": "$id", "match": "all", "created": "$time", "updated": "$time""""
        val action = """"action": {"kind": "add", "collections": ["$id"]}"""
        for (text in listOf(
            "{\"format\": 1}",
            "{\"format\": 1, \"rules\": 1}",
            "{\"format\": 1, \"rules\": [{$rule, $action}]}",
            "{\"format\": 1, \"rules\": [{$rule, \"conditions\": [], \"action\": 1}]}",
            "{\"format\": 1, \"rules\": [{$rule, \"conditions\": [], \"action\": {\"kind\": \"add\", \"collections\": [\"x\"]}}]}",
            "{\"format\": 1, \"rules\": [{$rule, \"conditions\": [], $action, \"enabled\": 1}]}",
        )) {
            assertEquals(text, ErrorCode.Malformed, assertThrows(FormatException::class.java) { parseRules(text.toByteArray(), "rules.json") }.code)
        }
    }

    /** A change of a library file starts from the file on disk, so what a sync run wrote meanwhile stays. */
    @Test
    fun changesKeepWhatChangedOnDisk() {
        val root = dir.resolve("Jotter")
        val library = Library.create(root)
        val tombstone = Tombstone(newId(), now())
        val time = now()
        val outside = Collection(newId(), null, "From sync", created = time, updated = time)
        Library.open(root).apply {
            changeInfo { it.copy(tombstones = listOf(tombstone), tagColors = mapOf("work" to "red")) }
            changeCollections { it + outside }
        }
        library.changeInfo { it.copy(favorites = listOf(FavoriteRef(FavoriteRef.Kind.Note, tombstone.id))) }
        library.changeCollections { it + outside.copy(id = newId(), name = "Mine") }
        val reopened = Library.open(root)
        assertEquals(listOf(tombstone), reopened.info.tombstones)
        assertEquals(mapOf("work" to "red"), reopened.info.tagColors)
        assertEquals(1, reopened.info.favorites.size)
        assertEquals(library.info, reopened.info)
        assertEquals(listOf("From sync", "Mine"), reopened.readCollections().map { it.name })
    }

    /** A note is replaced only when it is the version the save started from, and never with a body outside the vocabulary. */
    @Test
    fun noteWrites() {
        val library = Library.create(dir.resolve("Jotter"))
        val note = newNote().copy(body = "<p>one</p>")
        library.writeNote(note)
        val path = library.notePath(note.meta.id)
        val read = Files.readAllBytes(path)
        library.writeNote(note.copy(body = "<p>two</p>"), read)
        val second = Files.readAllBytes(path)
        assertEquals(ErrorCode.Deferred, assertThrows(FormatException::class.java) { library.writeNote(note.copy(body = "<p>three</p>"), read) }.code)
        assertTrue(second.contentEquals(Files.readAllBytes(path)))
        assertEquals(ErrorCode.Malformed, assertThrows(FormatException::class.java) { library.writeNote(note.copy(body = "<video></video>")) }.code)
        assertTrue(second.contentEquals(Files.readAllBytes(path)))
        Files.delete(path)
        assertEquals(ErrorCode.Deferred, assertThrows(FormatException::class.java) { library.writeNote(note, read) }.code)
        assertFalse(Files.exists(path))
    }

    /** Symbolic links are neither read through nor listed through, and a folder that cannot be listed is an error, not an empty folder. */
    @Test
    fun linksAndFailedListings() {
        val outside = dir.resolve("outside").also(Files::createDirectories)
        val secret = outside.resolve("secret").also { it.writeText("x") }
        val link = dir.resolve("link")
        Files.createSymbolicLink(link, secret)
        assertEquals(ErrorCode.Io, assertThrows(FormatException::class.java) { readFile(link) }.code)
        val folderLink = dir.resolve("folder-link")
        Files.createSymbolicLink(folderLink, outside)
        assertEquals(ErrorCode.Io, assertThrows(FormatException::class.java) { listDirectory(folderLink) }.code)
        assertEquals(ErrorCode.Io, assertThrows(FormatException::class.java) { listDirectory(secret) }.code)
        assertEquals(ErrorCode.NotFound, assertThrows(FormatException::class.java) { listDirectory(dir.resolve("missing")) }.code)
        removePath(folderLink)
        assertTrue(Files.exists(secret))

        val library = Library.create(dir.resolve("Jotter"))
        val note = library.notePath(newId())
        Files.createSymbolicLink(note, secret)
        assertEquals(listOf(note), library.noteFiles())
        assertEquals(ErrorCode.Io, assertThrows(FormatException::class.java) { library.readNote(note.name.removeSuffix(".html")) }.code)
    }
}
