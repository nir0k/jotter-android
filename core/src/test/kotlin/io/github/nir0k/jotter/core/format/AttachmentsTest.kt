package io.github.nir0k.jotter.core.format

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks storing files in attachment folders with the desktop's names and limits (desktop spec section 3.5). */
@OptIn(ExperimentalPathApi::class)
class AttachmentsTest {
    private val attachments: Path = createTempDirectory()
    private val folder: Path = attachments.resolve("note")

    @After
    fun cleanup() {
        attachments.deleteRecursively()
    }

    /** Stores bytes under a name. */
    private fun store(name: String, data: ByteArray, storeAs: StoreAs = StoreAs.Image) = storeFile(folder, name, data.inputStream(), storeAs)

    /** Returns an image head of a kind with its dimensions written where the kind keeps them. */
    private fun head(kind: String, width: Int, height: Int): ByteArray {
        val buffer = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(at: Int, text: String) = text.forEachIndexed { i, c -> buffer.put(at + i, c.code.toByte()) }
        when (kind) {
            "png" -> {
                ascii(0, "\u0089PNG\r\n\u001a\n")
                buffer.order(ByteOrder.BIG_ENDIAN).putInt(16, width).putInt(20, height)
            }
            "gif" -> {
                ascii(0, "GIF89a")
                buffer.putShort(6, width.toShort()).putShort(8, height.toShort())
            }
            else -> {
                ascii(0, "RIFF")
                ascii(8, "WEBP")
                ascii(12, kind)
                when (kind) {
                    "VP8X" -> {
                        buffer.putInt(24, width - 1)
                        // The height's three bytes follow the width's at offset 27.
                        buffer.put(27, (height - 1).toByte()).put(28, ((height - 1) shr 8).toByte()).put(29, ((height - 1) shr 16).toByte())
                    }
                    "VP8L" -> buffer.putInt(21, (width - 1) or ((height - 1) shl 14))
                    "VP8 " -> buffer.putShort(26, width.toShort()).putShort(28, height.toShort())
                }
            }
        }
        return buffer.array()
    }

    /** Clashing names get a suffix before the extension, and unsafe characters are replaced. */
    @Test
    fun names() {
        assertEquals("photo.jpg", store("photo.jpg", byteArrayOf(1)).fileName)
        assertEquals("photo (2).jpg", store("photo.jpg", byteArrayOf(2)).fileName)
        assertEquals("photo (3).jpg", store("photo.jpg", byteArrayOf(3)).fileName)
        assertEquals(".hidden", store(".hidden", byteArrayOf(4)).fileName)
        assertEquals(".hidden (2)", store(".hidden", byteArrayOf(5)).fileName)
        assertEquals("a_b_.txt", store(" a/b\u0001.txt ", byteArrayOf(6)).fileName)
        assertEquals("file", store("..", byteArrayOf(7)).fileName)
        // Nothing but the stored files is left beside the note folders.
        assertEquals(listOf("note"), attachments.listDirectoryEntries().map { it.name })
    }

