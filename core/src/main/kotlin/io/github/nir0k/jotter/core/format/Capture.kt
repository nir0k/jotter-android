package io.github.nir0k.jotter.core.format

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
