package io.github.nir0k.jotter.core.sync

import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.Tombstone
import io.github.nir0k.jotter.core.format.attachmentSource
import io.github.nir0k.jotter.core.format.formatTime
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.format.newNote
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.format.serializeNote
import io.github.nir0k.jotter.core.format.serializeNote
import io.github.nir0k.jotter.core.format.writeFileAtomic
import io.github.nir0k.jotter.core.sftp.SftpStand
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import io.github.nir0k.jotter.core.format.MaxFileBytes
import org.junit.Assert.assertThrows

/**
 * Checks synchronisation (desktop spec section 12) with two devices sharing one sync storage, as
 * the desktop's sync tests do: copies, merges and conflicts, deletions and tombstones, the first
 * connection, the lock and the handling of files the storage holds.
 *
 * The storage is a local folder. With `JOTTER_ANDROID_SFTP` set (see [SftpStand]), the same
 * scenarios run against that SFTP server, each in a folder of its own.
 */
@OptIn(ExperimentalPathApi::class)
class SyncTest {
    /** A device in a scenario: its library folder and its sync state. */
    private class Device(val root: Path, val statePath: Path) {
        val state: SyncState = SyncState.open(statePath)
    }

    private lateinit var dir: Path
    private lateinit var folder: String
    private lateinit var storage: RemoteStorage
    private lateinit var a: Device
    private lateinit var b: Device
    private val devices = mutableListOf<Device>()
    private val storages = mutableListOf<RemoteStorage>()

    @Before
    fun setUp() {
        dir = createTempDirectory("sync")
        folder = "run-${newId()}"
        storage = openStorage("")
        a = device("a")
        b = device("b")
    }

    @After
    fun tearDown() {
        devices.forEach { it.state.close() }
        SftpStand.remove(folder)
        storages.forEach { (it as? AutoCloseable)?.close() }
        dir.deleteRecursively()
    }

    /** Opens the sync storage of the running test, or a folder inside it. */
    private fun openStorage(sub: String): RemoteStorage {
        val path = listOf(folder, sub).filter { it.isNotEmpty() }.joinToString("/")
        return (SftpStand.open(path) ?: LocalStorage(dir.resolve("remote").resolve(path))).also { storages += it }
    }

    /** Returns a device with a library folder and a sync state in the test folder; the folder itself is not created. */
    private fun device(name: String) = Device(dir.resolve(name), dir.resolve("$name-state.sqlite")).also { devices += it }

    private fun sync(device: Device, through: RemoteStorage = storage): SyncReport = synchronise(device.root, through, device.state)

    /** Runs a sync that must fail and returns its error code. */
    private fun syncError(device: Device, through: RemoteStorage = storage): ErrorCode = try {
        sync(device, through)
        error("the sync run succeeded")
    } catch (e: FormatException) {
        e.code
    }

    private fun readNote(device: Device, id: String): Note = Library.open(device.root).readNote(id)

    /** Writes a note into a library, as the editor saves it. */
    private fun writeNote(device: Device, note: Note) {
        Library.open(device.root).writeNote(note.copy(meta = note.meta.copy(updated = now())))
    }

    private fun noteIds(device: Device): List<String> =
        device.root.resolve("notes").listDirectoryEntries("*.html").map { it.name.removeSuffix(".html") }.sorted()

    private fun readLocal(device: Device, path: String): ByteArray = device.root.resolve(path).readBytes()

    private fun writeLocal(device: Device, path: String, data: String) = writeFileAtomic(device.root.resolve(path), data.toByteArray())

    /**
     * Creates the library on device a with notes of the given bodies, uploads it and downloads it
     * on device b.
     *
     * @return The first note, or null when no bodies were given.
     */
    private fun share(vararg bodies: String): Note? {
        Library.create(a.root)
        var first: Note? = null
        for (body in bodies) {
            val note = newNote().copy(body = body)
            writeNote(a, note)
            if (first == null) first = readNote(a, note.meta.id)
        }
        sync(a)
        sync(b)
        return first
    }

    /** Deletes a note as Jotter does: its file and attachments go, and a tombstone is recorded. */
    private fun deletePermanently(device: Device, id: String) {
        val library = Library.open(device.root)
        Files.delete(library.notePath(id))
        library.attachmentsDir(id).deleteRecursively()
        library.changeInfo { it.copy(tombstones = it.tombstones + Tombstone(id, now())) }
    }

