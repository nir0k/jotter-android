package io.github.nir0k.jotter.core.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pasted Markdown reads into the note model as on the desktop: the cases are the desktop's
 * (tests/tst_editor.cpp), and the body is written in the desktop's form.
 */
class MarkdownTest {
    /** Reads Markdown and writes the body, failing when the text is not taken for Markdown. */
    private fun body(text: String, requireExplicitSyntax: Boolean = false): String {
        val doc = readMarkdown(text, requireExplicitSyntax)
        assertNotNull(text, doc)
        return serializeBody(doc!!)
    }

    /** Asserts that Markdown writes a body, and that the body reads and writes back unchanged. */
    private fun assertBody(text: String, expected: String) {
        assertEquals(text, expected, body(text))
        assertEquals(text, expected, serializeBody(parseBody(expected)))
    }

    /** Emphasis, strikethrough and inline code. */
    @Test
    fun inline() = assertBody(
        "a **bold** *italic* ~~gone~~ `code` z",
        "<p>a <strong>bold</strong> <em>italic</em> <s>gone</s> <code>code</code> z</p>",
    )

    /** Headings deeper than three become level three. */
    @Test
    fun headings() = assertBody("# One\n\n###### Six", "<h1>One</h1>\n<h3>Six</h3>")

    /** A fence keeps its code's indentation and its language. */
    @Test
    fun fence() {
        assertBody(
            "before\n\n```go\nx\n    y\n```\n\nafter",
            "<p>before</p>\n<pre data-language=\"go\"><code>x\n    y</code></pre>\n<p>after</p>",
        )
        assertBody("```\n```", "<pre><code></code></pre>")
        assertBody("```\n\nx\n\n```", "<pre><code>\nx\n</code></pre>")
    }

    /** A fence's language goes through the language lookup in lower case; its other words are dropped. */
    @Test
    fun fenceLanguage() {
        val doc = readMarkdown("```KT title\nx\n```", language = { if (it == "kt") "kotlin" else it })
        assertEquals("<pre data-language=\"kotlin\"><code>x</code></pre>", serializeBody(doc!!))
    }

    /** Copied text indents lists by a tab or four spaces; only fences make code. */
    @Test
    fun indentationMakesNoCode() {
        assertBody("text\n\n    - x\n    - y", "<p>text</p>\n<ul>\n<li>x</li>\n<li>y</li>\n</ul>")
        assertBody("text\n\n\t1. x\n\t2. y", "<p>text</p>\n<ol>\n<li>x</li>\n<li>y</li>\n</ol>")
        assertBody("**text**\n\n    x\n    y", "<p><strong>text</strong></p>\n<p>x<br>y</p>")
        // Deep indentation inside a list item is its text too.
        assertBody("- one\n\n        more", "<ul>\n<li>one<br>more</li>\n</ul>")
        assertBody("**a**\n\n        ```go\n        x\n        ```", "<p><strong>a</strong></p>\n<pre data-language=\"go\"><code>x</code></pre>")
        // Indented lines right after a paragraph start blocks as well; indented text continues it.
        assertBody("text\n    - x\n    - y", "<p>text</p>\n<ul>\n<li>x</li>\n<li>y</li>\n</ul>")
        assertBody("text\n\t# Head", "<p>text</p>\n<h1>Head</h1>")
        assertBody("**a**\n        more", "<p><strong>a</strong><br>more</p>")
        assertBody("- a\n      - b", "<ul>\n<li>a\n<ul>\n<li>b</li>\n</ul>\n</li>\n</ul>")
    }

    /** A bulleted list whose first item is a task is a checklist. */
    @Test
    fun tasks() = assertBody(
        "- [x] done\n- [ ] todo",
        "<ul class=\"checklist\">\n<li data-checked=\"true\">done</li>\n<li data-checked=\"false\">todo</li>\n</ul>",
    )

    /** Bulleted and numbered lists, a numbered one keeping its start. */
    @Test
    fun lists() = assertBody(
        "- one\n- two\n\n3. three\n4. four",
        "<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n<ol start=\"3\">\n<li>three</li>\n<li>four</li>\n</ol>",
    )

    /** A nested list stays nested. */
    @Test
    fun nestedList() = assertBody("- a\n    - b\n- c", "<ul>\n<li>a\n<ul>\n<li>b</li>\n</ul>\n</li>\n<li>c</li>\n</ul>")

    /** A quote, and a link. */
    @Test
    fun quoteAndLink() {
        assertBody("> quoted", "<blockquote>\n<p>quoted</p>\n</blockquote>")
        assertBody("[site](https://example.com)", "<p><a href=\"https://example.com\">site</a></p>")
    }

    /** Embedded HTML is text. */
    @Test
    fun literalHtml() {
        assertNull(readMarkdown("<b>literal</b>"))
        assertBody("**a** <b>literal</b>", "<p><strong>a</strong> &lt;b&gt;literal&lt;/b&gt;</p>")
        assertBody("**a**\n\n<div>\nx\n</div>", "<p><strong>a</strong></p>\n<p>&lt;div&gt;<br>x<br>&lt;/div&gt;</p>")
    }

