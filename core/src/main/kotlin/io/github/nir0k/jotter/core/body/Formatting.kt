package io.github.nir0k.jotter.core.body

import io.github.nir0k.jotter.core.format.noteLinkAddress

// The formatting commands: inline formats, colours, links, paragraph styles, quotes and lists
// with their numbering. Each returns the new state, or null when it does not apply where the
// selection is.

/** An inline format the formatting bar toggles. */
enum class Mark { Bold, Italic, Underline, Strike, Sup, Sub, Code }

/** Tells whether a format has a mark. */
fun TextFormat.has(mark: Mark): Boolean = when (mark) {
    Mark.Bold -> bold
    Mark.Italic -> italic
    Mark.Underline -> underline
    Mark.Strike -> strike
    Mark.Sup -> valign == VAlign.Sup
    Mark.Sub -> valign == VAlign.Sub
    Mark.Code -> code
}

/** Returns a format with a mark set or cleared; superscript and subscript exclude each other. */
fun TextFormat.with(mark: Mark, on: Boolean): TextFormat = when (mark) {
    Mark.Bold -> copy(bold = on)
    Mark.Italic -> copy(italic = on)
    Mark.Underline -> copy(underline = on)
    Mark.Strike -> copy(strike = on)
    Mark.Sup -> copy(valign = if (on) VAlign.Sup else valign.takeIf { it != VAlign.Sup })
    Mark.Sub -> copy(valign = if (on) VAlign.Sub else valign.takeIf { it != VAlign.Sub })
    Mark.Code -> copy(code = on)
}

/** Returns the format the next typed text gets at the cursor. */
fun EditorState.cursorFormat(): TextFormat {
    stored?.let { return it }
    val range = textRange() ?: return TextFormat.Plain
    val (index, offset) = range.run.locate(range.from)
    return formatAt(range.run.blocks[index].inline, offset)
}

/** Returns the formats of the text runs a range covers, block by block. */
fun RunRange.formats(): List<TextFormat> {
    val (first, firstOffset) = run.locate(from)
    val (last, lastOffset) = run.locate(to)
    val out = ArrayList<TextFormat>()
    for (i in first..last) {
        val block = run.blocks[i]
        val start = if (i == first) firstOffset else 0
        val end = if (i == last) lastOffset else block.length
        out += formatsIn(block.inline, start, end)
    }
    return out
}

/** Tells whether a mark is on for the selection: for all its text, or for the text typed next at a cursor. */
fun EditorState.markActive(mark: Mark): Boolean {
    val range = textRange() ?: return false
    if (range.collapsed) return cursorFormat().has(mark)
    val formats = range.formats()
    return formats.isNotEmpty() && formats.all { it.has(mark) }
}

/**
 * Changes the format of the text of a range; objects keep theirs.
 *
 * @return The state with the same selection.
 */
fun EditorState.mapFormat(range: RunRange, change: (TextFormat) -> TextFormat): EditorState {
    val run = range.run
    val (first, firstOffset) = run.locate(range.from)
    val (last, lastOffset) = run.locate(range.to)
    val blocks = run.blocks.mapIndexed { i, block ->
        if (i < first || i > last) return@mapIndexed block
        val start = if (i == first) firstOffset else 0
        val end = if (i == last) lastOffset else block.length
        val middle = slice(block.inline, start, end).map { if (it is TextRun) TextRun(it.text, change(it.format)) else it }
        block.withInline(slice(block.inline, 0, start) + middle + slice(block.inline, end, block.length))
    }
    val items = replaceRange(doc.itemsIn(run.container), run.start, run.end, blocks)
    return copy(doc = doc.withItemsIn(run.container, items))
}

/** Toggles an inline format over the selection, or for the text typed next at a cursor. */
fun EditorState.toggleMark(mark: Mark): EditorState? {
    val range = textRange() ?: return null
    val on = !markActive(mark)
    if (range.collapsed) return copy(stored = cursorFormat().with(mark, on))
    return mapFormat(range) { it.with(mark, on) }
}

