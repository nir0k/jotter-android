package io.github.nir0k.jotter.core.format

import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel

private val IdPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

private val random = SecureRandom()

/**
 * Creates an identifier for something new: a lowercase UUID version 7, so identifiers sort by
 * creation time (desktop spec section 3.2).
 */
fun newId(): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    val millis = System.currentTimeMillis()
    for (i in 0 until 6) bytes[i] = (millis ushr (40 - 8 * i)).toByte()
    bytes[6] = (bytes[6].toInt() and 0x0F or 0x70).toByte()
    bytes[8] = (bytes[8].toInt() and 0x3F or 0x80).toByte()
    val high = bytes.copyOfRange(0, 8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    val low = bytes.copyOfRange(8, 16).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    return UUID(high, low).toString()
}

/** Tells whether a text is an identifier: a lowercase UUID of any version. */
fun isValidId(id: String): Boolean = IdPattern.matches(id)

/**
 * Tells whether a name from outside, such as a server's listing or a note's address, names a file
 * inside its folder: not empty, without a path separator and not `.` or `..`.
 */
fun isSafeFileName(name: String): Boolean = name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != ".."

/** Formats times as Qt's ISO form with milliseconds in UTC: `2026-10-23T10:15:00.000Z`. */
private val TimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Reads a date and time with optional seconds fraction, separated by `T` or a space. */
private val LocalTimeFormat: DateTimeFormatter = DateTimeFormatterBuilder()
    .append(DateTimeFormatter.ISO_LOCAL_DATE)
    .optionalStart().appendLiteral('T').optionalEnd()
    .optionalStart().appendLiteral(' ').optionalEnd()
    .append(DateTimeFormatter.ISO_LOCAL_TIME)
    .toFormatter()

/** Reads [LocalTimeFormat] followed by `Z` or an offset such as `+03:00`. */
private val OffsetTimeFormat: DateTimeFormatter = DateTimeFormatterBuilder()
    .append(LocalTimeFormat)
    .appendOffset("+HH:MM", "Z")
    .toFormatter()

/** Formats a time for a library file. */
fun formatTime(time: Instant): String = TimeFormat.format(time)

/**
 * Reads a time of a library file as the desktop does: an ISO date and time with or without an
 * offset, a time without one being local to the device, kept to the millisecond.
 *
 * @param text The time as written.
 * @return The time, or null when the text is not a time.
 */
fun parseTime(text: String): Instant? = try {
    OffsetDateTime.parse(text, OffsetTimeFormat).toInstant()
} catch (_: DateTimeParseException) {
    try {
        LocalDateTime.parse(text, LocalTimeFormat).atZone(ZoneId.systemDefault()).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}?.truncatedTo(ChronoUnit.MILLIS)

/** Returns the current time to the millisecond, the precision library files keep. */
fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

/** Serialises the local read, compare and write steps of the sync run and the application. */
private val FileTransactions = ReentrantLock()

/**
 * Runs a short local transaction over library files: what it reads cannot be replaced by another
 * thread of this process before it writes. One lock serves all files, so nested transactions cannot
 * deadlock. Never run a network operation or a database call inside it: a scan or a slow server
 * would then hold back every save.
 *
 * @return What the transaction returned.
 */
fun <T> fileTransaction(block: () -> T): T = FileTransactions.withLock(block)

/**
 * Opens a regular file for reading without following a symbolic link in its last component. The
 * kind is checked first, as opening a pipe could block for ever.
 *
 * @return The open stream, which the caller closes.
 * @throws FormatException NotFound for a missing file, Io for another kind of file or a failure.
 */
fun openRegularFile(path: Path): InputStream = try {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attributes.isRegularFile) throw FormatException(ErrorCode.Io, path.toString(), "not a regular file")
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)
} catch (e: NoSuchFileException) {
    throw FormatException(ErrorCode.NotFound, path.toString(), cause = e)
} catch (e: IOException) {
    throw FormatException(ErrorCode.Io, path.toString(), e.message.orEmpty(), e)
}

/**
 * Reads a whole regular file, never through a symbolic link.
 *
 * @throws FormatException NotFound for a missing file, Io for another kind of file or a failure.
 */