    /** A library uploaded to an empty storage downloads into an empty folder on another device with its notes and attachments, file names with any characters included; runs after that find nothing to do. */
    @Test
    fun uploadAndDownload() {
        val note = share("<p>Hello</p>")!!
        val attachment = "attachments/${note.meta.id}/Отчёт #1 (final)*?.pdf"
        writeLocal(a, attachment, "%PDF-1.7")
        sync(a)
        sync(b)
        assertEquals(note, readNote(b, note.meta.id))
        assertEquals("%PDF-1.7", String(readLocal(b, attachment)))
        assertArrayEquals(readLocal(a, "library.json"), readLocal(b, "library.json"))
        for (device in listOf(a, b)) {
            val report = sync(device)
            assertEquals(0, report.uploaded + report.downloaded + report.merged + report.deleted)
        }
    }

    /** A download goes only into an empty folder, and only from a storage holding a library. */
    @Test
    fun downloadNeedsEmptyFolderAndLibrary() {
        assertEquals(ErrorCode.NotFound, syncError(b))
        Library.create(a.root)
        sync(a)
        writeLocal(b, "notes.txt", "mine")
        assertEquals(ErrorCode.NotEmpty, syncError(b))
    }

    /** Changes made on one device reach the other. */
    @Test
    fun oneSidedChanges() {
        val note = share("<p>A</p>")!!.copy(body = "<p>A edited</p>")
        writeNote(a, note)
        sync(a)
        assertEquals(1, sync(b).downloaded)
        assertEquals(note.body, readNote(b, note.meta.id).body)
    }

    /** Edits of different blocks on two devices combine without a conflict. */
    @Test
    fun differentBlocksMerge() {
        val note = share("<p>A</p>\n<p>B</p>\n<p>C</p>")!!
        writeNote(a, note.copy(body = "<p>A from a</p>\n<p>B</p>\n<p>C</p>"))
        writeNote(b, note.copy(body = "<p>A</p>\n<p>B</p>\n<p>C from b</p>"))
        sync(a)
        val report = sync(b)
        assertEquals(1, report.merged)
        assertTrue(report.noteConflicts.isEmpty())
        sync(a)
        val merged = "<p>A from a</p>\n<p>B</p>\n<p>C from b</p>"
        assertEquals(merged, readNote(a, note.meta.id).body)
        assertEquals(merged, readNote(b, note.meta.id).body)
    }

    /** The same block edited on two devices keeps the version synchronised first; the other becomes a conflict copy in the same collections, which reaches both devices. */
    @Test
    fun sameBlockMakesConflictCopy() {
        var note = share("<p>Shared</p>")!!
        note = note.copy(meta = note.meta.copy(collections = listOf(newId())))
        writeNote(a, note)
        sync(a)
        sync(b)
        val onA = readNote(a, note.meta.id).copy(body = "<p>From a</p>")
        val onB = readNote(b, note.meta.id).copy(body = "<p>From b</p>")
        writeNote(a, onA)
        writeNote(b, onB)
        sync(a)
        val report = synchronise(b.root, storage, b.state, SyncOptions(conflictTitle = { "$it (conflict)" }))
        assertEquals(1, report.noteConflicts.size)
        assertEquals(onA.body, readNote(b, note.meta.id).body)
        val copy = readNote(b, report.noteConflicts.single())
        assertEquals(onB.body, copy.body)
        assertEquals(note.meta.collections, copy.meta.collections)
        assertEquals("${note.meta.title} (conflict)", copy.meta.title)
        sync(a)
        assertEquals(noteIds(a), noteIds(b))
    }

    /** An attachment changed on two devices keeps the remote file; the local one stays beside it as a conflict copy on both devices. */
    @Test
    fun attachmentConflict() {
        val note = share("<p>Photo</p>")!!
        val path = "attachments/${note.meta.id}/photo.jpg"
        writeLocal(a, path, "original")
        sync(a)
        sync(b)
        writeLocal(a, path, "from a")
        writeLocal(b, path, "from b!")
        sync(a)
        val report = sync(b)
        val copy = "attachments/${note.meta.id}/photo (conflict).jpg"
        assertEquals(listOf(copy), report.fileConflicts)
        assertEquals("from a", String(readLocal(b, path)))
        assertEquals("from b!", String(readLocal(b, copy)))
        sync(a)
        assertEquals("from b!", String(readLocal(a, copy)))
    }

