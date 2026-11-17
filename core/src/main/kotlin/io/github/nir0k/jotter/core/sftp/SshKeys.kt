package io.github.nir0k.jotter.core.sftp

import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import java.io.IOException
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * A key pair for signing in to an SFTP server.
 *
 * @param privateKey The private key file as stored, encrypted with [passphrase] when it has one.
 * @param passphrase The passphrase of an imported key, or null.
 * @param publicKey The public key as one `authorized_keys` line: type, key and an optional comment.
 */
class SshKey(val privateKey: ByteArray, val passphrase: String?, val publicKey: String) {
    /** The SHA256 fingerprint of the public key, as OpenSSH shows it. */
    val fingerprint: String
        get() = fingerprintOf(Base64.getDecoder().decode(publicKey.split(' ')[1]))
}

/**
 * Makes sure the JCE provider named "BC" is the full Bouncy Castle, which sshj uses for Ed25519,
 * X25519 and the ciphers. Android registers a reduced Bouncy Castle of its own under that name,
 * without these algorithms; it is replaced for this application's process.
 */
@Synchronized
internal fun useBouncyCastle() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) is BouncyCastleProvider) return
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.addProvider(BouncyCastleProvider())
}

/** Base64 lines of OpenSSH's key files are this long. */
private const val PemLineLength = 70

/**
 * Generates an Ed25519 key pair on the phone, the private key in OpenSSH's format; it has no
 * passphrase of its own, as it is stored encrypted.
 *
 * @param comment The comment of the public key, telling the server's owner whose key it is.
 */
fun generateKey(comment: String): SshKey {
    val generator = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
    val pair = generator.generateKeyPair()
    val body = Base64.getEncoder().encodeToString(OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)).chunked(PemLineLength)
    val privateKey = (listOf("-----BEGIN OPENSSH PRIVATE KEY-----") + body + "-----END OPENSSH PRIVATE KEY-----").joinToString("\n", postfix = "\n")
    val publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(pair.public))} $comment"
    return SshKey(privateKey.toByteArray(Charsets.US_ASCII), null, publicKey)
}

/**
 * Answers a key file's request for its passphrase, remembering that it was asked.
 *
 * @param passphrase The passphrase to give, or null for none.
 */
private class Passphrase(private val passphrase: String?) : PasswordFinder {
    var asked = false
        private set

    // sshj fails on no answer at all; an empty passphrase opens no encrypted key.
    override fun reqPassword(resource: Resource<*>?): CharArray {
        asked = true
        return passphrase.orEmpty().toCharArray()
    }

    override fun shouldRetry(resource: Resource<*>?) = false
}

/**
 * Reads a private key file with its passphrase, decrypting it: an imported key and a stored one
 * for signing in.
 *
 * @return The keys, and whether the file asked for a passphrase.
 * @throws FormatException Malformed for a file that is not a private key, SignIn for a missing or
 *   wrong passphrase.
 */
internal fun readKey(data: ByteArray, passphrase: String?): Pair<KeyProvider, Boolean> {
    useBouncyCastle()
    val finder = Passphrase(passphrase)
    try {
        val keys = SSHClient().loadKeys(String(data, Charsets.UTF_8), null, finder)
        keys.private
        if (finder.asked && passphrase.isNullOrEmpty()) throw FormatException(ErrorCode.SignIn, "private key", "missing passphrase")
        return keys to finder.asked
    } catch (e: IOException) {
        throw unreadable(e, finder.asked)
    } catch (e: RuntimeException) {
        // A damaged file fails in the parsers with runtime errors of all kinds.
        throw unreadable(e, finder.asked)
    }
}

/** Describes a key file that could not be read: a wrong passphrase when it asked for one, otherwise no key. */
private fun unreadable(e: Exception, asked: Boolean) =
    if (asked) FormatException(ErrorCode.SignIn, "private key", "missing or wrong passphrase", e)
    else FormatException(ErrorCode.Malformed, "private key", e.message.orEmpty(), e)

/**
 * Reads an imported private key file and checks its passphrase.
 *
 * @param data The key file: OpenSSH, PEM or PuTTY format.
 * @param passphrase The passphrase of an encrypted key; ignored for a key without one.
 * @return The key, with the passphrase kept only when the key needs it.
 * @throws FormatException Malformed for a file that is not a private key, SignIn for a missing or
 *   wrong passphrase.
 */
fun importKey(data: ByteArray, passphrase: String?): SshKey {
    val (keys, encrypted) = readKey(data, passphrase)
    val public = keys.public
    return SshKey(data, if (encrypted) passphrase else null, "${KeyType.fromKey(public)} ${Base64.getEncoder().encodeToString(blobOf(public))}")
}
