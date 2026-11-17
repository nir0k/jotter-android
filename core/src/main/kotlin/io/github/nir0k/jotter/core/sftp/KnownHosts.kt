package io.github.nir0k.jotter.core.sftp

import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.writeFileAtomic
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * What is known about a server's host key.
 *
 * @param type The key type, such as `ssh-ed25519`.
 * @param fingerprint The SHA256 fingerprint as OpenSSH shows it, `SHA256:…`.
 * @param knownFingerprint For a changed key, the fingerprint of the key recorded before.
 */
data class HostKey(val state: State, val type: String, val fingerprint: String, val knownFingerprint: String? = null) {
    enum class State {
        /** Recorded in Jotter's list. */
        Known,

        /** Not recorded; the person must accept it. */
        Unknown,

        /** Different from the key recorded for the host; never accepted by a sync run. */
        Changed,
    }
}

/**
 * HostKeyException reports a server whose host key is unknown or changed, with the key, so the
 * person can compare its fingerprint and trust it.
 */
class HostKeyException(val hostKey: HostKey, location: String) :
    FormatException(ErrorCode.HostKey, location, "${hostKey.state} host key ${hostKey.fingerprint}")

/** Returns the SHA256 fingerprint of a public key blob as OpenSSH shows it. */
fun fingerprintOf(blob: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

/** Returns the type a public key blob names in its first field, such as `ssh-ed25519`. */
internal fun keyTypeOf(blob: ByteArray): String {
    if (blob.size < 4) return ""
    val length = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
        ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
    if (length < 0 || length > blob.size - 4) return ""
    return String(blob, 4, length, Charsets.US_ASCII)
}

/**
 * One line of the known hosts file.
 *
 * @param host The host as OpenSSH writes it: the name, or `[name]:port` for another port than 22.
 * @param key The public key blob.
 */
internal class KnownHost(val host: String, val type: String, val key: ByteArray)

/** Returns the host name of a connection as known hosts files write it. */
internal fun knownHostName(host: String, port: Int) = if (port == 22) host else "[$host]:$port"

/**
 * Reads the known hosts file: lines of `host type base64-key`; lines that do not parse are left out.
 *
 * @throws FormatException Io when the file exists but cannot be read.
 */
internal fun readKnownHosts(file: Path): List<KnownHost> {
    val text = try {
        String(Files.readAllBytes(file), Charsets.UTF_8)
    } catch (_: NoSuchFileException) {
        return emptyList()
    } catch (e: IOException) {
        throw FormatException(ErrorCode.Io, file.toString(), e.message.orEmpty(), e)
    }
    return text.lines().mapNotNull { line ->
        val parts = line.trim().split(' ')
        if (parts.size < 3) return@mapNotNull null
        val key = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return@mapNotNull null
        KnownHost(parts[0], parts[1], key)
    }
}

/**
 * Records a host key in the known hosts file, replacing any key recorded for the host before; the
 * file is written atomically.
 *
 * @throws FormatException Io.
 */
internal fun trustKnownHost(file: Path, host: String, key: ByteArray) {
    val lines = readKnownHosts(file).filter { it.host != host }.map { "${it.host} ${it.type} ${Base64.getEncoder().encodeToString(it.key)}" } +
        "$host ${keyTypeOf(key)} ${Base64.getEncoder().encodeToString(key)}"
    writeFileAtomic(file, lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
}

/** Returns the SSH wire form of a public key, as known hosts files and fingerprints use it. */
internal fun blobOf(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData

/**
 * HostKeyCheck checks a server's host key against Jotter's own known hosts file during the key
 * exchange and remembers what it saw. A key whose fingerprint is [trust] is recorded and accepted:
 * the person accepted that key after seeing it, and it is trusted only while the server still has
 * it.
 *
 * @param file Jotter's known hosts file, `no_backup/known_hosts`.
 * @param trust The fingerprint the person accepted, or null.
 */
internal class HostKeyCheck(private val file: Path, private val trust: String?) : HostKeyVerifier {
    /** The server's key as the last check saw it, or null before the key exchange. */
    var seen: HostKey? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val host = knownHostName(hostname, port)
        val blob = blobOf(key)
        val type = keyTypeOf(blob)
        val fingerprint = fingerprintOf(blob)
        // A host has one recorded key, so a key of another type than the recorded one is a changed
        // key too: someone posing as the server could otherwise offer another type and be shown as
        // a server never seen before.
        val recorded = readKnownHosts(file).filter { it.host == host }
        val state = when {
            recorded.isEmpty() -> HostKey.State.Unknown
            recorded.any { it.key.contentEquals(blob) } -> HostKey.State.Known
            else -> HostKey.State.Changed
        }
        if (state != HostKey.State.Known && fingerprint == trust) {
            trustKnownHost(file, host, blob)
            seen = HostKey(HostKey.State.Known, type, fingerprint)
            return true
        }
        seen = HostKey(state, type, fingerprint, recorded.firstOrNull()?.takeIf { state == HostKey.State.Changed }?.let { fingerprintOf(it.key) })
        return state == HostKey.State.Known
    }

    /**
     * Prefers the key types recorded for the host in the key exchange, so a server with several
     * host keys presents the one the person accepted. An RSA key is signed with SHA-2 by current
     * servers.
     */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        readKnownHosts(file).filter { it.host == knownHostName(hostname, port) }.flatMap {
            if (it.type == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa") else listOf(it.type)
        }
}
