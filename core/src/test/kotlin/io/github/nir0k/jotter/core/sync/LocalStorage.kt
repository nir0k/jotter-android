package io.github.nir0k.jotter.core.sync

import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.newId
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.name
import io.github.nir0k.jotter.core.format.MaxFileBytes

/**
 * LocalStorage is a sync storage in a local folder, standing in for a remote one in tests, as the
 * desktop's sync tests do. Its modification times follow the file system's clock.
 */
class LocalStorage(private val root: Path) : RemoteStorage {
    init {
        Files.createDirectories(root)
    }

    override val location: String get() = root.toString()

    override val timePrecisionMs: Int get() = 20

    override var checkpoint: (() -> Unit)? = null

    private fun absolute(path: String): Path = if (path.isEmpty()) root else root.resolve(path)

    /** Runs a file operation, reporting a missing file as NotFound and other failures as Io. */
    private fun <T> io(path: Path, block: () -> T): T = try {
        block()
    } catch (e: NoSuchFileException) {
        throw FormatException(ErrorCode.NotFound, path.toString(), cause = e)
    } catch (e: IOException) {
        throw FormatException(ErrorCode.Io, path.toString(), e.message.orEmpty(), e)
    }

    private fun entry(file: Path): RemoteEntry {
        val folder = Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
        return RemoteEntry(file.name, folder, if (folder) 0 else Files.size(file), Files.getLastModifiedTime(file).toMillis())
    }

    override fun list(path: String): List<RemoteEntry> = absolute(path).let { folder ->
        checkpoint?.invoke()
        io(folder) { Files.list(folder).use { stream -> stream.filter { !Files.isSymbolicLink(it) }.map(::entry).toList() } }
    }

    override fun read(path: String): ByteArray = absolute(path).let {
        checkpoint?.invoke()
        if (io(it) { Files.size(it) } > MaxFileBytes) throw FormatException(ErrorCode.TooLarge, it.toString())
        io(it) { Files.readAllBytes(it) }
    }

    override fun write(path: String, data: ByteArray): RemoteEntry {
        checkpoint?.invoke()
        val target = absolute(path)
        val temp = target.resolveSibling(".jotter-tmp-${newId()}")
        return io(target) {
            Files.write(temp, data)
            // An atomic move replaces the target, as the SFTP rename extension does.
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            entry(target)
        }
    }

    override fun create(path: String, data: ByteArray): Boolean = absolute(path).let { file ->
        try {
            io(file) { Files.write(file, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) }
            true
        } catch (e: FormatException) {
            if (e.cause is FileAlreadyExistsException) false else throw e
        }
    }

    override fun rename(from: String, to: String) {
        io(absolute(from)) { Files.move(absolute(from), absolute(to), StandardCopyOption.ATOMIC_MOVE) }
    }

    override fun remove(path: String) {
        io(absolute(path)) { Files.deleteIfExists(absolute(path)) }
    }

    @OptIn(ExperimentalPathApi::class)
    override fun removeFolder(path: String) {
        io(absolute(path)) { absolute(path).deleteRecursively() }
    }

    override fun makeFolder(path: String) {
        io(absolute(path)) { Files.createDirectories(absolute(path)) }
    }

    /** Sets a file's modification time back by a number of days, as an old leftover would have. */
    fun age(path: String, days: Long) {
        val file = absolute(path)
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() - days * 24 * 60 * 60 * 1000))
    }
}
