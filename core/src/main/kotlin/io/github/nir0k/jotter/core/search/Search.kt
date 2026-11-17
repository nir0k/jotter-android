package io.github.nir0k.jotter.core.search

import io.github.nir0k.jotter.core.format.isQtSpace
import io.github.nir0k.jotter.core.format.normalizedTag
import java.time.Instant

/** Where the words of a query are looked for. */
enum class SearchScope {
    Title,
    Text,
    Tags,

    /** Collection names: a match finds the notes of the collection and its descendants. */
    Collections,
}

/**
 * One part of a query.
 *
 * @param text The word or phrase as typed, or the tag in its stored form.
 * @param prefix Whether the last word, still being typed, matches as a prefix.
 * @param exclude Whether notes that match are left out (`-word`).
 */
data class SearchTerm(
    val kind: Kind = Kind.Word,
    val text: String,
    val prefix: Boolean = false,
    val exclude: Boolean = false,
) {
    enum class Kind {
        /** A bare word; punctuation inside it (`e-mail`) makes it a short phrase. */
        Word,

        /** Words in double quotes, matched in their order. */
        Phrase,

        /** `#tag`: a filter by the tag and its children, not a word of the text. */
        Tag,
    }
}

/**
 * Splits a query into terms: words must all match, `"exact phrase"`, `-word` excludes, `#tag`
 * filters by a tag. The last word or an unclosed phrase matches as a prefix unless the query
 * ends with a space. Terms without a single letter or digit are dropped.
 *
 * @return The terms in their order.
 */
fun parseQuery(query: String): List<SearchTerm> {
    val terms = mutableListOf<SearchTerm>()
    val size = query.length
    var i = 0
    while (i < size) {
        if (query[i].isQtSpace()) {
            i++
            continue
        }
        var exclude = false
        if (query[i] == '-' && i + 1 < size && !query[i + 1].isQtSpace()) {
            exclude = true
            i++
        }
        var open = true
        var kind = SearchTerm.Kind.Word
        val text: String
        if (query[i] == '"') {
            val end = query.indexOf('"', i + 1)
            kind = SearchTerm.Kind.Phrase
            text = query.substring(i + 1, if (end < 0) size else end)
            open = end < 0
            i = if (end < 0) size else end + 1
        } else {
            var end = i
            while (end < size && !query[end].isQtSpace() && query[end] != '"') end++
            val word = query.substring(i, end)
            i = end
            if (word.startsWith('#')) {
                kind = SearchTerm.Kind.Tag
                text = normalizedTag(word)
            } else {
                text = word
            }
        }
        // Only a term that ends the query is still being typed.
        val term = SearchTerm(kind, text, prefix = open && i >= size, exclude = exclude)
        val meaningful = if (kind == SearchTerm.Kind.Tag) text.isNotEmpty() else splitWords(text).isNotEmpty()
        if (meaningful) terms += term
    }
    return terms
}

/**
 * A search of the note list: the query, its scopes and the filters.
 *
 * @param createdFrom Notes created at or after this time; [createdUntil] is exclusive, as are the
 *   updated times.
 * @param collections Notes in any of these collections or their descendants.
 * @param tags Notes with any of these tags or their children.
 * @param includeArchived Whether archived notes are found too, besides the notes of the view.
 */
data class Search(
    val query: String = "",
    val scopes: Set<SearchScope> = SearchScope.entries.toSet(),
    val createdFrom: Instant? = null,
    val createdUntil: Instant? = null,
    val updatedFrom: Instant? = null,
    val updatedUntil: Instant? = null,
    val collections: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val withAttachments: Boolean = false,
    val withLocation: Boolean = false,
    val includeArchived: Boolean = false,
)

/**
 * TermMatcher finds the words of the positive word and phrase terms of a query in a text, in every
 * word form, as the index matches them: for the fragments of search results, collection names and
 * the highlights in a note.
 *
 * @param terms The query's terms; tag terms and excluded terms are ignored.
 */
class TermMatcher(terms: List<SearchTerm>) {
    /** The stems of one term; with prefix, the last stem matches the start of a word. */
    private class Pattern(val stems: List<String>, val prefix: Boolean)

    private val stemmer = Stemmer()
    private val patterns = terms
        .filter { !it.exclude && it.kind != SearchTerm.Kind.Tag }
        .map { Pattern(stemmer.stems(it.text), it.prefix) }
        .filter { it.stems.isNotEmpty() }

    /**
     * Finds the matches in a text: each matching word, and the words of a phrase only where they
     * follow each other.
     *
     * @return The matching words in their order.
     */
    fun find(text: String): List<TextSpan> {
        if (patterns.isEmpty()) return emptyList()
        val words = splitWords(text)
        val stems = words.map { stemmer.stem(text.substring(it.start, it.end)) }
        val matched = BooleanArray(words.size)
        for (pattern in patterns) {
            val length = pattern.stems.size
            for (at in 0..stems.size - length) {
                val same = (0 until length).all { k ->
                    val wanted = pattern.stems[k]
                    if (pattern.prefix && k == length - 1) stems[at + k].startsWith(wanted) else stems[at + k] == wanted
                }
                if (same) matched.fill(true, at, at + length)
            }
        }
        return words.filterIndexed { i, _ -> matched[i] }
    }
}
