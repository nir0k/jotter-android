package io.github.nir0k.jotter.core.format

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The longest base name of an exported file, well within the limits of file systems and archives once an extension and a number are added. */
private const val MaxBaseLength = 100

/** Tells whether a character is refused in file names on some system an export may reach. */
private fun isRefused(c: Char): Boolean = c.code < 0x20 || c.code == 0x7F || c in "/\\:*?\"<>|"

/**
 * Returns the base name of a note's exported file: its title with the characters that file
 * systems or archives refuse replaced by `_`, without leading or trailing spaces and dots, and at
 * most 100 characters long.
 *
 * @param title The note's title.
 * @param untitled The name of a note without a usable title, in the interface language.
 * @return The name without an extension.
 */
fun exportBaseName(title: String, untitled: String): String {
    val trimmed = { c: Char -> c.isQtSpace() || c == '.' }
    var name = title.map { if (isRefused(it)) '_' else it }.joinToString("").dropWhile(trimmed)
    if (name.length > MaxBaseLength) {
        // A surrogate pair is kept whole.
        name = name.take(if (name[MaxBaseLength - 1].isHighSurrogate()) MaxBaseLength - 1 else MaxBaseLength)
    }
    return name.dropLastWhile(trimmed).ifEmpty { untitled }
}

/**
 * ExportNames hands out the base names of the notes of one export, so that no two files share a
 * name: a repeated name becomes `Name (2)`, `Name (3)`… Names are compared without regard to case,
 * as some file systems do.
 */
class ExportNames {
    private val taken = mutableSetOf<String>()

    /** Returns [base], or its first numbered variant not handed out yet. */
    fun take(base: String): String {
        var name = base
        var number = 2
        while (!taken.add(name.lowercase(Locale.ROOT))) name = "$base (${number++})"
        return name
    }
}

/**
 * A file of an export.
 *
 * @param path Its path inside the export.
 * @param data Its contents, when they are in memory.
 * @param source The library file to copy instead of [data].
 */
class ExportFile(val path: String, val data: ByteArray? = null, val source: Path? = null)

/** The elements of the note vocabulary (desktop spec section 3.3); an export writes no others. */
private val VocabularyElements = setOf(
    "a", "blockquote", "br", "code", "col", "colgroup", "em", "h1", "h2", "h3", "hr", "img", "li", "mark", "ol", "p", "pre", "s", "span",
    "strong", "sub", "sup", "table", "tbody", "td", "th", "thead", "tr", "u", "ul",
)

/**
 * Tells whether an element of the note vocabulary keeps an attribute in an export: the vocabulary's
 * own, and a style only without functions or escapes, so it cannot load anything. Event handlers and
 * other attributes of a file changed outside Jotter are left out.
 */
private fun keepsAttribute(name: String, value: String): Boolean = when {
    name == "style" -> '(' !in value && '\\' !in value
    else -> name.startsWith("data-") || name == "href" || name == "src" || name == "class" || name == "start"
}

/** Returns a name as one segment of a relative URL. */
private fun urlSegment(name: String): String = buildString {
    for (byte in name.toByteArray(Charsets.UTF_8)) {
        val c = (byte.toInt() and 0xFF).toChar()
        if (c.code < 0x80 && (c.isLetterOrDigit() || c in "-._~")) append(c) else append('%').append(c.code.toString(16).uppercase().padStart(2, '0'))
    }
}

/** Returns an image file as a `data:` address, or null when it is missing, too large or not an accepted image. */
private fun imageData(path: Path): String? {
    val bytes = try {
        if (Files.size(path) > MaxFileBytes) return null
        readFile(path)
    } catch (_: IOException) {
        return null
    } catch (_: FormatException) {
        return null
    }
    val mime = imageMimeType(imageKind(bytes)) ?: return null
    return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
}

/** Replaces the value of an attribute of a start tag. */
private fun HtmlToken.withAttribute(name: String, value: String) =
    copy(attributes = attributes.map { if (it.first == name) name to value else it })

/** Writes a start tag with its attributes. */
private fun startTag(token: HtmlToken): String = buildString {
    append('<').append(token.name)
    for ((name, value) in token.attributes) append(' ').append(name).append("=\"").append(escapedAttribute(value)).append('"')
    append('>')
}

/**
 * Exports a note as HTML: a self-contained document with the styles inline and the images embedded,
 * and the files of its attachment chips in a `<base>_files/` folder beside it. Links to notes of
 * the same export point to their files, other note links become plain text, and the document holds
 * no scripts.
 *
 * @param library The library holding the note's attachments.
 * @param note The note; its title is the one to show.
 * @param base The document's base name, from [ExportNames].
 * @param exported The base names of the notes in the export, by note identifier.
 * @param subtitle The line under the title, such as the updated date.
 * @return The document, `<base>.html`, followed by the attachment files it links to.
 */