fun readFile(path: Path): ByteArray = openRegularFile(path).use { input ->
    try {
        input.readBytes()
    } catch (e: IOException) {
        throw FormatException(ErrorCode.Io, path.toString(), e.message.orEmpty(), e)
    }
}

/**
 * Lists a real folder. A failure is reported rather than taken for an empty folder, as an empty
 * folder would read as every note having been deleted; a symbolic link is not followed.
 *
 * @return The entries in no particular order.
 * @throws FormatException NotFound for a missing folder, Io for another kind of file or a failure.
 */
fun listDirectory(folder: Path): List<Path> = try {
    requireRealFolder(folder)
    Files.newDirectoryStream(folder).use { it.toList() }
} catch (e: NoSuchFileException) {
    throw FormatException(ErrorCode.NotFound, folder.toString(), cause = e)
} catch (e: DirectoryIteratorException) {
    throw FormatException(ErrorCode.Io, folder.toString(), e.message.orEmpty(), e)
} catch (e: IOException) {
    throw FormatException(ErrorCode.Io, folder.toString(), e.message.orEmpty(), e)
}

/**
 * Refuses a path that exists as anything but a real folder, such as a symbolic link.
 *
 * @throws NoSuchFileException For a missing path.
 * @throws FormatException Io for another kind of file.
 */
internal fun requireRealFolder(folder: Path) {
    val attributes = Files.readAttributes(folder, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attributes.isDirectory) throw FormatException(ErrorCode.Io, folder.toString(), "not a real folder")
}

/**
 * Removes a file or a folder with what it holds, removing symbolic links without following them;
 * a missing path is already removed.
 *
 * @throws FormatException Io when something cannot be removed.
 */
@OptIn(ExperimentalPathApi::class)
fun removePath(path: Path) {
    try {
        path.deleteRecursively()
    } catch (e: IOException) {
        throw FormatException(ErrorCode.Io, path.toString(), e.message.orEmpty(), e)
    }
}

/**
 * Identifies one version of a file more closely than its size and time in milliseconds: an atomic
 * replacement gives it another inode, and an edit in place another time in nanoseconds.
 *
 * @return The version, or null for a missing file.
 */
fun fileRevision(path: Path): String? = try {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    "${attributes.fileKey()}:${attributes.size()}:${attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)}"
} catch (_: IOException) {
    null
}

/**
 * Writes a file atomically: the data goes to a temporary file in the same folder, is flushed to
 * disk and renamed over the target, and the folder is flushed, so readers see the old or the new
 * contents, a crash leaves the old file intact and a loss of power after the write keeps the new
 * one. Missing parent folders are created.
 *
 * @param path The file to write.
 * @param data The new contents.
 * @param staging The folder of the temporary file: the target's own by default, or another folder
 *   on the same file system where a file left by a crash does no harm, as for an attachment, whose
 *   folder sync would upload it from.
 * @param prefix The start of the temporary file's name.
 * @throws FormatException Io when writing fails; the temporary file is removed.
 */
fun writeFileAtomic(path: Path, data: ByteArray, staging: Path? = null, prefix: String = ".${path.fileName}.") {
    val folder = path.toAbsolutePath().parent
    var temporary: Path? = null
    try {
        Files.createDirectories(folder)
        temporary = Files.createTempFile(staging?.toAbsolutePath() ?: folder, prefix, ".tmp")
        FileOutputStream(temporary.toFile()).use { out ->
            out.write(data)
            out.fd.sync()
        }
        // An atomic move is rename(2), which replaces an existing target.
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
        temporary = null
        // The rename reaches the disk with the folder's entries, so a loss of power keeps the new
        // file. The file is in place either way, so a file system that cannot flush a folder does not
        // turn the write into a failure.
        try {
            FileChannel.open(folder, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
        }
    } catch (e: IOException) {
        throw FormatException(ErrorCode.Io, path.toString(), e.message.orEmpty(), e)
    } finally {
        temporary?.let { runCatching { Files.deleteIfExists(it) } }
    }
}
