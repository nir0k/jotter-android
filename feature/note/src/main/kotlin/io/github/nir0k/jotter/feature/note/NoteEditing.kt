package io.github.nir0k.jotter.feature.note

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.newNote
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.format.parseNote
import io.github.nir0k.jotter.core.format.readFile
import io.github.nir0k.jotter.core.format.serializeNote
import io.github.nir0k.jotter.core.organize.RuleChange
import io.github.nir0k.jotter.editor.NoteEditorController
import io.github.nir0k.jotter.data.library.LibrarySession
import io.github.nir0k.jotter.data.library.NoteDraft
import io.github.nir0k.jotter.data.library.NoteWrite
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** NoteLoad is what the note screen has read of the note's file. */
sealed interface NoteLoad {
    data object Loading : NoteLoad
    data class Loaded(val note: Note) : NoteLoad
    data object Failed : NoteLoad
}

/** The bodies of a note without content: what a new note has and what the editor writes when emptied. */
private val EmptyBodies = setOf("", "<p></p>")

/** How long after typing stops the body is saved. */
private const val BodySaveDelayMs = 1000L

/**
 * NoteEditing holds a note open in the editor and saves it: the body one second after typing
 * stops, the title one second after it was typed, tags at once, and everything when the note is
 * left or the application goes to the background. A value is shown as saved only after its write
 * succeeded, and replaces the file only when it is still the version read. A change of the note's
 * file from outside, such as a sync run, reloads the note in place; with unsaved changes the
 * outside version is kept and the local text becomes a conflict copy (desktop spec section 3.7).
 * Saves run in the application's scope, so leaving the screen never loses the last edit. A failed
 * save keeps the typed text here: leaving goes through [flush], and only the person's explicit
 * [abandon] gives the text up.
 *
 * @param session The open library's session, which writes the files.
 * @param library The open library.
 * @param id The note.
 * @param conflictTitle Returns the title of a conflict copy from the note's title.
 */