    /** A file over 50 MB is refused and leaves nothing behind, without being read whole. */
    @Test
    fun tooLarge() {
        val endless = object : InputStream() {
            var left = MaxFileBytes + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        val error = assertThrows(FormatException::class.java) { storeFile(folder, "big.bin", endless, StoreAs.File) }
        assertEquals(ErrorCode.TooLarge, error.code)
        assertEquals(listOf("note"), attachments.listDirectoryEntries().map { it.name })
        assertTrue(folder.listDirectoryEntries().isEmpty())
    }

    /** Images over 8192 × 8192 pixels in PNG, GIF and WebP are attachments; JPEG has no limit. */
    @Test
    fun pixelLimit() {
        val cases = mapOf("png" to ImageKind.Png, "gif" to ImageKind.Gif, "VP8X" to ImageKind.WebP, "VP8L" to ImageKind.WebP, "VP8 " to ImageKind.WebP)
        for ((kind, expected) in cases) {
            assertEquals(kind, expected, shownKind(head(kind, 8192, 8192)))
            assertEquals(kind, ImageKind.None, shownKind(head(kind, 8193, 8192)))
        }
        assertEquals(ImageKind.Jpeg, store("big.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(40) { 0x7F }).kind)
        assertEquals(ImageKind.None, store("huge.png", head("png", 9000, 9000)).kind)
        assertEquals(ImageKind.None, store("photo.png", head("png", 10, 10), StoreAs.File).kind)
        // A pasted picture that is no accepted image is not added at all.
        assertEquals(ErrorCode.Refused, assertThrows(FormatException::class.java) { store("x.png", "text".toByteArray(), StoreAs.ImageOnly) }.code)
    }

    /** Android packages are recognised by their name or by their content. */
    @Test
    fun packages() {
        fun zip(name: String, entry: String): Path = attachments.resolve(name).also { path ->
            ZipOutputStream(path.outputStream()).use { it.putNextEntry(ZipEntry(entry)); it.closeEntry() }
        }
        assertTrue(isInstallable(zip("game.zip", "AndroidManifest.xml").toFile()))
        assertTrue(isInstallable(zip("bundle.dat", "base.apk").toFile()))
        assertTrue(isInstallable(attachments.resolve("App.APK").toFile()))
        assertFalse(isInstallable(zip("photos.zip", "photo.jpg").toFile()))
        assertFalse(isInstallable(attachments.resolve("notes.txt").also { it.outputStream().use { out -> out.write(1) } }.toFile()))
    }

    /** A copy of a note gets its own files under addresses naming its folder, whatever spelling the body uses; the sources stay. */
    @Test
    fun copiedAttachments() {
        val source = newId()
        val folderOf = { id: String -> attachments.resolve("attachments").resolve(id) }
        storeFile(folderOf(source), "a b.png", "image".byteInputStream(), StoreAs.File)
        storeFile(folderOf(source), "c.pdf", "file".byteInputStream(), StoreAs.File)
        val address = attachmentSource(source, "a b.png")
        val body = "<p><img src='$address' data-width=\"50\"><img src=\"$address\"> x &amp; y <a class=\"attachment\" href=\"${attachmentSource(source, "c.pdf")}\" data-size=\"4\">c.pdf</a></p>"
        val copy = newNote().let { it.copy(body = body) }
        val copied = copyNoteAttachments(attachments, copy)
        val id = copy.meta.id
        assertEquals(listOf("a b.png", "c.pdf"), copied.fileNames)
        assertEquals(
            "<p><img src=\"${attachmentSource(id, "a b.png")}\" data-width=\"50\"><img src=\"${attachmentSource(id, "a b.png")}\"> x &amp; y " +
                "<a class=\"attachment\" href=\"${attachmentSource(id, "c.pdf")}\" data-size=\"4\">c.pdf</a></p>",
            copied.note.body,
        )
        assertEquals("image", folderOf(id).resolve("a b.png").readText())
        assertEquals("image", folderOf(source).resolve("a b.png").readText())
        assertEquals(copy, copyNoteAttachments(attachments, copy.copy(body = "<p>none</p>")).note.copy(body = body))
    }

    /** A file that cannot be read stops the copy and leaves nothing of it behind. */
    @Test
    fun copyFailsWithoutLeftovers() {
        val source = newId()
        val folderOf = { id: String -> attachments.resolve("attachments").resolve(id) }
        storeFile(folderOf(source), "a.png", "image".byteInputStream(), StoreAs.File)
        val copy = newNote().copy(body = "<p><img src=\"${attachmentSource(source, "a.png")}\"><img src=\"${attachmentSource(source, "gone.png")}\"></p>")
        assertEquals(ErrorCode.NotFound, assertThrows(FormatException::class.java) { copyNoteAttachments(attachments, copy) }.code)
        assertFalse(folderOf(copy.meta.id).exists())
        val outside = newNote().copy(body = "<p><img src=\"../attachments/x/a.png\"></p>")
        assertEquals(ErrorCode.Refused, assertThrows(FormatException::class.java) { copyNoteAttachments(attachments, outside) }.code)
    }

    /** The components of an address cannot leave the note folders. */
    @Test
    fun addressComponents() {
        for (address in listOf("../attachments/a/..", "../attachments/a/x%2Fy", "../attachments/a/x%5Cy", "../attachments/a/x%00y", "../attachments/a\\b/c", "../attachments/../c")) {
            assertEquals(address, null, parseAttachmentSource(address))
        }
    }

    /**
     * Temporary files of a stored file left by a stop half way go when the application starts,
     * but not one written within the hour, which a sync run may still be storing.
     */
    @Test
    fun removesOnlyStaleStoreLeftovers() {
        val root = attachments
        val dir = root.resolve("attachments").also { Files.createDirectories(it) }
        val now = System.currentTimeMillis()
        val stale = dir.resolve("${StorePrefix}1.tmp").also { Files.write(it, byteArrayOf(1)) }
        stale.toFile().setLastModified(now - 2 * 60 * 60 * 1000)
        val fresh = dir.resolve("${StorePrefix}2.tmp").also { Files.write(it, byteArrayOf(2)) }
        val other = dir.resolve("photo.jpg").also { Files.write(it, byteArrayOf(3)) }
        other.toFile().setLastModified(now - 2 * 60 * 60 * 1000)
        removeStoreLeftovers(root, now)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(other.exists())
    }
}
