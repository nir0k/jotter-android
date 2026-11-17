package io.github.nir0k.jotter.core.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A note as the table of contents lists it.
 *
 * @param collections The collections it is in.
 * @param archived Whether it is in the archive.
 */
data class ContentsNote(
    val id: String,
    val title: String,
    val updated: Instant,
    val tags: List<String>,
    val collections: List<String>,
    val archived: Boolean,
)

/**
 * The texts of the table of contents in the interface language.
 *
 * @param title The document's title and heading.
 * @param untitled The name of a note without a title.
 * @param withoutCollection The heading of the notes in no collection.
 * @param archive The heading of the archived notes.
 * @param empty The sentence of a library without notes.
 */
data class ContentsText(val title: String, val untitled: String, val withoutCollection: String, val archive: String, val empty: String)

/** The style of the table of contents: readable type in a narrow column, in light colours, as the desktop's. */
private const val ContentsStyle =
    "body{font-family:system-ui,sans-serif;line-height:1.5;max-width:45rem;margin:2rem auto;" +
        "padding:0 1rem;color:#1f2328;background:#fff}" +
        "h1{font-size:1.6rem}h2,h3,h4,h5,h6{margin:1.5rem 0 .5rem}" +
        "ul{list-style:none;padding:0;margin:0}li{margin:.25rem 0}" +
        "a{color:#0969da;text-decoration:none}a:hover{text-decoration:underline}" +
        ".meta{color:#59636e;font-size:.85em;margin-left:.5rem}"

/**
 * Builds the generated table of contents, `index.html` (desktop spec section 3.6), for reading
 * the library without Jotter: the notes grouped by collection, a note under each of its
 * collections and nested collections as nested headings, then the notes without a collection,
 * then the archive. Each entry links to the note file and shows its updated date and tags. A
 * collection is listed when it or a descendant holds notes; one whose parent is missing, or that
 * sits in a cycle of a damaged file, is left out, as in the collection tree.
 *
 * @param notes The notes outside the trash, ordered by title.
 * @param collections The collection tree.
 * @param text The headings in the interface language.
 * @param locale The interface language, which the dates follow.
 * @param zone The time zone of the dates.
 * @return The document.
 */
fun tableOfContents(notes: List<ContentsNote>, collections: List<Collection>, text: ContentsText, locale: Locale, zone: ZoneId): ByteArray {
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
    fun list(entries: List<ContentsNote>) = entries.joinToString("", "<ul>\n", "</ul>\n") { note ->
        val meta = dates.format(note.updated.atZone(zone)) + note.tags.joinToString("") { " #$it" }
        "<li><a href=\"notes/${escapedAttribute(noteLinkAddress(note.id))}\">${escapedText(note.title.ifEmpty { text.untitled })}</a>" +
            "<span class=\"meta\">${escapedText(meta)}</span></li>\n"
    }

    val (archived, active) = notes.partition { it.archived }
    val byCollection = active.flatMap { note -> note.collections.map { it to note } }.groupBy({ it.first }, { it.second })
    val loose = active.filter { it.collections.isEmpty() }

    // The tree in the stored order, parents before their children, with their depths.
    val children = collections.groupBy { it.parent }
    val seen = mutableSetOf<String>()
    val tree = mutableListOf<Pair<Collection, Int>>()
    fun walk(parent: String?, depth: Int) {
        for (collection in children[parent].orEmpty()) {
            if (!seen.add(collection.id)) continue
            tree += collection to depth
            walk(collection.id, depth + 1)
        }
    }
    walk(null, 0)
    // Walking the tree backwards reaches every child before its parent.
    val used = mutableSetOf<String>()
    for ((collection, _) in tree.asReversed()) {
        if (collection.id in byCollection || collection.id in used) {
            used += collection.id
            collection.parent?.let(used::add)
        }
    }

    val body = buildString {
        for ((collection, depth) in tree) {
            if (collection.id !in used) continue
            val level = minOf(6, depth + 2)
            append("<h$level>").append(escapedText(collection.name)).append("</h$level>\n")
            byCollection[collection.id]?.let { append(list(it)) }
        }
        if (loose.isNotEmpty()) append("<h2>").append(escapedText(text.withoutCollection)).append("</h2>\n").append(list(loose))
        if (archived.isNotEmpty()) append("<h2>").append(escapedText(text.archive)).append("</h2>\n").append(list(archived))
        if (isEmpty()) append("<p>").append(escapedText(text.empty)).append("</p>\n")
    }
    val title = escapedText(text.title)
    return ("<!doctype html>\n<html lang=\"${locale.toLanguageTag()}\">\n<head>\n<meta charset=\"utf-8\">\n" +
        "<title>$title</title>\n<style>$ContentsStyle</style>\n</head>\n<body>\n<h1>$title</h1>\n$body</body>\n</html>\n").toByteArray()
}
