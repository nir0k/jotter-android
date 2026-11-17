package io.github.nir0k.jotter.core.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The highlighter interprets the bundled KDE SyntaxHighlighting definitions: every language the
 * picker offers loads and highlights, and tokens get the theme's styles.
 */
class HighlighterTest {
    private val light = CodeTheme.named("breeze-light")

    /** Returns the style of the run that covers an offset, or null for plain code. */
    private fun styleAt(text: String, language: String, offset: Int): TokenStyle? =
        CodeHighlighter.highlight(text, language, light).firstOrNull { offset >= it.start && offset < it.end }?.style

    /** Every offered language but R (not bundled) is bundled and highlights a sample without failing. */
    @Test
    fun everyLanguageLoads() {
        val languages = listOf(
            "bash", "ms-dos batch", "c", "c#", "c++", "clojure", "cmake", "css", "csv", "dart", "diff", "dockerfile", "elixir",
            "erlang", "go", "graphql", "haskell", "html", "java", "javascript", "javascript react (jsx)", "json", "julia", "kotlin",
            "lua", "makefile", "markdown", "nginx configuration", "objective-c", "perl", "php/php", "powershell", "protobuf",
            "python", "qml", "ruby", "rust", "scala", "sql", "swift", "systemd unit", "toml", "typescript",
            "typescript react (tsx)", "xml", "yaml", "zig", "zsh", "vue",
        )
        val sample = "// comment\n\"string\" 'c' 123 0x1F 1.5e3\nif (a) { return b; }\n<tag attr=\"v\">text</tag>\n# heading\n"
        for (language in languages) {
            assertNotNull(language, SyntaxRepository.forLanguage(language))
            CodeHighlighter.highlight(sample, language, light)
        }
        assertTrue(CodeHighlighter.highlight(sample, "r script", light).isEmpty())
    }

    /** The project's own Go and JSON definitions tell keys from values and keywords, strings and comments apart. */
    @Test
    fun ownDefinitions() {
        val json = "{\"key\": \"value\", \"n\": 1.5, \"ok\": true}"
        val key = styleAt(json, "json", json.indexOf("key"))
        val value = styleAt(json, "json", json.indexOf("value"))
        assertNotNull(key)
        assertEquals(0xbf0303, value?.color)
        assertTrue(key != value)
        assertNotNull(styleAt(json, "json", json.indexOf("true")))

        val go = "func main() {\n\tfmt.Printf(\"n=%d\\n\", 42) // done\n}"
        assertEquals(true, styleAt(go, "go", 0)?.bold)
        assertEquals(0xbf0303, styleAt(go, "go", go.indexOf("Printf(") + 8)?.color)
        assertEquals(0x898887, styleAt(go, "go", go.indexOf("done"))?.color)
        assertTrue(styleAt(go, "go", go.indexOf("%d")) != styleAt(go, "go", go.indexOf("Printf(") + 8))
    }

    /** Keywords, strings and comments get the styles of Breeze Light. */
    @Test
    fun stylesOfTokens() {
        val code = "fun main() {\n    val s = \"hi\" // note\n}"
        assertEquals(true, styleAt(code, "kotlin", 0)?.bold)
        assertEquals(0xbf0303, styleAt(code, "kotlin", code.indexOf("\"hi\"") + 1)?.color)
        assertEquals(0x898887, styleAt(code, "kotlin", code.indexOf("note"))?.color)
    }

    /** A context open at the end of a line goes on in the next: a block comment spans lines. */
    @Test
    fun stateCarriesOver() {
        val code = "/* first\nsecond */ int x;"
        val comment = styleAt(code, "c", code.indexOf("second"))
        assertEquals(0x898887, comment?.color)
        assertTrue(styleAt(code, "c", code.indexOf("x;")) == null || styleAt(code, "c", code.indexOf("x;"))?.color != 0x898887)
    }

    /** Embedded languages switch definitions: CSS inside HTML is highlighted as CSS. */
    @Test
    fun embeddedLanguages() {
        val code = "<style>\nbody { color: red; }\n</style>"
        assertNotNull(styleAt(code, "html", code.indexOf("color")))
    }
}
