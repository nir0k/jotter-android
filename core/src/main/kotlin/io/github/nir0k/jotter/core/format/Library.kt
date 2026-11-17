package io.github.nir0k.jotter.core.format

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private const val InfoFile = "library.json"
private const val CollectionsFile = "collections.json"
private const val RulesFile = "rules.json"
private const val ContentsFile = "index.html"
private const val NotesDir = "notes"
internal const val AttachmentsDir = "attachments"

/**
 * Library is a library folder: its layout, `library.json`, the collection tree, the rules and
 * the note files (desktop spec section 3). The files are the only source of truth and every write
 * is atomic. A library written by a newer Jotter opens read-only and refuses writes.
 */
class Library private constructor(
    /** The library folder, as an absolute path. */
    val root: Path,
    info: LibraryInfo,
    /** Whether the library is of a newer format and refuses writes. */
    val isReadOnly: Boolean,
) {
    /** The contents of `library.json`. */
    var info: LibraryInfo = info
        private set

    /** The folder holding the note files. */
    val notesDir: Path get() = root.resolve(NotesDir)

    /** Returns the file of a note. */
    fun notePath(id: String): Path = notesDir.resolve("$id.html")

    /** Returns the attachment folder of a note: `attachments/<note-id>`. */
    fun attachmentsDir(noteId: String): Path = root.resolve(AttachmentsDir).resolve(noteId)

    /** Refuses a write while the library is read-only. */
    private fun checkWritable() {
        if (isReadOnly) throw FormatException(ErrorCode.ReadOnly, root.toString())
    }

    /**
     * Applies a change to `library.json` as it is on disk in one short transaction, so tombstones,
     * favourites, tag colours and settings a sync run wrote meanwhile stay. An unchanged file is
     * not written.
     *
     * @param change Returns the new contents from the current ones.
     * @throws FormatException ReadOnly, Io, Malformed, NewerFormat, or OtherLibrary when the folder
     *   now holds another library.
     */
    fun changeInfo(change: (LibraryInfo) -> LibraryInfo) {
        checkWritable()
        val path = root.resolve(InfoFile)
        fileTransaction {
            val current = parseLibraryInfo(readFile(path), path.toString())
            if (current.id != info.id) throw FormatException(ErrorCode.OtherLibrary, path.toString())
            val changed = change(current)
            if (changed != current) writeFileAtomic(path, serializeLibraryInfo(changed))
            info = changed
        }
    }

    /**
     * Reads a note file.
     *
     * @throws FormatException NotFound, Io, Malformed or NewerFormat.
     */
    fun readNote(id: String): Note = notePath(id).let { parseNote(readFile(it), it) }

    /**
     * Writes a note file atomically.
     *
     * @throws FormatException ReadOnly or Io.
     */
    fun writeNote(note: Note) = writeNote(note, expected = null)

    /**
     * Writes a note file atomically, when it is still the version the caller read.
     *
     * @param expected The file's contents the change is based on, or null to replace whatever is there.
     * @throws FormatException ReadOnly or Io; Refused for an invalid identifier; Malformed for a body
     *   outside the vocabulary, which is never written; Deferred when the file is another version
     *   or gone, which leaves it as it is.
     */
    fun writeNote(note: Note, expected: ByteArray?) {
        checkWritable()
        if (!isValidId(note.meta.id)) throw FormatException(ErrorCode.Refused, note.meta.id)
        val path = notePath(note.meta.id)
        bodyProblem(note.body)?.let { throw FormatException(ErrorCode.Malformed, path.toString(), it) }
        val data = serializeNote(note)
        fileTransaction {
            if (expected != null) {
                val current = try {
                    readFile(path)
                } catch (e: FormatException) {
                    if (e.code != ErrorCode.NotFound) throw e
                    null
                }
                if (current == null || !current.contentEquals(expected)) {
                    throw FormatException(ErrorCode.Deferred, path.toString(), "the note changed before it could be written")
                }
            }
            writeFileAtomic(path, data)
        }
    }

    /**
     * Applies a change to a note as it is on disk in one short transaction, so a version a sync run
     * wrote meanwhile is the one changed.
     *
     * @param change Returns the new note, or null to leave the file as it is.
     * @return The note as written, or null when the change left it.
     * @throws FormatException NotFound, Io, ReadOnly, Malformed or NewerFormat.
     */
    fun changeNote(id: String, change: (Note) -> Note?): Note? = fileTransaction {
        val changed = change(readNote(id)) ?: return@fileTransaction null
        writeNote(changed)
        changed
    }

    /**
     * Reads `collections.json`; a missing file is an empty tree.
     *
     * @return The collections in their order within each parent.
     * @throws FormatException Io, Malformed or NewerFormat.
     */
    fun readCollections(): List<Collection> =
        readListFile(CollectionsFile)?.let { parseCollections(it, root.resolve(CollectionsFile).toString()) }.orEmpty()

    /**
     * Applies a change to `collections.json` as it is on disk in one short transaction, so entries a
     * sync run wrote meanwhile stay.
     *
     * @param change Returns the new list from the current one.
     * @throws FormatException ReadOnly, Io, Malformed or NewerFormat.
     */
    fun changeCollections(change: (List<Collection>) -> List<Collection>) {
        checkWritable()
        fileTransaction { writeFileAtomic(root.resolve(CollectionsFile), serializeCollections(change(readCollections()))) }
    }

    /**
     * Reads `rules.json`; a missing file holds no rules.
     *
     * @return The rules in the order they run.
     * @throws FormatException Io, Malformed or NewerFormat.
     */
    fun readRules(): List<Rule> =
        readListFile(RulesFile)?.let { parseRules(it, root.resolve(RulesFile).toString()) }.orEmpty()

    /**
     * Applies a change to `rules.json` as it is on disk in one short transaction, so entries a sync
     * run wrote meanwhile stay.
     *
     * @param change Returns the new list from the current one.
     * @throws FormatException ReadOnly, Io, Malformed or NewerFormat.
     */
    fun changeRules(change: (List<Rule>) -> List<Rule>) {
        checkWritable()
        fileTransaction { writeFileAtomic(root.resolve(RulesFile), serializeRules(change(readRules()))) }
    }

    /**
     * Writes the generated table of contents, `index.html` (desktop spec section 3.6), when its
     * contents changed; an unchanged file is left alone, so synchronisation sees no change.
     *
     * @return Whether the file was written.
     * @throws FormatException ReadOnly or Io.
     */
    fun writeTableOfContents(contents: ByteArray): Boolean {
        checkWritable()
        val path = root.resolve(ContentsFile)
        return fileTransaction {
            val current = try {
                readFile(path)
            } catch (_: FormatException) {
                null
            }
            if (current.contentEquals(contents)) return@fileTransaction false
            writeFileAtomic(path, contents)
            true
        }
    }

    /** Reads a list file of the library root, or returns null when it is missing. */
    private fun readListFile(name: String): ByteArray? = try {
        readFile(root.resolve(name))
    } catch (e: FormatException) {
        if (e.code == ErrorCode.NotFound) null else throw e
    }

    /**
     * Lists the note files of the `notes` folder in file name order. Files not named
     * `<uuid>.html` are not notes and are left out (desktop spec section 3.3); one of another kind,
     * such as a symbolic link, is listed and then fails to read, so it shows under Problems.
     *
     * @throws FormatException NotFound or Io when the folder cannot be listed.
     */
    fun noteFiles(): List<Path> = listDirectory(notesDir)
        .filter { it.name.endsWith(".html") && isValidId(it.name.removeSuffix(".html")) }
        .sortedBy { it.name }

    companion object {
        /**
         * Creates a new library in a folder, which must be missing or empty.
         *
         * @param root The library folder.
         * @return The open library.
         * @throws FormatException NotEmpty or Io.
         */
        fun create(root: Path): Library {
            val absolute = root.toAbsolutePath()
            try {
                if (absolute.isDirectory() && Files.list(absolute).use { it.findAny().isPresent }) {
                    throw FormatException(ErrorCode.NotEmpty, absolute.toString())
                }
                Files.createDirectories(absolute.resolve(NotesDir))
                Files.createDirectories(absolute.resolve(AttachmentsDir))
            } catch (e: IOException) {
                throw FormatException(ErrorCode.Io, absolute.toString(), e.message.orEmpty(), e)
            }
            val info = LibraryInfo(id = newId(), created = now())
            writeFileAtomic(absolute.resolve(InfoFile), serializeLibraryInfo(info))
            writeFileAtomic(absolute.resolve(CollectionsFile), emptyListFile("collections"))
            writeFileAtomic(absolute.resolve(RulesFile), emptyListFile("rules"))
            return Library(absolute, info, isReadOnly = false)
        }

        /**
         * Opens an existing library. Folders removed by hand are recreated, as they hold no data
         * of their own; a library of a newer format is left untouched.
         *
         * @param root The library folder.
         * @return The library, read-only when its format is newer.
         * @throws FormatException NotFound, Io or Malformed.
         */
        fun open(root: Path): Library {
            val absolute = root.toAbsolutePath()
            val path = absolute.resolve(InfoFile)
            val json = parseJsonObject(readFile(path), path.toString())
            val format = json["format"].qtInt(0)
            if (format < 1) throw FormatException(ErrorCode.Malformed, path.toString(), "invalid format")
            val newer = format > LibraryFormat
            val info = libraryInfoFromJson(json, path.toString(), newer)
            if (!newer) {
                for (sub in listOf(NotesDir, AttachmentsDir)) {
                    val folder = absolute.resolve(sub)
                    try {
                        Files.createDirectories(folder)
                        requireRealFolder(folder)
                    } catch (e: IOException) {
                        throw FormatException(ErrorCode.Io, folder.toString(), e.message.orEmpty(), e)
                    }
                }
            }
            return Library(absolute, info, newer)
        }
    }
}
