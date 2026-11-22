package io.github.nir0k.jotter.core.format

import io.github.nir0k.jotter.core.body.Block
import io.github.nir0k.jotter.core.body.Doc
import io.github.nir0k.jotter.core.body.LineSeparator
import io.github.nir0k.jotter.core.body.ObjectCharacter
import io.github.nir0k.jotter.core.body.readMarkdown
import io.github.nir0k.jotter.core.body.serializeBody
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** What may be an address in shared text; only a valid one becomes a link, as the desktop links typed addresses. */
private val WebAddress = Regex("""https?://[^\s<>"]+""")

/** Characters that end a sentence around an address rather than belong to it. */
private const val TrailingPunctuation = ".,;:!?'\"»…"

/**
 * Returns where an address found in text ends: closing punctuation is left out, and a closing
 * bracket too unless the address opened one, as in a Wikipedia address.
 */
private fun addressEnd(address: String): Int {
    var end = address.length
    while (end > 0) {
        val c = address[end - 1]
        val unmatched = c == ')' && address.take(end).count { it == '(' } < address.take(end).count { it == ')' }
        if (c !in TrailingPunctuation && !unmatched) break
        end--
    }
    return end
}

/** Writes a line of shared text with its web addresses as links. */
private fun linkedLine(line: String): String = buildString {
    var at = 0
    for (match in WebAddress.findAll(line)) {
        val address = match.value.take(addressEnd(match.value))
        if (!isWebLink(address)) continue
        append(escapedText(line.substring(at, match.range.first)))
        append("<a href=\"").append(escapedAttribute(address)).append("\">").append(escapedText(address)).append("</a>")
        at = match.range.first + address.length
    }
    append(escapedText(line.substring(at)))
}

/**
 * Builds the body of a note captured from another application in the form the desktop writes:
 * each line of the text a paragraph, empty lines empty paragraphs and web addresses links;
 * then the stored files in one paragraph, separated by spaces, as the editor inserts them:
 * accepted images at full width, other files as attachment chips.
 *
 * @param text The shared text; blank lines around it are dropped.
 * @param files The files stored in the note's attachment folder.
 * @param noteId The note, whose folder the files' addresses name.
 * @return The body, empty when nothing was shared.
 */
fun capturedBody(text: String, files: List<StoredFile>, noteId: String): String {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
    val blocks = lines.map { line -> if (line.isBlank()) "<p></p>" else "<p>${linkedLine(line)}</p>" }.toMutableList()
    if (files.isNotEmpty()) {
        blocks += files.joinToString(" ", "<p>", "</p>") { file ->
            val source = escapedAttribute(attachmentSource(noteId, file.fileName))
            if (file.kind != ImageKind.None) "<img src=\"$source\">"
            else "<a class=\"attachment\" href=\"$source\" data-size=\"${file.size}\">${escapedText(file.fileName)}</a>"
        }
    }
    return blocks.joinToString("\n")
}

/** The largest text file that opens as a note, in bytes: one megabyte, so the editor stays responsive. */
const val OpenedFileLimit = 1 shl 20

/** The media types of Markdown; other files opened in Jotter are plain text. */
private val MarkdownTypes = setOf("text/markdown", "text/x-markdown")

/** The file name extensions of Markdown, for a file whose media type does not say so. */
private val MarkdownExtensions = setOf("md", "markdown")

/**
 * OpenedNote is the note made of a text file another application opened in Jotter.
 *
 * @param title The note's title.
 * @param body The body in the desktop's form, empty for an empty file.
 */
data class OpenedNote(val title: String, val body: String)

/**
 * Reads the bytes of a file opened in Jotter as UTF-8 text.
 *
 * @return The text without a leading byte order mark, or null when the bytes are not valid UTF-8.
 */
fun openedText(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
        .removePrefix("\uFEFF")
} catch (_: CharacterCodingException) {
    null
}

/**
 * Tells whether a file opened in Jotter is Markdown: by its media type, or by its name's extension
 * when the media type is missing or generic, as some applications send `text/plain` for Markdown.
 *
 * @param fileName The file's display name.
 * @param mediaType The media type the opening application or the provider gave, if any.
 */
fun isMarkdownFile(fileName: String, mediaType: String?): Boolean =
    mediaType?.substringBefore(';')?.trim()?.lowercase() in MarkdownTypes ||
        fileName.substringAfterLast('.', "").lowercase() in MarkdownExtensions

/**
 * Builds the note of a text file another application opened in Jotter. Markdown is read as pasted
 * Markdown is ([readMarkdown]), and a level-one heading that starts it becomes the title; plain
 * text, and Markdown without formatting, goes in as shared text does ([capturedBody]). Without such
 * a heading the title is the file name without its extension.
 *
 * @param fileName The file's display name.
 * @param text The file's text.
 * @param markdown Whether the file is Markdown (see [isMarkdownFile]).
 * @param language Returns the stored language of a code fence's language, given in lower case.
 */
fun openedNote(fileName: String, text: String, markdown: Boolean, language: (String) -> String): OpenedNote {
    val name = fileName.substringBeforeLast('.').trim().ifEmpty { fileName.trim() }
    val doc = (if (markdown) readMarkdown(text, language = language) else null)
        ?: return OpenedNote(name, capturedBody(text, emptyList(), ""))
    val first = doc.items.firstOrNull()
    val heading = (first as? Block)?.takeIf { it.heading == 1 }?.text
        ?.replace(LineSeparator, ' ')?.replace(ObjectCharacter.toString(), "")?.trim().orEmpty()
    if (heading.isEmpty()) return OpenedNote(name, serializeBody(doc))
    val rest = doc.items.drop(1).dropWhile { it is Block && it.isEmptyParagraph }
    return OpenedNote(heading, if (rest.isEmpty()) "" else serializeBody(Doc(rest)))
}
