package io.github.nir0k.jotter.core.highlight

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

// The highlighting engine: a port of KDE SyntaxHighlighting's AbstractHighlighter. Code is read
// line by line; each line starts in the contexts the line before left open, the rules of the
// context on top are tried at each offset in their order, a match colours its text with its
// attribute and switches contexts, and a line end switches as the context says. Lines already seen
// in the same state are not read again.

/** A frame of the context stack: a context and the captures of the expression that entered it. */
private class Frame(val context: Context, val captures: List<String>) {
    override fun equals(other: Any?): Boolean = other is Frame && other.context === context && other.captures == captures
    override fun hashCode(): Int = System.identityHashCode(context) * 31 + captures.hashCode()
}

/** The contexts open at the end of a line, which the next line starts in. */
private data class LineState(val stack: List<Frame>)

/** A run of a line in one attribute. */
private class LineSpan(val start: Int, val end: Int, val item: ItemData)

/** What reading a line gave: its runs and the state the next line starts in. */
private class LineResult(val spans: List<LineSpan>, val end: LineState)

/**
 * A run of highlighted code.
 *
 * @param start The first character.
 * @param end The end, exclusive.
 */
class HighlightSpan(val start: Int, val end: Int, val style: TokenStyle)

/** The bundled syntax definitions, read when first used. */
object SyntaxRepository {
    private val files: Map<String, String> by lazy {
        val text = javaClass.getResourceAsStream("/syntax/index.tsv")?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        text.lines().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
    }
    private val byKey: Map<String, String> by lazy { files.keys.associateBy { it.lowercase() } }
    private val loaded = ConcurrentHashMap<String, Definition>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Returns the definition of a stored language, reading it when first asked; null for an unknown language. */
    fun forLanguage(language: String): Definition? = byKey[language.lowercase()]?.let(::definition)

    /** Returns a definition by its name, as definitions name each other; null when it is not bundled. */
    fun definition(name: String): Definition? {
        loaded[name]?.let { return it }
        if (name in missing) return null
        val file = files[name]
        val definition = file?.let { path ->
            runCatching { javaClass.getResourceAsStream("/syntax/$path")?.use(::readDefinition) }.getOrNull()
        }
        if (definition == null) {
            missing += name
            return null
        }
        return loaded.putIfAbsent(name, definition) ?: definition
    }
}

/** Highlights code in a stored language with the styles of a theme. */
object CodeHighlighter {
    private const val LineCacheSize = 4000
    private const val TextCacheSize = 32

