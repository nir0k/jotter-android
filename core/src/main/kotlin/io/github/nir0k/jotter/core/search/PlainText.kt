package io.github.nir0k.jotter.core.search

import io.github.nir0k.jotter.core.format.HtmlToken
import io.github.nir0k.jotter.core.format.isBlockElement
import io.github.nir0k.jotter.core.format.isQtSpace
import io.github.nir0k.jotter.core.format.tokenizeHtml

/**
 * Extracts the readable text of a note body: tags are dropped, block elements and line breaks
 * become line breaks, character references are decoded and runs of spaces collapse.
 *
 * @param html The body HTML.
 * @return The text, with blocks separated by single line breaks.
 */
fun plainTextFromHtml(html: String): String {
    val text = StringBuilder(html.length)
    var pendingSpace = false
    var pendingBreak = false
    for (token in tokenizeHtml(html)) {
        if (token.kind != HtmlToken.Kind.Text) {
            if (isBlockElement(token.name)) pendingBreak = true
            continue
        }
        for (c in token.text) {
            if (c.isQtSpace()) {
                pendingSpace = true
                continue
            }
            if (text.isNotEmpty()) {
                if (pendingBreak) text.append('\n') else if (pendingSpace) text.append(' ')
            }
            pendingSpace = false
            pendingBreak = false
            text.append(c)
        }
    }
    return text.toString()
}

/**
 * Shortens text to a preview of at most [length] characters on one line, cutting at a word
 * boundary when one is near and marking the cut with an ellipsis.
 */
fun snippet(text: String, length: Int): String {
    val line = text.replace('\n', ' ')
    if (line.length <= length) return line
    var cut = line.lastIndexOf(' ', length)
    if (cut < length / 2) cut = length
    return line.substring(0, cut).trim() + "…"
}
