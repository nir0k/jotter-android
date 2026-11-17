package io.github.nir0k.jotter.data.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.writeFileAtomic
import io.github.nir0k.jotter.core.sftp.SftpSecret
import io.github.nir0k.jotter.core.sftp.SshKey
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The Keystore alias of the key that encrypts the credentials. */
private const val KeyAlias = "jotter.credentials.v1"

/** The length of the GCM nonce written before each encrypted file. */
private const val NonceBytes = 12

/**
 * Credentials keeps the sync secrets of each library: passwords, private keys and key passphrases.
 * Each library's secret is one file in `no_backup/credentials/`, encrypted with an AES-GCM key
 * generated in the Android Keystore, which never leaves it. A file that cannot be decrypted, such
 * as one restored without its key, reads as no secret.
 *
 * @param folder The credentials folder.
 */
class Credentials(private val folder: File) {

    private fun file(libraryId: String) = File(folder, "$libraryId.bin")

    /** Returns the installation's encryption key, creating it on first use. */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KeyAlias, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(KeyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
    }

    /**
     * Stores the secret of a library, replacing the one stored before.
     *
     * @throws FormatException Io when the file cannot be written.
     */
    fun write(libraryId: String, secret: SftpSecret) {
        val fields = when (secret) {
            is SftpSecret.Password -> mapOf("password" to JsonPrimitive(secret.password))
            is SftpSecret.Key -> mapOf(
                "privateKey" to JsonPrimitive(Base64.getEncoder().encodeToString(secret.key.privateKey)),
                "passphrase" to JsonPrimitive(secret.key.passphrase),
                "publicKey" to JsonPrimitive(secret.key.publicKey),
            )
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        writeFileAtomic(file(libraryId).toPath(), cipher.iv + cipher.doFinal(JsonObject(fields).toString().toByteArray()))
    }

    /** Reads the secret of a library, or null when none is stored or it cannot be decrypted. */
    fun read(libraryId: String): SftpSecret? = try {
        val data = file(libraryId).readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, NonceBytes))
        val json = Json.parseToJsonElement(String(cipher.doFinal(data, NonceBytes, data.size - NonceBytes))).jsonObject
        fun string(key: String) = json[key]?.jsonPrimitive?.takeIf { it.isString }?.content
        string("password")?.let { SftpSecret.Password(it) } ?: SftpSecret.Key(
            SshKey(Base64.getDecoder().decode(string("privateKey")!!), string("passphrase"), string("publicKey")!!),
        )
    } catch (_: Exception) {
        null
    }

    /** Removes the secret of a library. */
    fun remove(libraryId: String) {
        file(libraryId).delete()
    }
}
