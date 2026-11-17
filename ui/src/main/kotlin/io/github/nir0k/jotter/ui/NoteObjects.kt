package io.github.nir0k.jotter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.MaxFileBytes
import io.github.nir0k.jotter.core.format.StoreAs
import io.github.nir0k.jotter.core.format.StoredFile
import io.github.nir0k.jotter.core.format.isInstallable
import io.github.nir0k.jotter.core.format.isValidId
import io.github.nir0k.jotter.core.format.parseAttachmentSource
import io.github.nir0k.jotter.core.format.storeFile
import io.github.nir0k.jotter.editor.NoteAssets
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * A file handed to the note by another application: a picker's document or the camera's photo.
 *
 * @param name The name it is stored under, made safe and unique.
 * @param open Opens its contents; null when it can no longer be read.
 */
class IncomingFile(val name: String, val open: () -> InputStream?)

/**
 * What storing files in a note gave.
 *
 * @param stored The stored files, in the order they came.
 * @param failed The names of the files that could not be added, with why.
 */
data class StoreResult(val stored: List<StoredFile>, val failed: List<Pair<String, ErrorCode>>)

/**
 * Stores files in a note's attachment folder (desktop spec section 3.5): images by their bytes,
 * everything up to 50 MB. Run off the main thread.
 *
 * @param dir The note's attachment folder.
 * @param files The files.
 * @param storeAs Whether accepted images become images or every file an attachment.
 */
fun storeIncoming(dir: Path, files: List<IncomingFile>, storeAs: StoreAs): StoreResult {
    val stored = mutableListOf<StoredFile>()
    val failed = mutableListOf<Pair<String, ErrorCode>>()
    for (file in files) {
        try {
            val input = file.open() ?: throw FormatException(ErrorCode.NotFound, file.name)
            stored += input.use { storeFile(dir, file.name, it, storeAs) }
        } catch (e: FormatException) {
            failed += file.name to e.code
        } catch (_: IOException) {
            failed += file.name to ErrorCode.Io
        } catch (_: SecurityException) {
            // The handing application withdrew the permission to read the document.
            failed += file.name to ErrorCode.Io
        }
    }
    return StoreResult(stored, failed)
}

/** Describes documents a picker returned, named as the providing application names them. */
fun incomingDocuments(context: Context, uris: List<Uri>): List<IncomingFile> = uris.map { uri ->
    val name = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    IncomingFile(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file") { context.contentResolver.openInputStream(uri) }
}

/**
 * Returns a new file for the camera application to write a photo to, in the cache folder the
 * application's `FileProvider` offers it; named as cameras name photos.
 */
fun cameraFile(context: Context): File {
    val folder = File(context.cacheDir, "camera").apply { mkdirs() }
    return File(folder, "IMG_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.jpg")
}

/** Returns the authority of the application's `FileProvider`, as the manifest declares it. */
fun fileAuthority(context: Context): String = "${context.packageName}.files"

/** Returns the content address another application reads or writes a file of the libraries or the camera folder through. */
fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, fileAuthority(context), file)

/** A picture in a `data:` address, as pasted from a web page; only the kinds a note shows as images. */
private val DataImage = Regex("^data:image/(png|jpeg|gif|webp);base64,(.*)$", RegexOption.DOT_MATCHES_ALL)

/**
 * Stores what a paste brought into the open note's folder (desktop spec section 3.5): a picture
 * of the clipboard, given as a content address, or of pasted HTML, given as a `data:` address,
 * named `<pastedName>.<kind>` and added only when it is an accepted image; or a file of another
 * note of the library, copied so the note does not depend on that note's folder. Run off the main
 * thread.
 *
 * @param context A context to read a content address with.
 * @param library The open library.
 * @param noteId The open note.
 * @param source The `data:` address or the other note's file address.
 * @param image Whether the object is an image rather than an attachment chip.
 * @param pastedName The name of a pasted picture without its extension, in the interface language.
 * @throws FormatException When the object cannot be stored: TooLarge, Refused, NotFound or Io.
 */
fun storePasted(context: Context, library: Library, noteId: String, source: String, image: Boolean, pastedName: String): StoredFile {
    val dir = library.attachmentsDir(noteId)
    if (source.startsWith("content:")) {
        val input = try {
            context.contentResolver.openInputStream(source.toUri())
        } catch (e: Exception) {
            throw FormatException(ErrorCode.Io, pastedName, e.message.orEmpty())
        } ?: throw FormatException(ErrorCode.NotFound, pastedName)
        // The name takes the extension of the type the clipboard gives; the kind is checked from the bytes.
        val extension = when (val type = context.contentResolver.getType(source.toUri())) {
            "image/jpeg" -> "jpg"
            null -> "png"
            else -> type.substringAfter('/').substringBefore('+')
        }
        return input.use { storeFile(dir, "$pastedName.$extension", it, StoreAs.ImageOnly) }
    }
    val data = DataImage.matchEntire(source)
    if (data != null) {
        val (type, encoded) = data.destructured
        if (encoded.length / 4L * 3 > MaxFileBytes + 2) throw FormatException(ErrorCode.TooLarge, pastedName)
        val bytes = try {
            Base64.getMimeDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw FormatException(ErrorCode.Refused, pastedName, e.message.orEmpty())
        }
        val name = "$pastedName.${if (type == "jpeg") "jpg" else type}"
        return storeFile(dir, name, bytes.inputStream(), StoreAs.ImageOnly)
    }
    val ref = parseAttachmentSource(source)?.takeIf { isValidId(it.noteId) && it.noteId != noteId }
        ?: throw FormatException(ErrorCode.Refused, source)
    val file = NoteAssets(library.root.toFile(), ref.noteId).attachment(ref.fileName)?.file
        ?: throw FormatException(ErrorCode.NotFound, ref.fileName)
    return try {
        file.inputStream().use { storeFile(dir, ref.fileName, it, if (image) StoreAs.Image else StoreAs.File) }
    } catch (e: IOException) {
        throw FormatException(ErrorCode.Io, file.path, e.message.orEmpty(), e)
    }
}

/** Returns a file's media type by its extension, as other applications expect it. */
fun mediaType(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

/** Starts another application's activity; returns whether one took the intent. */
fun startActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

/**
 * Offers a file of a note to other applications through the share sheet, with a content address
 * that grants reading this one file.
 *
 * @return Whether the share sheet opened.
 */
fun shareFile(context: Context, file: File): Boolean {
    val share = Intent(Intent.ACTION_SEND).setType(mediaType(file)).putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return startActivity(context, Intent.createChooser(share, null))
}

/**
 * Opens a file of a note's attachment folder with the application the system chooses, through a
 * content address that grants reading this one file. A package the phone would install is never
 * opened from a note, only offered for sharing.
 *
 * @return Whether an application took the file.
 */
fun openFile(context: Context, file: File): Boolean {
    if (isInstallable(file)) return shareFile(context, file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri(context, file), mediaType(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return startActivity(context, view)
}

/**
 * Saves a copy of a note's file where the person chose with the system's document picker.
 *
 * @return Whether the copy was written.
 */
fun saveFileCopy(context: Context, file: File, target: Uri): Boolean = try {
    context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}
