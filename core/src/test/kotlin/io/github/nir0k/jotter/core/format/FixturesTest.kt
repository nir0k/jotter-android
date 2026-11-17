package io.github.nir0k.jotter.core.format

import io.github.nir0k.jotter.core.index.Index
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.walk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the library fixtures (`fixtures/README.md`), written as the desktop writes them: every file
 * reads and writes back byte for byte, attachments are where the notes refer to them, and damaged
 * files give the problems they are listed with.
 */
@OptIn(ExperimentalPathApi::class)
class FixturesTest {
    private val fixtures: Path = Paths.get(javaClass.getResource("/fixtures")!!.toURI())

    /** Every library file and note of the readable libraries writes back exactly as the desktop wrote it. */
    @Test
    fun filesWriteBackUnchanged() {
        val files = listOf("library", "problems").flatMap { library ->
            listOf("library.json", "collections.json", "rules.json").map { fixtures.resolve(library).resolve(it) }
        } + fixtures.resolve("library/notes").listDirectoryEntries("*.html").sortedBy { it.name }
        for (file in files) {
            val data = Files.readAllBytes(file)
            val written = when (file.name) {
                "library.json" -> serializeLibraryInfo(parseLibraryInfo(data, file.toString()))
                "collections.json" -> serializeCollections(parseCollections(data, file.toString()))
                "rules.json" -> serializeRules(parseRules(data, file.toString()))
                else -> serializeNote(parseNote(data, file))
            }
            assertEquals(file.toString(), String(data, Charsets.UTF_8), String(written, Charsets.UTF_8))
        }
    }

    /**
     * Every image and attachment a note shows is in its folder under the address the desktop
     * writes for it, images are of an accepted kind by their bytes, and no attachment file is unused.
     */
    @Test
    fun attachmentsMatchTheirReferences() {
        val library = Library.open(fixtures.resolve("library"))
        val referenced = mutableSetOf<Path>()
        for (file in library.noteFiles()) {
            val note = library.readNote(file.name.substringBeforeLast('.'))
            for (ref in bodyAttachments(note.body)) {
                assertEquals(note.meta.id, ref.noteId)
                assertTrue(note.body.contains(attachmentSource(ref.noteId, ref.fileName)))
                val path = library.attachmentsDir(ref.noteId).resolve(ref.fileName)
                assertEquals(path.toString(), ref.image, imageKind(Files.readAllBytes(path)) != ImageKind.None)
                referenced.add(path)
            }
        }
        val stored = fixtures.resolve("library/attachments").walk().filter { it.isRegularFile() }.toSet()
        assertEquals(stored, referenced)
    }

    /** The damaged notes are listed with the codes `expected.json` gives; other files are not notes. */
    @Test
    fun problemsAreTheExpectedOnes() {
        val dir = createTempDirectory()
        try {
            val root = dir.resolve("problems")
            fixtures.resolve("problems").copyToRecursively(root, followLinks = false, overwrite = false)
            Index.open(dir.resolve("index.sqlite"), compareBy { it }).use { index ->
                index.scan(Library.open(root))
                val found = index.problems().associate {
                    it.fileName to if (it.code == ErrorCode.NewerFormat) "newerFormat" else it.code.name.lowercase()
                }
                val expected = parseJsonObject(Files.readAllBytes(fixtures.resolve("problems/expected.json")), "expected.json")
                assertEquals(expected["problems"].qtObject().mapValues { it.value.qtString() }, found)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A library of a newer format opens read-only and refuses writes. */
    @Test
    fun newerLibraryIsReadOnly() {
        val library = Library.open(fixtures.resolve("newer-library"))
        assertTrue(library.isReadOnly)
        val error = runCatching { library.changeRules { emptyList() } }.exceptionOrNull() as FormatException
        assertEquals(ErrorCode.ReadOnly, error.code)
        assertFalse(Files.exists(fixtures.resolve("newer-library/rules.json")))
    }

    /** A note with valid metadata and a body outside the vocabulary is a problem, stays unchanged by a scan and reads only for normalisation. */
    @Test
    fun unsupportedBodyStaysUnchanged() {
        val file = fixtures.resolve("problems/notes/01a1a316-0000-7000-8000-000000000001.html")
        val data = Files.readAllBytes(file)
        assertEquals(ErrorCode.Malformed, (runCatching { parseNote(data, file) }.exceptionOrNull() as FormatException).code)
        assertTrue(parseNoteForNormalization(data, file).body.contains("<video"))
    }
}
