package io.github.nir0k.jotter.core.format

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks exports without drawing: file names, self-contained HTML and packing. */
@OptIn(ExperimentalPathApi::class)
class ExportTest {
    private val folder: Path = createTempDirectory()
    private val library = Library.create(folder.resolve("library"))

    /** The smallest PNG head the kind check accepts. */
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @After
    fun cleanup() {
        folder.deleteRecursively()
    }

    /** Returns a new note with a title and a body. */
    private fun note(title: String, body: String): Note = newNote().let { it.copy(meta = it.meta.copy(title = title), body = body) }

    /** Returns the text of an exported document. */
    private fun ExportFile.text() = String(data!!, Charsets.UTF_8)

    /** Stores a file in a note's attachment folder. */
    private fun attach(noteId: String, name: String, data: ByteArray) {
        val dir = library.attachmentsDir(noteId)
        Files.createDirectories(dir)
        Files.write(dir.resolve(name), data)
    }

    /** Returns the names of an archive's files. */
    private fun entries(path: Path): List<String> = ZipFile(path.toFile()).use { zip -> zip.entries().toList().map { it.name } }

    /** Refused characters become `_`, edges are trimmed, long names are cut with surrogate pairs whole, and nothing usable is Untitled. */
    @Test
    fun baseNames() {
        assertEquals("a_b_ c_", exportBaseName(" a/b: c?. ", "Untitled"))
        assertEquals("Untitled", exportBaseName(" . ", "Untitled"))
        val cut = exportBaseName("x".repeat(99) + "😀" + "y", "Untitled")
        assertEquals("x".repeat(99), cut)
        assertEquals(100, exportBaseName("z".repeat(150), "Untitled").length)
    }

    /** Repeated names are numbered without regard to case. */
    @Test
    fun repeatedNamesAreNumbered() {
        val names = ExportNames()
        assertEquals(listOf("Plan", "plan (2)", "PLAN (3)", "Other"), listOf("Plan", "plan", "PLAN", "Other").map(names::take))
    }

    /** HTML embeds images, puts attachment files in `<name>_files/`, points note links to the files of the same export and makes other note links plain text. */
    @Test
    fun htmlIsSelfContained() {
        val other = note("Budget", "<p>Numbers</p>")
        val gone = newId()
        val trip = newNote()
        attach(trip.meta.id, "pic.png", png)
        attach(trip.meta.id, "plan #1.txt", "plan".toByteArray())
        val body = "<p><img src=\"${attachmentSource(trip.meta.id, "pic.png")}\"></p>" +
            "<p><a class=\"attachment\" href=\"${attachmentSource(trip.meta.id, "plan #1.txt")}\" data-size=\"4\">plan #1.txt</a></p>" +
            "<p><a class=\"note-link\" href=\"${noteLinkAddress(other.meta.id)}\">Budget</a> and " +
            "<a class=\"note-link\" href=\"${noteLinkAddress(gone)}\">Gone</a></p>"
        val exported = mapOf(trip.meta.id to "Trip", other.meta.id to "Budget")
        val files = exportHtml(library, trip.copy(meta = trip.meta.copy(title = "Trip"), body = body), "Trip", exported, "Updated today")
        assertEquals(listOf("Trip.html", "Trip_files/plan #1.txt"), files.map { it.path })
        assertEquals(library.attachmentsDir(trip.meta.id).resolve("plan #1.txt"), files[1].source)
        val html = files[0].text()
        assertTrue(html.contains("src=\"data:image/png;base64,"))
        assertTrue(html.contains("href=\"Trip_files/plan%20%231.txt\""))
        assertTrue(html.contains("<a class=\"note-link\" href=\"Budget.html\">Budget</a>"))
        assertTrue(html.contains("and Gone</p>"))
        assertTrue(html.contains("<p>Updated today</p>"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("../attachments"))
    }

    /** A note file changed outside Jotter brings no scripts, event handlers, remote images or links with other schemes into an export; unknown elements keep their text. */
    @Test
    fun htmlKeepsOnlyTheVocabulary() {
        val source = note(
            "Note",
            "<p onclick=\"x()\">Hi<script>alert(1)</script><style>p{}</style> <img src=\"https://example.com/t.png\" onerror=\"x()\"> " +
                "<a href=\"javascript:x()\">bad</a> <a href=\"file:///etc/passwd\">file</a> <a href=\"https://example.com/\">web</a> " +
                "<blink>old</blink> <span style=\"background: url(https://example.com/t)\">styled</span></p>",
        )
        val html = exportHtml(library, source, "Note", mapOf(source.meta.id to "Note"), "")[0].text()
        val start = html.indexOf("<p>Hi")
        assertEquals(
            "<p>Hi  bad file <a href=\"https://example.com/\">web</a> old <span>styled</span></p>",
            html.substring(start, html.indexOf("</p>", start) + 4),
        )
    }

    /** A missing image becomes its file name, as the editor shows it. */
    @Test
    fun missingImageKeepsItsName() {
        val source = newNote().let { it.copy(body = "<p><img src=\"${attachmentSource(it.meta.id, "gone.png")}\"></p>") }
        assertTrue(exportHtml(library, source, "Note", emptyMap(), "")[0].text().contains("<p>gone.png</p>"))
    }

    /** One note that made one file stays that file, unpacked. */
    @Test
    fun oneFileIsNotPacked() {
        val target = folder.resolve("out")
        val pack = ExportPackage(target, several = false)
        pack.add(listOf(ExportFile("Note.jpg", byteArrayOf(1, 2))))
        assertEquals("Note.jpg", pack.finish("Note"))
        assertArrayEquals(byteArrayOf(1, 2), Files.readAllBytes(target))
    }

    /** One note that made several files is an archive named after it, and a library file that has gone is left out. */
    @Test
    fun oneNoteWithSeveralFilesIsPacked() {
        val source = folder.resolve("kept.txt").also { Files.write(it, "kept".toByteArray()) }
        val target = folder.resolve("out")
        val pack = ExportPackage(target, several = false)
        pack.add(listOf(ExportFile("Note.html", "<p>".toByteArray()), ExportFile("Note_files/kept.txt", source = source), ExportFile("Note_files/gone.txt", source = folder.resolve("gone.txt"))))
        assertEquals("Note.zip", pack.finish("Note"))
        assertEquals(listOf("Note.html", "Note_files/kept.txt"), entries(target))
    }

    /** Several notes are one archive under the collective name, also when each made one file. */
    @Test
    fun severalNotesArePacked() {
        val target = folder.resolve("out")
        val pack = ExportPackage(target, several = true)
        pack.add(listOf(ExportFile("Plan.jpg", byteArrayOf(1))))
        pack.add(listOf(ExportFile("plan (2).jpg", byteArrayOf(2))))
        assertEquals("Notes.zip", pack.finish("Notes"))
        assertEquals(listOf("Plan.jpg", "plan (2).jpg"), entries(target))
    }

    /** Parts start at block boundaries, and a block taller than a part is cut. */
    @Test
    fun partsStartAtBlocks() {
        assertEquals(listOf(0.0), exportPartStarts(listOf(ExportBlock(0.0, 50.0), ExportBlock(50.0, 100.0)), 100.0))
        assertEquals(listOf(0.0, 60.0), exportPartStarts(listOf(ExportBlock(0.0, 60.0), ExportBlock(60.0, 120.0)), 100.0))
        assertEquals(listOf(0.0, 10.0, 110.0, 210.0), exportPartStarts(listOf(ExportBlock(0.0, 10.0), ExportBlock(10.0, 250.0)), 100.0))
    }
}
