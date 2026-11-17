package io.github.nir0k.jotter.core.body

import io.github.nir0k.jotter.core.format.isWebLink

// Markdown shortcuts while typing: at the start of a paragraph `# `, `## `, `### ` make headings,
// `> ` a quote, `- ` or `* ` a bulleted list, `N. ` a numbered list starting at N and `[] ` or `[
// ] ` a checklist; in a list item the list shortcuts change the kind of its list. Text typed
// between two backticks becomes inline code once the closing backtick is typed, and a web address
// becomes a link when a space follows it; `[[` opens the note picker. None applies inside code
// blocks.

/** What a shortcut typed in text did. */
sealed interface RuleResult {
    /** The text changed; undo right after it brings back what was typed. */
    data class Changed(val state: EditorState) : RuleResult

    /**
     * `[[` was typed: the note picker opens for the brackets between two offsets of a run.
     *
     * @param key The run's key.
     */
    data class NotePicker(val container: Container, val key: Long, val from: Int, val to: Int) : RuleResult
}

private val HeadingRule = Regex("^(#{1,3})\\s$")
private val QuoteRule = Regex("^>\\s$")
private val BulletRule = Regex("^[-*]\\s$")
private val NumberRule = Regex("^(\\d+)\\.\\s$")
private val CheckRule = Regex("^\\[ ?]\\s$")
private val CodeRule = Regex("(^|[^`])`([^`]*[^`\\s][^`]*)`$")
private val LinkRule = Regex("(?:^|\\s)(https?://\\S+)\\s$")

/**
 * Applies the shortcut that the character just typed at the cursor completes, if any.
 *
 * @param typed The character typed.
 * @return What the shortcut did, or null when none applies.
 */
fun EditorState.inputRule(typed: Char): RuleResult? {
    val range = textRange() ?: return null
    if (!range.collapsed) return null
    val run = range.run
    val (index, offset) = run.locate(range.from)
    val block = run.blocks[index]
    val before = block.text.substring(0, offset)
    val blockStart = run.startOf(index)
    if (typed == '[' && before.endsWith("[[")) return RuleResult.NotePicker(run.container, run.key, range.from - 2, range.from)
    if (typed.isWhitespace()) blockRule(run, index, before)?.let { return RuleResult.Changed(it) }
    if (typed == '`') {
        val match = CodeRule.find(before) ?: return null
        // The opening backtick, and the code typed after it.
        val tick = match.range.first + match.groupValues[1].length
        val format = formatAt(block.inline, tick + 1).copy(code = true)
        val replaced = replaceText(run.container, run.key, blockStart + tick, range.from, match.groupValues[2], format) ?: return null
        return RuleResult.Changed(replaced.copy(stored = format.copy(code = false)))
    }
    if (typed == ' ') {
        val match = LinkRule.find(before) ?: return null
        val url = match.groupValues[1]
        if (!isWebLink(url)) return null
        val end = range.from - 1
        val start = end - url.length
        val linked = RunRange(run, start, end)
        if (linked.formats().any { it.href != null }) return null
        return RuleResult.Changed(mapFormat(linked) { it.copy(href = url) })
    }
    return null
}

/** Applies a shortcut typed at the start of a block: headings, a quote and lists. */
private fun EditorState.blockRule(run: Run, index: Int, before: String): EditorState? {
    val block = run.blocks[index]
    if (block.hr) return null
    val container = run.container
    val start = run.startOf(index)
    val end = start + before.length
    fun cleared() = replaceText(container, run.key, start, end, "", TextFormat.Plain)
    HeadingRule.find(before)?.let { match ->
        if (container != RootContainer || block.list != null || block.quote) return null
        val state = cleared() ?: return null
        return state.copy(doc = state.doc.mapBlocks(container, setOf(block.id)) { it.copy(heading = match.groupValues[1].length) })
    }
    if (QuoteRule.matches(before)) {
        if (container != RootContainer || block.list != null || block.quote || block.heading != 0) return null
        val state = cleared() ?: return null
        return state.copy(doc = state.doc.mapBlocks(container, setOf(block.id)) { it.copy(quote = true) })
    }
    val kind: ListKind
    var listStart = 1
    when {
        BulletRule.matches(before) -> kind = ListKind.Bullet
        CheckRule.matches(before) -> kind = ListKind.Check
        else -> {
            val match = NumberRule.find(before) ?: return null
            kind = ListKind.Number
            listStart = maxOf(1, match.groupValues[1].toIntOrNull() ?: 1)
        }
    }
    if (block.heading != 0 || block.quote) return null
    val state = cleared() ?: return null
    val list = block.list
    if (list != null) {
        // In a list item the kind of its list changes.
        val changed = list.copy(kind = kind, style = "", start = listStart)
        return state.copy(doc = state.doc.withItemsIn(container, state.doc.itemsIn(container).map {
            if (it is Block && it.list?.id == list.id) it.copy(list = changed) else it
        }))
    }
    val created = ListInfo(newId(), kind, 1, "", listStart)
    return state.copy(doc = state.doc.mapBlocks(container, setOf(block.id)) { it.copy(list = created) })
}
