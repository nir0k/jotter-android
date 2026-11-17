package io.github.nir0k.jotter.core.sync

/**
 * A regular file or a folder in a sync storage.
 *
 * @param modified Modification time in milliseconds since the epoch, at the precision of the storage.
 */
data class RemoteEntry(val name: String, val folder: Boolean, val size: Long, val modified: Long)

/**
 * RemoteStorage is a sync storage: a remote folder holding a copy of the library (desktop spec
 * section 12). Every sync storage implements this interface; the sync engine knows no other.
 *
 * Paths are relative to the remote folder, with `/` between their parts; the empty path is the
 * remote folder itself. An implementation is used by one thread at a time. Failures are thrown as
 * [io.github.nir0k.jotter.core.format.FormatException]: NotFound for a missing file or folder,
 * Network for a lost connection, Io otherwise.
 */
interface RemoteStorage {
    /** Describes the remote folder for messages, such as `user@host:/path`. */
    val location: String

    /**
     * How precise modification times are, in milliseconds: changes this close to each other may
     * carry the same time. A sync run waits this long after its last change before it releases
     * the lock, so later changes carry later times.
     */
    val timePrecisionMs: Int

    /**
     * Called between bounded steps of a long listing or transfer, on the thread of the operation:
     * the sync run renews its lock here, through this same storage, and throws when it lost the
     * lock, which stops the operation. Null outside a run.
     */
    var checkpoint: (() -> Unit)?

    /**
     * Lists the regular files and folders of a folder; other kinds of entries are left out.
     *
     * @return The entries in no particular order.
     */
    fun list(path: String): List<RemoteEntry>

    /**
     * Reads a whole file of at most [io.github.nir0k.jotter.core.format.MaxFileBytes], the largest file
     * a library holds, so a damaged or hostile storage cannot exhaust the memory.
     *
     * @throws io.github.nir0k.jotter.core.format.FormatException TooLarge for a larger file.
     */
    fun read(path: String): ByteArray

    /**
     * Writes a file so that readers see the old or the new contents only: the data goes to a file
     * `.jotter-tmp-<random>` in the same folder, which is then renamed over the target. The folder
     * must exist.
     *
     * @return The written file as a listing shows it.
     */
    fun write(path: String, data: ByteArray): RemoteEntry

    /**
     * Creates a file only when it does not exist yet, as the lock needs.
     *
     * @return True when the file was created, false when it already existed.
     */
    fun create(path: String, data: ByteArray): Boolean

    /** Renames a file, replacing an existing target; NotFound when [from] is missing. */
    fun rename(from: String, to: String)

    /** Removes a file; a missing file is not an error. */
    fun remove(path: String)

    /** Removes a folder with the files in it; a missing folder is not an error. */
    fun removeFolder(path: String)

    /** Creates a folder and its missing parents; an existing folder is not an error. */
    fun makeFolder(path: String)
}
