package io.github.nir0k.jotter.core.body

// The editor's state over the body model: the document, the selection and the format of the text
// typed next. The screen edits runs of blocks — the blocks between two frames of the root, or
// the blocks of a table cell — each as one text field whose text is the blocks' text joined by
// [BlockSeparator]; code blocks are edited as plain text. Every change is a pure function from
// one state to the next, so the history keeps whole states.

/** The character between two blocks in a run's text; a text field breaks the line there. */
const val BlockSeparator = ' '

/** The line break inside a block in a run's text. */
const val FieldLineBreak = '\n'

/** Lists nest up to this many levels (desktop spec section 3.3). */
const val MaxListDepth = 9

/** Where blocks live: the root of the note or a table cell. */
sealed interface Container

/** The root of the note. */
data object RootContainer : Container

/** A cell of a table of the root. */
data class CellContainer(val table: Long, val row: Int, val column: Int) : Container

/** A place in a block: the block and the offset in its text. */
data class Point(val block: Long, val offset: Int)

/** What is selected. */
sealed interface Selection

/**
 * A selection in one run of blocks: [anchor] where it starts, [head] where the cursor is.
 *
 * @param container Where the run is.
 */
data class TextSelection(val container: Container, val anchor: Point, val head: Point) : Selection {
    /** Tells whether nothing is selected but a cursor. */
    val collapsed: Boolean get() = anchor == head
}

/** A selection in a code block, as offsets of its text. */
data class CodeSelection(val code: Long, val anchor: Int, val head: Int) : Selection

/**
 * The editor's state.
 *
 * @param stored The format of the text typed next at a cursor, when it differs from the text there.
 */
data class EditorState(val doc: Doc, val selection: Selection, val stored: TextFormat? = null) {
    companion object {
        /** Returns a state for a document with the cursor at its start. */
        fun of(doc: Doc): EditorState = EditorState(doc, startOf(doc))
    }
}

/** Returns the cursor at the start of a document. */
fun startOf(doc: Doc): Selection {
    val first = doc.items.first()
    return if (first is Block) TextSelection(RootContainer, Point(first.id, 0), Point(first.id, 0)) else firstPlaceIn(doc, first)
}

/** Returns the first place the cursor can stand in a frame. */
private fun firstPlaceIn(doc: Doc, frame: Item): Selection = when (frame) {
    is CodeBlock -> CodeSelection(frame.id, 0, 0)
    is Table -> {
        val block = frame.cells[0][0][0]
        TextSelection(CellContainer(frame.id, 0, 0), Point(block.id, 0), Point(block.id, 0))
    }
    is Block -> TextSelection(RootContainer, Point(frame.id, 0), Point(frame.id, 0))
}

/** Returns the items of a container: the root's items or a cell's blocks; empty for a container that is gone. */
fun Doc.itemsIn(container: Container): List<Item> = when (container) {
    RootContainer -> items
    is CellContainer -> tableById(container.table)?.cells?.getOrNull(container.row)?.getOrNull(container.column) ?: emptyList()
}

/** Returns the document with the items of a container replaced. */
fun Doc.withItemsIn(container: Container, replaced: List<Item>): Doc = when (container) {
    RootContainer -> Doc(replaced)
    is CellContainer -> withTable(container.table) { table ->
        table.copy(cells = table.cells.mapIndexed { r, row ->
            if (r != container.row) row else row.mapIndexed { c, cell -> if (c == container.column) replaced.map { it as Block } else cell }
        })
    }
}

/** Returns the table of the root with an identifier. */
fun Doc.tableById(id: Long): Table? = items.firstOrNull { it.id == id } as? Table

/** Returns the code block of the root with an identifier. */
fun Doc.codeById(id: Long): CodeBlock? = items.firstOrNull { it.id == id } as? CodeBlock

/** Returns the document with a table changed. */
fun Doc.withTable(id: Long, change: (Table) -> Table): Doc = Doc(items.map { if (it.id == id && it is Table) change(it) else it })

/** Returns the document with a code block changed. */
fun Doc.withCode(id: Long, change: (CodeBlock) -> CodeBlock): Doc = Doc(items.map { if (it.id == id && it is CodeBlock) change(it) else it })

