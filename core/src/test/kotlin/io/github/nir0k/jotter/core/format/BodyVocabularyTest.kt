package io.github.nir0k.jotter.core.format

import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks which note bodies count as following the vocabulary (desktop spec section 3.3). */
class BodyVocabularyTest {
    private val attachment = "../attachments/01a1872b-97d1-708d-b700-82a92841f512/a.png"

    /** Every kind of form outside the vocabulary is found. */
    @Test
    fun unsupportedForms() {
        for (body in listOf(
            "<p>a</p><video src=\"x\">b</video>",
            "<!-- comment --><p>a</p>",
            "<p onclick=\"x\">a</p>",
            "<p class=\"x\">a</p>",
            "<ul class=\"other\"><li>a</li></ul>",
            "<strong>a</strong>",
            "<p>a",
            "<p>a</strong></p>",
            "<li>a</li>",
            "<table><tr><td><table><tr><td>a</td></tr></table></td></tr></table>",
            "<pre><code><span>a</span></code></pre>",
            "<p><span style=\"color:red\">a</span></p>",
            "<p><span data-color=\"red\" style=\"color:url(x)\">a</span></p>",
            "<p><img src=\"https://example.com/a.png\"></p>",
            "<p><img src=\"$attachment\" data-width=\"5\"></p>",
            "<p><img src=\"$attachment\" data-wrap=\"tight\"></p>",
            "<p><img src=\"../attachments/x/..%2Fa.png\"></p>",
            "<p><img src=\"../attachments/x/a%00.png\"></p>",
            "<p><a href=\"javascript:alert(1)\">a</a></p>",
            "<p><a href=\"file:///etc/passwd\">a</a></p>",
            "<ul><li>a</li></ul></ul>",
            "<p><a href=\"https://a.b\" href=\"https://c.d\">a</a></p>",
            (1..10).joinToString("") { "<ul><li>" } + "a" + (1..10).joinToString("") { "</li></ul>" },
        )) {
            assertNotNull(body, bodyProblem(body))
            assertTrue(body, bodyProblem(body)!!.startsWith(UnsupportedBody))
        }
    }

    /** Unknown values of data attributes are supported and stay; so are links of every accepted form. */
    @Test
    fun supportedForms() {
        for (body in listOf(
            "",
            "<p></p>",
            "<ol data-style=\"decimal-paren\" start=\"3\"><li>a</li></ol>",
            "<p><span data-color=\"unknown-key\">a</span><mark data-color=\"#123456\">b</mark></p>",
            "<pre data-language=\"no-such-language\" data-title=\"a &gt; b\"><code>x &lt; y</code></pre>",
            "<p>1 &lt; 2 and \"quoted\" text</p>",
            "<p><a href=\"https://example.com/a?b=c#d\">a</a> <a href=\"mailto:a@b.c\">b</a> <a href=\"example.com\">c</a></p>",
            "<p><a href=\"https://ru.wikipedia.org/wiki/Заметка\">a</a></p>",
            "<p><a class=\"note-link\" href=\"01a1872b-97d1-708d-b700-82a92841f512.html\">a</a></p>",
            "<p><a class=\"attachment\" href=\"$attachment\" data-size=\"10\">a.png</a></p>",
            "<p><img src=\"$attachment\" data-width=\"50\" data-wrap=\"square\"></p>",
            "<ul class=\"checklist\"><li data-checked=\"true\"><p>a</p></li></ul>",
            "<table data-width=\"50\"><colgroup><col style=\"width:40%\"></colgroup><tbody><tr><td><p>a</p></td></tr></tbody></table>",
        )) {
            assertNull(body, bodyProblem(body))
        }
    }

    /** A `>` inside a quoted attribute value does not end the tag. */
    @Test
    fun quotedValuesStayInTheirTag() {
        val tokens = tokenizeHtml("<pre data-title=\"a > b\"><code>x</code></pre>")
        assertEquals("a > b", tokens.first().attribute("data-title"))
        assertEquals("pre", tokens.first().name)
    }

    /** A note outside the vocabulary is malformed for reading and is read only for an explicit normalised copy. */
    @Test
    fun readingForNormalisation() {
        val id = "01a1a316-0000-7000-8000-000000000001"
        val path = Paths.get("$id.html")
        val time = now()
        val data = serializeNote(Note(NoteMeta(id = id, created = time, updated = time), "<p>a</p><video>b</video>"))
        val error = runCatching { parseNote(data, path) }.exceptionOrNull() as FormatException
        assertEquals(ErrorCode.Malformed, error.code)
        assertTrue(error.detail.startsWith(UnsupportedBody))
        assertEquals("<p>a</p><video>b</video>", parseNoteForNormalization(data, path).body)
    }

    /** Only absolute addresses of the accepted schemes that are valid URLs count as web links. */
    @Test
    fun webLinks() {
        for (address in listOf("https://example.com", "HTTP://example.com:8080/a", "mailto:a@b.c", "https://[::1]/a", "https://example.com/%20")) {
            assertTrue(address, isWebLink(address))
        }
        for (address in listOf("example.com", "ftp://a.b", "javascript:alert(1)", "https://", "https://a b", "https://a.b/{x}", "https://a.b/%zz", "https://a:b", "mailto:")) {
            assertFalse(address, isWebLink(address))
        }
    }
}
