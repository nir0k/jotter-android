package io.github.nir0k.jotter.data.library

import android.content.Context
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import io.github.nir0k.jotter.data.export.NoteExporter
import io.github.nir0k.jotter.core.body.normalizedBody
import io.github.nir0k.jotter.core.format.ContentsText
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.tableOfContents
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.merge
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.GeoLocation
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.format.newNote
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.format.parseNoteForNormalization
import io.github.nir0k.jotter.core.format.readFile
import io.github.nir0k.jotter.core.format.removePath
import io.github.nir0k.jotter.core.format.renameNoteLinks
import io.github.nir0k.jotter.core.format.removeStoreLeftovers
import io.github.nir0k.jotter.core.index.Index
import io.github.nir0k.jotter.core.sftp.SftpConnection
import io.github.nir0k.jotter.core.organize.Organizer
import io.github.nir0k.jotter.core.organize.RuleChange
import io.github.nir0k.jotter.data.settings.DevicePreferences
import io.github.nir0k.jotter.data.settings.inAppLanguage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.github.nir0k.jotter.data.R

/** LibraryState is what the application can show of the library of this device. */
sealed interface LibraryState {
    /** The stored choice is being read or the library is being opened. */
    data object Opening : LibraryState

    /**
     * No library yet: the first start.
     *
     * @param creating Whether a library is being created.
     * @param failed Whether the last attempt to create one failed.
     */
    data class None(val creating: Boolean = false, val failed: Boolean = false) : LibraryState

    /**
     * The open library with its index.
     *
     * @param revision Grows with every scan that changed the index, so screens read it again.
     */
    data class Open(val library: Library, val index: Index, val revision: Long) : LibraryState

    /** The stored library exists but could not be opened; Retry tries again. */
    data object Failed : LibraryState
}

/**
 * LibraryEntry is a library of this device as the screens of Settings list it.
 *
 * @param id The library's folder, named after its identifier.
 * @param name The name of its remote folder, or null for a library that exists only on this phone,
 *   as the library format gives a library no name and its local folder is only an identifier.
 * @param changed When a note of it last changed, or when it was created while it has no notes.
 * @param open Whether it is the open library.
 */
data class LibraryEntry(val id: String, val name: String?, val changed: Instant, val open: Boolean)

/**
 * Names a connected library after its remote folder: the last part of the path. The home folder,
 * given as an empty path or by a path without a name of its own, is named after the server.
 */
fun libraryName(connection: SftpConnection): String =
    connection.folder.trimEnd('/').substringAfterLast('/').takeUnless { it.isEmpty() || it == "." || it == ".." || it == "~" } ?: connection.host

/**
 * CreatedNote is a note just created, with what its screen announces: what the rules did with it
 * and the shared files that could not be added.
 *
 * @param failed The names of the files left out, with why.
 */
data class CreatedNote(val id: String, val filed: RuleChange?, val failed: List<Pair<String, ErrorCode>> = emptyList())

/** What became of a note its editor tried to write. */
sealed interface NoteWrite {
    /** The note was written, as the rules left it. */
    data class Written(val note: Note) : NoteWrite

    /** The file is not the version the editor read, so it was left as it is. */
    data object Changed : NoteWrite

    /** The note could not be written. */
    data object Failed : NoteWrite
}

/**
 * NoteDraft is what an editor closed with a failed save holds, kept for as long as the application
 * runs, so the note's next editor, such as after a rotation, shows the typed text and saves it.
 *
 * @param savedBytes The file's contents the text is based on.
 */
class NoteDraft(val title: String, val tags: List<String>, val body: String, val savedBytes: ByteArray?)

/** The table of contents is written this long after the last change (desktop spec section 3.6). */
private const val ContentsDelayMs = 3000L

/** How long a new editor of a note waits for the one it replaces to finish saving. */
private const val EditorCloseWaitMs = 3000L

/** The prefixes of the temporary folders of a library being created or downloaded. */
private const val NewPrefix = ".new-"
private const val DownloadPrefix = ".download-"

/**
 * LibrarySession owns the library open on this device and its index: libraries live in
 * `files/libraries/<library-id>/`, indexes in `no_backup/index/<library-id>.sqlite`. All file
 * work runs on the IO dispatcher; the state flow tells the screens what to show.
 *
 * @param context The application context, for the private folders.
 * @param preferences The device preferences that remember the open library.
 * @param scope The application's scope, in which opening, creating and scanning run.
 */
