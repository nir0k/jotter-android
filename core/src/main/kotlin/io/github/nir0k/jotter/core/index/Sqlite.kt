package io.github.nir0k.jotter.core.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Runs a statement with bound values, reading each result row. */
internal fun <T> SQLiteConnection.query(sql: String, values: List<Any?> = emptyList(), read: (SQLiteStatement) -> T): List<T> =
    prepare(sql).use { statement ->
        values.forEachIndexed { i, value ->
            when (value) {
                null -> statement.bindNull(i + 1)
                is String -> statement.bindText(i + 1, value)
                is Long -> statement.bindLong(i + 1, value)
                is Int -> statement.bindLong(i + 1, value.toLong())
                is Boolean -> statement.bindLong(i + 1, if (value) 1 else 0)
                is ByteArray -> statement.bindBlob(i + 1, value)
                else -> error("cannot bind ${value::class}")
            }
        }
        buildList { while (statement.step()) add(read(statement)) }
    }

/** Runs a statement with bound values, ignoring any result rows. */
internal fun SQLiteConnection.execute(sql: String, vararg values: Any?) {
    query(sql, values.toList()) {}
}

/** Runs a block in one transaction, rolled back when it fails. */
internal fun <T> SQLiteConnection.transaction(block: () -> T): T {
    execute("BEGIN IMMEDIATE")
    try {
        return block().also { execute("COMMIT") }
    } catch (e: Throwable) {
        runCatching { execute("ROLLBACK") }
        throw e
    }
}

/**
 * Runs a database call and reports its failures as Io errors of the database file. The driver
 * throws `androidx.sqlite.SQLiteException` on the JVM but `android.database.SQLException` on
 * Android, both runtime exceptions, so every runtime failure of the database is caught here.
 *
 * @param file The database file, for the error.
 * @throws FormatException Io.
 */
internal fun <T> databaseCall(file: String, block: () -> T): T = try {
    block()
} catch (e: RuntimeException) {
    throw FormatException(ErrorCode.Io, file, e.message.orEmpty(), e)
}

/**
 * Opens or creates an SQLite database with the bundled SQLite, which has FTS5, and sets it up: runs
 * the connection's pragmas and, when the database is new or outdated, drops [tables] and builds
 * [schema] at [version] in one transaction. Such a database holds only data that may be lost:
 * derived data or state whose loss only repeats work.
 *
 * @param path The database file; missing folders are created.
 * @param pragmas Settings of the connection, such as `journal_mode = WAL`.
 * @param version The schema version, kept in `user_version`.
 * @param tables Every table of this and earlier schemas.
 * @param current Tells whether a database of the right version is up to date in other respects.
 * @param seed Writes the rows a new schema starts with.
 * @throws FormatException Io.
 */
internal fun openDatabase(
    path: Path,
    pragmas: List<String>,
    version: Int,
    tables: List<String>,
    schema: List<String>,
    current: SQLiteConnection.() -> Boolean = { true },
    seed: SQLiteConnection.() -> Unit = {},
): SQLiteConnection {
    val file = path.toAbsolutePath().toString()
    try {
        Files.createDirectories(path.toAbsolutePath().parent)
    } catch (e: IOException) {
        throw FormatException(ErrorCode.Io, file, e.message.orEmpty(), e)
    }
    val db = databaseCall(file) { BundledSQLiteDriver().open(file) }
    try {
        databaseCall(file) {
            for (pragma in pragmas) db.execute("PRAGMA $pragma")
            if (db.query("PRAGMA user_version") { it.getLong(0) }.single() != version.toLong() || !db.current()) {
                db.transaction {
                    for (table in tables) db.execute("DROP TABLE IF EXISTS $table")
                    for (statement in schema) db.execute(statement)
                    db.seed()
                    db.execute("PRAGMA user_version = $version")
                }
            }
        }
    } catch (e: FormatException) {
        db.close()
        throw e
    }
    return db
}
