package io.github.nir0k.jotter.core.sync

import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.StorePrefix
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.copyNoteAttachments
import io.github.nir0k.jotter.core.format.fileRevision
import io.github.nir0k.jotter.core.format.fileTransaction
import io.github.nir0k.jotter.core.format.isSafeFileName
import io.github.nir0k.jotter.core.format.isValidId
import io.github.nir0k.jotter.core.format.listDirectory
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.format.newNote
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.format.parseCollections
import io.github.nir0k.jotter.core.format.parseJsonObject
import io.github.nir0k.jotter.core.format.parseLibraryInfo
import io.github.nir0k.jotter.core.format.parseNote
import io.github.nir0k.jotter.core.format.parseRules
import io.github.nir0k.jotter.core.format.parseTime
import io.github.nir0k.jotter.core.format.qtString
import io.github.nir0k.jotter.core.format.readFile
import io.github.nir0k.jotter.core.format.removePath
import io.github.nir0k.jotter.core.format.serializeCollections
import io.github.nir0k.jotter.core.format.serializeLibraryInfo
import io.github.nir0k.jotter.core.format.serializeNote
import io.github.nir0k.jotter.core.format.serializeRules
import io.github.nir0k.jotter.core.format.toJsonText
import io.github.nir0k.jotter.core.format.writeFileAtomic
import io.github.nir0k.jotter.core.format.formatTime
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a sync run did.
 *
 * @param uploaded Files copied to the sync storage.
 * @param downloaded Files copied into the library.
 * @param merged Files changed on both sides and merged.
 * @param deleted Files deleted on either side.
 * @param noteConflicts Identifiers of the conflict copies of notes the run made.
 * @param fileConflicts Conflict copies of attachments the run made, relative to the library.
 * @param skipped Files left for the next run, relative to the library: changed in the library
 *   while the run was about to write them. A run that leaves any is partial, not a full success.
 * @param deferredAttachments Attachments new or changed in the storage that the run did not
 *   download, as [SyncOptions.deferAttachmentDownloads] asked; a later run downloads them.
 */
data class SyncReport(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val merged: Int = 0,
    val deleted: Int = 0,
    val noteConflicts: List<String> = emptyList(),
    val fileConflicts: List<String> = emptyList(),
    val skipped: List<String> = emptyList(),
    val deferredAttachments: Int = 0,
)

/**
 * Options of a sync run.
 *
 * @param fullListing Lists every remote attachment folder, not only those whose time changed (Sync now).
 * @param deferAttachmentDownloads Leaves attachments new or changed in the storage for a later run
 *   instead of reading them, so a run on a metered network moves only text. Uploads still happen.
 * @param progress Called on the run's thread with the number of files done and to do.
 * @param conflictTitle Returns the title of a note's conflict copy from the note's title, which may
 *   be empty; the application gives it in the interface language.
 * @param elapsedMs Returns milliseconds from a monotonic clock, by which the lock is renewed;
 *   a test gives its own clock, so a run lasting minutes takes none.
 */
class SyncOptions(
    val fullListing: Boolean = false,
    val deferAttachmentDownloads: Boolean = false,
    val progress: ((done: Int, total: Int) -> Unit)? = null,
    val conflictTitle: (String) -> String = ::englishConflictTitle,
    val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
)

/** Minutes a sync run's lock lasts unless renewed (desktop spec section 12.3). */
const val SyncLockMinutes = 10

private const val InfoFile = "library.json"
private const val CollectionsFile = "collections.json"
private const val RulesFile = "rules.json"
private const val ContentsFile = "index.html"
private const val NotesDir = "notes"
private const val AttachmentsDir = "attachments"
private const val LockFile = ".jotter-lock"
private const val TempPrefix = ".jotter-tmp-"

private const val LockLifetimeMs = SyncLockMinutes * 60L * 1000

/** A long run renews its lock when half of its lifetime has passed. */
private const val LockRenewMs = LockLifetimeMs / 2

/** Temporary files of interrupted uploads are removed after a day (desktop spec section 12.2). */
private const val TempMaxAgeMs = 24L * 60 * 60 * 1000

/**
 * A library file modified this recently may change again without its time or size changing, as
 * file system times are coarse; its time is not recorded, so the next run reads it again.
 */
private const val RacyMs = 2000L

/**
 * The sync state is written in batches of this many files, so an interrupted run keeps what it did
 * without a disk flush per file.
 */
private const val CommitInterval = 200

/** The kinds of synchronised files, which differ in how they merge; files are synchronised in this order. */
private enum class FileKind { Info, Collections, Rules, Note, Attachment, Contents }

/** The size and modification time of a file on one side. */
private data class Attributes(val size: Long, val modified: Long)

/**
 * One side's version of a file during a run.
 *
 * @param changed Whether it differs from the synchronised contents, or is new, or was deleted.
 * @param read Whether the contents were read; they are only when needed.
 */
