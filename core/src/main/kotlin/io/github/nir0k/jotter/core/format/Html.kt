package io.github.nir0k.jotter.core.format

import java.util.Locale

/**
 * One piece of a note body: a start tag, an end tag or decoded text.
 *
 * @param kind What the token is.
 * @param name The element name in lower case, for tags.
 * @param attributes Attributes of a start tag with decoded values, in their order.
 * @param text Decoded text, for text tokens.
 */
data class HtmlToken(
    val kind: Kind,
    val name: String = "",
    val attributes: List<Pair<String, String>> = emptyList(),
    val text: String = "",
) {
    enum class Kind { Start, End, Text }

    /** Returns the value of an attribute, or an empty string when the tag has none. */
    fun attribute(name: String): String = attributes.firstOrNull { it.first == name }?.second.orEmpty()

    /** Tells whether the tag has an attribute. */
    fun hasAttribute(name: String): Boolean = attributes.any { it.first == name }
}

private val NamedReferences = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
)

/** Tells whether a character is white space as Qt's `QChar::isSpace` counts it. */
internal fun Char.isQtSpace(): Boolean = isWhitespace() || this == '\u0085'

/** Tells whether a character is a letter or a number of any kind, as Qt's `isLetterOrNumber`. */
internal fun Char.isLetterOrNumber(): Boolean = isLetter() || category == CharCategory.DECIMAL_DIGIT_NUMBER ||
    category == CharCategory.LETTER_NUMBER || category == CharCategory.OTHER_NUMBER

/**
 * Decodes one character reference without its `&` and `;` (`amp`, `#39`, `#x2014`).
 *
 * @return The character, or null for an unknown name, which is then kept as written.
 */
private fun decodeReference(reference: String): String? {
    if (!reference.startsWith('#')) return NamedReferences[reference]
    val hex = reference.length > 1 && (reference[1] == 'x' || reference[1] == 'X')
    val code = reference.substring(if (hex) 2 else 1).toLongOrNull(if (hex) 16 else 10) ?: return null
    return if (code in 1..0x10FFFF) String(Character.toChars(code.toInt())) else null
}

/** Decodes the character references of a text run or an attribute value. */
private fun decodeReferences(text: String): String {
    if ('&' !in text) return text
    val decoded = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '&') {
            val end = text.indexOf(';', i)
            if (end > i + 1 && end - i <= 10) {
                val character = decodeReference(text.substring(i + 1, end))
                if (character != null) {
                    decoded.append(character)
                    i = end + 1
                    continue
                }
            }
        }
        decoded.append(text[i])
        i++
    }
    return decoded.toString()
}

/** Parses the attributes of a start tag: the text after its name. */
private fun parseAttributes(source: String): List<Pair<String, String>> {
    val attributes = mutableListOf<Pair<String, String>>()
    var i = 0
    fun skipSpace() {
        while (i < source.length && source[i].isQtSpace()) i++
    }
    while (true) {
        skipSpace()
        if (i >= source.length || source[i] == '/') break
        val nameStart = i
        while (i < source.length && !source[i].isQtSpace() && source[i] != '=' && source[i] != '/') i++
        val name = source.substring(nameStart, i).lowercase(Locale.ROOT)
        skipSpace()
        var value = ""
        if (i < source.length && source[i] == '=') {
            i++
            skipSpace()
            if (i < source.length && (source[i] == '"' || source[i] == '\'')) {
                val end = source.indexOf(source[i], i + 1)
                val stop = if (end < 0) source.length else end
                value = decodeReferences(source.substring(i + 1, stop))
                i = stop + 1
            } else {
                val valueStart = i
                while (i < source.length && !source[i].isQtSpace()) i++
                value = decodeReferences(source.substring(valueStart, i))
            }
        }
        if (name.isNotEmpty()) attributes += name to value
    }
    return attributes
}

/**
 * Finds the `>` ending a tag, outside quoted attribute values.
 *
 * @param open The index of the tag's `<`.
 * @return The index, or -1 when the tag does not end.
 */
private fun tagEnd(html: String, open: Int): Int {
    var quote: Char? = null
    for (i in open + 1 until html.length) {
        val c = html[i]
        when {
            quote != null -> if (c == quote) quote = null
            c == '"' || c == '\'' -> quote = c
            c == '>' -> return i
        }
    }
    return -1
}

