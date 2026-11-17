package io.github.nir0k.jotter.core.sftp

import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.MaxFileBytes
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.sync.RemoteEntry
import io.github.nir0k.jotter.core.sync.RemoteStorage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.EnumSet
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceFilter
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource

/** How a connection signs in. */
enum class SignInMethod { Password, Key }

/**
 * An SFTP connection of a library, kept in the device settings.
 *
 * @param folder The remote folder: absolute, or relative to the user's home folder on the server.
 */
data class SftpConnection(val host: String, val port: Int = 22, val user: String, val folder: String, val signIn: SignInMethod)

/** The secret a connection signs in with: a password, or a private key with its passphrase. */
sealed interface SftpSecret {
    class Password(val password: String) : SftpSecret

    class Key(val key: SshKey) : SftpSecret
}

/** The server must answer within this time (desktop spec section 12.1). */
private const val TimeoutMs = 20_000

/** Requests sent ahead of their answers while a file is read or written. */
private const val RequestsInFlight = 16

/** The piece of a file read between two calls of the storage's checkpoint. */
private const val ReadPieceBytes = 256 * 1024

/** Answers a password prompt once: offering a refused password again only delays the refusal. */
private class OncePassword(private val password: String) : PasswordFinder {
    override fun reqPassword(resource: Resource<*>?): CharArray = password.toCharArray()
    override fun shouldRetry(resource: Resource<*>?) = false
}

/**
 * SftpStorage is a sync storage in a folder on an SFTP server, through sshj. The lock is created
 * exclusively (`SSH_FXF_EXCL`), and uploads replace their target through
 * `posix-rename@openssh.com` when the server offers it, otherwise through the plain rename, which
 * servers without the extension, such as SFTPGo, perform replacing the target, as the desktop
 * does through libssh.
 *
 * Times are in whole seconds, as SFTP version 3, which these servers speak, carries them.
 */
