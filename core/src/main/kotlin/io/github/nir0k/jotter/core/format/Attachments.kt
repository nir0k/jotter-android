package io.github.nir0k.jotter.core.format

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** The image kinds a note shows as images (desktop spec section 3.5); any other file is an attachment. */
enum class ImageKind { None, Png, Jpeg, Gif, WebP }

/**
 * Detects an accepted image kind from the first bytes of a file, never from its name or declared
 * type.
 *
 * @param head At least the first 12 bytes of the file.
 * @return The kind, or [ImageKind.None] for any other content.
 */
fun imageKind(head: ByteArray): ImageKind = when {
    head.startsWith("\u0089PNG\r\n\u001a\n") -> ImageKind.Png
    head.startsWith("\u00ff\u00d8\u00ff") -> ImageKind.Jpeg
    head.startsWith("GIF87a") || head.startsWith("GIF89a") -> ImageKind.Gif
    head.startsWith("RIFF") && head.startsWith("WEBP", at = 8) -> ImageKind.WebP
    else -> ImageKind.None
}

/** Returns the media type of an accepted image kind, or null for anything else. */
fun imageMimeType(kind: ImageKind): String? = when (kind) {
    ImageKind.Png -> "image/png"
    ImageKind.Jpeg -> "image/jpeg"
    ImageKind.Gif -> "image/gif"
    ImageKind.WebP -> "image/webp"
    ImageKind.None -> null
}

/** The largest file an image or attachment may be when inserted (desktop spec section 3.5). */
const val MaxFileBytes = 50L * 1024 * 1024

/** The most pixels an inserted PNG, GIF or WebP may have and still be shown as an image. */
const val MaxImagePixels = 8192L * 8192

/** Bytes of a file's head that hold its image kind and dimensions. */
private const val ImageHeadBytes = 30

/**
 * Returns the kind an inserted file is shown as (desktop spec section 3.5): its image kind, or
 * [ImageKind.None] for a PNG, GIF or WebP with more than [MaxImagePixels] by the dimensions in its
 * head. JPEG has no pixel limit, and a head too short to hold the dimensions counts as fitting.
 *
 * @param head The first 30 bytes of the file, or all of it when shorter.
 */
fun shownKind(head: ByteArray): ImageKind {
    val kind = imageKind(head)
    val little = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
    val big = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
    fun u16(at: Int) = little.getShort(at).toLong() and 0xFFFF
    fun u32(at: Int) = little.getInt(at).toLong() and 0xFFFFFFFFL
    var width = 0L
    var height = 0L
    if (kind == ImageKind.Png && head.size >= 24) {
        width = big.getInt(16).toLong() and 0xFFFFFFFFL
        height = big.getInt(20).toLong() and 0xFFFFFFFFL
    } else if (kind == ImageKind.Gif && head.size >= 10) {
        width = u16(6)
        height = u16(8)
    } else if (kind == ImageKind.WebP && head.size >= ImageHeadBytes) {
        // The first chunk is VP8X (extended), VP8L (lossless) or VP8 (lossy), each with its own
        // way of writing the dimensions.
        when (String(head, 12, 4, Charsets.ISO_8859_1)) {
            "VP8X" -> {
                width = (u32(24) and 0xFFFFFF) + 1
                height = (u32(26) shr 8) + 1
            }
            "VP8L" -> {
                val bits = u32(21)
                width = (bits and 0x3FFF) + 1
                height = ((bits shr 14) and 0x3FFF) + 1
            }
            "VP8 " -> {
                width = u16(26) and 0x3FFF
                height = u16(28) and 0x3FFF
            }
        }
    }
    return if (width * height > MaxImagePixels) ImageKind.None else kind
}

/** Tells whether bytes hold a signature, given as Latin-1 text, at an offset. */
private fun ByteArray.startsWith(signature: String, at: Int = 0): Boolean =
    size >= at + signature.length && signature.indices.all { this[at + it] == signature[it].code.toByte() }