    /** A note deleted permanently on one device is deleted on the other with its attachments, even when that device edited it meanwhile; it does not come back. */
    @Test
    fun tombstoneDeletesEverywhere() {
        val note = share("<p>Doomed</p>")!!
        val attachment = "attachments/${note.meta.id}/file.txt"
        writeLocal(a, attachment, "data")
        sync(a)
        sync(b)
        deletePermanently(a, note.meta.id)
        writeNote(b, note.copy(body = "<p>Still editing</p>"))
        sync(a)
        sync(b)
        sync(a)
        for (device in listOf(a, b)) {
            assertFalse(note.meta.id in noteIds(device))
            assertFalse(device.root.resolve(attachment).exists())
        }
        assertEquals(ErrorCode.NotFound, runCatching { storage.read("notes/${note.meta.id}.html") }.exceptionOrNull().let { (it as FormatException).code })
    }

    /** A note file removed outside Jotter is removed on the other device too, unless that device changed it: then it is copied back, so no change is lost. */
    @Test
    fun removedOutside() {
        val kept = share("<p>Kept</p>", "<p>Gone</p>")!!
        val gone = noteIds(a).first { it != kept.meta.id }
        Files.delete(a.root.resolve("notes/$gone.html"))
        Files.delete(a.root.resolve("notes/${kept.meta.id}.html"))
        val edited = kept.copy(body = "<p>Kept and edited</p>")
        writeNote(b, edited)
        sync(a)
        sync(b)
        sync(a)
        assertEquals(listOf(kept.meta.id), noteIds(a))
        assertEquals(listOf(kept.meta.id), noteIds(b))
        assertEquals(edited.body, readNote(a, kept.meta.id).body)
    }

    /** Connecting a copy of the same library: equal notes match, and a note that differs keeps the remote version with the local one as a conflict copy. */
    @Test
    fun firstConnectionToSameLibrary() {
        val same = share("<p>Same</p>")!!
        val other = newNote().copy(body = "<p>Remote</p>")
        writeNote(a, other)
        sync(a)
        // Device c holds a copy of the library made before, without sync state.
        val copy = device("copy")
        Files.createDirectories(copy.root.resolve("notes"))
        for (file in listOf("library.json", "collections.json", "rules.json", "notes/${same.meta.id}.html")) {
            Files.copy(a.root.resolve(file), copy.root.resolve(file))
        }
        writeNote(copy, other.copy(body = "<p>Local</p>"))
        val report = sync(copy)
        assertEquals(1, report.noteConflicts.size)
        assertEquals("<p>Remote</p>", readNote(copy, other.meta.id).body)
        assertEquals("<p>Local</p>", readNote(copy, report.noteConflicts.single()).body)
    }

    /** A file name in a listing that would leave its attachment folder, as a hostile server could send, is never downloaded. */
    @Test
    fun unsafeRemoteNamesIgnored() {
        val note = share("<p>Photo</p>")!!
        val folder = "attachments/${note.meta.id}"
        storage.makeFolder(folder)
        storage.write("$folder/photo.jpg", "photo".toByteArray())
        val escape = "../../../escaped"
        val hostile = object : RemoteStorage by storage {
            override fun list(path: String) =
                storage.list(path) + if (path == folder) listOf(RemoteEntry(escape, false, 4, now().toEpochMilli())) else emptyList()

            override fun read(path: String) = if (path.endsWith(escape)) "evil".toByteArray() else storage.read(path)
        }
        val report = sync(b, hostile)
        assertEquals(1, report.downloaded)
        assertEquals("photo", String(readLocal(b, "$folder/photo.jpg")))
        assertFalse(dir.resolve("escaped").exists())
    }

    /** A storage holding another library, other files or a library of a newer Jotter is refused. */
    @Test
    fun refusesOtherContents() {
        share()
        val other = device("other")
        Library.create(other.root)
        assertEquals(ErrorCode.OtherLibrary, syncError(other))
        assertEquals(ErrorCode.OtherLibrary, runCatching { checkStorageFor(storage, Library.open(other.root).info.id) }
            .exceptionOrNull().let { (it as FormatException).code })

        val info = String(readLocal(a, "library.json")).replace("\"format\": 1", "\"format\": 99")
        storage.write("library.json", info.toByteArray())
        assertEquals(ErrorCode.NewerFormat, syncError(a))

        storage.makeFolder("foreign")
        val foreign = openStorage("foreign")
        foreign.write("notes.txt", "someone else's".toByteArray())
        assertEquals(ErrorCode.NotEmpty, syncError(a, foreign))
    }