class NoteEditing(
    private val session: LibrarySession,
    private val library: Library,
    val id: String,
    private val conflictTitle: (String) -> String,
) {
    private val scope = session.background
    private val writes = Mutex()
    private val path = library.notePath(id)

    /** What has been read of the note; the loaded note is the one last read or written. */
    var load by mutableStateOf<NoteLoad>(NoteLoad.Loading)
        private set

    /** The title as typed. */
    var title by mutableStateOf("")
        private set

    /** The tags as edited. */
    var tags by mutableStateOf(emptyList<String>())
        private set

    /** Whether the last write failed; the text stays and the next change tries again. */
    var saveFailed by mutableStateOf(false)
        private set

    /** Whether the note's file was deleted outside while it was open. */
    var deleted by mutableStateOf(false)
        private set

    private val conflictCopies = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** The identifiers of conflict copies made of local changes. */
    val conflicts: SharedFlow<String> = conflictCopies.asSharedFlow()

    private val rulesFiled = MutableSharedFlow<RuleChange>(extraBufferCapacity = 1)

    /** What the rules changed when a save gave the note other tags, announced with Undo. */
    val filed: SharedFlow<RuleChange> = rulesFiled.asSharedFlow()

    private var controller: NoteEditorController? = null
    private var body = ""
    private var bodyDirty = false
    private var savedBytes: ByteArray? = null
    private var titleSave: Job? = null
    private var bodySave: Job? = null
    private var discarded = false
    private var abandoned = false

    private val saved: Note? get() = (load as? NoteLoad.Loaded)?.note

    /** Whether the note may be edited: not archived, trashed or in a library of a newer format. */
    val editable: Boolean get() = saved?.let { !library.isReadOnly && it.meta.archived == null && it.meta.trashed == null } ?: false

    /** The body the editor shows when it loads. */
    val loadedBody: String get() = body

    /** Whether the note is new: no title and no body yet. */
    val isBlank: Boolean get() = title.isEmpty() && body in EmptyBodies

    private val hasUnsaved: Boolean
        get() = bodyDirty || saved?.let { it.meta.title != title || it.meta.tags != tags || it.body != body } ?: false

    /**
     * Reads the note's file, after the editor this one replaces finished saving. What an editor
     * closed with a failed save kept comes back as unsaved text, based on the version it read.
     */
    suspend fun read() {
        session.awaitEditorClosed(id)
        val bytes = withContext(Dispatchers.IO) { runCatching { readFile(path) }.getOrNull() }
        val note = bytes?.let { runCatching { parseNote(it, path) }.getOrNull() }
        if (note == null) {
            load = NoteLoad.Failed
            return
        }
        show(note, bytes)
        session.takeDraft(id)?.let { draft ->
            title = draft.title
            tags = draft.tags
            body = draft.body
            savedBytes = draft.savedBytes
            saveFailed = true
        }
    }

    /** Takes a version of the note as the saved one and shows it. */
    private fun show(note: Note, bytes: ByteArray) {
        savedBytes = bytes
        title = note.meta.title
        tags = note.meta.tags
        body = note.body
        bodyDirty = false
        load = NoteLoad.Loaded(note)
    }

    /** Starts following the note's editor; from now on this editor saves the note's position. */
    fun attach(controller: NoteEditorController) {
        this.controller = controller
        session.editorOpened(id)
    }

    /**
     * Saves the position found for the note in the background with the note's next write, so it
     * never reaches the editor as an outside change. Runs while the screen shows the note.
     */
    suspend fun followLocation() {
        session.capturedLocation(id).filterNotNull().collect { location ->
            // A failed write keeps the position for the next try, when the screen shows the note again.
            if (saved?.meta?.location != null || changeMeta { it.copy(location = location) } != null) session.locationSaved(id)
        }
    }

    /**
     * Changes the note's metadata at once through the editor's own write, so the change never
     * reaches the editor as an outside change: its position, icon, colour, collections, archive
     * or trash. The title, tags and body are the ones being edited.
     *
     * @return The metadata before the change, for Undo, or null when the write failed.
     */
    suspend fun changeMeta(change: (NoteMeta) -> NoteMeta): NoteMeta? = writes.withLock {
        val base = saved ?: return@withLock null
        if (discarded) return@withLock null
        val changed = base.copy(meta = change(base.meta))
        load = NoteLoad.Loaded(changed)
        if (saveNow(force = true)) return@withLock base.meta
        // An outside version shown meanwhile stays; only the change that failed is taken back.
        if (saved === changed) load = NoteLoad.Loaded(base)
        null
    }

    /** Takes a change of the body in the editor: it is saved one second after typing stops. */
    fun bodyChanged() {
        bodyDirty = true
        bodySave?.cancel()
        bodySave = scope.launch(Dispatchers.Main) {
            delay(BodySaveDelayMs)
            takeBody()
            writes.withLock { saveNow() }
        }
    }

    /** Takes the editor's body when it changed since it was last taken; call on the main thread. */
    private fun takeBody() {
        val editor = controller ?: return
        if (!bodyDirty && !editor.dirty) return
        body = editor.body()
        bodyDirty = false
    }

    /** Changes the title; it is saved one second after typing stops. */
    fun changeTitle(value: String) {
        title = value
        titleSave?.cancel()
        titleSave = scope.launch(Dispatchers.Main) {
            delay(1000)
            writes.withLock { saveNow() }
        }
    }

    /** Changes the tags; they are saved at once. */
    fun changeTags(value: List<String>) {
        tags = value
        save()
    }

    /** Saves what changed. */
    private fun save() {
        scope.launch(Dispatchers.Main) { writes.withLock { saveNow() } }
    }

    /**
     * Writes the note when its title, tags or body changed, with the time of the change. The rules
     * file the note when its tags changed; the editor keeps the note as written, so its next save
     * keeps the collections they chose.
     *
     * @param force Writes it even unchanged, as a change of its metadata needs.
     * @param overwrite Replaces whatever the file holds, to keep a note deleted outside; otherwise
     *   a file that is not the version read is handled as an outside change instead.
     * @return Whether this write reached the file; a note given up by [abandon] counts as saved.
     */
    private suspend fun saveNow(force: Boolean = false, overwrite: Boolean = false): Boolean {
        if (abandoned) return true
        val base = saved ?: return true
        if (!force && base.meta.title == title && base.meta.tags == tags && base.body == body) return true
        val note = Note(base.meta.copy(title = title, tags = tags, updated = now()), body)
        val before = savedBytes
        // The library's scan after the write must not take it for an outside change.
        savedBytes = serializeNote(note)
        when (val result = session.writeEditedNote(note, base.meta, before.takeUnless { overwrite })) {
            is NoteWrite.Written -> {
                val written = result.note
                if (written != note) {
                    savedBytes = serializeNote(written)
                    RuleChange.of(note.meta, written.meta)?.let(rulesFiled::tryEmit)
                }
                load = NoteLoad.Loaded(written)
                saveFailed = false
                deleted = false
                // Links that still show the old title follow the new one.
                if (base.meta.title != title) scope.launch { session.renameLinksTo(id, base.meta.title, title) }
                return true
            }
            NoteWrite.Changed -> {
                savedBytes = before
                reconcile()
                saveFailed = hasUnsaved && !deleted
                return false
            }
            NoteWrite.Failed -> {
                savedBytes = before
                saveFailed = true
                return false
            }
        }
    }

    /** Takes the editor's last changes, which are in this process, so this object holds all the text. */
    private fun flushEditor() {
        bodySave?.cancel()
        takeBody()
    }

    /**
     * Saves everything now: the editor's last changes and a title still being typed.
     *
     * @return Whether nothing typed is left unsaved; otherwise the text stays here and the screen
     *   warns of it. A note that went to a conflict copy counts as saved.
     */
    suspend fun flush(): Boolean {
        if (abandoned) return true
        flushEditor()
        titleSave?.cancel()
        return writes.withLock {
            saveNow()
            !hasUnsaved
        }
    }

    /**
     * Gives up what could not be saved, after the person confirmed leaving without saving. Nothing
     * of this editor is written after this.
     */
    fun abandon() {
        abandoned = true
        titleSave?.cancel()
        bodySave?.cancel()
        session.takeDraft(id)
    }

    /** Saves the note after a failed write. */
    fun retry() {
        save()
    }

    /** Writes the note again after its file was deleted outside, as Keep does on the desktop. */
    fun keep() {
        scope.launch(Dispatchers.Main) { writes.withLock { saveNow(force = true, overwrite = true) } }
    }

    /**
     * Follows a change of the library's files: a note file that is not what the application last
     * read or wrote changed outside. Without unsaved changes it reloads in place; with them the
     * outside version is kept and the local text is saved as a conflict copy in the same
     * collections and owning its attachments. When the copy cannot be written, the outside version
     * is not shown and the text stays here, to be tried again. A deleted file is reported.
     */
    suspend fun followOutsideChanges() {
        if (saved == null || abandoned) return
        // Under the lock no save runs, so the file read is either the last write or a change from outside.
        writes.withLock { reconcile() }
    }

    /** Compares the note's file with the version read and follows a change; the caller holds the lock. */
    private suspend fun reconcile() {
        val bytes = withContext(Dispatchers.IO) {
            try {
                readFile(path)
            } catch (_: FormatException) {
                null
            }
        }
        deleted = bytes == null && withContext(Dispatchers.IO) { !Files.exists(path, LinkOption.NOFOLLOW_LINKS) }
        if (bytes == null || bytes.contentEquals(savedBytes)) return
        val outside = try {
            parseNote(bytes, path)
        } catch (_: FormatException) {
            return
        }
        // The editor's last changes are taken now.
        flushEditor()
        val base = saved ?: return
        if (hasUnsaved) {
            val fresh = newNote()
            val copy = Note(fresh.meta.copy(title = conflictTitle(title), collections = base.meta.collections, tags = tags), body)
            if (session.writeConflictCopy(copy) == null) {
                saveFailed = true
                return
            }
            conflictCopies.tryEmit(copy.meta.id)
        }
        saveFailed = false
        val editableBefore = editable
        show(outside, bytes)
        // A note archived or trashed outside is drawn anew by the screen; otherwise the editor reloads in place.
        if (editable == editableBefore) controller?.reload(outside.body)
    }

    /**
     * Saves the note as its editor goes, then runs [done], which closes the editor. What cannot be
     * saved stays as a draft for the note's next editor, unless the person gave it up. A note left
     * without title, body or attachments is discarded.
     *
     * @param discardEmpty Whether an empty note is discarded; not while the screen is only
     *   recreated, as on rotation.
     */
    fun close(discardEmpty: Boolean, done: () -> Unit) {
        titleSave?.cancel()
        scope.launch(Dispatchers.Main) {
            val clean = flush()
            if (!clean && !abandoned) session.keepDraft(id, NoteDraft(title, tags, body, savedBytes))
            controller = null
            session.editorClosed(id)
            done()
            if (clean && !abandoned && discardEmpty && editable && !deleted && title.isBlank() && body in EmptyBodies && !hasAttachments()) {
                // A position found later does not bring the note back.
                discarded = true
                session.deleteNote(id)
            }
        }
    }

    /** Tells whether the note's attachment folder holds files; one that cannot be read may hold some. */
    private suspend fun hasAttachments(): Boolean = withContext(Dispatchers.IO) {
        val folder = library.attachmentsDir(id).toFile()
        folder.exists() && folder.list()?.isNotEmpty() != false
    }
}
