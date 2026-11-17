package io.github.nir0k.jotter.editor

import io.github.nir0k.jotter.core.format.imageKind
import io.github.nir0k.jotter.core.format.imageMimeType
import io.github.nir0k.jotter.core.format.isSafeFileName
import java.io.File
import java.io.IOException

/**
 * A file of the note's attachment folder.
 *
 * @param file The file in the library.
 * @param mimeType Its media type; images by their bytes, never by their names.
 */
data class NoteResource(val file: File, val mimeType: String)

/**
 * NoteAssets resolves the files of one note's attachment folder, which its images and chips name: a
 * name leaving the folder, a file of another note's folder and a missing file are refused, so a
 * note's content reaches nothing else on the device.
 *
 * @param libraryRoot The library folder.
 * @param noteId The open note.
 */
class NoteAssets(private val libraryRoot: File, val noteId: String) {
    private val attachments = File(File(libraryRoot, "attachments"), noteId)

    /**
     * Resolves an attachment of the note by its file name, as an image or a chip names it.
     *
     * @return The file, or null for a name outside the folder or a missing file.
     */
    fun attachment(name: String): NoteResource? {
        if (!isSafeFileName(name)) return null
        val file = File(attachments, name)
        val inside = try {
            file.canonicalFile.parentFile == attachments.canonicalFile
        } catch (_: IOException) {
            false
        }
        if (!inside || !file.isFile) return null
        val head = ByteArray(12)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (_: IOException) {
            return null
        }
        val mime = imageMimeType(imageKind(head.copyOf(maxOf(read, 0)))) ?: "application/octet-stream"
        return NoteResource(file, mime)
    }
}