/**
 * A run of blocks: the blocks of a container between two frames, edited as one text field.
 *
 * @param start The index of its first block among the container's items.
 */
data class Run(val container: Container, val start: Int, val blocks: List<Block>) {
    /** Identifies the run while its first block lives. */
    val key: Long get() = blocks.first().id

    /** The index after its last block. */
    val end: Int get() = start + blocks.size

    /** The run's text: the blocks' text joined by [BlockSeparator], line breaks as [FieldLineBreak]. */
    val text: String by lazy { blocks.joinToString(BlockSeparator.toString()) { fieldText(it) } }

    /** Returns the offset in the run's text where a block starts. */
    fun startOf(block: Int): Int {
        var offset = 0
        for (i in 0 until block) offset += blocks[i].length + 1
        return offset
    }

    /** Returns the index of a block in the run, or -1. */
    fun indexOf(blockId: Long): Int = blocks.indexOfFirst { it.id == blockId }

    /** Returns the run text offset of a point, or null when its block is not in the run. */
    fun offsetOf(point: Point): Int? {
        val index = indexOf(point.block)
        if (index < 0) return null
        return startOf(index) + point.offset.coerceIn(0, blocks[index].length)
    }

    /** Returns the block index and offset of a run text offset. */
    fun locate(offset: Int): Pair<Int, Int> {
        var rest = offset.coerceAtLeast(0)
        for ((i, block) in blocks.withIndex()) {
            if (rest <= block.length) return i to rest
            rest -= block.length + 1
        }
        return blocks.lastIndex to blocks.last().length
    }

    /** Returns the point of a run text offset. */
    fun pointAt(offset: Int): Point {
        val (index, inBlock) = locate(offset)
        return Point(blocks[index].id, inBlock)
    }
}

/** Returns a block's text as a run shows it: line breaks as [FieldLineBreak], objects as [ObjectCharacter]. */
fun fieldText(block: Block): String = block.text.replace(LineSeparator, FieldLineBreak)

/** Returns the runs of a container in order. */
fun Doc.runsIn(container: Container): List<Run> {
    val items = itemsIn(container)
    val runs = ArrayList<Run>()
    var start = -1
    for ((i, item) in items.withIndex()) {
        if (item is Block) {
            if (start < 0) start = i
        } else if (start >= 0) {
            runs += Run(container, start, items.subList(start, i).map { it as Block })
            start = -1
        }
    }
    if (start >= 0) runs += Run(container, start, items.subList(start, items.size).map { it as Block })
    return runs
}

/** Returns the run of a container that holds a block, or null. */
fun Doc.runOf(container: Container, blockId: Long): Run? = runsIn(container).firstOrNull { run -> run.blocks.any { it.id == blockId } }

/** Returns the run with a key in a container, or null. */
fun Doc.runByKey(container: Container, key: Long): Run? = runsIn(container).firstOrNull { it.key == key }

/** Returns the block with an identifier in a container. */
fun Doc.block(container: Container, id: Long): Block? = itemsIn(container).firstOrNull { it.id == id } as? Block

/** The ordered range of a text selection in its run. */
data class RunRange(val run: Run, val from: Int, val to: Int) {
    /** Tells whether the range is empty. */
    val collapsed: Boolean get() = from == to
}

/** Returns the run and the ordered offsets of a text selection, or null when it no longer fits the document. */
fun EditorState.textRange(): RunRange? {
    val selection = selection as? TextSelection ?: return null
    val run = doc.runOf(selection.container, selection.head.block) ?: return null
    val anchor = run.offsetOf(selection.anchor) ?: run.offsetOf(selection.head) ?: return null
    val head = run.offsetOf(selection.head) ?: return null
    return RunRange(run, minOf(anchor, head), maxOf(anchor, head))
}

/** Returns a cursor at a run text offset. */
fun Run.cursor(offset: Int): TextSelection = pointAt(offset).let { TextSelection(container, it, it) }

/** Returns a selection between two run text offsets. */
fun Run.select(anchor: Int, head: Int): TextSelection = TextSelection(container, pointAt(anchor), pointAt(head))

/** Returns the block at the selection's start, as commands read the current block. */
fun EditorState.currentBlock(): Block? {
    val range = textRange() ?: return null
    return range.run.blocks[range.run.locate(range.from).first]
}