/**
 * Sets the text colour or the highlight of the selection, or of the text typed next.
 *
 * @param key A tag colour key or `#rrggbb`; null for Default.
 */
fun EditorState.setColor(highlight: Boolean, key: String?): EditorState? {
    val range = textRange() ?: return null
    val change: (TextFormat) -> TextFormat = { if (highlight) it.copy(highlight = key) else it.copy(color = key) }
    if (range.collapsed) return copy(stored = change(cursorFormat()))
    return mapFormat(range, change)
}

/** A link of a block: where it is in the run and its address. */
data class LinkRange(val from: Int, val to: Int, val href: String)

/** Returns the link at a cursor, the whole run of text with its address around it. */
fun EditorState.linkAtCursor(): LinkRange? {
    val range = textRange() ?: return null
    if (!range.collapsed) return null
    val run = range.run
    val (index, offset) = run.locate(range.from)
    val block = run.blocks[index]
    // The run of pieces around the cursor with one address.
    var pos = 0
    val pieces = block.inline.map { piece -> (pos to pos + piece.length).also { pos += piece.length } }
    val href = block.inline.withIndex().firstOrNull { (i, piece) ->
        piece is TextRun && piece.format.href != null && pieces[i].first <= offset && offset <= pieces[i].second
    }?.let { (it.value as TextRun).format.href } ?: return null
    fun linked(i: Int) = (block.inline[i] as? TextRun)?.format?.href == href
    var first = block.inline.indices.first { linked(it) && pieces[it].first <= offset && offset <= pieces[it].second }
    var last = first
    while (first > 0 && linked(first - 1)) first--
    while (last < block.inline.lastIndex && linked(last + 1)) last++
    val start = run.startOf(index)
    return LinkRange(start + pieces[first].first, start + pieces[last].second, href)
}

/** Returns the text between two offsets of a run, with line breaks and block ends as spaces. */
fun Run.textBetween(from: Int, to: Int): String = text.substring(from, to).replace(BlockSeparator, ' ').replace(ObjectCharacter.toString(), "")

/** Returns what the link dialog starts with: the selected text, or the link at the cursor and its address. */
fun EditorState.linkDialogText(): Pair<String, String> {
    val range = textRange() ?: return "" to ""
    linkAtCursor()?.let { return range.run.textBetween(it.from, it.to) to it.href }
    return range.run.textBetween(range.from, range.to) to ""
}

/**
 * Inserts a link or changes the one at the cursor: its text and address.
 *
 * @param text The text the link shows; the address when empty.
 * @param href The address, already checked.
 */
fun EditorState.setLink(text: String, href: String): EditorState? {
    val range = textRange() ?: return null
    val link = if (range.collapsed) linkAtCursor() else null
    val from = link?.from ?: range.from
    val to = link?.to ?: range.to
    val shown = text.ifEmpty { href }
    val (index, offset) = range.run.locate(from)
    val format = formatAt(range.run.blocks[index].inline, if (link != null) offset + 1 else offset).copy(href = href)
    val replaced = replaceText(range.run.container, range.run.key, from, to, shown, format) ?: return null
    return replaced.copy(stored = replaced.cursorFormat().copy(href = null))
}

/**
 * Inserts a link to a note with the note's title as its text: in place of the `[[` typed between
 * two offsets of a run when they are still there, otherwise in place of the selection.
 */
fun EditorState.insertNoteLink(container: Container, key: Long, from: Int, to: Int, id: String, title: String): EditorState? {
    val run = doc.runByKey(container, key)
    val brackets = run != null && to - from == 2 && to <= run.text.length && run.text.substring(from, to) == "[["
    if (brackets) {
        val (index, offset) = run.locate(from)
        val format = formatAt(run.blocks[index].inline, offset).copy(href = noteLinkAddress(id))
        val replaced = replaceText(container, key, from, to, title, format) ?: return null
        return replaced.copy(stored = replaced.cursorFormat().copy(href = null))
    }
    return setLink(title, noteLinkAddress(id))
}

