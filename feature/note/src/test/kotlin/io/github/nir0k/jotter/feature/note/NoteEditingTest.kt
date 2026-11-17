package io.github.nir0k.jotter.feature.note

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.format.serializeNote
import io.github.nir0k.jotter.core.format.writeFileAtomic
import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.data.library.LibraryState
import io.github.nir0k.jotter.data.settings.DevicePreferences
import java.io.File
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Checks how an open note follows its file (desktop spec section 3.7) and what leaving it does: an
 * outside change reloads the note, or with unsaved text keeps the outside version and the text as a
 * conflict copy; an empty note is discarded, one with files kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NoteEditingTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private lateinit var session: LibrarySession
    private lateinit var library: Library

    @Before
    fun openLibrary() {
        val context = RuntimeEnvironment.getApplication()
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "datastore/test.preferences_pb") }
        session = LibrarySession(context, DevicePreferences(store), scope)
        session.create()
        until("the library opens") { session.current.value is LibraryState.Open }
        library = (session.current.value as LibraryState.Open).library
    }

    @After
    fun cleanup() {
        scope.cancel()
    }

    /** Runs the main looper, where the editor's saves run, until a condition holds. */
    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting until $what" }
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
    }

    /** Creates a note with a title and opens it in an editor of its own. */
    private fun open(title: String = ""): NoteEditing {
        val id = runBlocking { session.createNote(emptyList(), emptyList(), title) }!!.id
        return NoteEditing(session, library, id) { "$it (conflict)" }.also { runBlocking { it.read() } }
    }

    /** Replaces the note's file as a sync run would, with another title. */
    private fun changeOutside(editing: NoteEditing, title: String) {
        val note = library.readNote(editing.id)
        writeFileAtomic(library.notePath(editing.id), serializeNote(Note(note.meta.copy(title = title, updated = now()), note.body)))
    }

    private fun titles(): List<String> = library.notesDir.listDirectoryEntries("*.html").map { library.readNote(it.fileName.toString().removeSuffix(".html")).meta.title }.sorted()

    /** Without unsaved text, an outside change reloads the note in place and makes no copy. */
    @Test
    fun outsideChangeReloadsANoteWithoutUnsavedText() {
        val editing = open("Plan")
        changeOutside(editing, "Plan from the desktop")
        runBlocking { editing.followOutsideChanges() }
        assertEquals("Plan from the desktop", editing.title)
        assertEquals(listOf("Plan from the desktop"), titles())
    }

    /** With unsaved text, the outside version is shown and the typed text becomes a conflict copy. */
    @Test
    fun outsideChangeKeepsUnsavedTextAsAConflictCopy() {
        val editing = open("Plan")
        // The title is saved a second after typing; the looper is not run, so it stays unsaved.
        editing.changeTitle("Plan typed here")
        changeOutside(editing, "Plan from the desktop")
        runBlocking { editing.followOutsideChanges() }
        assertEquals("Plan from the desktop", editing.title)
        assertEquals(listOf("Plan from the desktop", "Plan typed here (conflict)"), titles())
    }

    /** Leaving a note without title, body or files discards it, leaving a tombstone for other devices. */
    @Test
    fun leavingAnEmptyNoteDiscardsIt() {
        val editing = open()
        editing.close(discardEmpty = true) {}
        until("the note is discarded") { !Files.exists(library.notePath(editing.id)) }
        assertTrue(Library.open(library.root).info.tombstones.any { it.id == editing.id })
    }

    /** A note without title or body that holds a file is kept when left. */
    @Test
    fun leavingANoteWithAFileKeepsIt() {
        val editing = open()
        Files.createDirectories(library.attachmentsDir(editing.id))
        library.attachmentsDir(editing.id).resolve("scan.pdf").writeText("%PDF-1.7")
        editing.close(discardEmpty = true) {}
        until("leaving the note ends") { job.children.none { it.isActive } }
        assertTrue(Files.exists(library.notePath(editing.id)))
        assertFalse(Library.open(library.root).info.tombstones.any { it.id == editing.id })
    }
}