/**
 * A reference of a note body to a file of an attachment folder.
 *
 * @param noteId The note whose folder holds the file.
 * @param image Whether the body shows it as an image rather than as an attachment chip.
 */
data class AttachmentRef(val noteId: String, val fileName: String, val image: Boolean)

private const val SourcePrefix = "../attachments/"

/**
 * Tells whether a character of a file name is percent-encoded in an address: those that end or
 * change a URL or an attribute. Other characters, Cyrillic letters included, stay readable.
 */
private fun needsEncoding(c: Char): Boolean = c < ' ' || c in "%#? \"<>\\^`{|}" || c == '\u007F'

/**
 * Returns the address a note body uses for a file: `../attachments/<note-id>/<file>`, with the
 * characters that would break a URL percent-encoded.
 */
fun attachmentSource(noteId: String, fileName: String): String = buildString {
    append(SourcePrefix).append(noteId).append('/')
    for (c in fileName) {
        if (needsEncoding(c)) append('%').append(c.code.toString(16).uppercase().padStart(2, '0')) else append(c)
    }
}

/** Decodes `%XX` sequences into UTF-8 bytes, as Qt's `QUrl::fromPercentEncoding`; others stay. */
private fun percentDecoded(text: String): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val high = if (i + 2 < bytes.size && bytes[i] == '%'.code.toByte()) Character.digit(bytes[i + 1].toInt(), 16) else -1
        val low = if (high >= 0) Character.digit(bytes[i + 2].toInt(), 16) else -1
        if (low >= 0) {
            out.write(high * 16 + low)
            i += 3
        } else {
            out.write(bytes[i].toInt())
            i++
        }
    }
    return out.toString(Charsets.UTF_8.name())
}

/**
 * Parses an address written by [attachmentSource].
 *
 * @return The note and the decoded file name, or null for any other address.
 */
fun parseAttachmentSource(source: String): AttachmentRef? {
    if (!source.startsWith(SourcePrefix)) return null
    val rest = source.substring(SourcePrefix.length)
    val slash = rest.indexOf('/')
    if (slash <= 0) return null
    val noteId = rest.substring(0, slash)
    val fileName = percentDecoded(rest.substring(slash + 1))
    if (!isSafeFileName(fileName) || '\u0000' in fileName || '.' in noteId || '\\' in noteId || '\u0000' in noteId) return null
    return AttachmentRef(noteId, fileName, image = false)
}

/**
 * Finds the images and attachment chips a note body refers to.
 *
 * @return The references in body order; a file used twice is listed twice.
 */
fun bodyAttachments(body: String): List<AttachmentRef> = tokenizeHtml(body).mapNotNull { token ->
    when {
        token.kind != HtmlToken.Kind.Start || token.name != "img" && token.name != "a" -> null
        token.name == "img" -> parseAttachmentSource(token.attribute("src"))?.copy(image = true)
        token.attribute("class") == "attachment" -> parseAttachmentSource(token.attribute("href"))
        else -> null
    }
}

/**
 * Returns a name a file can be stored under: slashes and control characters become `_`, and an
 * empty name or a dot path becomes `file`.
 */
fun safeFileName(name: String): String {
    val safe = name.trim().map { if (it == '/' || it == '\\' || it < ' ' || it == '\u007F') '_' else it }.joinToString("")
    return if (safe.isEmpty() || safe == "." || safe == "..") "file" else safe
}

/**
 * Returns a name not used in a folder: the name itself, or with a suffix before its extension
 * (`photo (2).jpg`); a name starting with a dot has no extension of its own.
 */
fun uniqueFileName(dir: Path, name: String): String {
    if (!Files.exists(dir.resolve(name))) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    return generateSequence(2) { it + 1 }.map { "$base ($it)$extension" }.first { !Files.exists(dir.resolve(it)) }
}

/** How a file is stored: as an attachment, as an image when it is one, or only when it is one. */
enum class StoreAs { File, Image, ImageOnly }