/** Removes the link at the cursor or from the selection; its text stays. */
fun EditorState.removeLink(): EditorState? {
    val range = textRange() ?: return null
    val target = if (range.collapsed) linkAtCursor()?.let { RunRange(range.run, it.from, it.to) } ?: return null else range
    return mapFormat(target) { it.copy(href = null) }
}

/** The paragraph styles of the formatting bar. */
enum class BlockStyle { Paragraph, Heading1, Heading2, Heading3, Quote }

/** Returns the blocks a text selection touches. */
private fun RunRange.selectedBlocks(): List<Block> {
    val first = run.locate(from).first
    val last = run.locate(to).first
    return run.blocks.subList(first, last + 1)
}

/**
 * Applies a paragraph style to the selected blocks: Paragraph and Heading 1 to 3 leave a quote
 * first; list items and dividers keep theirs, and table cells hold no headings. Quote makes the
 * selected paragraphs and headings of the root a quote, or plain paragraphs again.
 */
fun EditorState.setBlockStyle(style: BlockStyle): EditorState? {
    val range = textRange() ?: return null
    val blocks = range.selectedBlocks()
    val container = range.run.container
    if (style == BlockStyle.Quote) {
        if (blocks.first().quote) return copy(doc = doc.mapBlocks(container, blocks.map { it.id }.toSet()) { it.copy(quote = false) })
        if (container != RootContainer || blocks.any { it.list != null || it.hr }) return null
        return copy(doc = doc.mapBlocks(container, blocks.map { it.id }.toSet()) { it.copy(quote = true, heading = 0) })
    }
    val level = style.ordinal
    val ids = blocks.filter { it.list == null && !it.hr && (level == 0 || container == RootContainer) }.map { it.id }.toSet()
    if (ids.isEmpty()) return null
    return copy(doc = doc.mapBlocks(container, ids) { it.copy(heading = level, quote = false) })
}

/** Returns the paragraph style of a block, as the formatting bar shows it. */
fun blockStyleOf(block: Block): BlockStyle = when {
    block.quote && block.list == null -> BlockStyle.Quote
    block.heading == 1 -> BlockStyle.Heading1
    block.heading == 2 -> BlockStyle.Heading2
    block.heading == 3 -> BlockStyle.Heading3
    else -> BlockStyle.Paragraph
}

/**
 * Returns the index after the last block of a list item's subtree: the item and the deeper items
 * after it.
 */
private fun subtreeEnd(items: List<Item>, index: Int): Int {
    val indent = (items[index] as Block).list?.indent ?: return index + 1
    var end = index + 1
    while (end < items.size) {
        val list = (items[end] as? Block)?.list ?: break
        if (list.indent <= indent) break
        end++
    }
    return end
}

/** Returns the selected list items of the current item's list and level, with the indices after their subtrees. */
private fun EditorState.selectedItems(): Triple<Container, IntRange, ListInfo>? {
    val range = textRange() ?: return null
    val indices = range.blockIndices()
    val items = doc.itemsIn(range.run.container)
    val list = (items[indices.first] as Block).list ?: return null
    // The siblings the selection reaches, and the deeper items under the last of them.
    var last = indices.first
    for (i in indices) {
        val other = (items[i] as Block).list ?: break
        if (other.indent < list.indent) break
        last = i
    }
    return Triple(range.run.container, indices.first until subtreeEnd(items, last), list)
}

/** Tells whether the selected list items can nest a level deeper: an item before them on their level, and room below nine levels. */
fun EditorState.canIndent(): Boolean = indentChange() != null

/** Tells whether the selected list items can move a level up. */
fun EditorState.canOutdent(): Boolean = currentBlock()?.list != null