/** Returns the indices among the container's items of the blocks a text range touches. */
fun RunRange.blockIndices(): IntRange {
    val first = run.locate(from).first
    val last = run.locate(to).first
    return (run.start + first)..(run.start + last)
}

/**
 * Replaces the text between two offsets of a run: typing, deleting and pasting plain text.
 * Deleting across blocks joins them, the first block's format staying; a line break in the text
 * starts a new block of the same format. A divider takes no text: text typed on it goes into a
 * new paragraph after it, and joining another block into it removes it.
 *
 * @param container Where the run is.
 * @param key The run's key.
 * @param from The start of the replaced text.
 * @param to Its end.
 * @param text The new text; [FieldLineBreak], [BlockSeparator] or `\r\n` start a new block.
 * @param format The format of the new text; by default the stored format or the format at [from].
 * @return The state with the cursor after the new text, or null when the run is gone. The format
 *   stored for the next text is spent: what follows takes the format of the text before it.
 */
fun EditorState.replaceText(container: Container, key: Long, from: Int, to: Int, text: String, format: TextFormat? = null): EditorState? {
    val run = doc.runByKey(container, key) ?: return null
    val (startIndex, startOffset) = run.locate(minOf(from, to))
    val (endIndex, endOffset) = run.locate(maxOf(from, to))
    val first = run.blocks[startIndex]
    val last = run.blocks[endIndex]
    val typed = format ?: stored ?: formatAt(first.inline, startOffset)
    val lines = text.replace("\r\n", "\n").split(FieldLineBreak, BlockSeparator, '\r')
    val left = slice(first.inline, 0, startOffset)
    val right = slice(last.inline, endOffset, last.length)
    val replaced = ArrayList<Block>()
    // The format the joined block keeps: the first block's, unless that is a divider being joined.
    val base = if (first.hr && endIndex > startIndex) last else first
    if (first.hr && startIndex == endIndex && text.isNotEmpty()) {
        // A divider takes no text: what is typed on it goes into paragraphs after it.
        replaced += first
        lines.forEach { line -> replaced += Block(inline = normalized(listOf(TextRun(line, typed)))) }
    }
    val cursor: Point
    if (replaced.isNotEmpty()) {
        cursor = Point(replaced.last().id, lines.last().length)
    } else if (lines.size == 1) {
        val joined = base.copy(id = first.id).withInline(left + TextRun(lines[0], typed) + right)
        replaced += joined
        cursor = Point(joined.id, startOffset + lines[0].length)
    } else {
        replaced += base.copy(id = first.id).withInline(left + TextRun(lines[0], typed))
        for (i in 1 until lines.lastIndex) replaced += continuation(base).withInline(listOf(TextRun(lines[i], typed)))
        val tail = continuation(base).withInline(listOf(TextRun(lines.last(), typed)) + right)
        replaced += tail
        cursor = Point(tail.id, lines.last().length)
    }
    val items = run.blocks.subList(0, startIndex) + replaced + run.blocks.subList(endIndex + 1, run.blocks.size)
    val newDoc = doc.withItemsIn(container, replaceRange(doc.itemsIn(container), run.start, run.end, items))
    return EditorState(newDoc, TextSelection(container, cursor, cursor))
}

/** Returns a new block continuing another: the same format, unchecked, a divider's as a paragraph. */
private fun continuation(block: Block): Block = Block(heading = block.heading, quote = block.quote, list = block.list, hr = false)

/** Returns a list with the items between two indices replaced. */
fun <T> replaceRange(list: List<T>, from: Int, to: Int, with: List<T>): List<T> = list.subList(0, from) + with + list.subList(to, list.size)

/**
 * Starts a new block at the cursor, for Enter: an empty list item leaves its list a level up, an
 * empty quoted paragraph leaves the quote, a block splits at the cursor; at the end of a heading
 * the new block is a paragraph. On a top-level paragraph that is only ``` with a language or
 * `---`, a code block or a divider takes its place.
 *
 * @param language Returns the stored language of a language typed after ```.
 */
