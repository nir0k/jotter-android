package io.github.nir0k.jotter.core.highlight

import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The application runs its regular expressions on Android's ICU engine, which refuses some forms
 * the JVM these tests run on accepts. These checks keep the known differences out: the
 * `UNICODE_CHARACTER_CLASS` flag, which ICU refuses, and braces that do not delimit a repetition,
 * which ICU reads as a syntax error.
 */
class AndroidExpressionsTest {

    /**
     * Returns the braces of an expression that do not delimit a repetition such as `{2}` or
     * `{1,3}`, outside character classes and escapes such as `\p{L}`, which ICU refuses; empty when
     * there are none.
     */
    private fun strayBraces(expression: String): List<Int> {
        val stray = mutableListOf<Int>()
        var i = 0
        var inClass = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c == '\\' -> {
                    i++
                    // Escapes such as \p{L} or \x{41} take their braces with them.
                    if (expression.getOrNull(i) in setOf('p', 'P', 'x', 'N', 'k', 'g') && expression.getOrNull(i + 1) == '{') {
                        i = expression.indexOf('}', i).takeIf { it >= 0 } ?: expression.length
                    }
                }
                inClass > 0 && c == '[' -> inClass++
                inClass > 0 && c == ']' -> inClass--
                inClass > 0 -> {}
                c == '[' -> {
                    inClass = 1
                    // A `]` right after the opening bracket, or after its `^`, is a literal.
                    if (expression.getOrNull(i + 1) == '^') i++
                    if (expression.getOrNull(i + 1) == ']') i++
                }
                c == '{' -> {
                    val repetition = Regex("\\{[0-9]+(,[0-9]*)?\\}").matchAt(expression, i)
                    if (repetition == null) stray += i else i += repetition.value.length - 1
                }
                c == '}' -> stray += i
            }
            i++
        }
        return stray
    }

    /** Checks one expression as Android would compile it. */
    private fun assertSuitsAndroid(expression: String, where: String) {
        assertTrue("$where: braces ICU refuses at ${strayBraces(expression)} in $expression", strayBraces(expression).isEmpty())
        Pattern.compile(expression)
    }

    /** Every expression of the bundled definitions, as the highlighter rewrites them, suits Android. */
    @Test
    fun definitionExpressions() {
        val index = javaClass.getResourceAsStream("/syntax/index.tsv")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        var checked = 0
        for (name in index.lines().filter { '\t' in it }.map { it.substringBefore('\t') }) {
            val definition = SyntaxRepository.definition(name) ?: continue
            for (rule in definition.contexts.values.flatMap { it.rules }) {
                if (rule.type != "RegExpr" || rule.dynamic) continue
                val (expression, _) = CodeHighlighter.javaDialect(rule.string, rule.minimal)
                // An expression the JVM cannot compile either is left out by the highlighter, as on Android.
                if (runCatching { Pattern.compile(expression) }.isFailure) continue
                assertSuitsAndroid(expression, "${definition.name}: ${rule.string}")
                checked++
            }
        }
        assertTrue("expressions checked: $checked", checked > 1000)
    }

    /** The expression literals of core's own code suit Android, such as the highlighter's repetition. */
    @Test
    fun codeExpressions() {
        val literal = Regex("(?:Regex|Pattern\\.compile)\\(\\s*(\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")")
        var checked = 0
        File("src/main/kotlin").walk().filter { it.extension == "kt" }.forEach { file ->
            for (match in literal.findAll(file.readText())) {
                val raw = match.groups[2]?.value
                val expression = raw ?: Regex("\\\\(.)").replace(match.groups[3]!!.value) { escape ->
                    when (val c = escape.groupValues[1]) { "n" -> "\n"; "t" -> "\t"; else -> c }
                }
                assertSuitsAndroid(expression, file.name)
                checked++
            }
        }
        assertTrue("expressions checked: $checked", checked > 10)
        // The flag only goes through the guarded value the highlighter falls back from.
        val uses = File("src/main/kotlin").walk().filter { it.extension == "kt" }.sumOf { Regex("Pattern\\.UNICODE_CHARACTER_CLASS").findAll(it.readText()).count() }
        assertTrue("UNICODE_CHARACTER_CLASS used outside its guarded value", uses == 2)
    }
}