class SftpStorage private constructor(
    private val client: SSHClient,
    private val sftp: SFTPClient,
    private val folder: String,
    override val location: String,
) : RemoteStorage, AutoCloseable {

    override val timePrecisionMs: Int get() = 1000

    override var checkpoint: (() -> Unit)? = null

    /** The flags of a rename that replaces its target: posix-rename when the server offers it. */
    private val replacing: Set<RenameFlags> =
        if (sftp.sftpEngine.supportsServerExtension("posix-rename", "openssh.com")) EnumSet.of(RenameFlags.OVERWRITE)
        else EnumSet.noneOf(RenameFlags::class.java)

    /** Returns the server's path of a path relative to the remote folder. */
    private fun remotePath(path: String): String {
        val base = folder.ifEmpty { "." }
        if (path.isEmpty()) return base
        // The root folder `/` already ends with the separator.
        return if (base.endsWith('/')) base + path else "$base/$path"
    }

    /** Describes a failed request: Network for a lost connection, NotFound for a missing file, Io otherwise. */
    private fun failure(path: String, e: IOException): FormatException = when {
        !client.isConnected || e is TransportException -> FormatException(ErrorCode.Network, location, e.message.orEmpty(), e)
        e is SFTPException && (e.statusCode == Response.StatusCode.NO_SUCH_FILE || e.statusCode == Response.StatusCode.NO_SUCH_PATH) ->
            FormatException(ErrorCode.NotFound, "$location/$path", e.message.orEmpty(), e)
        else -> FormatException(ErrorCode.Io, "$location/$path", e.message.orEmpty(), e)
    }

    /** Runs requests, reporting their failures with [failure]. */
    private fun <T> request(path: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw failure(path, e)
    }

    private fun entryOf(name: String, attributes: FileAttributes): RemoteEntry {
        val folder = attributes.type == FileMode.Type.DIRECTORY
        return RemoteEntry(name, folder, if (folder) 0 else attributes.size, attributes.mtime * 1000)
    }

    /** Returns the entry of a path, or null when nothing is there. */
    private fun existing(path: String): RemoteEntry? = try {
        request(path) { entryOf(path.substringAfterLast('/'), sftp.stat(remotePath(path))) }
    } catch (e: FormatException) {
        if (e.code == ErrorCode.NotFound) null else throw e
    }

    override fun list(path: String): List<RemoteEntry> = request(path) {
        // The filter runs between the server's answers, each a bounded batch of entries.
        val keep = RemoteResourceFilter {
            checkpoint?.invoke()
            true
        }
        sftp.ls(remotePath(path), keep).mapNotNull { entry ->
            val type = entry.attributes.type
            // Symbolic links and other special files are left out, as the interface promises.
            if (entry.name == "." || entry.name == ".." || (type != FileMode.Type.REGULAR && type != FileMode.Type.DIRECTORY)) null
            else entryOf(entry.name, entry.attributes)
        }
    }

    override fun read(path: String): ByteArray = request(path) {
        sftp.open(remotePath(path), EnumSet.of(OpenMode.READ)).use { file ->
            val tooLarge = { FormatException(ErrorCode.TooLarge, "$location/$path", "larger than $MaxFileBytes bytes") }
            val size = file.length()
            if (size > MaxFileBytes) throw tooLarge()
            file.ReadAheadRemoteFileInputStream(RequestsInFlight).use { input ->
                // The size the server reports is only a hint, so the data read is counted too: a
                // damaged or hostile server sending more than a library holds stops the read.
                val data = ByteArrayOutputStream(size.coerceAtLeast(0).toInt())
                val piece = ByteArray(ReadPieceBytes)
                while (true) {
                    checkpoint?.invoke()
                    val read = input.read(piece)
                    if (read < 0) break
                    if (data.size() + read > MaxFileBytes) throw tooLarge()
                    data.write(piece, 0, read)
                }
                data.toByteArray()
            }
        }
    }

    /**
     * Writes a whole file opened with the given modes. The stream sends each write as one request,
     * so the data goes in pieces that fit the channel's packets, as sshj's own uploads send it.
     */
    private fun put(path: String, data: ByteArray, modes: Set<OpenMode>) {
        sftp.open(remotePath(path), modes).use { file ->
            val piece = sftp.sftpEngine.subsystem.remoteMaxPacketSize - file.outgoingPacketOverhead
            file.RemoteFileOutputStream(0, RequestsInFlight).use { out ->
                for (start in data.indices step piece) {
                    checkpoint?.invoke()
                    out.write(data, start, minOf(piece, data.size - start))
                }
            }
        }
    }

    override fun write(path: String, data: ByteArray): RemoteEntry {
        val temp = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + ".jotter-tmp-" + newId()
        try {
            request(temp) { put(temp, data, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)) }
            rename(temp, path)
        } catch (e: FormatException) {
            runCatching { sftp.rm(remotePath(temp)) }
            throw e
        }
        return existing(path) ?: throw FormatException(ErrorCode.NotFound, "$location/$path")
    }

    override fun create(path: String, data: ByteArray): Boolean {
        try {
            request(path) { put(path, data, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)) }
            return true
        } catch (e: FormatException) {
            // SFTP version 3 reports an existing file only as a failure.
            if (e.code != ErrorCode.Network && existing(path) != null) return false
            throw e
        }
    }

    override fun rename(from: String, to: String) {
        request(from) { sftp.rename(remotePath(from), remotePath(to), replacing) }
    }

    override fun remove(path: String) {
        try {
            request(path) { sftp.rm(remotePath(path)) }
        } catch (e: FormatException) {
            if (e.code != ErrorCode.NotFound) throw e
        }
    }

    override fun removeFolder(path: String) {
        val entries = try {
            list(path)
        } catch (e: FormatException) {
            if (e.code == ErrorCode.NotFound) return
            throw e
        }
        for (entry in entries) {
            val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
            if (entry.folder) removeFolder(child) else remove(child)
        }
        request(path) { sftp.rmdir(remotePath(path)) }
    }

    override fun makeFolder(path: String) {
        // The folder is created first and its parents, up to the remote folder itself, which the
        // empty path names, only when that fails: a folder inside existing ones costs one request.
        try {
            request(path) { sftp.mkdir(remotePath(path)) }
            return
        } catch (e: FormatException) {
            if (e.code == ErrorCode.Network) throw e
            // It may exist already, made by another device meanwhile.
            if (existing(path)?.folder == true) return
            if (path.isEmpty()) throw e
        }
        makeFolder(path.substringBeforeLast('/', ""))
        try {
            request(path) { sftp.mkdir(remotePath(path)) }
        } catch (e: FormatException) {
            if (existing(path)?.folder != true) throw e
        }
    }

    /**
     * Writes a probe file into the remote folder through a temporary file and a rename, as uploads
     * do, and removes it again.
     *
     * @throws FormatException NotFound for a missing folder, or the error of writing.
     */
    fun probe() {
        list("")
        val name = ".jotter-probe-${newId()}"
        write(name, "Jotter connection check\n".toByteArray())
        remove(name)
    }

    /**
     * Resolves a folder on the server to its absolute path, as Browse shows it.
     *
     * @param folder The folder, absolute or relative to the home folder; empty for the home folder.
     * @throws FormatException NotFound, Network or Io.
     */
    fun resolveFolder(folder: String): String = request(folder) { sftp.canonicalize(folder.ifEmpty { "." }) }

    /** Closes the SFTP session and the connection. */
    override fun close() {
        runCatching { sftp.close() }
        runCatching { client.disconnect() }
    }

    companion object {
        /**
         * Connects, checks the host key and signs in, as a sync run and the connection check need.
         * The steps fail with their own codes, so the check can name the step that failed.
         *
         * @param connection The connection.
         * @param secret The password or the key.
         * @param knownHosts Jotter's known hosts file.
         * @param trust The fingerprint of a host key the person accepted after seeing it; such a
         *   key is recorded when the server still has it.
         * @return The storage, which must be closed.
         * @throws FormatException Network when the server cannot be reached, [HostKeyException]
         *   for an unknown or changed host key, SignIn for a refused sign-in or an unreadable key.
         */
        fun open(connection: SftpConnection, secret: SftpSecret, knownHosts: Path, trust: String? = null): SftpStorage {
            useBouncyCastle()
            val location = "${connection.user}@${connection.host}:${connection.folder}"
            val hostKeys = HostKeyCheck(knownHosts, trust)
            val client = SSHClient()
            client.addHostKeyVerifier(hostKeys)
            client.connectTimeout = TimeoutMs
            client.timeout = TimeoutMs
            try {
                client.connect(connection.host, connection.port)
            } catch (e: IOException) {
                runCatching { client.disconnect() }
                val key = hostKeys.seen
                if (e is TransportException && e.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE && key != null) {
                    throw HostKeyException(key, location)
                }
                throw FormatException(ErrorCode.Network, location, e.message.orEmpty(), e)
            }
            try {
                when (secret) {
                    is SftpSecret.Password -> {
                        val password = OncePassword(secret.password)
                        // A password also answers a server that asks for it through keyboard-interactive sign-in.
                        client.auth(connection.user, AuthPassword(password), AuthKeyboardInteractive(PasswordResponseProvider(password)))
                    }
                    is SftpSecret.Key -> client.authPublickey(connection.user, readKey(secret.key.privateKey, secret.key.passphrase).first)
                }
                return SftpStorage(client, client.newSFTPClient(), connection.folder, location)
            } catch (e: FormatException) {
                runCatching { client.disconnect() }
                throw FormatException(ErrorCode.SignIn, location, "cannot read the key or its passphrase", e)
            } catch (e: IOException) {
                runCatching { client.disconnect() }
                // A server may disconnect after too many refused tries instead of refusing once more.
                val refused = e is UserAuthException || (e is TransportException && e.disconnectReason in
                    setOf(DisconnectReason.NO_MORE_AUTH_METHODS_AVAILABLE, DisconnectReason.AUTH_CANCELLED_BY_USER))
                throw FormatException(if (refused) ErrorCode.SignIn else ErrorCode.Network, location, e.message.orEmpty(), e)
            }
        }
    }
}