/** Returns the document with the selected list items nested a level deeper, or null. */
private fun EditorState.indentChange(): Doc? {
    val (container, span, list) = selectedItems() ?: return null
    val items = doc.itemsIn(container)
    // An earlier item of the same list and level becomes the parent.
    var i = span.first - 1
    var sibling = false
    while (i >= 0) {
        val other = (items[i] as? Block)?.list ?: break
        if (other.indent > list.indent) {
            i--
            continue
        }
        sibling = other.id == list.id
        break
    }
    if (!sibling) return null
    if (span.any { ((items[it] as Block).list?.indent ?: 0) + 1 > MaxListDepth }) return null
    // The parent's last nested list takes the items when it is of the same kind; otherwise a new one.
    val nested = (span.first - 1 downTo 0).asSequence()
        .map { (items[it] as Block).list!! }
        .takeWhile { it.indent > list.indent }
        .firstOrNull { it.indent == list.indent + 1 }
    val target = if (nested != null && nested.kind == list.kind) nested else ListInfo(newId(), list.kind, list.indent + 1)
    val deeper = HashMap<Long, ListInfo>()
    val changed = items.mapIndexed { index, item ->
        if (index !in span || item !is Block) return@mapIndexed item
        val own = item.list!!
        if (own.id == list.id && own.indent == list.indent) item.copy(list = target)
        else item.copy(list = deeper.getOrPut(own.id) { own.copy(indent = own.indent + 1) })
    }
    return doc.withItemsIn(container, changed)
}

/** Nests the selected list items one level deeper, up to nine levels; their nested items go with them. */
fun EditorState.indent(): EditorState? = indentChange()?.let { copy(doc = it) }

/**
 * Moves the selected list items one level up with their nested items: into the list of their
 * parent item, or out of the list at the top level, where they become paragraphs.
 */
fun EditorState.outdent(): EditorState? {
    val (container, span, list) = selectedItems() ?: return null
    val items = doc.itemsIn(container)
    // The list of the parent item, the nearest earlier item one level up.
    val parent = if (list.indent > 1) {
        (span.first - 1 downTo 0).asSequence().mapNotNull { (items[it] as? Block)?.list }.firstOrNull { it.indent < list.indent }
    } else {
        null
    }
    val shallower = HashMap<Long, ListInfo>()
    val changed = items.mapIndexed { index, item ->
        if (index !in span || item !is Block) return@mapIndexed item
        val own = item.list!!
        when {
            own.id == list.id && own.indent == list.indent ->
                if (parent != null) item.copy(list = parent) else item.copy(list = null, checked = false)
            else -> item.copy(list = shallower.getOrPut(own.id) { own.copy(indent = own.indent - 1) })
        }
    }
    return copy(doc = doc.withItemsIn(container, changed))
}

/**
 * Makes the selected blocks a list of a kind, changes the kind of the list the cursor is in, or
 * ends the list when it is of that kind already.
 *
 * @param style The numbering style of a new numbered list; null for the default.
 */
fun EditorState.toggleList(kind: ListKind, style: String? = null): EditorState? {
    val range = textRange() ?: return null
    val container = range.run.container
    val current = range.selectedBlocks().first().list
    if (current != null) {
        if (current.kind == kind && style == null) return outdent()
        val changed = current.copy(kind = kind, style = if (kind == ListKind.Number) style.orEmpty() else "", start = 1)
        return copy(doc = doc.withItemsIn(container, doc.itemsIn(container).map {
            if (it is Block && it.list?.id == current.id) it.copy(list = changed.copy(indent = it.list.indent)) else it
        }))
    }
    val blocks = range.selectedBlocks().filter { !it.hr }
    if (blocks.isEmpty()) return null
    val list = ListInfo(newId(), kind, 1, if (kind == ListKind.Number) style.orEmpty() else "", 1)
    return copy(doc = doc.mapBlocks(container, blocks.map { it.id }.toSet()) { it.copy(list = list, heading = 0, quote = false, checked = false) })
}

/** Sets the numbering style of the numbered list at the cursor, or makes the selection a numbered list of that style. */
fun EditorState.setNumbering(style: String): EditorState? {
    val range = textRange() ?: return null
    val current = currentBlock()?.list
    if (current?.kind == ListKind.Number) {
        val container = range.run.container
        return copy(doc = doc.withItemsIn(container, doc.itemsIn(container).map {
            if (it is Block && it.list?.id == current.id) it.copy(list = it.list.copy(style = style)) else it
        }))
    }
    return toggleList(ListKind.Number, style)
}

