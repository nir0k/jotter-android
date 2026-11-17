package io.github.nir0k.jotter.core.sftp

import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.PublicKey
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the host key check against Jotter's own known hosts file without a server: unknown and
 * known keys, a changed key of the same or of another type, and a key the person accepted.
 */
class KnownHostsTest {
    private val dir: Path = createTempDirectory("known-hosts")
    private val file: Path = dir.resolve("known_hosts")

    @OptIn(ExperimentalPathApi::class)
    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** Returns a new public key of a JDK algorithm the SSH wire form supports: EC (ECDSA on P-256) or RSA. */
    private fun key(algorithm: String): PublicKey = KeyPairGenerator.getInstance(algorithm).apply {
        initialize(if (algorithm == "EC") 256 else 2048)
    }.generateKeyPair().public

    /** Runs a check of a server's key for `example.org:2222` and returns what it saw. */
    private fun check(key: PublicKey, trust: String? = null): Pair<Boolean, HostKey> {
        val check = HostKeyCheck(file, trust)
        return check.verify("example.org", 2222, key) to check.seen!!
    }

    /** An unknown key is refused until the person accepts its fingerprint; then it is recorded and known. */
    @Test
    fun unknownKeyIsTrustedOnlyByItsFingerprint() {
        val server = key("EC")
        val (accepted, seen) = check(server)
        assertFalse(accepted)
        assertEquals(HostKey.State.Unknown, seen.state)
        assertEquals("ecdsa-sha2-nistp256", seen.type)
        assertNull(seen.knownFingerprint)

        assertFalse(check(server, trust = fingerprintOf(blobOf(key("EC")))).first)
        assertTrue(check(server, trust = seen.fingerprint).first)
        val (known, again) = check(server)
        assertTrue(known)
        assertEquals(HostKey.State.Known, again.state)
        assertEquals(listOf("[example.org]:2222"), readKnownHosts(file).map { it.host })
    }

    /**
     * A key other than the recorded one is changed whatever its type, with the recorded fingerprint
     * to compare: a server posing as the real one could otherwise offer another key type and be
     * shown as one never seen before. Trusting it replaces the recorded key.
     */
    @Test
    fun anotherKeyIsChangedWhateverItsType() {
        val recorded = key("EC")
        trustKnownHost(file, knownHostName("example.org", 2222), blobOf(recorded))
        for (other in listOf(key("EC"), key("RSA"))) {
            val (accepted, seen) = check(other)
            assertFalse(accepted)
            assertEquals(HostKey.State.Changed, seen.state)
            assertEquals(fingerprintOf(blobOf(recorded)), seen.knownFingerprint)
        }

        val replacement = key("RSA")
        assertTrue(check(replacement, trust = fingerprintOf(blobOf(replacement))).first)
        assertEquals(listOf("ssh-rsa"), readKnownHosts(file).map { it.type })
        assertEquals(HostKey.State.Changed, check(recorded).second.state)
    }
}