fun EditorState.enter(language: (String) -> String): EditorState? {
    val deleted = deleteSelection() ?: return null
    val range = deleted.textRange() ?: return null
    val run = range.run
    val (index, offset) = run.locate(range.from)
    val block = run.blocks[index]
    deleted.enterShortcut(run, index, offset, language)?.let { return it }
    if (block.list != null && block.length == 0) return deleted.outdent()
    if (block.quote && block.length == 0) return deleted.copy(doc = deleted.doc.mapBlocks(run.container, setOf(block.id)) { it.copy(quote = false) })
    val first = block.withInline(slice(block.inline, 0, offset))
    val atEnd = offset == block.length
    val second = Block(
        heading = if (atEnd) 0 else block.heading,
        quote = block.quote,
        list = block.list,
        inline = normalized(slice(block.inline, offset, block.length)),
    ).let { if (block.hr) Block() else it }
    val blocks = if (block.hr) listOf(block, second) else listOf(first, second)
    val items = replaceRange(deleted.doc.itemsIn(run.container), run.start + index, run.start + index + 1, blocks)
    val point = Point(second.id, 0)
    return deleted.copy(doc = deleted.doc.withItemsIn(run.container, items), selection = TextSelection(run.container, point, point))
}

private val Fence = Regex("^```(\\S*)$")

/** Applies the Enter shortcuts of a top-level paragraph: ``` with a language makes a code block, `---` a divider. */
private fun EditorState.enterShortcut(run: Run, index: Int, offset: Int, language: (String) -> String): EditorState? {
    val block = run.blocks[index]
    if (run.container != RootContainer || block.list != null || block.quote || block.heading != 0 || block.hr || offset != block.length) return null
    val text = block.text
    val fence = Fence.find(text)
    if (fence == null && text != "---") return null
    val at = run.start + index
    val items = doc.items
    return if (fence != null) {
        val code = CodeBlock(language = fence.groupValues[1].let { if (it.isEmpty()) "" else language(it) })
        val newDoc = Doc(framed(replaceRange(items, at, at + 1, listOf(code))))
        EditorState(newDoc, CodeSelection(code.id, 0, 0))
    } else {
        val paragraph = Block()
        val newDoc = Doc(framed(replaceRange(items, at, at + 1, listOf(Block(hr = true), paragraph))))
        EditorState(newDoc, TextSelection(RootContainer, Point(paragraph.id, 0), Point(paragraph.id, 0)))
    }
}

/** Removes the selected text; a cursor stays as it is. */
fun EditorState.deleteSelection(): EditorState? {
    if (selection is CodeSelection) return this
    val range = textRange() ?: return null
    if (range.collapsed) return this
    return replaceText(range.run.container, range.run.key, range.from, range.to, "")
}

/** Returns the document with blocks of a container changed. */
fun Doc.mapBlocks(container: Container, ids: Set<Long>, change: (Block) -> Block): Doc =
    withItemsIn(container, itemsIn(container).map { if (it is Block && it.id in ids) change(it) else it })

/**
 * Handles Backspace at the start of a block: a list item leaves its list a level up, a quoted
 * paragraph leaves the quote, other blocks join the block before. At the start of a run after a
 * frame, the cursor goes to the end of the frame; an empty block there goes too when the frame
 * rule keeps a block after the frame.
 *
 * @return The new state, or null when nothing changes.
 */
fun EditorState.backspaceAtStart(): EditorState? {
    val range = textRange() ?: return null
    if (!range.collapsed) return null
    val run = range.run
    val (index, offset) = run.locate(range.from)
    if (offset != 0) return null
    val block = run.blocks[index]
    if (block.list != null) return outdent()
    if (block.quote) return copy(doc = doc.mapBlocks(run.container, setOf(block.id)) { it.copy(quote = false) })
    if (index > 0) {
        return replaceText(run.container, run.key, run.startOf(index) - 1, run.startOf(index), "")
    }
    if (run.container != RootContainer || run.start == 0) return null
    val frame = doc.items[run.start - 1]
    val items = doc.items
    val removable = block.isEmptyParagraph && run.start + 1 < items.size && items[run.start + 1] is Block
    val newDoc = if (removable) Doc(replaceRange(items, run.start, run.start + 1, emptyList())) else doc
    return EditorState(newDoc, endOf(frame), null)
}

