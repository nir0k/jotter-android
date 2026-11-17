package io.github.nir0k.jotter.core.sync

import androidx.sqlite.SQLiteConnection
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.index.databaseCall
import io.github.nir0k.jotter.core.index.execute
import io.github.nir0k.jotter.core.index.openDatabase
import io.github.nir0k.jotter.core.index.query
import io.github.nir0k.jotter.core.index.transaction
import java.nio.file.Path
import java.time.Instant

/**
 * What the last sync run recorded about one synchronised file (desktop spec section 12.2).
 *
 * @param hash The SHA-256 hash of the contents both sides had, in hexadecimal.
 * @param remoteModified The remote modification time in milliseconds since the epoch.
 * @param localModified The local modification time in milliseconds since the epoch; -1 for a file
 *   modified too recently to trust its time, which the next run reads again.
 */
data class SyncedFile(
    val hash: String,
    val remoteSize: Long,
    val remoteModified: Long,
    val localSize: Long,
    val localModified: Long,
)

/**
 * One entry of the sync log the Sync tab of Settings shows: when a run ended and what it did, or why it
 * failed.
 *
 * @param conflicts The conflict copies of notes and attachments the run made.
 * @param error Why the run failed, Deferred for a partial run that left changed files for later
 *   and still did the rest, or null for a successful run.
 */
data class SyncRun(
    val finished: Instant,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val merged: Int = 0,
    val deleted: Int = 0,
    val conflicts: Int = 0,
    val error: ErrorCode? = null,
)

/** The number of runs the sync log keeps. */
const val SyncLogSize = 5

/**
 * The schema version; an older state is dropped, which only makes the next run a first
 * connection.
 */
private const val SchemaVersion = 1