fun exportHtml(library: Library, note: Note, base: String, exported: Map<String, String>, subtitle: String): List<ExportFile> {
    val folder = "${base}_files"
    val attachments = linkedMapOf<String, ExportFile>()
    val body = StringBuilder()
    // The end tag of a link that became plain text is left out with its start tag.
    var dropLinkEnd = false
    // A script or style sheet in a file changed outside Jotter is left out with its contents.
    var skipUntil = ""
    for (read in tokenizeHtml(note.body)) {
        var token = read
        if (skipUntil.isNotEmpty()) {
            if (token.kind == HtmlToken.Kind.End && token.name == skipUntil) skipUntil = ""
            continue
        }
        if (token.kind == HtmlToken.Kind.Text) {
            body.append(escapedText(token.text))
            continue
        }
        if (token.kind == HtmlToken.Kind.Start && (token.name == "script" || token.name == "style")) {
            skipUntil = token.name
            continue
        }
        // Other elements outside the vocabulary keep only their text, as the editor reads them.
        if (token.name !in VocabularyElements) continue
        val link = token.name == "a"
        if (token.kind == HtmlToken.Kind.End) {
            if (link && dropLinkEnd) dropLinkEnd = false else body.append("</").append(token.name).append('>')
            continue
        }
        token = token.copy(attributes = token.attributes.filter { keepsAttribute(it.first, it.second) })
        if (token.name == "img") {
            // Only the note's own images are embedded; an export loads nothing from elsewhere.
            val ref = parseAttachmentSource(token.attribute("src")) ?: continue
            val data = imageData(library.attachmentsDir(ref.noteId).resolve(ref.fileName))
            if (data == null) {
                // A missing image keeps its name, as the editor shows it.
                body.append(escapedText(ref.fileName))
                continue
            }
            token = token.withAttribute("src", data)
        } else if (link) {
            val href = token.attribute("href")
            val target = noteLinkTarget(href)
            val ref = parseAttachmentSource(href)
            when {
                target != null -> {
                    val file = exported[target]
                    if (file == null) {
                        dropLinkEnd = true
                        continue
                    }
                    token = token.withAttribute("href", urlSegment("$file.html"))
                }
                ref != null -> {
                    attachments.getOrPut("$folder/${ref.fileName}") {
                        ExportFile("$folder/${ref.fileName}", source = library.attachmentsDir(ref.noteId).resolve(ref.fileName))
                    }
                    token = token.withAttribute("href", "${urlSegment(folder)}/${urlSegment(ref.fileName)}")
                }
                token.hasAttribute("href") && !isWebLink(href) -> {
                    dropLinkEnd = true
                    continue
                }
            }
        }
        body.append(startTag(token))
    }
    return listOf(ExportFile("$base.html", exportNoteDocument(note.meta, body.toString(), subtitle))) + attachments.values
}

/**
 * ExportPackage collects the files of an export into one file: one note that made one file stays
 * that file, and several notes, or one note that made several files, become a ZIP archive.
 *
 * @param target The temporary file to write.
 * @param several Whether the export holds several notes.
 */
class ExportPackage(private val target: Path, several: Boolean) {
    private var zip: ZipOutputStream? = if (several) openZip() else null
    private var files: List<ExportFile> = emptyList()

    /** Opens the archive at the target. */
    private fun openZip() = ZipOutputStream(Files.newOutputStream(target).buffered())

    /**
     * Adds the files of a note, in the order they are written. A library file that has gone is left
     * out of an archive, as its link in the note already leads nowhere.
     *
     * @throws IOException The archive cannot be written.
     */
    fun add(noteFiles: List<ExportFile>) {
        val archive = zip
        if (archive == null) files = noteFiles else archive.write(noteFiles)
    }

    /** Writes files into the archive; library files are streamed, as an attachment can be large. */
    private fun ZipOutputStream.write(entries: List<ExportFile>) {
        for (file in entries) {
            val source = file.source
            if (source != null && !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue
            putNextEntry(ZipEntry(file.path))
            if (source != null) Files.copy(source, this) else file.data?.let(::write)
            closeEntry()
        }
    }

    /**
     * Finishes the file.
     *
     * @param base The name of an archive without its extension: the collective name of several
     *   notes, or the base name of the one note.
     * @return The name the file is to be offered under.
     * @throws IOException The file cannot be written.
     */
    fun finish(base: String): String {
        val single = files.singleOrNull()?.takeIf { zip == null && it.source == null }
        if (single != null) {
            Files.write(target, single.data ?: ByteArray(0))
            return single.path
        }
        val archive = zip ?: openZip().also { it.write(files) }
        zip = null
        archive.close()
        return "$base.zip"
    }

    /** Closes an archive still open, as an export that stops leaves its temporary file for removal. */
    fun abandon() {
        runCatching { zip?.close() }
        zip = null
    }
}

/**
 * A block of a drawn note.
 *
 * @param top Where it starts in the document.
 * @param bottom Where it ends.
 */
data class ExportBlock(val top: Double, val bottom: Double)

/**
 * Returns where the parts of a tall note start, so that no part is taller than a limit: at the top
 * of a block of the top level, and inside one only when it is taller than the limit by itself.
 *
 * @param blocks The top-level blocks in document order.
 * @param limit The tallest part.
 * @return The start of each part; the first is 0.
 */
fun exportPartStarts(blocks: List<ExportBlock>, limit: Double): List<Double> {
    val starts = mutableListOf(0.0)
    for (block in blocks) {
        if (block.bottom - starts.last() <= limit) continue
        if (block.top > starts.last()) starts += block.top
        while (block.bottom - starts.last() > limit) starts += starts.last() + limit
    }
    return starts
}