    /** A lock another device holds stops the run; an expired one is taken over, and the run removes its own lock when it ends. */
    @Test
    fun lock() {
        share()
        fun lockFor(seconds: Long) =
            "{\n    \"device\": \"${newId()}\",\n    \"expires\": \"${formatTime(now().plusSeconds(seconds))}\"\n}\n".toByteArray()
        storage.write(".jotter-lock", lockFor(300))
        assertEquals(ErrorCode.Locked, syncError(a))
        storage.write(".jotter-lock", lockFor(-1))
        sync(a)
        assertTrue(storage.list("").none { it.name == ".jotter-lock" })
    }

    /** Two devices syncing at once take turns through the lock, and both end with both changes. */
    @Test
    fun concurrentRuns() {
        share()
        writeNote(a, newNote())
        writeNote(b, newNote())
        // Each device has its own storage connection and state, as two phones would.
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runs = listOf(a, b).map { device ->
                val through = openStorage("")
                pool.submit(
                    Callable {
                        SyncState.open(device.statePath).use { state ->
                            repeat(100) {
                                try {
                                    return@Callable synchronise(device.root, through, state)
                                } catch (e: FormatException) {
                                    if (e.code != ErrorCode.Locked) throw e
                                    Thread.sleep(10)
                                }
                            }
                            error("the lock was never free")
                        }
                    },
                )
            }
            runs.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        // Whichever device ran first has not seen the other's change yet.
        sync(a)
        sync(b)
        assertEquals(noteIds(a), noteIds(b))
        assertEquals(2, noteIds(a).size)
    }

    /** A remote file rewritten with the same contents is not a change, and temporary files of interrupted uploads are removed after a day. */
    @Test
    fun remoteHousekeeping() {
        val note = share("<p>Text</p>")!!
        val path = "notes/${note.meta.id}.html"
        storage.write(path, storage.read(path))
        val report = sync(b)
        assertEquals(0, report.downloaded + report.merged)

        val local = storage as? LocalStorage
        assumeTrue("File times cannot be set back on an SFTP server; checked with a local folder", local != null)
        storage.write(".jotter-tmp-old", "partial".toByteArray())
        storage.write(".jotter-tmp-new", "partial".toByteArray())
        local!!.age(".jotter-tmp-old", 2)
        sync(a)
        val names = storage.list("").map { it.name }
        assertFalse(".jotter-tmp-old" in names)
        assertTrue(".jotter-tmp-new" in names)
    }

    /** Collections added on two devices are all kept. */
    @Test
    fun libraryFilesMerge() {
        share()
        for ((device, name) in listOf(a to "From a", b to "From b")) {
            val library = Library.open(device.root)
            val time = now()
            library.changeCollections { it + Collection(newId(), null, name, created = time, updated = time) }
        }
        sync(a)
        sync(b)
        sync(a)
        assertArrayEquals(readLocal(a, "collections.json"), readLocal(b, "collections.json"))
        assertEquals(2, Library.open(a.root).readCollections().size)
    }

    /** A file written again replaces the old one in one step, leaving no temporary file, and an existing file is not created again. */
    @Test
    fun writeReplaces() {
        storage.write("file.txt", "first".toByteArray())
        storage.write("file.txt", "second".toByteArray())
        assertEquals("second", String(storage.read("file.txt")))
        assertEquals(listOf("file.txt"), storage.list("").map { it.name })
        assertFalse(storage.create("file.txt", "third".toByteArray()))
        assertTrue(storage.create("other.txt", "fourth".toByteArray()))
        assertEquals(listOf("file.txt", "other.txt"), storage.list("").map { it.name }.sorted())
    }

    /** An attachment of the largest size Jotter accepts goes both ways unchanged through SFTP, whose uploads go in pieces. */
    @Test
    fun largeAttachment() {
        assumeTrue("Runs against an SFTP server: set JOTTER_ANDROID_SFTP", SftpStand.configured)
        val note = share("<p>Video</p>")!!
        val path = "attachments/${note.meta.id}/video.mp4"
        val data = ByteArray(50 * 1024 * 1024) { ((it * 7919L) shr 8).toByte() }
        writeFileAtomic(a.root.resolve(path), data)
        sync(a)
        sync(b)
        assertArrayEquals(data, readLocal(b, path))
    }