/** Returns the cursor at the end of a frame. */
fun endOf(frame: Item): Selection = when (frame) {
    is CodeBlock -> CodeSelection(frame.id, frame.text.length, frame.text.length)
    is Table -> {
        val row = frame.rows - 1
        val column = frame.columns - 1
        val block = frame.cells[row][column].last()
        TextSelection(CellContainer(frame.id, row, column), Point(block.id, block.length), Point(block.id, block.length))
    }
    is Block -> TextSelection(RootContainer, Point(frame.id, frame.length), Point(frame.id, frame.length))
}

/**
 * Returns positions of the whole document as single numbers, so a selection outlives the editor
 * that made it, as on rotation: the root's blocks, code blocks and table cells in order, each
 * taking its length and one more.
 */
fun Doc.globalOffset(selection: Selection, head: Boolean): Int {
    var offset = 0
    fun countBlocks(container: Container, blocks: List<Item>): Int? {
        for (item in blocks) {
            if (item !is Block) continue
            if (selection is TextSelection && selection.container == container) {
                val point = if (head) selection.head else selection.anchor
                if (point.block == item.id) return offset + point.offset.coerceAtMost(item.length)
            }
            offset += item.length + 1
        }
        return null
    }
    for (item in items) {
        when (item) {
            is Block -> countBlocks(RootContainer, listOf(item))?.let { return it }
            is CodeBlock -> {
                if (selection is CodeSelection && selection.code == item.id) return offset + (if (head) selection.head else selection.anchor).coerceAtMost(item.text.length)
                offset += item.text.length + 1
            }
            is Table -> item.cells.forEachIndexed { r, row ->
                row.forEachIndexed { c, cell -> countBlocks(CellContainer(item.id, r, c), cell)?.let { return it } }
            }
        }
    }
    return offset
}

/** Returns the selection between two global offsets of [globalOffset], kept inside the document and in one run. */
fun Doc.selectionAt(anchor: Int, head: Int): Selection {
    data class Place(val container: Container?, val code: Long?, val block: Long, val offset: Int)
    fun find(target: Int): Place {
        var offset = 0
        var last = Place(null, null, 0, 0)
        fun inBlocks(container: Container, blocks: List<Block>): Place? {
            for (block in blocks) {
                if (target <= offset + block.length) return Place(container, null, block.id, target - offset)
                offset += block.length + 1
                last = Place(container, null, block.id, block.length)
            }
            return null
        }
        for (item in items) {
            when (item) {
                is Block -> inBlocks(RootContainer, listOf(item))?.let { return it }
                is CodeBlock -> {
                    if (target <= offset + item.text.length) return Place(null, item.id, 0, target - offset)
                    offset += item.text.length + 1
                    last = Place(null, item.id, 0, item.text.length)
                }
                is Table -> item.cells.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell -> inBlocks(CellContainer(item.id, r, c), cell)?.let { return it } }
                }
            }
        }
        return last
    }
    val h = find(maxOf(0, head))
    val a = find(maxOf(0, anchor))
    if (h.code != null) return CodeSelection(h.code, if (a.code == h.code) a.offset else h.offset, h.offset)
    val container = h.container ?: return startOf(this)
    val headPoint = Point(h.block, h.offset)
    val run = runOf(container, h.block)
    val anchorPoint = if (a.container == container && run?.indexOf(a.block)?.let { it >= 0 } == true) Point(a.block, a.offset) else headPoint
    return TextSelection(container, anchorPoint, headPoint)
}

/** Returns a selection that fits the document: a block or frame that went is replaced by the start of the note. */
fun Doc.validSelection(selection: Selection): Selection = when (selection) {
    is CodeSelection -> codeById(selection.code)?.let { code ->
        CodeSelection(code.id, selection.anchor.coerceIn(0, code.text.length), selection.head.coerceIn(0, code.text.length))
    } ?: startOf(this)
    is TextSelection -> {
        val run = runOf(selection.container, selection.head.block)
        if (run == null) {
            startOf(this)
        } else {
            val head = run.offsetOf(selection.head)!!
            val anchor = run.offsetOf(selection.anchor) ?: head
            run.select(anchor, head)
        }
    }
}