class LibrarySession(
    private val context: Context,
    private val preferences: DevicePreferences,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<LibraryState>(LibraryState.Opening)

    /** What the application shows of the library. */
    val current: StateFlow<LibraryState> = state.asStateFlow()

    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Emits the identifier of a library after the application changed one of its files, so
     * synchronisation follows.
     */
    val localChanges: SharedFlow<String> = changes.asSharedFlow()

    // Changes of the interface language, which the table of contents is written in.
    private val languageChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Tells that the interface language may have changed, so the table of contents follows it. */
    fun languageChanged() {
        languageChanges.tryEmit(Unit)
    }

    private val filings = MutableSharedFlow<List<RuleChange>>(extraBufferCapacity = 1)

    /**
     * Emits what the rules changed while notes were organised, such as tags given to selected
     * notes or a tag renamed, for the message with Undo.
     */
    val ruleChanges: SharedFlow<List<RuleChange>> = filings.asSharedFlow()

    // Opening, creating and scanning never overlap, so a scan never reads a half-created library.
    private val work = Mutex()

    private val librariesDir get() = File(context.filesDir, "libraries")

    /** The application's scope, for work that must finish after a screen is gone, such as saving a note being left. */
    val background: CoroutineScope get() = scope

    /** Exports notes in the background, so that an export outlives its dialog's screen. */
    val exporter = NoteExporter(context, scope)

    /** Returns the folder of a library of this device. */
    fun libraryFolder(id: String): File = File(librariesDir, id)

    /**
     * Returns a new folder among the libraries for a download, which becomes a library by [adopt]
     * once it is complete; its name starts with a dot, so it is never listed as a library.
     */
    fun downloadFolder(): File = File(librariesDir, "$DownloadPrefix${System.nanoTime()}")

    /**
     * Opens the library remembered by the device preferences, or shows the first start, as the
     * application starts. The temporary folders of a creation or download the system stopped half
     * way are removed first: nothing else would, and a half-downloaded library may be large. So
     * are the temporary files of attachments whose insertion was stopped and of an export.
     */
    fun start() {
        scope.launch(Dispatchers.IO) {
            work.withLock {
                val (leftovers, libraries) = librariesDir.listFiles().orEmpty().partition { it.name.startsWith(NewPrefix) || it.name.startsWith(DownloadPrefix) }
                leftovers.forEach { runCatching { removePath(it.toPath()) } }
                libraries.forEach { removeStoreLeftovers(it.toPath()) }
                exporter.removeFiles()
                openStored()
            }
            maintain()
        }
        scope.launch { followContents() }
    }

    /**
     * Keeps the open library's table of contents, `index.html`, up to date (desktop spec section
     * 3.6): it is built three seconds after the last change of the notes (a scan, a sync run or a
     * write of this application, collections included) or of the interface language, and written
     * only when it changed, never in a read-only library. Writing it starts no sync run: the file
     * goes to the sync storage with the next one.
     */
    private suspend fun followContents() {
        val libraries = state.filterIsInstance<LibraryState.Open>().map { it.library.root to it.revision }.distinctUntilChanged()
        merge(libraries.map { }, languageChanges, changes.map { }).collectLatest {
            delay(ContentsDelayMs)
            writeContents()
        }
    }

    /** Builds and writes the table of contents of the open library in the interface language. */
    private suspend fun writeContents() {
        val resources = context.inAppLanguage().resources
        val locale = resources.configuration.locales[0]
        val text = ContentsText(
            resources.getString(R.string.contents_title), resources.getString(R.string.note_untitled),
            resources.getString(R.string.contents_without_collection), resources.getString(R.string.archive_title),
            resources.getString(R.string.contents_empty),
        )
        withContext(Dispatchers.IO) {
            work.withLock {
                val open = state.value as? LibraryState.Open ?: return@withLock
                if (open.library.isReadOnly) return@withLock
                try {
                    val contents = tableOfContents(open.index.contentsNotes(), open.library.readCollections(), text, locale, ZoneId.systemDefault())
                    open.library.writeTableOfContents(contents)
                } catch (_: FormatException) {
                    // The next change tries again; the file is only a convenience for reading the library elsewhere.
                }
            }
        }
    }

    /** Opens the stored library again after it failed to open. */
    fun retry() {
        scope.launch(Dispatchers.IO) { work.withLock { openStored() } }
    }

    /** Creates the first library of the device and opens it. */
    fun create() {
        scope.launch(Dispatchers.IO) {
            work.withLock {
                state.value = LibraryState.None(creating = true)
                val id = try {
                    createFolder()
                } catch (_: Exception) {
                    state.value = LibraryState.None(failed = true)
                    return@withLock
                }
                // Remembered before it opens, so a library that fails to open is offered again with Retry.
                preferences.setOpenLibrary(id)
                try {
                    open(id)
                } catch (_: FormatException) {
                    state.value = LibraryState.Failed
                }
            }
        }
    }

    /**
     * Creates a library in a temporary folder and moves it under its identifier, so a failed
     * creation leaves no half-made library behind.
     *
     * @return The new library's identifier.
     * @throws Exception When the folder cannot be created or moved.
     */
    private fun createFolder(): String {
        val temporary = File(librariesDir, "$NewPrefix${System.nanoTime()}")
        try {
            return Library.create(temporary.toPath()).info.id.also {
                Files.move(temporary.toPath(), File(librariesDir, it).toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (e: Exception) {
            runCatching { removePath(temporary.toPath()) }
            throw e
        }
    }

    /**
     * Lists the libraries on this device for Settings, the most recently changed first. A folder
     * that does not open as a library is left out. Under the lock of library changes, as it reads
     * the indexes of libraries that are not open.
     */
    suspend fun libraries(): List<LibraryEntry> = withContext(Dispatchers.IO) {
        work.withLock {
            val open = state.value as? LibraryState.Open
            librariesDir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.mapNotNull { folder ->
                try {
                    val library = Library.open(folder.toPath())
                    val isOpen = library.root == open?.library?.root
                    val changed = if (isOpen) open.index.lastUpdated() else lastUpdated(folder.name)
                    val connection = preferences.syncSettings(folder.name).first()?.connection
                    LibraryEntry(folder.name, connection?.let(::libraryName), changed ?: library.info.created, isOpen)
                } catch (_: FormatException) {
                    null
                }
            }.sortedByDescending { it.changed }
        }
    }

    /**
     * Reads when a note of a library that is not open last changed, from its index.
     *
     * @return The time, or null when it has no index yet or the index cannot be read.
     */
    private fun lastUpdated(id: String): Instant? {
        val file = indexFile(id)
        if (!file.exists()) return null
        return try {
            Index.open(file.toPath(), titleCollator()).use { it.lastUpdated() }
        } catch (_: FormatException) {
            null
        }
    }

    /** Returns the file of a library's index. */
    private fun indexFile(id: String) = File(context.noBackupFilesDir, "index/$id.sqlite")

    /**
     * Opens another library of the device and remembers it; the library that was open stays
     * open when the other one fails to open.
     *
     * @return Whether the library opened.
     */
    suspend fun switchTo(id: String): Boolean = changeLibrary { id }

    /**
     * Turns a downloaded library into a library of this device, opens it and remembers it.
     *
     * @param folder The folder from [downloadFolder] the library was downloaded into.
     * @param id The library's identifier, which names its folder.
     * @return Whether it was moved and opened.
     */
    suspend fun adopt(folder: File, id: String): Boolean = changeLibrary {
        Files.move(folder.toPath(), libraryFolder(id).toPath(), StandardCopyOption.ATOMIC_MOVE)
        id
    }

    /**
     * Opens a library again after synchronisation changed its files, and scans it, so its
     * settings, collections and notes show as synchronised. Another library than the open one is
     * left alone.
     *
     * @param id The synchronised library.
     */
    suspend fun reloadAfterSync(id: String) = withContext(Dispatchers.IO) {
        work.withLock {
            val open = state.value as? LibraryState.Open ?: return@withLock
            if (open.library.root.fileName.toString() != id) return@withLock
            try {
                state.value = scan(open.copy(library = Library.open(open.library.root), revision = open.revision + 1))
            } catch (_: FormatException) {
                // The files stay as they are; the next scan or start tries again.
            }
        }
    }

    /**
     * Creates another library and switches to it.
     *
     * @return Whether it was created and opened.
     */
    suspend fun createAnother(): Boolean = changeLibrary(::createFolder)

    /** Runs a change of the open library: makes or picks a library, opens it and closes the old index. */
    private suspend fun changeLibrary(target: () -> String): Boolean = withContext(Dispatchers.IO) {
        work.withLock {
            val previous = state.value as? LibraryState.Open
            try {
                val id = target()
                open(id)
                preferences.setOpenLibrary(id)
                previous?.index?.close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Deletes the open library from this device with its index; copies on sync storages are not
     * touched. Another library of the device opens, or the first start shows.
     *
     * @return Whether the library was deleted.
     */
    suspend fun deleteOpen(): Boolean = withContext(Dispatchers.IO) {
        work.withLock {
            val open = state.value as? LibraryState.Open ?: return@withLock false
            open.index.close()
            val id = open.library.root.fileName.toString()
            val deleted = runCatching { removePath(open.library.root) }.isSuccess
            for (suffix in listOf("", "-wal", "-shm")) File(context.noBackupFilesDir, "index/$id.sqlite$suffix").delete()
            val next = librariesDir.listFiles().orEmpty().firstOrNull { it.isDirectory && !it.name.startsWith('.') }
            preferences.setOpenLibrary(next?.name)
            try {
                if (next != null) open(next.name) else state.value = LibraryState.None()
            } catch (_: FormatException) {
                state.value = LibraryState.Failed
            }
            deleted
        }
    }

    /**
     * Empties the index and reads every note file again, for Rebuild index on the Libraries screen.
     *
     * @return Whether the index was rebuilt.
     */
    suspend fun rebuildIndex(): Boolean = withContext(Dispatchers.IO) {
        work.withLock {
            val open = state.value as? LibraryState.Open ?: return@withLock false
            try {
                open.index.clear()
                open.index.scan(open.library)
                state.value = open.copy(revision = open.revision + 1)
                true
            } catch (_: FormatException) {
                false
            }
        }
    }

    /**
     * Stores how long trashed notes are kept, a setting of the library that follows it to other
     * devices.
     *
     * @param days The days, or null to keep them until the trash is emptied.
     * @return Whether `library.json` was written.
     */
    suspend fun setTrashRetention(days: Int?): Boolean = withContext(Dispatchers.IO) {
        work.withLock {
            val open = state.value as? LibraryState.Open ?: return@withLock false
            try {
                open.library.changeInfo { it.copy(trashRetentionDays = days) }
                state.value = open.copy(revision = open.revision + 1)
                changes.tryEmit(open.library.root.fileName.toString())
                true
            } catch (_: FormatException) {
                false
            }
        }
    }

    /**
     * Writes a note of the open library atomically and brings the index up to date; synchronisation
     * follows. The rules file a new note, or one whose tags changed.
     *
     * @param before The note as last written, or null for a new note.
     * @return The note as written, or null when it could not be written.
     */
    suspend fun writeNote(note: Note, before: NoteMeta?): Note? = changeFiles { Organizer(it.library, it.index).writeNote(note, before) }

    /**
     * Writes a note from its editor, when its file is still the version the editor read (desktop
     * spec section 3.7); otherwise as [writeNote].
     *
     * @param before The note as last written.
     * @param expected The file's contents the change is based on, or null to replace whatever is there.
     * @return What became of the note.
     */
    suspend fun writeEditedNote(note: Note, before: NoteMeta, expected: ByteArray?): NoteWrite {
        var changed = false
        val written = changeFiles {
            try {
                Organizer(it.library, it.index).writeNote(note, before, expected)
            } catch (e: FormatException) {
                changed = e.code == ErrorCode.Deferred
                throw e
            }
        }
        return when {
            written != null -> NoteWrite.Written(written)
            changed -> NoteWrite.Changed
            else -> NoteWrite.Failed
        }
    }

    /**
     * Writes the conflict copy of a note with its own attachments (desktop spec section 12.4). A
     * copy is not created by the person, so the rules leave it alone.
     *
     * @return The copy as written, or null when it could not be written, with nothing left of it.
     */
    suspend fun writeConflictCopy(copy: Note): Note? = changeFiles { Organizer(it.library, it.index).writeCopy(copy, copy.meta) }

    /**
     * Creates a normalised copy of a note whose body is outside the vocabulary, after the person
     * confirmed it (desktop spec section 3.3): a new note with its own attachments and the rules
     * filing it as a new note. The original file stays byte for byte and stays under Problems.
     *
     * The body is written back in the vocabulary by the editor's own codec.
     *
     * @param fileName The note's file in the `notes` folder.
     * @return The new note's identifier, or null when no copy was made.
     */
    suspend fun createNormalizedCopy(fileName: String): String? {
        val library = (state.value as? LibraryState.Open)?.library ?: return null
        val path = library.notesDir.resolve(fileName)
        val source = withContext(Dispatchers.IO) {
            try {
                parseNoteForNormalization(readFile(path), path)
            } catch (_: FormatException) {
                null
            }
        } ?: return null
        val body = normalizedBody(source.body) ?: return null
        val time = now()
        val copy = Note(source.meta.copy(id = newId(), created = time, updated = time), body)
        return organize { it.writeCopy(copy, null) }?.meta?.id
    }

    /**
     * Renames the links to a note whose text still shows its old title, in every note that links
     * to it, those in the trash included. The note itself is left out: its editor holds its body.
     * A note that cannot be read or written keeps its links.
     *
     * @param id The renamed note.
     */
    suspend fun renameLinksTo(id: String, oldTitle: String, newTitle: String) {
        if (oldTitle.isEmpty() || oldTitle == newTitle) return
        changeFiles { open ->
            for (linking in open.index.backlinks(id, includeTrashed = true)) {
                runCatching {
                    open.library.changeNote(linking.id) { note ->
                        renameNoteLinks(note.body, id, oldTitle, newTitle)?.let { Note(note.meta.copy(updated = now()), it) }
                    }
                }
            }
        }
    }

    private val capturedLocations = MutableStateFlow<Map<String, GeoLocation>>(emptyMap())
    private val missingLocations = MutableStateFlow<Set<String>>(emptySet())
    private val editors = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val drafts = mutableMapOf<String, NoteDraft>()

    /** The notes whose position could not be found since the application started. */
    val unavailableLocations: StateFlow<Set<String>> = missingLocations.asStateFlow()

    /** Emits the position found for a note that its editor has not saved yet, or null. */
    fun capturedLocation(id: String): Flow<GeoLocation?> = capturedLocations.map { it[id] }.distinctUntilChanged()

    /** Tells that an editor of a note opened, which then saves the note's position itself. */
    fun editorOpened(id: String) = editors.update { it + (id to (it[id] ?: 0) + 1) }

    /** Tells that an editor of a note closed. */
    fun editorClosed(id: String) = editors.update {
        val left = (it[id] ?: 1) - 1
        if (left > 0) it + (id to left) else it - id
    }

    /**
     * Waits a moment for the note's open editor to finish closing, so an editor taking its place
     * after a rotation reads what the other one saved or left as a draft. Another window keeping
     * the note open only delays this.
     */
    suspend fun awaitEditorClosed(id: String) {
        withTimeoutOrNull(EditorCloseWaitMs) { editors.first { id !in it } }
    }

    /** Keeps what an editor could not save for the note's next editor, while the application runs. */
    fun keepDraft(id: String, draft: NoteDraft) = synchronized(drafts) { drafts[id] = draft }

    /** Returns the draft of a note and forgets it. */
    fun takeDraft(id: String): NoteDraft? = synchronized(drafts) { drafts.remove(id) }

    /** Forgets the position of a note once its editor saved it. */
    fun locationSaved(id: String) = capturedLocations.update { it - id }

    /**
     * Finds the device's position for a note in the background: a new note when Add location to
     * new notes is on, or a note given one from its menu. An open editor of the note saves the
     * position with its next write, so typing goes on undisturbed; without one the note's file
     * gets it directly, unless the note is gone, as an empty note left at once is. A position not
     * found within ten seconds is remembered as unavailable.
     *
     * @param locate Finds the position.
     */
    fun locateNote(id: String, locate: suspend () -> GeoLocation?) {
        scope.launch {
            val location = locate()
            if (location == null) {
                missingLocations.update { it + id }
                return@launch
            }
            missingLocations.update { it - id }
            capturedLocations.update { it + (id to location) }
            if (id !in editors.value) {
                changeFiles { open ->
                    open.library.changeNote(id) { note ->
                        if (note.meta.location == null) note.copy(meta = note.meta.copy(location = location, updated = now())) else null
                    }
                }
                locationSaved(id)
            }
        }
    }

    /**
     * Creates a note in the open library: in the collections and with the tags of the list it is
     * created from. The rules file it at once.
     *
     * @param title The title of a captured note; new notes start without one.
     * @param body The body of a captured note, its files already stored under [id].
     * @param id The new note's identifier, chosen before its files are stored.
     * @return The new note, or null when it could not be written.
     */
    suspend fun createNote(
        collections: List<String>,
        tags: List<String>,
        title: String = "",
        body: String = "",
        id: String = newId(),
    ): CreatedNote? {
        val fresh = newNote()
        val note = Note(fresh.meta.copy(id = id, title = title, collections = collections, tags = tags), body)
        val written = writeNote(note, null) ?: return null
        return CreatedNote(id, RuleChange.of(note.meta, written.meta))
    }

    /**
     * Deletes a note of the open library and its attachment folder, as an empty note left by the
     * person is discarded.
     *
     * @return Whether the files are gone.
     */
    suspend fun deleteNote(id: String): Boolean = organize { it.deleteNotes(listOf(id)) } != null

    /**
     * Changes how the open library's notes are organised, then scans it so the lists follow, and
     * tells synchronisation. What the rules changed on the way, even when the change failed
     * later, goes to [ruleChanges].
     *
     * @return What the change returned, or null when it failed.
     */
    suspend fun <T> organize(change: (Organizer) -> T): T? = changeFiles { open ->
        val organizer = Organizer(open.library, open.index)
        try {
            change(organizer)
        } finally {
            if (organizer.filed.isNotEmpty()) filings.tryEmit(organizer.filed.toList())
        }
    }

    /**
     * Removes what the open library keeps no longer: notes trashed longer than the retention
     * period and attachment files unreferenced for 30 days. Runs when the application starts
     * and once a day in the background; a read-only library is left alone.
     */
    suspend fun maintain() = withContext(Dispatchers.IO) {
        work.withLock {
            val open = state.value as? LibraryState.Open ?: return@withLock
            if (open.library.isReadOnly) return@withLock
            // The index must know every note's files before unreferenced ones are judged.
            val scanned = runCatching { scan(open) }.getOrNull() ?: return@withLock
            state.value = scanned
            val organizer = Organizer(open.library, open.index)
            val removed = runCatching { organizer.purgeTrash() + organizer.purgeAttachments() }.getOrDefault(0)
            if (removed > 0) {
                runCatching { scan(scanned) }.getOrNull()?.let { state.value = it }
                changes.tryEmit(open.library.root.fileName.toString())
            }
        }
    }

    /**
     * Changes files of the open library, then scans it so the lists and the open note follow, and
     * tells synchronisation. The revision grows even when the scan finds no note changed: a change
     * of the collections, tag colours or favourites lives in files the index does not hold, and
     * screens read those again only on a new revision.
     *
     * @return What the change returned, or null when it failed.
     */
    private suspend fun <T> changeFiles(change: (LibraryState.Open) -> T): T? = withContext(Dispatchers.IO) {
        work.withLock {
            val open = state.value as? LibraryState.Open ?: return@withLock null
            val result = try {
                change(open)
            } catch (_: FormatException) {
                return@withLock null
            } catch (_: IOException) {
                return@withLock null
            }
            runCatching { open.index.scan(open.library) }
            state.value = open.copy(revision = open.revision + 1)
            changes.tryEmit(open.library.root.fileName.toString())
            result
        }
    }

    /**
     * Brings the index up to date with the library's files, as the application returns to the
     * foreground. A failed scan keeps the index as it was; the next one tries again.
     */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            work.withLock {
                val open = state.value as? LibraryState.Open ?: return@withLock
                runCatching { scan(open) }.getOrNull()?.let { state.value = it }
            }
        }
    }

    /** Opens the remembered library; a library that is gone returns to the first start. */
    private suspend fun openStored() {
        val id = preferences.openLibrary.first()
        if (id == null) {
            state.value = LibraryState.None()
            return
        }
        state.value = LibraryState.Opening
        try {
            open(id)
        } catch (e: FormatException) {
            if (e.code == ErrorCode.NotFound) {
                preferences.setOpenLibrary(null)
                state.value = LibraryState.None()
            } else {
                state.value = LibraryState.Failed
            }
        }
    }

    /**
     * Opens a library and its index and scans it.
     *
     * @throws FormatException When the library or its index cannot be opened.
     */
    private fun open(id: String) {
        val library = Library.open(File(librariesDir, id).toPath())
        val index = Index.open(indexFile(id).toPath(), titleCollator())
        try {
            state.value = scan(LibraryState.Open(library, index, revision = 0))
        } catch (e: FormatException) {
            index.close()
            throw e
        }
    }

    /** Scans a library into its index; the revision grows when the scan changed something. */
    private fun scan(open: LibraryState.Open): LibraryState.Open {
        val report = open.index.scan(open.library)
        val changed = report.added + report.updated + report.removed > 0
        return if (changed) open.copy(revision = open.revision + 1) else open
    }

    /**
     * Returns the order of titles and tags in lists, as the desktop's: the person's locale,
     * ignoring case, with numbers read by their value (`Note 2` before `Note 10`).
     */
    private fun titleCollator(): Comparator<String> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        (collator as? RuleBasedCollator)?.numericCollation = true
        return Comparator { a, b -> collator.compare(a, b) }
    }
}