    /**
     * A file on the storage larger than a library holds, which only a damaged or hostile server
     * offers, stops the run with TooLarge instead of being read into memory; checked against an SFTP
     * server, whose reads count the data.
     */
    @Test
    fun tooLargeRemoteFile() {
        assumeTrue("Runs against an SFTP server: set JOTTER_ANDROID_SFTP", SftpStand.configured)
        val note = share("<p>Video</p>")!!
        sync(a)
        val path = "attachments/${note.meta.id}/huge.bin"
        storage.makeFolder("attachments/${note.meta.id}")
        storage.write(path, ByteArray(MaxFileBytes.toInt() + 1))
        val error = assertThrows(FormatException::class.java) { sync(b) }
        assertEquals(ErrorCode.TooLarge, error.code)
    }

    /** Returns a storage that does something before each read of a file, as a slow transfer leaves time for. */
    private fun beforeRead(action: (String) -> Unit) = object : RemoteStorage by storage {
        override fun read(path: String): ByteArray {
            action(path)
            return storage.read(path)
        }
    }

    /** A conflict copy owns duplicates of its attachments, on both devices, uploaded before the note naming them; the original's files stay. */
    @Test
    fun conflictCopyOwnsItsAttachments() {
        val note = share("<p>Shared</p>")!!
        val id = note.meta.id
        val attachment = "attachments/$id/a.png"
        writeLocal(b, attachment, "image")
        writeNote(b, readNote(b, id).copy(body = "<p><img src=\"${attachmentSource(id, "a.png")}\"></p>"))
        writeNote(a, readNote(a, id).copy(body = "<p>From a</p>"))
        sync(a)
        val uploads = mutableListOf<String>()
        val recording = object : RemoteStorage by storage {
            override fun write(path: String, data: ByteArray): RemoteEntry {
                uploads += path
                return storage.write(path, data)
            }
        }
        val copyId = sync(b, recording).noteConflicts.single()
        val copied = "attachments/$copyId/a.png"
        assertEquals("<p><img src=\"${attachmentSource(copyId, "a.png")}\"></p>", readNote(b, copyId).body)
        assertEquals("image", String(readLocal(b, copied)))
        assertEquals("image", String(readLocal(b, attachment)))
        assertTrue(uploads.indexOf(copied) in 0 until uploads.indexOf("notes/$copyId.html"))
        sync(a)
        assertEquals("image", String(readLocal(a, copied)))
    }

    /** A conflict copy whose attachment is missing stops the run before the local version is replaced, and nothing of the copy is left. */
    @Test
    fun conflictCopyNeedsItsAttachments() {
        val note = share("<p>Shared</p>")!!
        val id = note.meta.id
        val onB = "<p><img src=\"${attachmentSource(id, "gone.png")}\"></p>"
        writeNote(b, readNote(b, id).copy(body = onB))
        writeNote(a, readNote(a, id).copy(body = "<p>From a</p>"))
        sync(a)
        assertEquals(ErrorCode.Io, syncError(b))
        assertEquals(onB, readNote(b, id).body)
        assertEquals(listOf(id), noteIds(b))
    }

    /** A version that cannot be read for a merge is an error for the person to repair, and its file stays as it is. */
    @Test
    fun unreadableMergeInputIsAnError() {
        val note = share("<p>Shared</p>")!!
        val id = note.meta.id
        writeNote(a, readNote(a, id).copy(body = "<p>From a</p>"))
        sync(a)
        val damaged = serializeNote(readNote(b, id).copy(body = "<p>From b</p><video></video>"))
        writeFileAtomic(b.root.resolve("notes/$id.html"), damaged)
        assertEquals(ErrorCode.Malformed, syncError(b))
        assertArrayEquals(damaged, readLocal(b, "notes/$id.html"))
    }

    /** A file changed in the library while the run was about to replace it is left, and the run reports it as deferred. */
    @Test
    fun changedDuringRunIsDeferred() {
        val note = share("<p>Shared</p>")!!
        val id = note.meta.id
        val path = "notes/$id.html"
        writeNote(a, readNote(a, id).copy(body = "<p>From a</p>"))
        sync(a)
        val local = readNote(b, id)
        val through = beforeRead { if (it == path) writeNote(b, local.copy(body = "<p>Typed on b</p>")) }
        // The change in the file comes after the run listed it, so the run took it for unchanged.
        assertEquals(listOf(path), sync(b, through).skipped)
        assertEquals("<p>Typed on b</p>", readNote(b, id).body)
        assertTrue(sync(b).skipped.isEmpty())
    }