private class Version(
    val exists: Boolean = false,
    val changed: Boolean = false,
    val read: Boolean = false,
    val data: ByteArray = ByteArray(0),
    val hash: String = "",
)

/** Returns the SHA-256 hash of data in hexadecimal. */
private fun hashOf(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

/** Throws the error of a failed operation. */
private fun fail(code: ErrorCode, path: String, detail: String = ""): Nothing = throw FormatException(code, path, detail)

/** Tells whether a file name is a note file, `<uuid>.html`. */
private fun isNoteFileName(name: String) = name.endsWith(".html") && isValidId(name.dropLast(5))

/** Returns the note a note file or attachment belongs to, or an empty string for other files. */
private fun noteIdOf(path: String): String = when {
    path.startsWith("$NotesDir/") -> path.substring(NotesDir.length + 1).dropLast(5)
    path.startsWith("$AttachmentsDir/") -> path.substring(AttachmentsDir.length + 1).substringBefore('/')
    else -> ""
}

private fun kindOf(path: String): FileKind = when (path) {
    InfoFile -> FileKind.Info
    CollectionsFile -> FileKind.Collections
    RulesFile -> FileKind.Rules
    ContentsFile -> FileKind.Contents
    else -> if (path.startsWith("$NotesDir/")) FileKind.Note else FileKind.Attachment
}

/** Returns the folder of a path relative to the library, empty for the root. */
private fun folderOf(path: String): String = path.substringBeforeLast('/', "")

/**
 * Returns the size and modification time of a local file, or null for a missing file or one that
 * is not a regular file. A symbolic link is not followed.
 */
private fun localAttributes(file: Path): Attributes? = try {
    val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (attributes.isRegularFile) Attributes(attributes.size(), attributes.lastModifiedTime().toMillis()) else null
} catch (_: IOException) {
    null
}

/**
 * Lists a folder of the library. A folder that is missing or cannot be read stops the run: read as
 * empty, it would delete on the storage every file recorded in it.
 *
 * @throws FormatException Io.
 */
private fun libraryEntries(folder: Path): List<Path> = try {
    listDirectory(folder)
} catch (e: FormatException) {
    throw FormatException(ErrorCode.Io, folder.toString(), e.detail, e)
}

/**
 * Tells that a file of the library is of a kind the run does not touch.
 *
 * @throws FormatException Io.
 */
private fun notRegular(file: Path): Nothing = fail(ErrorCode.Io, file.toString(), "not a regular library file")

/** Returns the English title of a conflict copy, for runs without an interface. */
private fun englishConflictTitle(title: String): String {
    val time = LocalDateTime.now().format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    return "${title.ifEmpty { "Untitled" }} (conflict, $time)"
}

/**
 * The result of merging one library file.
 *
 * @param data The merged contents, written to both sides.
 * @param conflict The local note when the note bodies conflict; it must be kept as a conflict copy.
 */
internal class MergedFile(val data: ByteArray, val conflict: Note?)

/**
 * Merges the versions of a library file changed on both sides (desktop spec section 12.4); the
 * path tells its kind. A base that cannot be read counts as none.
 *
 * @param path The file, relative to the library; a note file is named after its identifier.
 * @param base The contents both sides started from, or null for a first connection.
 * @param now The time of the merge, which expires tombstones.
 * @return The merged file, or null when the local or the remote version cannot be read.
 */
internal fun mergeLibraryFile(path: String, base: ByteArray?, local: ByteArray, remote: ByteArray, now: Instant): MergedFile? {
    // Each kind parses the three versions, merges them and serialises the result.
    fun <T> versions(parse: (ByteArray) -> T): Triple<T?, T, T>? = try {
        Triple(base?.let { runCatching { parse(it) }.getOrNull() }, parse(local), parse(remote))
    } catch (_: FormatException) {
        null
    }
    return when (kindOf(path)) {
        FileKind.Info -> versions { parseLibraryInfo(it, path) }?.let { (b, l, r) ->
            MergedFile(serializeLibraryInfo(mergeLibraryInfo(b, l, r, now)), null)
        }
        FileKind.Collections -> versions { parseCollections(it, path) }?.let { (b, l, r) ->
            MergedFile(serializeCollections(mergeCollections(b.orEmpty(), l, r)), null)
        }
        FileKind.Rules -> versions { parseRules(it, path) }?.let { (b, l, r) ->
            MergedFile(serializeRules(mergeRules(b.orEmpty(), l, r)), null)
        }
        FileKind.Note -> versions { parseNote(it, Paths.get(path)) }?.let { (b, l, r) ->
            val merged = mergeNote(b, l, r)
            MergedFile(serializeNote(merged.note), if (merged.conflict) l else null)
        }
        FileKind.Attachment, FileKind.Contents -> null
    }
}

/** One sync run; see [synchronise]. */
private class Run(private val root: Path, private val storage: RemoteStorage, private val state: SyncState, private val options: SyncOptions) {
    private var device = ""

    /** The contents of this run's lock, to recognise it. */
    private var lock = ByteArray(0)
    private val started = options.elapsedMs()
    private var lockRenewed = 0L

    /** Whether the lock is being renewed, which itself reads and writes through the storage. */
    private var renewing = false

    /** When the lock was taken, by the run's clock and by the server's. */
    private var lockedAt = 0L
    private var lockServerTime = 0L

    /** The latest remote modification time this run recorded. */
    private var latest = 0L

    /** Temporary files modified before this remote time are stale. */
    private var staleBefore = 0L
    private val local = HashMap<String, Attributes>()

    /** The versions of the library's files as listed; see [fileRevision]. */
    private val revisions = HashMap<String, String?>()
    private val remote = HashMap<String, Attributes>()
    private val files = HashMap<String, SyncedFile>()

    /** Recorded and listed modification times of remote attachment folders. */
    private var folders: Map<String, Long> = emptyMap()
    private val remoteFolders = HashMap<String, Long>()
    private val madeFolders = HashSet<String>()
    private val changedFolders = HashSet<String>()
    private val tombstones = HashSet<String>()
    private var pending = 0
    private var uploaded = 0
    private var downloaded = 0
    private var merged = 0
    private var deleted = 0
    private val noteConflicts = mutableListOf<String>()
    private val fileConflicts = mutableListOf<String>()
    private val skipped = mutableListOf<String>()
    private var deferredAttachments = 0

    /** Milliseconds since the run started. */
    private fun elapsed() = options.elapsedMs() - started

    private fun file(path: String): Path = root.resolve(path)

    fun exec(): SyncReport {
        device = state.deviceId()
        takeLock()
        storage.checkpoint = ::checkLock
        // What was recorded stays valid when the run fails half way.
        val failure = runCatching {
            state.begin()
            val synced = runCatching { syncAll() }
            val committed = runCatching { state.commit() }
            synced.getOrThrow()
            committed.getOrThrow()
        }.exceptionOrNull()
        storage.checkpoint = null
        // Changes made by others after the lock is released must carry later times than any this
        // run recorded, or the next run could not tell them apart: the run waits until the
        // server's clock has passed the precision of the latest recorded time. The server's time is
        // estimated from the lock's modification time, which is never later than the real one.
        val precision = storage.timePrecisionMs
        if (precision > 0 && latest > 0) {
            val serverNow = lockServerTime + elapsed() - lockedAt
            val wait = minOf(latest + precision + precision / 10 - serverNow, 2L * precision)
            if (wait > 0) Thread.sleep(wait)
        }
        unlock()
        failure?.let { throw it }
        return SyncReport(uploaded, downloaded, merged, deleted, noteConflicts, fileConflicts, skipped, deferredAttachments)
    }

    /** Returns the contents of the lock this device writes, expiring a lock lifetime from now. */
    private fun lockContents(): ByteArray = toJsonText(
        JsonObject(mapOf("device" to JsonPrimitive(device), "expires" to JsonPrimitive(formatTime(now().plusMillis(LockLifetimeMs))))),
    )

    private fun locked(): Nothing = fail(ErrorCode.Locked, storage.location)

    /** Runs a storage call, turning a missing file into Locked: another device moved the lock meanwhile. */
    private fun <T> lockCall(block: () -> T): T = try {
        block()
    } catch (e: FormatException) {
        if (e.code == ErrorCode.NotFound) locked() else throw e
    }

    /**
     * Takes the remote lock. An expired lock, or one this device left behind, is taken over: it is
     * first moved aside, so that of two devices taking it over at once only one wins.
     */
    private fun takeLock() {
        lock = lockContents()
        if (!storage.create(LockFile, lock)) {
            val held = lockCall { storage.read(LockFile) }
            val description = try {
                parseJsonObject(held, LockFile)
            } catch (_: FormatException) {
                null
            }
            val expires = description?.let { parseTime(it["expires"].qtString()) }
            val mine = description != null && description["device"].qtString() == device
            if (!mine && expires != null && expires > now()) locked()
            // A lock without a readable expiry is being written by another device, unless it is far
            // older than a lock lasts by this device's clock.
            if (expires == null) {
                val entry = storage.list("").firstOrNull { it.name == LockFile }
                if (entry != null && now().toEpochMilli() - entry.modified < 2 * LockLifetimeMs) locked()
            }
            val aside = TempPrefix + newId()
            lockCall { storage.rename(LockFile, aside) }
            val moved = runCatching { storage.read(aside) }.getOrNull()
            if (moved == null || !moved.contentEquals(held)) {
                // Another device took the lock over in between; its lock goes back.
                runCatching { storage.rename(aside, LockFile) }
                locked()
            }
            // A leftover is a temporary file, removed by a later run.
            runCatching { storage.remove(aside) }
            if (!storage.create(LockFile, lock)) locked()
        }
        lockedAt = elapsed()
        lockRenewed = lockedAt
    }

    /** Renews the lock when half of its lifetime has passed; a lock another device took over meanwhile stops the run. */
    private fun renewLock() {
        if (elapsed() - lockRenewed < LockRenewMs) return
        val held = try {
            storage.read(LockFile)
        } catch (_: FormatException) {
            null
        }
        if (held == null || !held.contentEquals(lock)) locked()
        lock = lockContents()
        storage.write(LockFile, lock)
        lockRenewed = elapsed()
    }

    /** Renews the lock from within a storage operation; renewing, which goes through the same storage, is not interrupted by itself. */
    private fun checkLock() {
        if (renewing) return
        renewing = true
        try {
            renewLock()
        } finally {
            renewing = false
        }
    }

    /** Removes the lock when it is still this run's; otherwise it expires by itself. */
    private fun unlock() {
        runCatching { if (storage.read(LockFile).contentEquals(lock)) storage.remove(LockFile) }
    }

    /**
     * Checks what both sides hold, then synchronises every file: the JSON files first, so the
     * tombstones are known, then notes, attachments and the table of contents.
     */
    private fun syncAll() {
        val rootEntries = storage.list("")
        prepare(rootEntries)
        listRemote(rootEntries)
        listLocal()
        // Only files whose attributes differ from the state can need work.
        val paths = (local.keys + remote.keys + files.keys)
            .toSortedSet(compareBy<String>({ kindOf(it) }, { it }))
            .filter(::candidate)
        var done = 0
        var tombstonesRead = false
        for (path in paths) {
            val kind = kindOf(path)
            if (!tombstonesRead && kind != FileKind.Info && kind != FileKind.Collections && kind != FileKind.Rules) {
                deleteTombstoned()
                tombstonesRead = true
            }
            if (noteIdOf(path) !in tombstones || kind == FileKind.Contents) syncFile(path)
            options.progress?.invoke(++done, paths.size)
        }
        if (!tombstonesRead) deleteTombstoned()
        recordFolders()
    }

    /** Checks the library on both sides (desktop spec section 12.6) and removes stale temporary files from the root of the storage. */
    private fun prepare(rootEntries: List<RemoteEntry>) {
        rootEntries.firstOrNull { it.name == LockFile }?.let { lockServerTime = it.modified }
        staleBefore = lockServerTime - TempMaxAgeMs
        var remoteLibrary = false
        var foreign = false
        for (entry in rootEntries) {
            when {
                entry.name == InfoFile -> remoteLibrary = true
                entry.name.startsWith(TempPrefix) -> removeStale("", entry)
                entry.name != LockFile -> foreign = true
            }
        }
        val localLibrary = file(InfoFile).exists()
        if (!localLibrary) {
            // A missing folder is what a download starts from; one that cannot be listed is not empty.
            if (root.exists() && libraryEntries(root).isNotEmpty()) fail(ErrorCode.NotEmpty, root.toString())
            if (!remoteLibrary) fail(ErrorCode.NotFound, storage.location, "the storage holds no library")
            for (sub in listOf(NotesDir, AttachmentsDir)) {
                try {
                    Files.createDirectories(file(sub))
                } catch (e: IOException) {
                    throw FormatException(ErrorCode.Io, file(sub).toString(), e.message.orEmpty(), e)
                }
            }
        }
        if (remoteLibrary) {
            val remoteInfo = parseLibraryInfo(storage.read(InfoFile), "${storage.location}/$InfoFile")
            if (localLibrary) {
                val localInfo = parseLibraryInfo(readFile(file(InfoFile)), file(InfoFile).toString())
                if (localInfo.id != remoteInfo.id) fail(ErrorCode.OtherLibrary, storage.location)
            } else {
                state.clear()
            }
        } else {
            if (foreign) fail(ErrorCode.NotEmpty, storage.location)
            // An empty storage receives the whole library, whatever the state remembers of an
            // earlier one; without this, files missing there would count as deleted.
            state.clear()
            for (sub in listOf(NotesDir, AttachmentsDir)) storage.makeFolder(sub)
        }
        files.putAll(state.files())
        folders = state.folders()
    }

    /** Removes a temporary file of an interrupted upload older than a day. */
    private fun removeStale(folder: String, entry: RemoteEntry) {
        if (entry.folder || entry.modified >= staleBefore) return
        storage.remove(if (folder.isEmpty()) entry.name else "$folder/${entry.name}")
    }

    /**
     * Lists the synchronised files of the storage. An attachment folder whose modification time is
     * the one recorded is not listed again; its files are taken from the state.
     */
    private fun listRemote(rootEntries: List<RemoteEntry>) {
        for (entry in rootEntries) {
            if (!entry.folder && (entry.name == InfoFile || entry.name == CollectionsFile || entry.name == RulesFile)) {
                remote[entry.name] = Attributes(entry.size, entry.modified)
            }
        }
        for (entry in listFolder(NotesDir)) {
            if (!entry.folder && isNoteFileName(entry.name)) remote["$NotesDir/${entry.name}"] = Attributes(entry.size, entry.modified)
        }
        val recorded = files.keys.filter { kindOf(it) == FileKind.Attachment }.groupBy(::folderOf)
        for (folder in listFolder(AttachmentsDir)) {
            if (!folder.folder || !isValidId(folder.name)) continue
            val path = "$AttachmentsDir/${folder.name}"
            remoteFolders[path] = folder.modified
            if (!options.fullListing && (folders[path] ?: -1) == folder.modified) {
                for (known in recorded[path].orEmpty()) {
                    val record = files.getValue(known)
                    remote[known] = Attributes(record.remoteSize, record.remoteModified)
                }
                continue
            }
            for (entry in listFolder(path)) {
                when {
                    // A name that would leave the folder, from a damaged or hostile server, is never downloaded.
                    entry.folder || !isSafeFileName(entry.name) -> {}
                    entry.name.startsWith(TempPrefix) -> removeStale(path, entry)
                    else -> remote["$path/${entry.name}"] = Attributes(entry.size, entry.modified)
                }
            }
        }
    }

    /** Lists a remote folder; a missing folder is empty. */
    private fun listFolder(path: String): List<RemoteEntry> = try {
        storage.list(path)
    } catch (e: FormatException) {
        if (e.code == ErrorCode.NotFound) emptyList() else throw e
    }

    /** Lists the synchronised files of the library. */
    private fun listLocal() {
        for (name in listOf(InfoFile, CollectionsFile, RulesFile, ContentsFile)) {
            if (Files.exists(file(name), LinkOption.NOFOLLOW_LINKS)) listLocalFile(name)
        }
        for (note in libraryEntries(file(NotesDir))) {
            if (isNoteFileName(note.name)) listLocalFile("$NotesDir/${note.name}")
        }
        for (folder in libraryEntries(file(AttachmentsDir))) {
            if (!isValidId(folder.name)) continue
            for (attachment in libraryEntries(folder)) listLocalFile("$AttachmentsDir/${folder.name}/${attachment.name}")
        }
    }

    /**
     * Records a file of the library as listed. A symbolic link or special file stops the run, as
     * it does not belong in a library and is neither read through nor taken for a deleted file.
     *
     * @throws FormatException Io.
     */
    private fun listLocalFile(path: String) {
        local[path] = localAttributes(file(path)) ?: notRegular(file(path))
        revisions[path] = fileRevision(file(path))
    }

    /** Tells whether a file may need work: it is new, gone on a side, or its attributes differ from those recorded. */
    private fun candidate(path: String): Boolean {
        val known = files[path] ?: return true
        val localAttributes = local[path]
        if (localAttributes == null || localAttributes != Attributes(known.localSize, known.localModified)) return true
        // The table of contents is never downloaded, so its remote side does not matter.
        return kindOf(path) != FileKind.Contents && remote[path] != Attributes(known.remoteSize, known.remoteModified)
    }

    /**
     * Reads one side's version of a file as far as needed to tell whether it changed: a file whose
     * attributes differ from those recorded is read and compared by its hash.
     *
     * @param attributes The file's attributes on that side, or null when it is missing there.
     * @param recorded The attributes the state recorded for that side, or null for a new file.
     * @param read Reads the file's contents from that side.
     */
    private fun version(attributes: Attributes?, known: SyncedFile?, recorded: Attributes?, read: () -> ByteArray): Version {
        if (attributes == null) return Version(changed = known != null)
        if (known != null && attributes == recorded) return Version(exists = true, hash = known.hash)
        val data = read()
        val hash = hashOf(data)
        return Version(exists = true, changed = known == null || hash != known.hash, read = true, data = data, hash = hash)
    }

    /** Synchronises one file (desktop spec sections 12.2 and 12.4). */
    private fun syncFile(path: String) {
        renewLock()
        val known = files[path]
        val localSide = version(local[path], known, known?.let { Attributes(it.localSize, it.localModified) }) { readFile(file(path)) }
        if (kindOf(path) == FileKind.Contents) {
            // Every device generates its own table of contents; it is only uploaded.
            if (localSide.changed && localSide.exists) upload(path, localSide)
            return
        }
        val recordedRemote = known?.let { Attributes(it.remoteSize, it.remoteModified) }
        if (deferredDownload(path, recordedRemote)) return
        val remoteSide = version(remote[path], known, recordedRemote) { storage.read(path) }
        when {
            !localSide.exists && !remoteSide.exists -> {
                files.remove(path)
                countChange { state.removeFile(path) }
            }
            localSide.exists && remoteSide.exists && localSide.hash == remoteSide.hash -> {
                // Unchanged, or changed alike on both sides: only the record changes.
                val inLibrary = settled(local.getValue(path))
                val inStorage = remote.getValue(path)
                val record = SyncedFile(localSide.hash, inStorage.size, inStorage.modified, inLibrary.size, inLibrary.modified)
                if (known == record) return
                files[path] = record
                latest = maxOf(latest, record.remoteModified)
                if (known != null && known.hash == record.hash) {
                    countChange { state.touchFile(path, record) }
                } else {
                    countChange { state.putFile(path, record, baseOf(path, if (localSide.read) localSide.data else remoteSide.data)) }
                }
            }
            !remoteSide.changed -> if (localSide.exists) upload(path, localSide) else deleteRemote(path)
            !localSide.changed -> if (remoteSide.exists) download(path, remoteSide) else deleteLocal(path)
            // Changed on both sides. A change wins over a deletion, so no change is lost.
            !remoteSide.exists -> upload(path, localSide)
            !localSide.exists -> download(path, remoteSide)
            else -> merge(path, known != null, localSide, remoteSide)
        }
    }

    /**
     * Tells whether a run that defers attachment downloads leaves this file for a later run: an
     * attachment that is new or changed in the storage, which would have to be read to go on. Its
     * folder is listed again next time, and nothing is recorded, so the local side, missing or not,
     * is never taken for a deletion.
     *
     * @param recordedRemote The storage's attributes the state recorded, or null for a new file.
     */
    private fun deferredDownload(path: String, recordedRemote: Attributes?): Boolean {
        if (!options.deferAttachmentDownloads || kindOf(path) != FileKind.Attachment) return false
        val inStorage = remote[path] ?: return false
        if (inStorage == recordedRemote) return false
        changedFolders += folderOf(path)
        deferredAttachments++
        return true
    }

    /** Returns the merge base to record for a file: its contents for notes and JSON files, nothing for attachments and the table of contents. */
    private fun baseOf(path: String, data: ByteArray): ByteArray? =
        if (kindOf(path) == FileKind.Attachment || kindOf(path) == FileKind.Contents) null else data

    /** Returns the attributes of a library file to record; see [RacyMs]. */
    private fun settled(attributes: Attributes): Attributes =
        if (System.currentTimeMillis() - attributes.modified < RacyMs) attributes.copy(modified = -1) else attributes

    /** Records a synchronised file. */
    private fun record(path: String, data: ByteArray, inStorage: Attributes, inLibrary: Attributes) {
        val settledLocal = settled(inLibrary)
        val synced = SyncedFile(hashOf(data), inStorage.size, inStorage.modified, settledLocal.size, settledLocal.modified)
        files[path] = synced
        latest = maxOf(latest, synced.remoteModified)
        countChange { state.putFile(path, synced, baseOf(path, data)) }
    }

    /** Runs a change of the sync state, committing it after every [CommitInterval] changes. */
    private fun countChange(change: () -> Unit) {
        change()
        if (++pending < CommitInterval) return
        pending = 0
        state.commit()
        state.begin()
    }

    /** Writes a file to the storage, creating its folder the first time. */
    private fun put(path: String, data: ByteArray): Attributes {
        val folder = folderOf(path)
        if (folder.isNotEmpty() && madeFolders.add(folder)) storage.makeFolder(folder)
        val entry = storage.write(path, data)
        changedFolders += folder
        return Attributes(entry.size, entry.modified).also { remote[path] = it }
    }

    /** Tells whether a library file is still as it was listed, so the run may replace it. */
    private fun unchangedLocally(path: String): Boolean =
        localAttributes(file(path)) == local[path] && fileRevision(file(path)) == revisions[path]

    /**
     * Writes a file into the library unless it changed since it was listed.
     *
     * @return Its new attributes, or null when it was left for the next run.
     */
    private fun putLocal(path: String, data: ByteArray): Attributes? = fileTransaction {
        if (!unchangedLocally(path)) {
            skipped += path
            return@fileTransaction null
        }
        // An attachment's temporary file lies beside the note folders: left by a crash in the note's
        // own folder, it would be listed and uploaded as an attachment.
        if (kindOf(path) == FileKind.Attachment) writeFileAtomic(file(path), data, file(AttachmentsDir), StorePrefix)
        else writeFileAtomic(file(path), data)
        val attributes = localAttributes(file(path)) ?: fail(ErrorCode.Io, file(path).toString())
        local[path] = attributes
        revisions[path] = fileRevision(file(path))
        attributes
    }

    private fun upload(path: String, version: Version) {
        val inStorage = put(path, version.data)
        uploaded++
        record(path, version.data, inStorage, local.getValue(path))
    }

    private fun download(path: String, version: Version) {
        val inLibrary = putLocal(path, version.data) ?: return
        downloaded++
        record(path, version.data, remote.getValue(path), inLibrary)
    }

    private fun deleteRemote(path: String) {
        storage.remove(path)
        changedFolders += folderOf(path)
        remote.remove(path)
        files.remove(path)
        deleted++
        countChange { state.removeFile(path) }
    }

    private fun deleteLocal(path: String) {
        val removed = fileTransaction {
            if (!unchangedLocally(path)) {
                skipped += path
                return@fileTransaction false
            }
            try {
                Files.deleteIfExists(file(path))
            } catch (e: IOException) {
                throw FormatException(ErrorCode.Io, file(path).toString(), "cannot delete the file", e)
            }
            true
        }
        if (!removed) return
        if (kindOf(path) == FileKind.Attachment) {
            // An attachment folder left empty goes too; one that is not stays.
            runCatching { Files.delete(file(folderOf(path))) }
        }
        local.remove(path)
        revisions.remove(path)
        files.remove(path)
        deleted++
        countChange { state.removeFile(path) }
    }

    /**
     * Merges a file changed on both sides and writes the result to both (desktop spec section 12.4).
     *
     * @param hasBase Whether the state holds a version both sides started from.
     * @param localSide The library's version, read.
     * @param remoteSide The storage's version, read.
     */
    private fun merge(path: String, hasBase: Boolean, localSide: Version, remoteSide: Version) {
        if (kindOf(path) == FileKind.Attachment) return attachmentConflict(path, localSide, remoteSide)
        val base = if (hasBase) state.base(path) else null
        // A version that cannot be parsed is an error for the person to repair, which retrying cannot solve.
        val result = mergeLibraryFile(path, base, localSide.data, remoteSide.data, now())
            ?: fail(ErrorCode.Malformed, path, "cannot merge an unreadable version")
        result.conflict?.let { conflict ->
            if (!unchangedLocally(path)) {
                skipped += path
                return
            }
            conflictCopy(conflict)
        }
        var inLibrary = local.getValue(path)
        if (!result.data.contentEquals(localSide.data)) inLibrary = putLocal(path, result.data) ?: return
        var inStorage = remote.getValue(path)
        if (!result.data.contentEquals(remoteSide.data)) inStorage = put(path, result.data)
        merged++
        record(path, result.data, inStorage, inLibrary)
    }

    /**
     * Saves the local version of a note whose body conflicts as a new note beside it, in the same
     * collections, as desktop spec section 3.7 does for outside changes. The copy owns duplicates of
     * its attachments, published before the note that names them (desktop spec section 12.4).
     *
     * @throws FormatException Io when an attachment cannot be read or copied; the run stops before
     *   the local version is replaced, so nothing of it is lost.
     */
    private fun conflictCopy(note: Note) {
        val fresh = newNote()
        val copied = try {
            copyNoteAttachments(
                root,
                Note(fresh.meta.copy(title = options.conflictTitle(note.meta.title), collections = note.meta.collections, tags = note.meta.tags), note.body),
            )
        } catch (e: FormatException) {
            throw FormatException(ErrorCode.Io, e.path, "cannot copy an attachment of a conflict copy: ${e.detail}", e)
        }
        val copy = copied.note
        for (name in copied.fileNames) {
            val attachment = "$AttachmentsDir/${copy.meta.id}/$name"
            val bytes = readFile(file(attachment))
            val inStorage = put(attachment, bytes)
            listLocalFile(attachment)
            record(attachment, bytes, inStorage, local.getValue(attachment))
        }
        val path = "$NotesDir/${copy.meta.id}.html"
        val data = serializeNote(copy)
        val inLibrary = putLocal(path, data) ?: return
        val inStorage = put(path, data)
        noteConflicts += copy.meta.id
        record(path, data, inStorage, inLibrary)
    }

    /**
     * Resolves an attachment changed on both sides: the remote file wins and the local one is kept
     * beside it as `<name> (conflict).<ext>` on both sides (desktop spec section 12.4).
     */
    private fun attachmentConflict(path: String, localSide: Version, remoteSide: Version) {
        if (!unchangedLocally(path)) {
            skipped += path
            return
        }
        val folder = folderOf(path)
        val name = path.substring(folder.length + 1)
        val dot = name.lastIndexOf('.')
        val suffix = if (dot < 0 || dot == name.length - 1) "" else name.substring(dot)
        val stem = if (suffix.isEmpty()) name else name.substring(0, dot)
        var copyPath: String
        var n = 1
        while (true) {
            copyPath = "$folder/$stem${if (n == 1) " (conflict)" else " (conflict $n)"}$suffix"
            if (!file(copyPath).exists() && copyPath !in remote) break
            n++
        }
        val copyLocal = putLocal(copyPath, localSide.data) ?: return
        val copyRemote = put(copyPath, localSide.data)
        record(copyPath, localSide.data, copyRemote, copyLocal)
        fileConflicts += copyPath
        download(path, remoteSide)
    }

    /**
     * Reads the tombstones of the synchronised `library.json` and deletes the notes they name on
     * both sides, with their attachment folders (desktop spec section 12.4).
     */
    private fun deleteTombstoned() {
        val info = parseLibraryInfo(readFile(file(InfoFile)), file(InfoFile).toString())
        info.tombstones.mapTo(tombstones) { it.id }
        if (tombstones.isEmpty()) return
        for (path in local.keys.filter { noteIdOf(it) in tombstones }) {
            if (kindOf(path) == FileKind.Note) {
                try {
                    fileTransaction { Files.deleteIfExists(file(path)) }
                } catch (e: IOException) {
                    throw FormatException(ErrorCode.Io, file(path).toString(), "cannot delete the file", e)
                }
                deleted++
            }
            local.remove(path)
            revisions.remove(path)
        }
        for (path in remote.keys.filter { kindOf(it) == FileKind.Note && noteIdOf(it) in tombstones }) {
            storage.remove(path)
            deleted++
        }
        for (id in tombstones) {
            val folder = "$AttachmentsDir/$id"
            removePath(file(folder))
            if (remoteFolders.remove(folder) != null) storage.removeFolder(folder)
        }
        remote.keys.removeAll { noteIdOf(it) in tombstones }
        for (path in files.keys.filter { noteIdOf(it) in tombstones }) {
            files.remove(path)
            countChange { state.removeFile(path) }
        }
    }

    /** Records the modification times of the remote attachment folders. A folder this run changed is listed again next time, as its new time is unknown. */
    private fun recordFolders() {
        for (path in changedFolders + folders.keys + remoteFolders.keys) {
            if (!path.startsWith("$AttachmentsDir/")) continue
            val listed = remoteFolders[path]
            when {
                path in changedFolders -> state.putFolder(path, -1)
                listed != null -> if ((folders[path] ?: -1) != listed) {
                    state.putFolder(path, listed)
                    latest = maxOf(latest, listed)
                }
                else -> state.removeFolder(path)
            }
        }
    }
}

/**
 * Runs one synchronisation of a library folder with a sync storage (desktop spec section 12.3):
 * takes the remote lock, compares both sides with the sync state, copies, merges and deletes
 * files, records the new state and releases the lock.
 *
 * A folder without a library that is empty or missing receives the library of the storage
 * (desktop spec section 12.6). A storage without a library receives the library, as long as it
 * holds no other files. The library may change meanwhile; files changed while the run was about to
 * write them are left for the next run.
 *
 * @param root The library folder.
 * @param storage The sync storage.
 * @param state The library's sync state on this device.
 * @param options Options of the run.
 * @return What the run did.
 * @throws FormatException Locked while another device holds the lock, OtherLibrary or NotEmpty for
 *   a storage holding something else, NotEmpty for a download into a folder with files, NotFound
 *   for a download from a storage without a library, NewerFormat for a library of a newer Jotter,
 *   or the error of a file or of the storage.
 */
fun synchronise(root: Path, storage: RemoteStorage, state: SyncState, options: SyncOptions = SyncOptions()): SyncReport =
    Run(root.toAbsolutePath(), storage, state, options).exec()

/**
 * Reads the identifier of the library a sync storage holds, to choose the sync state before
 * downloading it.
 *
 * @throws FormatException NotFound when the storage holds no library, NewerFormat, Malformed or the
 *   error of the storage.
 */
fun remoteLibraryId(storage: RemoteStorage): String =
    parseLibraryInfo(storage.read(InfoFile), "${storage.location}/$InfoFile").id

/**
 * Checks that a sync storage may be connected to a library (desktop spec section 12.6): it holds
 * the same library, or nothing but Jotter's own temporary and lock files.
 *
 * @throws FormatException OtherLibrary or NotEmpty, NewerFormat, Malformed or the error of the storage.
 */
fun checkStorageFor(storage: RemoteStorage, libraryId: String) {
    val remoteId = try {
        remoteLibraryId(storage)
    } catch (e: FormatException) {
        if (e.code != ErrorCode.NotFound) throw e
        null
    }
    if (remoteId != null) {
        if (remoteId != libraryId) fail(ErrorCode.OtherLibrary, storage.location)
        return
    }
    // Jotter's own names all start with `.jotter-`: the lock, temporary files and probes.
    if (storage.list("").any { !it.name.startsWith(".jotter-") }) fail(ErrorCode.NotEmpty, storage.location)
}
