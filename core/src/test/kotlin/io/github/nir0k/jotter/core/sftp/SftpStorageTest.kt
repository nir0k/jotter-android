package io.github.nir0k.jotter.core.sftp

import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.newId
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Checks the SSH keys made and imported on the phone, and, against the SFTP server of [SftpStand],
 * host keys, sign-in, the connection errors and choosing a folder.
 */
class SftpStorageTest {
    private fun failure(block: () -> Unit): FormatException =
        runCatching(block).exceptionOrNull().let { it as? FormatException ?: throw AssertionError("expected a FormatException", it) }

    /** A generated Ed25519 key reads back as the same key, and its public key is one `authorized_keys` line. */
    @Test
    fun generatedKeyReadsBack() {
        val key = generateKey("jotter@phone")
        assertTrue(key.publicKey.startsWith("ssh-ed25519 "))
        assertTrue(key.publicKey.endsWith(" jotter@phone"))
        val imported = importKey(key.privateKey, null)
        assertEquals(key.fingerprint, imported.fingerprint)
        assertTrue(key.fingerprint.startsWith("SHA256:"))
    }

    /** An encrypted key needs its passphrase, and a file that is not a key is refused. */
    @Test
    fun importedKeyNeedsItsPassphrase() {
        assertEquals(ErrorCode.Malformed, failure { importKey("not a key".toByteArray(), null) }.code)
        // ssh-keygen makes the key for the run, so the repository holds no private key, not even a test one.
        val dir = Files.createTempDirectory("key")
        val file = dir.resolve("key")
        val made = runCatching {
            ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "secret", "-C", "", "-f", file.toString()).start().waitFor()
        }.getOrNull()
        assumeTrue("Needs ssh-keygen", made == 0)
        val encrypted = Files.readAllBytes(file)
        dir.toFile().deleteRecursively()
        assertEquals(ErrorCode.SignIn, failure { importKey(encrypted, null) }.code)
        assertEquals(ErrorCode.SignIn, failure { importKey(encrypted, "wrong") }.code)
        val imported = importKey(encrypted, "secret")
        assertEquals("secret", imported.passphrase)
        assertTrue(imported.publicKey.startsWith("ssh-ed25519 "))
    }

    /** An unknown host key is reported with its fingerprint and refused until it is trusted; then it is known. */
    @Test
    fun hostKeyUnknown() {
        val connection = SftpStand.connection
        assumeTrue("Runs against an SFTP server: set JOTTER_ANDROID_SFTP", connection != null)
        val knownHosts = Files.createTempDirectory("hosts").resolve("known_hosts")
        val secret = SftpSecret.Password(SftpStand.password)
        val unknown = failure { SftpStorage.open(connection!!, secret, knownHosts) } as HostKeyException
        assertEquals(HostKey.State.Unknown, unknown.hostKey.state)
        assertTrue(unknown.hostKey.type.isNotEmpty())
        assertTrue(unknown.hostKey.fingerprint.startsWith("SHA256:"))
        SftpStorage.open(connection!!, secret, knownHosts, trust = unknown.hostKey.fingerprint).close()
        SftpStorage.open(connection, secret, knownHosts).close()
    }

    /** A changed host key shows the old and the new fingerprint and stops sign-in; trusting it replaces the old key. */
    @Test
    fun hostKeyChanged() {
        val connection = SftpStand.connection
        assumeTrue("Runs against an SFTP server: set JOTTER_ANDROID_SFTP", connection != null)
        val knownHosts = Files.createTempDirectory("hosts").resolve("known_hosts")
        // A key the server does not have, recorded for its address.
        Files.writeString(
            knownHosts,
            knownHostName(connection!!.host, connection.port) + " ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMAG7ofcYv3Vv3uWI60JcdpWwPvjCNXf7TXqWwWA0B6U\n",
        )
        val secret = SftpSecret.Password(SftpStand.password)
        val changed = failure { SftpStorage.open(connection, secret, knownHosts) } as HostKeyException
        assertEquals(HostKey.State.Changed, changed.hostKey.state)
        assertTrue(changed.hostKey.knownFingerprint!!.startsWith("SHA256:"))
        assertNotEquals(changed.hostKey.knownFingerprint, changed.hostKey.fingerprint)
        SftpStorage.open(connection, secret, knownHosts, trust = changed.hostKey.fingerprint).close()
        assertEquals(1, Files.readAllLines(knownHosts).size)
        SftpStorage.open(connection, secret, knownHosts).close()
    }

    /** A wrong password is a sign-in error and a closed port a network error, which the application retries; the probe names a missing folder. */
    @Test
    fun connectionErrors() {
        val connection = SftpStand.connection
        assumeTrue("Runs against an SFTP server: set JOTTER_ANDROID_SFTP", connection != null)
        SftpStand.connect(connection!!).close()
        val refused = failure { SftpStand.connect(connection, SftpSecret.Password("not the password")) }
        assertEquals(refused.message, ErrorCode.SignIn, refused.code)
        assertEquals(ErrorCode.Network, failure { SftpStand.connect(connection.copy(port = 1)) }.code)
        SftpStand.connect(connection).use { it.probe() }
        SftpStand.connect(connection.copy(folder = "${connection.folder}/missing-${newId()}")).use {
            assertEquals(ErrorCode.NotFound, failure(it::probe).code)
        }
    }

    /** The home folder and a folder relative to it resolve to absolute paths, and a folder made through a connection to the root is the one a connection to its path uses. */
    @Test
    fun chooseFolder() {
        val connection = SftpStand.connection
        assumeTrue("Runs against an SFTP server: set JOTTER_ANDROID_SFTP", connection != null)
        val name = "chosen-${newId()}"
        SftpStand.connect(connection!!.copy(folder = "/")).use { root ->
            val home = root.resolveFolder("")
            assertTrue(home.startsWith("/"))
            val folder = root.resolveFolder(connection.folder)
            // A server that keeps the user in a home of its own, such as SFTPGo, shows it as `/`.
            assertEquals("${home.trimEnd('/')}/${connection.folder}", folder)
            root.makeFolder("${folder.removePrefix("/")}/$name")
        }
        SftpStand.connect(connection).use { base ->
            assertTrue(base.list("").any { it.name == name && it.folder })
            base.removeFolder(name)
        }
    }

    /** A private key the server accepts signs in after it is imported. */
    @Test
    fun keySignIn() {
        val connection = SftpStand.connection
        val keyFile = SftpStand.keyFile
        assumeTrue("Runs against an SFTP server with a key: set JOTTER_ANDROID_SFTP and JOTTER_ANDROID_SFTP_KEY", connection != null && keyFile != null)
        val key = importKey(Files.readAllBytes(keyFile!!), null)
        SftpStand.connect(connection!!.copy(signIn = SignInMethod.Key), SftpSecret.Key(key)).close()
    }
}
