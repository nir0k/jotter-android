package io.github.nir0k.jotter.core.sftp

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * SftpStand is the SFTP server the sync tests run against when `JOTTER_ANDROID_SFTP` names one,
 * such as the test stand of the Jotter desktop project: `sftp://jotter:jotter@127.0.0.1:2222/library`, the
 * folder relative to the user's home. `JOTTER_ANDROID_SFTP_KEY` names a private key the server
 * accepts, for the key sign-in. Host keys go to a known hosts file of the test run.
 */
object SftpStand {
    private val address: URI? = System.getenv("JOTTER_ANDROID_SFTP")?.takeIf { it.isNotEmpty() }?.let(URI::create)

    /** Whether the tests run against an SFTP server. */
    val configured: Boolean get() = address != null

    /** The known hosts file of the test run, empty at first. */
    val knownHosts: Path by lazy { Files.createTempDirectory("known-hosts").resolve("known_hosts") }

    /** The stand's connection to its base folder, or null without a stand. */
    val connection: SftpConnection? = address?.let {
        SftpConnection(it.host, if (it.port > 0) it.port else 22, it.userInfo.substringBefore(':'), it.path.removePrefix("/"), SignInMethod.Password)
    }

    /** The stand's password. */
    val password: String get() = address?.userInfo?.substringAfter(':').orEmpty()

    /** The private key file the stand accepts, or null when none is given. */
    val keyFile: Path? = System.getenv("JOTTER_ANDROID_SFTP_KEY")?.takeIf { it.isNotEmpty() }?.let(Path::of)

    /** Opens a connection, trusting the server's host key on first use. */
    fun connect(connection: SftpConnection, secret: SftpSecret = SftpSecret.Password(password)): SftpStorage = try {
        SftpStorage.open(connection, secret, knownHosts)
    } catch (e: HostKeyException) {
        SftpStorage.open(connection, secret, knownHosts, trust = e.hostKey.fingerprint)
    }

    /**
     * Opens a folder inside the stand's base folder, creating it.
     *
     * @return The storage, or null without a stand.
     */
    fun open(folder: String): SftpStorage? {
        val base = connection ?: return null
        connect(base).use { it.makeFolder(folder) }
        return connect(base.copy(folder = "${base.folder}/$folder"))
    }

    /** Removes a folder inside the stand's base folder; nothing happens without a stand. */
    fun remove(folder: String) {
        val base = connection ?: return
        connect(base).use { it.removeFolder(folder) }
    }
}
