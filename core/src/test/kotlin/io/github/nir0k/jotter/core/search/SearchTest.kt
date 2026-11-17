package io.github.nir0k.jotter.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the reduction of words to stems and the parsing and matching of queries. */
class SearchTest {
    /**
     * Words reduce to the stems the desktop gives them, so both clients find the same word forms.
     * Expected values come from the desktop's Stemmer; words whose English stems changed in
     * Snowball after the desktop's 3.0.1 are left out.
     */
    @Test
    fun stemsMatchTheDesktop() {
        val stemmer = Stemmer()
        assertEquals("заметк заметк елк йогурт note run happili general", stemmer.fingerprint())
        val cases = mapOf(
            "заметка заметок заметке заметки" to "заметк заметк заметк заметк",
            "отец отца" to "отц отц",
            "ёлка елка" to "елк елк",
            "notes note running" to "note note run",
            "Café naïve" to "cafe naiv",
            "e-mail" to "e mail",
            "Ğ ǅ Ⅷ ½ ٣" to "g ǆ ⅷ ½ ٣",
            "東京 tokyo" to "東京 tokyo",
        )
        for ((text, stems) in cases) assertEquals(text, stems, stemmer.stems(text).joinToString(" "))
        assertEquals(listOf(TextSpan(0, 3), TextSpan(4, 1)), splitWords("𝔸b c"))
    }

    /** A query splits into words, phrases, exclusions and tags; only its last term is a prefix. */
    @Test
    fun parsesQueries() {
        assertEquals(
            listOf(
                SearchTerm(SearchTerm.Kind.Word, "plan"),
                SearchTerm(SearchTerm.Kind.Phrase, "big world"),
                SearchTerm(SearchTerm.Kind.Word, "bad", exclude = true),
                SearchTerm(SearchTerm.Kind.Tag, "work/urgent"),
                SearchTerm(SearchTerm.Kind.Word, "e-mail", prefix = true),
            ),
            parseQuery("plan \"big world\" -bad ## #work//urgent/ -- … e-mail"),
        )
        assertEquals(listOf(SearchTerm(SearchTerm.Kind.Word, "done")), parseQuery("done "))
        assertEquals(listOf(SearchTerm(SearchTerm.Kind.Phrase, "open phr", prefix = true)), parseQuery("\"open phr"))
    }

    /** Matches find every word form, phrases only in their order, and the typed prefix. */
    @Test
    fun matchesWordForms() {
        val text = "Заметки о поездке: мы купили ёлку и running shoes."
        fun found(query: String) = TermMatcher(parseQuery(query)).find(text).map { text.substring(it.start, it.end) }
        assertEquals(listOf("Заметки", "ёлку"), found("заметок елка "))
        assertEquals(listOf("купили", "ёлку"), found("\"купил елки\" "))
        assertEquals(emptyList<String>(), found("\"ёлку купили\" "))
        assertEquals(listOf("running"), found("ru"))
        assertEquals(emptyList<String>(), found("-заметки #поездка"))
    }
}
