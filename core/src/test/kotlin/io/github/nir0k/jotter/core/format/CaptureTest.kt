package io.github.nir0k.jotter.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the body of a captured note: the desktop's forms of paragraphs, links, images and chips. */
class CaptureTest {
    private val id = "01a1872b-97c3-75de-bada-1e2d73552a21"

    /** Lines become paragraphs with escaped text; addresses become links without the punctuation after them. */
    @Test
    fun text() {
        val body = capturedBody("\r\n  \nSee https://example.com/a?x=1&y=2.\r\n\n<b> & (https://en.wikipedia.org/wiki/Rust_(language))\n\n", emptyList(), id)
        assertEquals(
            "<p>See <a href=\"https://example.com/a?x=1&amp;y=2\">https://example.com/a?x=1&amp;y=2</a>.</p>\n" +
                "<p></p>\n" +
                "<p>&lt;b&gt; &amp; (<a href=\"https://en.wikipedia.org/wiki/Rust_(language)\">https://en.wikipedia.org/wiki/Rust_(language)</a>)</p>",
            body,
        )
        assertEquals("", capturedBody(" \n", emptyList(), id))
    }

    /** Files follow the text in one paragraph: an image at full width and a chip with its size, addresses percent-encoded. */
    @Test
    fun files() {
        val files = listOf(StoredFile("фото 1.png", 10, ImageKind.Png), StoredFile("a&b #2.pdf", 580, ImageKind.None))
        assertEquals(
            "<p>Note</p>\n<p><img src=\"../attachments/$id/фото%201.png\"> " +
                "<a class=\"attachment\" href=\"../attachments/$id/a&amp;b%20%232.pdf\" data-size=\"580\">a&amp;b #2.pdf</a></p>",
            capturedBody("Note", files, id),
        )
    }
}