    /**
     * `UNICODE_CHARACTER_CLASS` where the platform takes it: the JVM needs it for `\w`, `\d`, `\s`
     * and `\b` to follow Unicode as Qt's do, while Android's ICU expressions follow Unicode already
     * and refuse the flag.
     */
    private val unicodeClasses = try {
        Pattern.compile("", Pattern.UNICODE_CHARACTER_CLASS)
        Pattern.UNICODE_CHARACTER_CLASS
    } catch (_: IllegalArgumentException) {
        0
    }
    private val lineCache = object : LinkedHashMap<Pair<LineState, String>, LineResult>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<LineState, String>, LineResult>?) = size > LineCacheSize
    }
    private val textCache = object : LinkedHashMap<Triple<String, String, CodeTheme>, List<HighlightSpan>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, CodeTheme>, List<HighlightSpan>>?) = size > TextCacheSize
    }

    /** Reads a language's definitions ahead of use, off the main thread. */
    fun prepare(language: String) {
        val definition = SyntaxRepository.forLanguage(language) ?: return
        synchronized(this) { highlightLine(definition, "", LineState(listOf(Frame(definition.initial.also { resolve(it) }, emptyList())))) }
    }

    /** Tells whether a language's definition is read, so highlighting it is quick. */
    fun isReady(language: String): Boolean = SyntaxRepository.forLanguage(language) != null

    /**
     * Highlights code.
     *
     * @param language The stored language; an unknown one gives no runs, and the code shows as plain text.
     * @return The runs whose style differs from plain code, in order.
     */
    fun highlight(text: String, language: String, theme: CodeTheme): List<HighlightSpan> {
        if (text.isEmpty() || language.isEmpty()) return emptyList()
        val definition = SyntaxRepository.forLanguage(language) ?: return emptyList()
        synchronized(this) {
            val key = Triple(language, text, theme)
            textCache[key]?.let { return it }
            val out = ArrayList<HighlightSpan>()
            var state = LineState(listOf(Frame(definition.initial, emptyList())))
            var lineStart = 0
            for (line in text.split('\n')) {
                val result = lineCache.getOrPut(state to line) { highlightLine(definition, line, state) }
                for (span in result.spans) {
                    val style = theme.styleOf(span.item)
                    if (!style.isPlain) out += HighlightSpan(lineStart + span.start, lineStart + span.end, style)
                }
                state = result.end
                lineStart += line.length + 1
            }
            textCache[key] = out
            return out
        }
    }

    /** Puts the included rules of a context in place, once; a cycle of includes stops at the context already being resolved. */
    private fun resolve(context: Context) {
        if (context.resolved) return
        context.resolved = true
        var inserted = 0
        for ((position, reference, includeAttribute) in context.includes) {
            val included = target(context.definition, ContextSwitch.parse(reference)) ?: continue
            resolve(included)
            context.rules.addAll(position + inserted, included.rules)
            inserted += included.rules.size
            if (includeAttribute) context.attribute = included.attributeItem()?.let { "${it.definition}\u0000${it.name}" }
        }
    }

    /** Returns the context a switch enters, relative to the definition it is written in; null for one not bundled. */
    private fun target(from: Definition, switch: ContextSwitch): Context? {
        val definition = switch.language?.let { SyntaxRepository.definition(it) ?: return null } ?: from
        val context = switch.target?.let { definition.contexts[it] } ?: if (switch.target == null) definition.initial else null
        return context?.also { resolve(it) }
    }

    /** Returns a context's attribute, which may name an attribute of another definition after `includeAttrib`. */
    private fun Context.attributeItem(): ItemData? {
        val name = attribute ?: return null
        val split = name.indexOf('\u0000')
        if (split < 0) return definition.itemData[name]
        return SyntaxRepository.definition(name.substring(0, split))?.itemData?.get(name.substring(split + 1))
    }

    /** Switches contexts; returns whether the stack changed. */
    private fun switchContext(stack: MutableList<Frame>, from: Definition, switch: ContextSwitch, captures: List<String>): Boolean {
        val before = stack.size
        var changed = false
        repeat(switch.pops) {
            if (stack.size > 1) {
                stack.removeAt(stack.lastIndex)
                changed = true
            }
        }
        if (switch.target != null || switch.language != null) {
            val context = target(from, switch) ?: return changed || stack.size != before
            stack += Frame(context, if (context.dynamic) captures else emptyList())
            changed = true
        }
        return changed
    }

    /** Reads one line in a state. */
    private fun highlightLine(definition: Definition, text: String, start: LineState): LineResult {
        val stack = start.stack.toMutableList()
        stack.forEach { resolve(it.context) }
        val spans = ArrayList<LineSpan>()
        if (text.isEmpty()) {
            var guard = 0
            while (!stack.last().context.lineEmpty.isStay && guard++ < MaxSwitches) {
                if (!switchContext(stack, stack.last().context.definition, stack.last().context.lineEmpty, emptyList())) break
            }
            endLine(stack)
            return LineResult(spans, LineState(stack.toList()))
        }
        val firstNonSpace = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) text.length else it }
        var offset = 0
        var runStart = 0
        var runItem: ItemData? = null
        var lineContinuation = false
        var stuck = 0
        var lastOffset = -1
        while (offset < text.length) {
            if (offset == lastOffset) stuck++ else stuck = 0
            lastOffset = offset
            val frame = stack.last()
            val context = frame.context
            var newOffset = offset
            var newItem: ItemData? = null
            var lookAhead = false
            if (stuck < MaxSwitches) {
                for (rule in context.rules) {
                    if (rule.firstNonSpace && offset > firstNonSpace) continue
                    if (rule.column >= 0 && rule.column != offset) continue
                    val (end, captures) = match(rule, text, offset, frame.captures)
                    if (end <= offset) continue
                    if (rule.lookAhead) {
                        if (!rule.context.isStay) {
                            switchContext(stack, rule.definition, rule.context, captures)
                            lookAhead = true
                        }
                        break
                    }
                    switchContext(stack, rule.definition, rule.context, captures)
                    newOffset = end
                    newItem = rule.attribute?.let { rule.definition.itemData[it] } ?: stack.last().context.attributeItem()
                    if (end == text.length && rule.type == "LineContinue") lineContinuation = true
                    break
                }
            }
            if (lookAhead) continue
            if (newOffset <= offset) {
                val fallthrough = context.fallthrough
                if (fallthrough != null && stuck < MaxSwitches) {
                    switchContext(stack, context.definition, fallthrough, emptyList())
                    continue
                }
                newOffset = offset + if (Character.isHighSurrogate(text[offset]) && offset + 1 < text.length) 2 else 1
                newItem = context.attributeItem()
            }
            if (newItem !== runItem) {
                runItem?.let { if (offset > runStart) spans += LineSpan(runStart, offset, it) }
                runStart = offset
                runItem = newItem
            }
            offset = newOffset
        }
        runItem?.let { if (text.length > runStart) spans += LineSpan(runStart, text.length, it) }
        if (!lineContinuation) endLine(stack)
        return LineResult(spans, LineState(stack.toList()))
    }

    /** Switches contexts at the end of a line as the contexts on top say. */
    private fun endLine(stack: MutableList<Frame>) {
        var guard = 0
        while (!stack.last().context.lineEnd.isStay && guard++ < MaxSwitches) {
            val context = stack.last().context
            if (!switchContext(stack, context.definition, context.lineEnd, emptyList())) break
        }
    }

    /** The most context switches at one offset before the engine moves on, against definitions that loop. */
    private const val MaxSwitches = 64

    private val NoMatch = Pair(-1, emptyList<String>())

    /** Tries a rule at an offset: the offset after its match (not past [offset] when it does not match) and its captures. */
    private fun match(rule: Rule, text: String, offset: Int, captures: List<String>): Pair<Int, List<String>> {
        val c = text[offset]
        fun delimiter(at: Int) = text[at] in rule.delimiters
        val end = when (rule.type) {
            "DetectChar" -> {
                val expected = if (rule.dynamic) captures.getOrNull(rule.char0.digitToIntOrNull() ?: -1)?.firstOrNull() else rule.char0
                if (expected != null && c == expected) offset + 1 else offset
            }
            "Detect2Chars" -> if (c == rule.char0 && offset + 1 < text.length && text[offset + 1] == rule.char1) offset + 2 else offset
            "AnyChar" -> if (c in rule.string) offset + 1 else offset
            "StringDetect" -> {
                val string = if (rule.dynamic) substitute(rule.string, captures, regex = false) else rule.string
                if (string.isNotEmpty() && text.regionMatches(offset, string, 0, string.length, rule.insensitive)) offset + string.length else offset
            }
            "WordDetect" -> {
                val word = rule.string
                when {
                    word.isEmpty() || text.length - offset < word.length -> offset
                    offset > 0 && !delimiter(offset - 1) && !delimiter(offset) -> offset
                    !text.regionMatches(offset, word, 0, word.length, rule.insensitive) -> offset
                    offset + word.length == text.length || delimiter(offset + word.length) || delimiter(offset + word.length - 1) -> offset + word.length
                    else -> offset
                }
            }
            "keyword" -> {
                if (offset > 0 && !delimiter(offset - 1)) return NoMatch
                var wordEnd = offset
                while (wordEnd < text.length && !delimiter(wordEnd)) wordEnd++
                if (wordEnd == offset) return NoMatch
                val word = text.substring(offset, wordEnd)
                val sensitive = !rule.insensitive && rule.definition.caseSensitive
                if (Keywords.contains(rule.definition, rule.string, word, sensitive)) wordEnd else offset
            }
            "RegExpr" -> return matchExpression(rule, text, offset, captures)
            "DetectSpaces" -> {
                var i = offset
                while (i < text.length && text[i].isWhitespace()) i++
                i
            }
            "DetectIdentifier" -> {
                if (!c.isLetter() && c != '_') return NoMatch
                var i = offset + 1
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                i
            }
            "LineContinue" -> if (offset == text.length - 1 && c == rule.char0) offset + 1 else offset
            "Int" -> {
                if (offset > 0 && !delimiter(offset - 1)) return NoMatch
                var i = offset
                while (i < text.length && text[i].isDigit()) i++
                i
            }
            "Float" -> matchFloat(text, offset, rule)
            "HlCOct" -> {
                if (offset > 0 && !delimiter(offset - 1)) return NoMatch
                if (text.length < offset + 2 || c != '0') return NoMatch
                var i = offset + 1
                while (i < text.length && text[i] in '0'..'7') i++
                if (i == offset + 1) offset else if (i < text.length && text[i] in "LlUu") i + 1 else i
            }
            "HlCHex" -> {
                if (offset > 0 && !delimiter(offset - 1)) return NoMatch
                if (text.length < offset + 3 || c != '0' || (text[offset + 1] != 'x' && text[offset + 1] != 'X') || !text[offset + 2].isHex()) return NoMatch
                var i = offset + 3
                while (i < text.length && text[i].isHex()) i++
                if (i < text.length && text[i] in "LlUu") i + 1 else i
            }
            "HlCStringChar" -> escapedChar(text, offset)
            "HlCChar" -> {
                if (text.length < offset + 3 || c != '\'' || text[offset + 1] == '\'') return NoMatch
                var i = escapedChar(text, offset + 1)
                if (i == offset + 1) {
                    if (text[i] == '\\') return NoMatch
                    i++
                }
                if (i < text.length && text[i] == '\'') i + 1 else offset
            }
            "RangeDetect" -> {
                if (text.length - offset < 2 || c != rule.char0) return NoMatch
                val close = text.indexOf(rule.char1, offset + 1)
                if (close < 0) offset else close + 1
            }
            else -> offset
        }
        return Pair(end, captures)
    }

    /** Matches an escape sequence of C at an offset, as KDE SyntaxHighlighting's `matchEscapedChar`. */
    private fun escapedChar(text: String, offset: Int): Int {
        if (text[offset] != '\\' || text.length < offset + 2) return offset
        return when (text[offset + 1]) {
            'a', 'b', 'e', 'f', 'n', 'r', 't', 'v', '"', '\'', '?', '\\' -> offset + 2
            'x' -> if (offset + 2 < text.length && text[offset + 2].isHex()) {
                if (offset + 3 < text.length && text[offset + 3].isHex()) offset + 4 else offset + 3
            } else {
                offset
            }
            in '0'..'7' -> if (offset + 2 < text.length && text[offset + 2] in '0'..'7') {
                if (offset + 3 < text.length && text[offset + 3] in '0'..'7') offset + 4 else offset + 3
            } else {
                offset + 2
            }
            else -> offset
        }
    }

    /** Matches a floating point number, as KDE SyntaxHighlighting's `Float` rule. */
    private fun matchFloat(text: String, offset: Int, rule: Rule): Int {
        if (offset > 0 && text[offset - 1] !in rule.delimiters) return offset
        var i = offset
        while (i < text.length && text[i].isDigit()) i++
        if (i >= text.length || text[i] != '.') return offset
        i++
        while (i < text.length && text[i].isDigit()) i++
        if (i == offset + 1) return offset
        var exponent = i
        if (exponent >= text.length || (text[exponent] != 'e' && text[exponent] != 'E')) return i
        exponent++
        if (exponent < text.length && (text[exponent] == '+' || text[exponent] == '-')) exponent++
        var digits = false
        while (exponent < text.length && text[exponent].isDigit()) {
            exponent++
            digits = true
        }
        return if (digits) exponent else i
    }

    /** Matches a regular expression rule at an offset; text before the offset is seen by lookbehinds. */
    private fun matchExpression(rule: Rule, text: String, offset: Int, captures: List<String>): Pair<Int, List<String>> {
        val pattern = if (rule.dynamic) {
            compile(substitute(rule.string, captures, regex = true), rule.insensitive, rule.minimal)
        } else {
            if (!rule.compiled) {
                rule.pattern = compile(rule.string, rule.insensitive, rule.minimal)
                rule.compiled = true
            }
            rule.pattern
        } ?: return NoMatch
        val matcher = pattern.matcher(text)
        matcher.region(offset, text.length)
        matcher.useTransparentBounds(true)
        matcher.useAnchoringBounds(false)
        if (!matcher.lookingAt()) return NoMatch
        val groups = (0..matcher.groupCount()).map { matcher.group(it).orEmpty() }
        return Pair(matcher.end(), captures(pattern, groups))
    }

    /** Replaces `%1` to `%9` with the captures of the expression that entered the context, escaped for an expression when asked. */
    private fun substitute(text: String, captures: List<String>, regex: Boolean): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val digit = if (text[i] == '%' && i + 1 < text.length) text[i + 1].digitToIntOrNull() else null
            if (digit != null) {
                val capture = captures.getOrNull(digit).orEmpty()
                out.append(if (regex) Regex.escape(capture) else capture)
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private val compiled = ConcurrentHashMap<Triple<String, Boolean, Boolean>, Pattern>()

    /** The compiled expressions that had branch reset groups, whose alternatives all capture into group 1. */
    private val branchReset = ConcurrentHashMap.newKeySet<Pattern>()

    /** Compiles an expression of a definition into Java's dialect, with Unicode classes as Qt's use them; null when it cannot be. */
    private fun compile(source: String, insensitive: Boolean, minimal: Boolean): Pattern? {
        val key = Triple(source, insensitive, minimal)
        compiled[key]?.let { return it }
        val flags = unicodeClasses or (if (insensitive) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
        val (converted, reset) = javaDialect(source, minimal)
        val pattern = try {
            Pattern.compile(converted, flags)
        } catch (_: PatternSyntaxException) {
            // Recursion, which Java's expressions lack, leaves a few rules out; their text stays plain.
            return null
        }
        if (reset) branchReset += pattern
        compiled[key] = pattern
        return pattern
    }

    /** Returns the captures of a match: with branch reset groups, the first alternative that captured is group 1. */
    private fun captures(pattern: Pattern, groups: List<String>): List<String> =
        if (pattern in branchReset && groups.size > 1) listOf(groups[0], groups.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()) else groups

    private val PosixClasses = mapOf(
        "alpha" to "\\p{IsAlphabetic}", "digit" to "\\p{IsDigit}", "alnum" to "\\p{IsAlphabetic}\\p{IsDigit}",
        "space" to "\\s", "upper" to "\\p{IsUppercase}", "lower" to "\\p{IsLowercase}", "punct" to "\\p{IsPunctuation}",
        "xdigit" to "0-9A-Fa-f", "word" to "\\w", "blank" to " \\t", "cntrl" to "\\p{Cntrl}", "graph" to "\\p{Graph}", "print" to "\\p{Print}",
    )
    private val NegatedPosixClasses = mapOf(
        "alpha" to "\\P{IsAlphabetic}", "digit" to "\\P{IsDigit}", "space" to "\\S", "word" to "\\W", "alnum" to "\\W", "upper" to "\\P{IsUppercase}",
        "lower" to "\\P{IsLowercase}", "punct" to "\\P{IsPunctuation}",
    )
    // The closing brace is escaped: Android's ICU expressions refuse a bare one, which the JVM accepts.
    private val Repetition = Regex("\\{([0-9]*)(,([0-9]*))?\\}")

    /**
     * Rewrites an expression of Qt's dialect (PCRE2) in Java's: brackets and braces that PCRE reads
     * as literals are escaped, POSIX classes and Python-style named groups are spelled as Java
     * spells them, branch reset groups become plain groups, and for `minimal` rules greedy
     * quantifiers become lazy and lazy ones greedy, as Qt's InvertedGreedinessOption does.
     *
     * @return The expression, and whether it had branch reset groups.
     */
    internal fun javaDialect(source: String, minimal: Boolean): Pair<String, Boolean> {
        val out = StringBuilder(source.length + 8)
        var reset = false
        var i = 0
        var inClass = false
        var classStart = false
        /** Appends the greediness marker of a quantifier just written, inverted for minimal rules. */
        fun quantifierSuffix() {
            when (source.getOrNull(i + 1)) {
                '?' -> {
                    if (!minimal) out.append('?')
                    i++
                }
                '+' -> {
                    out.append('+')
                    i++
                }
                else -> if (minimal) out.append('?')
            }
        }
        while (i < source.length) {
            val c = source[i]
            if (c == '\\' && i + 1 < source.length) {
                if (inClass && source[i + 1] == 'b') {
                    // A backspace in a class, as PCRE reads it; Java's `\b` there is an error.
                    out.append("\\x08")
                    i += 2
                    classStart = false
                    continue
                }
                if (source[i + 1].isLetter() && source.getOrNull(i + 2) == '{') {
                    val close = source.indexOf('}', i + 3)
                    if (close > 0) {
                        out.append(source, i, close + 1)
                        i = close + 1
                        continue
                    }
                }
                out.append(c).append(source[i + 1])
                i += 2
                classStart = false
                continue
            }
            if (inClass) {
                when {
                    c == ']' && !classStart -> {
                        inClass = false
                        out.append(']')
                    }
                    c == ']' -> out.append("\\]")
                    c == '[' && source.startsWith("[:", i) -> {
                        val close = source.indexOf(":]", i + 2)
                        val name = if (close > 0) source.substring(i + 2, close) else ""
                        val replacement = if (name.startsWith("^")) NegatedPosixClasses[name.drop(1)] else PosixClasses[name]
                        if (replacement != null) {
                            out.append(replacement)
                            i = close + 2
                            classStart = false
                            continue
                        }
                        out.append("\\[")
                    }
                    c == '[' -> out.append("\\[")
                    c == '&' -> out.append("\\&")
                    else -> out.append(c)
                }
                classStart = false
                i++
                continue
            }
            when {
                c == '[' -> {
                    inClass = true
                    classStart = true
                    out.append('[')
                    if (source.getOrNull(i + 1) == '^') {
                        out.append('^')
                        i++
                    }
                }
                c == '(' && source.startsWith("(?|", i) -> {
                    reset = true
                    out.append("(?:")
                    i += 2
                }
                c == '(' && source.startsWith("(?P<", i) -> {
                    out.append("(?<")
                    i += 3
                }
                c == '(' && source.startsWith("(?P=", i) -> {
                    val close = source.indexOf(')', i)
                    if (close > 0) {
                        out.append("\\k<").append(source, i + 4, close).append('>')
                        i = close
                    } else {
                        out.append(c)
                    }
                }
                c == '{' -> {
                    val repetition = Repetition.matchAt(source, i)
                    if (repetition != null && (repetition.groupValues[1].isNotEmpty() || repetition.groupValues[3].isNotEmpty())) {
                        val min = repetition.groupValues[1].ifEmpty { "0" }
                        out.append('{').append(min).append(repetition.groupValues[2]).append('}')
                        i += repetition.value.length - 1
                        quantifierSuffix()
                    } else {
                        out.append("\\{")
                    }
                }
                c == '}' -> out.append("\\}")
                c == '*' || c == '+' || (c == '?' && i > 0 && source[i - 1] != '(') -> {
                    out.append(c)
                    quantifierSuffix()
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString() to reset
    }

    /** Tells whether a character is an ASCII hexadecimal digit. */
    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

/** The keyword lists of the definitions with their includes resolved. */
private object Keywords {
    private val sets = ConcurrentHashMap<Pair<Definition, String>, Pair<Set<String>, Set<String>>>()

    /** Tells whether a list of a definition holds a word. */
    fun contains(definition: Definition, list: String, word: String, caseSensitive: Boolean): Boolean {
        val (exact, lower) = sets.getOrPut(definition to list) { collect(definition, list, HashSet()).let { it to it.mapTo(HashSet()) { w -> w.lowercase() } } }
        return if (caseSensitive) word in exact else word.lowercase() in lower
    }

    /** Collects a list's words with those of the lists it includes, which may be another definition's (`list##Language`). */
    private fun collect(definition: Definition, list: String, seen: MutableSet<Pair<String, String>>): Set<String> {
        if (!seen.add(definition.name to list)) return emptySet()
        val (items, includes) = definition.lists[list] ?: return emptySet()
        val out = HashSet(items)
        for (include in includes) {
            val split = include.indexOf("##")
            val other = if (split >= 0) SyntaxRepository.definition(include.substring(split + 2)) else definition
            if (other != null) out += collect(other, if (split >= 0) include.substring(0, split) else include, seen)
        }
        return out
    }
}