private val Schema = listOf(
    "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    "CREATE TABLE files (path TEXT PRIMARY KEY, hash TEXT NOT NULL, remote_size INTEGER NOT NULL, " +
        "remote_modified INTEGER NOT NULL, local_size INTEGER NOT NULL, local_modified INTEGER NOT NULL, base BLOB)",
    "CREATE TABLE folders (path TEXT PRIMARY KEY, modified INTEGER NOT NULL)",
    "CREATE TABLE runs (id INTEGER PRIMARY KEY, finished INTEGER NOT NULL, uploaded INTEGER NOT NULL, " +
        "downloaded INTEGER NOT NULL, merged INTEGER NOT NULL, deleted INTEGER NOT NULL, conflicts INTEGER NOT NULL, error TEXT)",
)

/**
 * SyncState is the sync state of a library on this device, in
 * `no_backup/sync/<library-id>/state.sqlite`: the device identifier, a record of every
 * synchronised file with the merge base of note and JSON files, the modification times of remote
 * attachment folders, and the log of the most recent runs. It lives outside the library, so it
 * never reaches a sync storage, and losing it only makes the next run a first connection.
 *
 * One connection per object, used by one thread at a time; each thread opens its own.
 */
class SyncState private constructor(private val db: SQLiteConnection, private val file: String) : AutoCloseable {

    /** Runs a database call, reporting its failures as Io errors of the state file. */
    private fun <T> call(block: () -> T): T = databaseCall(file, block)

    /** Returns this device's identifier, creating it on first use. */
    fun deviceId(): String = call {
        db.query("SELECT value FROM settings WHERE key = 'device'") { it.getText(0) }.singleOrNull()
            ?: newId().also { db.execute("INSERT INTO settings (key, value) VALUES ('device', ?)", it) }
    }

    /** Reads the records of all synchronised files by path relative to the library, without their merge bases. */
    fun files(): Map<String, SyncedFile> = call {
        db.query("SELECT path, hash, remote_size, remote_modified, local_size, local_modified FROM files") {
            it.getText(0) to SyncedFile(it.getText(1), it.getLong(2), it.getLong(3), it.getLong(4), it.getLong(5))
        }.toMap()
    }

    /** Reads the merge base of a file: the contents last synchronised, or null for a file without one. */
    fun base(path: String): ByteArray? = call {
        db.query("SELECT base FROM files WHERE path = ?", listOf(path)) { if (it.isNull(0)) null else it.getBlob(0) }.singleOrNull()
    }

    /**
     * Records a synchronised file with its merge base.
     *
     * @param path The file, relative to the library.
     * @param base The contents to merge against later; null for attachments.
     */
    fun putFile(path: String, file: SyncedFile, base: ByteArray?) = call {
        db.execute(
            "INSERT OR REPLACE INTO files VALUES (?, ?, ?, ?, ?, ?, ?)",
            path, file.hash, file.remoteSize, file.remoteModified, file.localSize, file.localModified, base,
        )
    }

    /** Changes the record of a file and keeps its merge base, for a file whose times changed without its contents. */
    fun touchFile(path: String, file: SyncedFile) = call {
        db.execute(
            "UPDATE files SET hash = ?, remote_size = ?, remote_modified = ?, local_size = ?, local_modified = ? WHERE path = ?",
            file.hash, file.remoteSize, file.remoteModified, file.localSize, file.localModified, path,
        )
    }

    /** Forgets a file that is gone on both sides. */
    fun removeFile(path: String) = call { db.execute("DELETE FROM files WHERE path = ?", path) }

    /** Reads the remote modification times of attachment folders by path. */
    fun folders(): Map<String, Long> = call {
        db.query("SELECT path, modified FROM folders") { it.getText(0) to it.getLong(1) }.toMap()
    }

    /** Records the remote modification time of an attachment folder; -1 lists it again next time. */
    fun putFolder(path: String, modified: Long) = call {
        db.execute("INSERT OR REPLACE INTO folders VALUES (?, ?)", path, modified)
    }

    /** Forgets an attachment folder. */
    fun removeFolder(path: String) = call { db.execute("DELETE FROM folders WHERE path = ?", path) }

    /** Forgets every file and folder, keeping the device identifier and the log; the next run is a first connection. */
    fun clear() = call {
        db.execute("DELETE FROM files")
        db.execute("DELETE FROM folders")
    }

    /** Starts a transaction; the changes are written together by [commit]. */
    fun begin() = call { db.execute("BEGIN IMMEDIATE") }

    /** Writes the changes since [begin]. */
    fun commit() = call { db.execute("COMMIT") }

    /** Adds a run to the log, removing the entries beyond the [SyncLogSize] most recent. */
    fun addRun(run: SyncRun) = call {
        db.transaction {
            db.execute(
                "INSERT INTO runs (finished, uploaded, downloaded, merged, deleted, conflicts, error) VALUES (?, ?, ?, ?, ?, ?, ?)",
                run.finished.toEpochMilli(), run.uploaded, run.downloaded, run.merged, run.deleted, run.conflicts, run.error?.name,
            )
            db.execute("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY id DESC LIMIT $SyncLogSize)")
        }
    }

    /** Reads the log, the most recent run first. */
    fun runs(): List<SyncRun> = call {
        db.query("SELECT finished, uploaded, downloaded, merged, deleted, conflicts, error FROM runs ORDER BY id DESC") { row ->
            SyncRun(
                Instant.ofEpochMilli(row.getLong(0)),
                row.getLong(1).toInt(), row.getLong(2).toInt(), row.getLong(3).toInt(), row.getLong(4).toInt(), row.getLong(5).toInt(),
                if (row.isNull(6)) null else ErrorCode.entries.firstOrNull { it.name == row.getText(6) } ?: ErrorCode.Io,
            )
        }
    }

    /** Closes the database connection. */
    override fun close() = db.close()

    companion object {
        /**
         * Opens or creates the sync state at a path.
         *
         * @param path The database file; missing folders are created.
         * @throws io.github.nir0k.jotter.core.format.FormatException Io.
         */
        fun open(path: Path): SyncState {
            val db = openDatabase(
                path,
                listOf("journal_mode = WAL", "busy_timeout = 5000", "synchronous = NORMAL"),
                SchemaVersion,
                listOf("settings", "files", "folders", "runs"),
                Schema,
            )
            return SyncState(db, path.toAbsolutePath().toString())
        }
    }
}