/**
 * Splits note body HTML into tokens, as the desktop's tolerant scanner does: the body uses
 * Jotter's restricted vocabulary, so comments and declarations are skipped, character references
 * are decoded and a stray `<` that does not start a tag is kept as text.
 *
 * @param html The body HTML.
 * @return The tokens in document order; adjacent text is joined into one token.
 */
fun tokenizeHtml(html: String): List<HtmlToken> {
    val tokens = mutableListOf<HtmlToken>()
    fun appendText(text: String) {
        if (text.isEmpty()) return
        val last = tokens.lastOrNull()
        if (last?.kind == HtmlToken.Kind.Text) {
            tokens[tokens.size - 1] = last.copy(text = last.text + decodeReferences(text))
        } else {
            tokens += HtmlToken(HtmlToken.Kind.Text, text = decodeReferences(text))
        }
    }
    var i = 0
    while (i < html.length) {
        val open = html.indexOf('<', i)
        if (open < 0) {
            appendText(html.substring(i))
            break
        }
        appendText(html.substring(i, open))
        if (html.startsWith("<!--", open)) {
            val end = html.indexOf("-->", open + 4)
            i = if (end < 0) html.length else end + 3
            continue
        }
        val close = tagEnd(html, open)
        val isTag = close > open + 1 &&
            (html[open + 1].isLetter() || html[open + 1] == '/' || html[open + 1] == '!')
        if (!isTag) {
            appendText("<")
            i = open + 1
            continue
        }
        val inside = html.substring(open + 1, close)
        i = close + 1
        when {
            inside.startsWith('!') -> Unit // A declaration such as <!doctype html>.
            inside.startsWith('/') ->
                tokens += HtmlToken(HtmlToken.Kind.End, name = inside.substring(1).trim().lowercase(Locale.ROOT))
            else -> {
                var nameEnd = 0
                while (nameEnd < inside.length && inside[nameEnd].isLetterOrNumber()) nameEnd++
                tokens += HtmlToken(
                    HtmlToken.Kind.Start,
                    name = inside.substring(0, nameEnd).lowercase(Locale.ROOT),
                    attributes = parseAttributes(inside.substring(nameEnd)),
                )
            }
        }
    }
    return tokens
}

/** Escapes text as Qt's `QString::toHtmlEscaped`: `<`, `>`, `&` and `"`. */
fun htmlEscaped(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }
}

