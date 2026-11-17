package io.github.nir0k.jotter.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Checks renaming the text of links when the linked note's title changes. */
class NoteLinksTest {
    private val id = newId()

    /** Only links that still show the old title, as a whole element, take the new one; others keep their text. */
    @Test
    fun renamesLinksShowingTheOldTitle() {
        val link = "<a class=\"note-link\" href=\"$id.html\">"
        // A formatted link has text of its own form, as does a link with other text.
        val kept = "${link}see A &amp; B</a>, ${link}<strong>A &amp; B</strong></a>"
        assertEquals(
            "<p>${link}C &lt;D&gt;</a>, $kept</p>",
            renameNoteLinks("<p>${link}A &amp; B</a>, $kept</p>", id, "A & B", "C <D>"),
        )
        assertNull(renameNoteLinks("<p>${link}Other</a></p>", id, "A & B", "C"))
        assertNull(renameNoteLinks("<p>${link}A</a></p>", id, "", "C"))
    }
}