    /** A picture on the web becomes a link to it, named by its description or its address. */
    @Test
    fun remoteImages() {
        assertBody(
            "![description](https://example.com/a.png) **end**",
            "<p><a href=\"https://example.com/a.png\">description</a> <strong>end</strong></p>",
        )
        assertBody(
            "![](https://example.com/a.png) **end**",
            "<p><a href=\"https://example.com/a.png\">https://example.com/a.png</a> <strong>end</strong></p>",
        )
        assertBody(
            "[![description](https://example.com/a.png)](https://example.org) **end**",
            "<p><a href=\"https://example.org\">description</a> <strong>end</strong></p>",
        )
    }

    /** A picture in a `data:` address waits to be stored, and one of a local file is left out. */
    @Test
    fun embeddedImages() {
        val block = readMarkdown("![a](data:image/png;base64,AA==) ![b](file:///a.png)")!!.items.single() as Block
        assertEquals(listOf("data:image/png;base64,AA=="), block.inline.filterIsInstance<ImageObject>().map { it.pending })
    }

    /** A table, with escaped pipes in its cells. */
    @Test
    fun tables() {
        assertBody(
            "| A | B |\n|---|---|\n|x|y|",
            "<table>\n<thead>\n<tr><th><p>A</p></th><th><p>B</p></th></tr>\n</thead>\n<tbody>\n<tr><td><p>x</p></td><td><p>y</p></td></tr>\n</tbody>\n</table>",
        )
        assertBody(
            "| `a\\|b` |\n|---|\n| c\\|d |",
            "<table>\n<thead>\n<tr><th><p><code>a|b</code></p></th></tr>\n</thead>\n<tbody>\n<tr><td><p>c|d</p></td></tr>\n</tbody>\n</table>",
        )
    }

    /** Inline code keeps the formatting around it. */
    @Test
    fun formattedCode() = assertBody(
        "**`bold`** *`italic`* ~~`gone`~~ [`linked`](https://example.com)",
        "<p><strong><code>bold</code></strong> <em><code>italic</code></em> " +
            "<s><code>gone</code></s> <a href=\"https://example.com\"><code>linked</code></a></p>",
    )

    /** Every line break stays, and a list item's later paragraphs join it after a line break. */
    @Test
    fun lineBreaks() {
        assertBody("one\\\ntwo  \nthree\n\n- a\\\n  b", "<p>one<br>two<br>three</p>\n<ul>\n<li>a<br>b</li>\n</ul>")
        assertBody("- one\n\n  more\n- two", "<ul>\n<li>one<br>more</li>\n<li>two</li>\n</ul>")
    }

    /** Entities are decoded. */
    @Test
    fun entities() = assertBody("**&copy; &#8212;**", "<p><strong>© —</strong></p>")

    /** A line of `-` or `=` under text is a divider below it, not a heading underline. */
    @Test
    fun plainDividers() {
        assertBody(
            "day one\n-----\nfirst\nsecond\n\n-----\nempty\n=====\n# Head\n---\n> *quote*\n> ---",
            "<p>day one</p>\n<hr>\n<p>first<br>second</p>\n<hr>\n<p>empty</p>\n<hr>\n" +
                "<h1>Head</h1>\n<hr>\n<blockquote>\n<p><em>quote</em></p>\n</blockquote>\n<hr>",
        )
        assertBody("---\n\n- x", "<hr>\n<ul>\n<li>x</li>\n</ul>")
        // A `#` that opens no heading is text of an underlined one.
        assertBody("#tag\n---", "<p>#tag</p>\n<hr>")
    }

    /** Text without Markdown formatting is not Markdown, and goes in as typed. */
    @Test
    fun plainText() {
        assertNull(readMarkdown(" one  two\nthree\n\n"))
        assertNull(readMarkdown("snake_case and 2 * 3"))
    }

    /**
     * A bare web address alone makes Markdown of plain text, but not when HTML comes with it;
     * explicit links and addresses in angle brackets do.
     */
    @Test
    fun bareAddresses() {
        assertEquals("<p>see <a href=\"https://example.com\">https://example.com</a></p>", body("see https://example.com"))
        assertNull(readMarkdown("rich https://example.com\nsecond line", requireExplicitSyntax = true))
        assertNull(readMarkdown("[literal] https://example.com", requireExplicitSyntax = true))
        assertNull(readMarkdown("rich text", requireExplicitSyntax = true))
        assertEquals("<p><a href=\"https://example.com\">site</a></p>", body("[site](https://example.com)", true))
        assertEquals("<p><a href=\"https://example.com\">https://example.com</a></p>", body("<https://example.com>", true))
        assertEquals("<ul>\n<li>first</li>\n<li>second</li>\n</ul>", body("- first\n- second", true))
    }

    /** Links keep only web and mail addresses; others become text. */
    @Test
    fun linksOfOtherSchemes() = assertBody("**a** [x](javascript:alert(1))", "<p><strong>a</strong> x</p>")

    /** A relative link or picture, without a scheme, is not taken for a domain: its text stays. */
    @Test
    fun relativeLinks() {
        assertBody("**a** [x](page.html) [y](example.com)", "<p><strong>a</strong> x y</p>")
        assertBody("![picture](docs/a.png) **end**", "<p>picture <strong>end</strong></p>")
        assertBody("[mail](mailto:a@example.com) www.example.com", "<p><a href=\"mailto:a@example.com\">mail</a> <a href=\"http://www.example.com\">www.example.com</a></p>")
        assertBody("<a@example.com>", "<p><a href=\"mailto:a@example.com\">a@example.com</a></p>")
    }
}