/**
 * A file stored in an attachment folder.
 *
 * @param fileName The name it was stored under.
 * @param kind The image kind it is shown as; [ImageKind.None] for an attachment chip.
 */
data class StoredFile(val fileName: String, val size: Long, val kind: ImageKind)

/** Files are copied a megabyte at a time, so a large attachment never sits in memory whole. */
private const val CopyChunkBytes = 1 shl 20

/**
 * The prefix of a file being stored, by an insertion or a sync run's download; it lies beside the
 * note folders, where sync does not look.
 */
internal const val StorePrefix = ".jotter-store-"

/** A file being stored this long ago was left by a stop half way: storing writes it without pause. */
private const val StoreLeftoverMs = 60L * 60 * 1000

/**
 * Stores a file in a note's attachment folder (desktop spec section 3.5) under its name, made safe
 * and unique. The data is copied into a temporary file in the folder of the attachment folders,
 * flushed and renamed into place, so neither the note nor a sync run sees half a file.
 *
 * @param dir The note's attachment folder; created when missing.
 * @param name The file's original name.
 * @param input The file's contents; read up to one byte past [limit], not closed.
 * @param storeAs Whether to look for an image kind, and whether to refuse a file that is none.
 * @param limit The largest file accepted: [MaxFileBytes] for a file being inserted.
 * @return The stored file.
 * @throws FormatException TooLarge over [limit], Refused for [StoreAs.ImageOnly] and a file
 *   that is not a shown image, Io when reading or writing fails; nothing is left behind.
 */
fun storeFile(dir: Path, name: String, input: InputStream, storeAs: StoreAs, limit: Long = MaxFileBytes): StoredFile {
    var temporary: Path? = null
    try {
        Files.createDirectories(dir)
        requireRealFolder(dir)
        val staged = Files.createTempFile(dir.toAbsolutePath().parent, StorePrefix, ".tmp")
        temporary = staged
        val head = ByteArrayOutputStream(ImageHeadBytes)
        var size = 0L
        FileOutputStream(staged.toFile()).use { out ->
            val buffer = ByteArray(CopyChunkBytes)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (head.size() < ImageHeadBytes) head.write(buffer, 0, minOf(read, ImageHeadBytes - head.size()))
                size += read
                if (size > limit) throw FormatException(ErrorCode.TooLarge, name)
                out.write(buffer, 0, read)
            }
            out.fd.sync()
        }
        val kind = if (storeAs == StoreAs.File) ImageKind.None else shownKind(head.toByteArray())
        if (storeAs == StoreAs.ImageOnly && kind == ImageKind.None) throw FormatException(ErrorCode.Refused, name, "not an accepted image")
        val safe = safeFileName(name)
        // Only this application writes the folder, so a name taken between the check and the
        // rename means another insertion of the same name; the next suffix is tried then.
        repeat(100) {
            val fileName = uniqueFileName(dir, safe)
            try {
                Files.move(staged, dir.resolve(fileName))
                temporary = null
                return StoredFile(fileName, size, kind)
            } catch (_: FileAlreadyExistsException) {
            }
        }
        throw FormatException(ErrorCode.Io, dir.resolve(safe).toString(), "no free file name")
    } catch (e: IOException) {
        throw FormatException(ErrorCode.Io, dir.resolve(name).toString(), e.message.orEmpty(), e)
    } finally {
        temporary?.let { runCatching { Files.deleteIfExists(it) } }
    }
}

/**
 * A note whose attachments were copied into its own folder.
 *
 * @param note The note, its body naming the copies.
 * @param fileNames The names of the copies in the note's folder.
 */
data class CopiedAttachments(val note: Note, val fileNames: List<String>)

/** Returns the address an image or attachment chip of a start tag names, or null for any other tag. */
private fun objectSource(token: HtmlToken): String? = when {
    token.kind != HtmlToken.Kind.Start -> null
    token.name == "img" -> token.attribute("src")
    token.name == "a" && token.attribute("class") == "attachment" -> token.attribute("href")
    else -> null
}

