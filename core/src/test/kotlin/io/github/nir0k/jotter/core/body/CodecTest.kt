package io.github.nir0k.jotter.core.body

import io.github.nir0k.jotter.core.format.parseNote
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trips of note bodies through the model: every shared fixture reads and writes back
 * unchanged, and documents the editor makes are written in the desktop's form.
 */
class CodecTest {
    private val notes: Path = Paths.get(javaClass.getResource("/fixtures/library/notes")!!.toURI())

    /** Every fixture's body reads into the model and writes back byte for byte. */
    @Test
    fun fixturesWriteBackUnchanged() {
        for (file in notes.listDirectoryEntries("*.html").sortedBy { it.name }) {
            val body = parseNote(Files.readAllBytes(file), file).body
            assertEquals(file.name, body, serializeBody(parseBody(body)))
        }
    }

    /** Adjacent frames get the empty paragraph between them that the desktop writes, and none at the ends. */
    @Test
    fun framesGetParagraphsBetweenThem() {
        val table = Table(cells = listOf(listOf(listOf(Block(inline = listOf(TextRun("a", TextFormat.Plain)))))))
        val doc = Doc(listOf(CodeBlock(text = "x"), table, CodeBlock()))
        assertEquals(
            "<pre><code>x</code></pre>\n<p></p>\n<table>\n<tbody>\n<tr><td><p>a</p></td></tr>\n</tbody>\n</table>\n<p></p>\n<pre><code></code></pre>",
            serializeBody(doc),
        )
        assertEquals("<pre><code>x</code></pre>", serializeBody(parseBody("<pre><code>x</code></pre>")))
    }

    /** Links of other schemes read as text, and a formatted line break stays inside its element. */
    @Test
    fun linksAndLineBreaks() {
        assertEquals("<p>x y</p>", serializeBody(parseBody("<p><a href=\"javascript:alert(1)\">x</a> <a href=\"file:///etc\">y</a></p>")))
        assertEquals("<p><strong>a<br>b</strong></p>", serializeBody(parseBody("<p><strong>a<br>b</strong></p>")))
    }

    /** A body outside the vocabulary reads as its supported text and formatting. */
    @Test
    fun unsupportedBodyKeepsText() {
        val normalised = serializeBody(parseBody("<p>Keep <strong>this</strong> text.</p><video src=\"external.webm\">Original unsupported content</video>"))
        assertFalse("video" in normalised)
        assertTrue("Keep <strong>this</strong> text." in normalised)
        assertTrue("Original unsupported content" in normalised)
    }

    /** Pasted HTML keeps structure and basic formatting from styles, drops colours, and keeps only web links. */
    @Test
    fun pasteCleaning() {
        val doc = parsePastedHtml(
            "<p><b style=\"font-weight:normal\"><span style=\"font-weight:700;color:red\">Bold</span> <a href=\"example.com\">x</a> " +
                "<a href=\"file:///etc\">y</a></b><img src=\"file:///a.png\"><img src=\"data:image/png;base64,AA==\"></p>",
        )
        assertEquals("<p><strong>Bold</strong> x y</p>", serializeBody(doc))
        val block = doc.items.single() as Block
        assertEquals(1, block.inline.count { it is ImageObject })
    }
}
