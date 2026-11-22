package io.github.nir0k.jotter.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the body of a captured note: the desktop's forms of paragraphs, links, images and chips;
 * and the note made of a text file opened in Jotter.
 */
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

    /** An opened file is UTF-8 without its byte order mark; other bytes are not taken. */
    @Test
    fun openedText() {
        assertEquals("# Plan\nдело", openedText("\uFEFF# Plan\nдело".toByteArray()))
        assertEquals("", openedText(ByteArray(0)))
        assertNull(openedText(byteArrayOf(0x61, 0xC3.toByte(), 0x28)))
        assertNull(openedText("Привет".toByteArray(charset("windows-1251"))))
    }

    /** Markdown is told by the media type, or by the extension when the type does not say so. */
    @Test
    fun markdownFile() {
        assertTrue(isMarkdownFile("export", "text/markdown"))
        assertTrue(isMarkdownFile("export", "text/x-markdown; charset=UTF-8"))
        assertTrue(isMarkdownFile("README.MD", "text/plain"))
        assertTrue(isMarkdownFile("notes.markdown", null))
        assertFalse(isMarkdownFile("notes.txt", "text/plain"))
        assertFalse(isMarkdownFile("md", null))
    }

    /** A level-one heading starting Markdown becomes the title, and the rest the formatted body. */
    @Test
    fun openedMarkdownWithHeading() {
        assertEquals(
            OpenedNote("Plan v2", "<ul>\n<li>one</li>\n<li><strong>two</strong></li>\n</ul>"),
            openedNote("chat.md", "\n# Plan **v2**\n\n- one\n- **two**\n", markdown = true) { it },
        )
        assertEquals(OpenedNote("Only", ""), openedNote("chat.md", "# Only\n", markdown = true) { it })
    }

    /** Code fences keep their language as the stored one, also right after the title. */
    @Test
    fun openedMarkdownCode() = assertEquals(
        OpenedNote("Code", "<pre data-language=\"kt\"><code>val x = 1</code></pre>"),
        openedNote("a.md", "# Code\n\n```Kotlin\nval x = 1\n```\n", markdown = true) { if (it == "kotlin") "kt" else it },
    )

    /** Without a level-one heading the title is the file name without its extension. */
    @Test
    fun openedMarkdownTitleFromName() {
        assertEquals(
            OpenedNote("notes.v2", "<h2>Part</h2>\n<p>Text with <em>emphasis</em></p>"),
            openedNote("notes.v2.md", "## Part\n\nText with *emphasis*", markdown = true) { it },
        )
        assertEquals(OpenedNote(".md", "<p>Plain</p>"), openedNote(".md", "Plain", markdown = true) { it })
    }

    /** Markdown without formatting and plain text go in as shared text, line by line, Markdown syntax kept. */
    @Test
    fun openedPlainText() {
        assertEquals(OpenedNote("todo", "<p>milk</p>\n<p>bread</p>"), openedNote("todo.md", "milk\nbread\n", markdown = true) { it })
        assertEquals(
            OpenedNote("todo", "<p># milk</p>\n<p></p>\n<p>- <a href=\"https://example.com\">https://example.com</a></p>"),
            openedNote("todo.txt", "# milk\n\n- https://example.com", markdown = false) { it },
        )
        assertEquals(OpenedNote("empty", ""), openedNote("empty.txt", "", markdown = false) { it })
    }
}
