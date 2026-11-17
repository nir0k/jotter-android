package io.github.nir0k.jotter.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks which of the library's tags a note's tags popup offers. */
class TagsTest {
    /** The note's own tags go in any case; matches anywhere count, those at the start first; a leading `#` is ignored; at most eight show. */
    @Test
    fun suggestions() {
        val known = listOf("archive", "Home", "improve", "project")
        assertEquals(listOf("archive", "improve", "project"), tagSuggestions(known, listOf("HOME"), ""))
        assertEquals(listOf("project", "improve"), tagSuggestions(known, emptyList(), "#PRO"))
        assertEquals(emptyList<String>(), tagSuggestions(known, emptyList(), "missing"))
        assertEquals(8, tagSuggestions((1..20).map { "tag$it" }, emptyList(), "").size)
    }
}