/** Escapes text as the desktop's body codec writes it: `&`, `<` and `>`, so quotes stay as they are. */
internal fun escapedText(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Escapes an attribute value in double quotes: as [escapedText], and `"`. */
internal fun escapedAttribute(text: String): String = escapedText(text).replace("\"", "&quot;")

/** Reads an integer attribute as Qt's `QString::toInt`, giving 0 for anything else. */
internal fun integerAttribute(value: String): Int = value.trim().toIntOrNull() ?: 0

private val BlockElements = setOf(
    "p", "h1", "h2", "h3", "li", "blockquote", "pre", "tr", "td", "th", "br", "hr", "ul", "ol", "table", "div", "img",
)

/** Tells whether an element separates blocks of text: paragraphs, headings, list items, cells, line breaks. */
fun isBlockElement(name: String): Boolean = name in BlockElements

/** How a problem of a body outside the vocabulary starts; such a note can be copied in a normalised form. */
const val UnsupportedBody = "unsupported body:"

/** The elements of the note vocabulary (desktop spec section 3.3). */
private val BodyElements = "a blockquote br code col colgroup em h1 h2 h3 hr img li mark ol p pre s span strong sub sup table tbody td th thead tr u ul"
    .split(' ').toSet()

/** The elements without an end tag. */
private val VoidElements = setOf("br", "hr", "img", "col")

/** The attributes each element may have. */
private val BodyAttributes = mapOf(
    "a" to setOf("href", "class", "data-size"),
    "span" to setOf("data-color", "style"),
    "mark" to setOf("data-color", "style"),
    "img" to setOf("src", "data-width", "data-wrap"),
    "pre" to setOf("data-language", "data-title"),
    "ul" to setOf("class"),
    "ol" to setOf("data-style", "start"),
    "li" to setOf("data-checked"),
    "table" to setOf("data-width"),
    "col" to setOf("style"),
)

private val TableParts = setOf("thead", "tbody", "colgroup")
private val BlockInCell = setOf("p", "h1", "h2", "h3", "blockquote", "ul", "ol", "pre", "table", "hr")
private val BlockParents = setOf("blockquote", "li", "td", "th")
private val InlineParents = setOf("p", "h1", "h2", "h3", "li", "td", "th", "strong", "em", "u", "s", "sup", "sub", "span", "mark", "a", "code")

/** Tells whether an element may stand directly inside a parent; the empty parent is the body. */
private fun allowedInside(name: String, parent: String): Boolean = when {
    name in TableParts -> parent == "table"
    name == "tr" -> parent == "table" || parent == "thead" || parent == "tbody"
    name == "td" || name == "th" -> parent == "tr"
    name == "col" -> parent == "colgroup"
    name == "li" -> parent == "ul" || parent == "ol"
    name in BlockInCell -> (parent.isEmpty() || parent in BlockParents) && !((name == "pre" || name == "table") && (parent == "td" || parent == "th"))
    else -> parent in InlineParents || (name == "code" && parent == "pre")
}

/** Tells whether a `style` attribute holds the one declaration the vocabulary writes on the element. */
private fun knownStyle(token: HtmlToken, value: String): Boolean {
    val known = (token.name == "col" && value.startsWith("width:")) ||
        (token.name == "span" && value.startsWith("font-family:")) ||
        (token.hasAttribute("data-color") && (value.startsWith("color:") || value.startsWith("background:")))
    return known && ';' !in value && '(' !in value && '\\' !in value
}

/** Returns what is wrong with the attributes of a start tag, or null when they are of the vocabulary. */
private fun attributesProblem(token: HtmlToken): String? {
    val seen = mutableSetOf<String>()
    for ((name, value) in token.attributes) {
        if (!seen.add(name) || name !in BodyAttributes[token.name].orEmpty()) return "attribute $name on ${token.name}"
        if (name == "style" && !knownStyle(token, value)) return "style"
    }
    val classValue = token.attribute("class")
    return when {
        token.hasAttribute("data-width") && integerAttribute(token.attribute("data-width")) !in 10..99 -> "width"
        token.hasAttribute("data-checked") && token.attribute("data-checked") != "true" && token.attribute("data-checked") != "false" -> "checklist state"
        token.hasAttribute("class") && !(
            (token.name == "ul" && classValue == "checklist") ||
                (token.name == "a" && (classValue == "attachment" || classValue == "note-link"))
            ) -> "class"
        token.name == "img" && (
            parseAttachmentSource(token.attribute("src")) == null ||
                (token.hasAttribute("data-wrap") && token.attribute("data-wrap") != "square" && token.attribute("data-wrap") != "top-bottom")
            ) -> "image source or wrap mode"
        token.name == "a" && !linkAccepted(token) -> "link address"
        else -> null
    }
}

/** Tells whether a link's address is what its kind may hold: an attachment's, a note's, or a web or mail address. */
private fun linkAccepted(token: HtmlToken): Boolean {
    val address = token.attribute("href")
    return if (token.attribute("class") == "attachment") parseAttachmentSource(address) != null
    else noteLinkTarget(address) != null || isAcceptedLink(address)
}

/**
 * Validates a note body against the vocabulary of desktop spec section 3.3, as the desktop's
 * `bodyProblem`: a body outside it is listed under Problems and is never rewritten. Unknown values
 * of data attributes, such as numbering styles, colour keys and code languages, are valid and stay.
 *
 * @return The first problem, an English text starting with `unsupported body:`, or null for a valid body.
 */
fun bodyProblem(body: String): String? {
    if ("<!" in body) return "$UnsupportedBody comment or declaration"
    val stack = ArrayList<String>()
    var lists = 0
    for (token in tokenizeHtml(body)) {
        if (token.kind == HtmlToken.Kind.Text) continue
        if (token.name !in BodyElements) return "$UnsupportedBody element ${token.name}"
        if (token.kind == HtmlToken.Kind.End) {
            if (token.name in VoidElements || stack.lastOrNull() != token.name) return "$UnsupportedBody mismatched closing element ${token.name}"
            stack.removeAt(stack.lastIndex)
            if (token.name == "ul" || token.name == "ol") lists--
            continue
        }
        val parent = stack.lastOrNull().orEmpty()
        val inCode = "pre" in stack && !(token.name == "code" && parent == "pre")
        if (inCode || !allowedInside(token.name, parent)) return "$UnsupportedBody ${token.name} inside $parent"
        attributesProblem(token)?.let { return "$UnsupportedBody $it" }
        if ((token.name == "ul" || token.name == "ol") && ++lists > 9) return "$UnsupportedBody list nesting exceeds nine levels"
        if (token.name !in VoidElements) stack += token.name
    }
    return if (stack.isEmpty()) null else "$UnsupportedBody unclosed element"
}