/**
 * Makes the item at the cursor start the numbering at a number. On an item after the first the
 * list is split there, so the items before keep their numbers.
 */
fun EditorState.startNumbering(start: Int): EditorState? {
    val range = textRange() ?: return null
    val container = range.run.container
    val items = doc.itemsIn(container)
    val at = range.blockIndices().first
    val list = (items[at] as Block).list?.takeIf { it.kind == ListKind.Number } ?: return null
    val first = items.indexOfFirst { it is Block && it.list?.id == list.id }
    val renumbered = if (first == at) list.copy(start = start) else list.copy(id = newId(), start = start)
    val changed = items.mapIndexed { i, item ->
        if (item is Block && item.list?.id == list.id && i >= at) item.copy(list = renumbered) else item
    }
    return copy(doc = doc.withItemsIn(container, changed))
}

/** Toggles the box of the checklist item at the cursor. */
fun EditorState.toggleChecked(): EditorState? {
    val range = textRange() ?: return null
    val block = currentBlock() ?: return null
    if (block.list?.kind != ListKind.Check) return null
    return copy(doc = doc.mapBlocks(range.run.container, setOf(block.id)) { it.copy(checked = !it.checked) })
}

/** Toggles the box of a checklist item, as a tap on it does. */
fun EditorState.toggleChecked(container: Container, blockId: Long): EditorState? {
    val block = doc.block(container, blockId) ?: return null
    if (block.list?.kind != ListKind.Check) return null
    return copy(doc = doc.mapBlocks(container, setOf(blockId)) { it.copy(checked = !it.checked) })
}

/**
 * Returns the marker of every item of the numbered lists of a run of blocks, counting items as
 * the note file nests them, and the nesting level of every list item, which picks the bullet of
 * bulleted lists.
 *
 * @return For each block, its marker (null for blocks without one) and its level (0 outside lists).
 */
fun listMarkers(blocks: List<Block>): List<Pair<String?, Int>> {
    // The lists open at each level as the writer nests them, with their item counts.
    data class Open(val list: ListInfo, var count: Int)
    val open = ArrayList<Open>()
    return blocks.map { block ->
        val list = block.list
        if (list == null) {
            open.clear()
            return@map null to 0
        }
        val indent = maxOf(1, list.indent)
        while (open.isNotEmpty() && (open.last().list.indent > indent || (open.last().list.indent == indent && open.last().list.id != list.id))) {
            open.removeAt(open.lastIndex)
        }
        if (open.isEmpty() || open.last().list.indent < indent) open += Open(list, if (list.kind == ListKind.Number) list.start - 1 else 0)
        val current = open.last()
        current.count++
        val level = open.size
        if (list.kind != ListKind.Number) return@map null to level
        marker(list.style, open.map { it.count }) to level
    }
}

/** Formats a number in capital letters as CSS's `upper-alpha`: A to Z, then AA. */
private fun letters(number: Int): String {
    var n = number
    val text = StringBuilder()
    while (n > 0) {
        n--
        text.insert(0, ('A' + n % 26))
        n /= 26
    }
    return text.toString()
}

/** Formats a number in Roman numerals as CSS's `upper-roman`, falling back to decimal outside 1 to 3999. */
private fun roman(number: Int): String {
    if (number !in 1..3999) return number.toString()
    val numerals = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
    var n = number
    val text = StringBuilder()
    for ((value, numeral) in numerals) {
        while (n >= value) {
            text.append(numeral)
            n -= value
        }
    }
    return text.toString()
}

/**
 * Returns the marker of an item of a numbered list.
 *
 * @param style The list's numbering style; an unknown one is drawn as `1.`.
 * @param counters The item's number at each level from the outermost list down.
 */
fun marker(style: String, counters: List<Int>): String {
    val own = counters.last()
    return when (style) {
        "upper-alpha" -> "${letters(own)}."
        "upper-roman" -> "${roman(own)}."
        "multilevel" -> counters.joinToString(".", postfix = ".")
        else -> "$own."
    }
}
