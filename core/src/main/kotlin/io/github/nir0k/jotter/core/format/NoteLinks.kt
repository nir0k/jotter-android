package io.github.nir0k.jotter.core.format

/**
 * Returns the address of a link to a note, `<note-id>.html`, which also works when the note file
 * is opened in a browser (desktop spec section 3.3).
 */
fun noteLinkAddress(noteId: String): String = "$noteId.html"

/** Characters a URL never holds unencoded (Qt's strict mode, and `isStrictUrl` of the editor's `html.js`). */
private val IllegalUrlCharacters = Regex("[\\u0000- \\u007f\"<>\\\\^`{|}]")

/** A `%` that does not start an escape. */
private val BrokenEscape = Regex("%(?![0-9a-fA-F]{2})")

/** The scheme of an address that is a web or mail link. */
private val WebSchemes = setOf("http", "https", "mailto")

/** The user, host and port of a web address, without the path after them. */
private val WebAuthority = Regex("^([^@]*@)?(\\[[^\\]]*]|[^:@]+)(:[0-9]*)?$")

/**
 * Tells whether an address is a web or mail link a note keeps: an absolute `http`, `https` or
 * `mailto` URL written as a valid URL. Other schemes, such as `file` or `javascript`, are refused
 * (desktop spec section 3.3).
 */
fun isWebLink(address: String): Boolean {
    val scheme = address.substringBefore(':', "").lowercase()
    if (scheme !in WebSchemes || IllegalUrlCharacters.containsMatchIn(address) || BrokenEscape.containsMatchIn(address)) return false
    if (scheme == "mailto") return address.length > scheme.length + 1
    val rest = address.substring(scheme.length + 1)
    return rest.startsWith("//") && WebAuthority.matches(rest.drop(2).takeWhile { it !in "/?#" })
}

/**
 * Tells whether a typed or stored address is one the note vocabulary accepts: as the desktop's
 * `normaliseLink`, a bare domain counts as `https`, a bare mail address as `mailto`, and characters
 * a URL holds only encoded as encoded, so only what [isWebLink] refuses then is refused.
 */
fun isAcceptedLink(address: String): Boolean {
    val text = address.trim()
    if (text.isEmpty()) return false
    val absolute = if ("://" !in text && !text.startsWith("mailto:")) {
        (if ('@' in text && '/' !in text) "mailto:" else "https://") + text
    } else {
        text
    }
    val encoded = absolute.map { if (it <= ' ' || it >= '\u007f' || it in "\"<>\\^`{|}") "%25" else it.toString() }.joinToString("")
    return isWebLink(encoded.replace(BrokenEscape, "%25"))
}

/**
 * Parses a link address written by [noteLinkAddress].
 *
 * @return The target note's identifier, or null for any other address.
 */
fun noteLinkTarget(address: String): String? = address.removeSuffix(".html").takeIf { address.endsWith(".html") && isValidId(it) }

/**
 * Finds the notes a body links to.
 *
 * @return The targets' identifiers, each once, in body order.
 */
fun bodyNoteLinks(body: String): List<String> = tokenizeHtml(body).mapNotNull { token ->
    if (token.kind == HtmlToken.Kind.Start && token.name == "a") noteLinkTarget(token.attribute("href")) else null
}.distinct()

/**
 * Renames the links to a note whose text still shows its old title; links with text of their own
 * keep it. The codec writes a link without other formatting as the element around its escaped
 * text, so a link that still shows the old title has exactly that form.
 *
 * @param body The body of a note that links to [noteId].
 * @param noteId The renamed note.
 * @param oldTitle Its title before the change.
 * @param newTitle Its new title.
 * @return The body with the links renamed, or null when no link showed the old title.
 */
fun renameNoteLinks(body: String, noteId: String, oldTitle: String, newTitle: String): String? {
    if (oldTitle.isEmpty() || oldTitle == newTitle) return null
    // An identifier holds no character that needs escaping.
    val open = "<a class=\"note-link\" href=\"${noteLinkAddress(noteId)}\">"
    val before = open + escapedText(oldTitle) + "</a>"
    if (before !in body) return null
    return body.replace(before, open + escapedText(newTitle) + "</a>")
}