/**
 * Copies every image and attachment a note refers to into the note's own folder and rewrites their
 * addresses, so a conflict or normalised copy depends on no other note (desktop spec section 12.4).
 * The source files stay as they are. A file already over the insertion limit is copied too.
 *
 * @param libraryRoot The library folder.
 * @param note The new note, under its new identifier, with the body of the note it copies.
 * @return The note with the rewritten body and the names of the copies.
 * @throws FormatException Refused for an address outside the library's note folders, NotFound or Io
 *   for a file that cannot be read or stored; the note's folder is removed again, and the note
 *   must not be published.
 */
fun copyNoteAttachments(libraryRoot: Path, note: Note): CopiedAttachments {
    val tokens = tokenizeHtml(note.body)
    val copied = LinkedHashMap<String, String>()
    val names = mutableListOf<String>()
    val dir = libraryRoot.resolve(AttachmentsDir).resolve(note.meta.id)
    try {
        for (source in tokens.mapNotNull(::objectSource).distinct()) {
            val reference = parseAttachmentSource(source) ?: continue
            if (!isValidId(reference.noteId)) throw FormatException(ErrorCode.Refused, source)
            val folder = libraryRoot.resolve(AttachmentsDir).resolve(reference.noteId)
            val stored = openRegularFile(folder.resolve(reference.fileName)).use {
                storeFile(dir, reference.fileName, it, StoreAs.File, limit = Long.MAX_VALUE)
            }
            copied[source] = attachmentSource(note.meta.id, stored.fileName)
            names += stored.fileName
        }
    } catch (e: FormatException) {
        runCatching { removePath(dir) }
        throw e
    }
    if (copied.isEmpty()) return CopiedAttachments(note, names)
    // Rebuilt from the decoded tokens, so that single quotes and other spellings of an address do
    // not leave the copy pointing at the original's folder.
    val body = buildString {
        for (token in tokens) {
            when (token.kind) {
                HtmlToken.Kind.Text -> append(escapedText(token.text))
                HtmlToken.Kind.End -> append("</").append(token.name).append('>')
                HtmlToken.Kind.Start -> {
                    append('<').append(token.name)
                    for ((key, value) in token.attributes) {
                        val rewritten = if (key == "src" || key == "href") copied[value] ?: value else value
                        append(' ').append(key).append("=\"").append(escapedAttribute(rewritten)).append('"')
                    }
                    append('>')
                }
            }
        }
    }
    return CopiedAttachments(note.copy(body = body), names)
}

/**
 * Removes the temporary files of insertions and downloads the system stopped half way, which lie
 * beside the note folders of a library's attachments. Only files not written for an hour go, as a
 * sync run may be storing one as the application starts.
 *
 * @param libraryRoot The library folder.
 * @param now The current time in milliseconds.
 */
fun removeStoreLeftovers(libraryRoot: Path, now: Long = System.currentTimeMillis()) {
    libraryRoot.resolve(AttachmentsDir).toFile()
        .listFiles { file -> file.isFile && file.name.startsWith(StorePrefix) && now - file.lastModified() > StoreLeftoverMs }
        ?.forEach { it.delete() }
}

/** Extensions of Android packages, which the phone would install. */
private val PackageExtensions = setOf("apk", "apks", "apkm", "xapk")

/**
 * Tells whether a file is an Android package the phone would install, by its name or its
 * content: a ZIP archive holding `AndroidManifest.xml` or packages. Either alone can be
 * misleading, so both are checked.
 */
fun isInstallable(file: File): Boolean {
    if (file.extension.lowercase() in PackageExtensions) return true
    return try {
        ZipFile(file).use { zip -> zip.entries().asSequence().any { it.name == "AndroidManifest.xml" || it.name.lowercase().endsWith(".apk") } }
    } catch (_: IOException) {
        false
    }
}