    /**
     * A run deferring attachment downloads brings notes but never reads new or changed attachments,
     * still uploads its own and takes no missing one for a deletion; the next ordinary run, without
     * a full listing, downloads them.
     */
    @Test
    fun deferredAttachmentDownloads() {
        val note = share("<p>Photos</p>")!!
        val id = note.meta.id
        val changed = "attachments/$id/photo.jpg"
        val added = "attachments/$id/new.jpg"
        val own = "attachments/$id/mine.jpg"
        writeLocal(a, changed, "original")
        sync(a)
        sync(b)
        writeLocal(a, changed, "edited")
        writeLocal(a, added, "added")
        writeNote(a, readNote(a, id).copy(body = "<p>Photos, two</p>"))
        sync(a)
        writeLocal(b, own, "from b")
        val noAttachmentReads = beforeRead { require(!it.startsWith("attachments/")) { "read $it" } }
        val report = synchronise(b.root, noAttachmentReads, b.state, SyncOptions(deferAttachmentDownloads = true))
        assertEquals(2, report.deferredAttachments)
        assertEquals("<p>Photos, two</p>", readNote(b, id).body)
        assertEquals("original", String(readLocal(b, changed)))
        assertFalse(b.root.resolve(added).exists())
        assertEquals(2, sync(b).downloaded)
        assertEquals("edited", String(readLocal(b, changed)))
        assertEquals("added", String(readLocal(b, added)))
        sync(a)
        assertEquals("from b", String(readLocal(a, own)))
        assertEquals("added", String(readLocal(a, added)))
    }

    /** A long run renews its lock from within storage operations, on the storage's own thread. */
    @Test
    fun longRunRenewsItsLock() {
        val note = share("<p>Shared</p>")!!
        writeNote(a, readNote(a, note.meta.id).copy(body = "<p>From a</p>"))
        sync(a)
        var clock = 0L
        var renewals = 0
        val through = object : RemoteStorage by storage {
            override fun read(path: String): ByteArray {
                if (path == "notes/${note.meta.id}.html") {
                    clock += 6 * 60 * 1000L
                }
                return storage.read(path)
            }

            override fun write(path: String, data: ByteArray): RemoteEntry {
                if (path == ".jotter-lock") renewals++
                return storage.write(path, data)
            }
        }
        synchronise(b.root, through, b.state, SyncOptions(elapsedMs = { clock }))
        assertEquals(1, renewals)
        assertEquals("<p>From a</p>", readNote(b, note.meta.id).body)
    }

    /** A lock taken over by another device during a transfer stops the run and stays that device's. */
    @Test
    fun lostLockStopsTheRun() {
        val note = share("<p>Shared</p>")!!
        val path = "notes/${note.meta.id}.html"
        writeNote(a, readNote(a, note.meta.id).copy(body = "<p>From a</p>"))
        sync(a)
        var clock = 0L
        val other = "{\"device\": \"other\"}".toByteArray()
        val through = beforeRead {
            if (it == path) {
                storage.write(".jotter-lock", other)
                clock += 6 * 60 * 1000L
            }
        }
        val error = runCatching { synchronise(b.root, through, b.state, SyncOptions(elapsedMs = { clock })) }.exceptionOrNull() as FormatException
        assertEquals(ErrorCode.Locked, error.code)
        assertArrayEquals(other, storage.read(".jotter-lock"))
        storage.remove(".jotter-lock")
        assertEquals("<p>Shared</p>", readNote(b, note.meta.id).body)
    }

    /** A symbolic link or special file in the library, or a folder that cannot be listed, stops the run without touching the storage's notes. */
    @Test
    fun unsafeLibraryFilesStopTheRun() {
        val note = share("<p>Shared</p>")!!
        val path = "notes/${note.meta.id}.html"
        val outside = dir.resolve("outside").also { Files.writeString(it, "secret") }
        val link = a.root.resolve("notes/${newId()}.html")
        Files.createSymbolicLink(link, outside)
        assertEquals(ErrorCode.Io, syncError(a))
        Files.delete(link)
        val folder = a.root.resolve("attachments/${newId()}")
        Files.createSymbolicLink(folder, dir)
        assertEquals(ErrorCode.Io, syncError(a))
        Files.delete(folder)

        val moved = dir.resolve("notes-moved")
        Files.move(a.root.resolve("notes"), moved)
        assertEquals(ErrorCode.Io, syncError(a))
        assertTrue(storage.list("notes").any { it.name == path.substringAfter('/') })
        Files.move(moved, a.root.resolve("notes"))
        assertEquals(0, sync(a).deleted)
    }
}
