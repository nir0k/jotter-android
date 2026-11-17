package io.github.nir0k.jotter.core.body

// Code blocks, tables and dividers: inserting them, the table menu's commands and the editing
// keys of code blocks. Frames stand in the root; a frame inserted from a table cell goes after
// the table.

/**
 * Returns where a block is inserted after the current one: the index in the root, and whether an
 * empty top-level paragraph at the cursor is replaced.
 */
private fun EditorState.insertionPoint(): Pair<Int, Boolean> {
    when (val selection = selection) {
        is CodeSelection -> return (doc.items.indexOfFirst { it.id == selection.code } + 1) to false
        is TextSelection -> {
            if (selection.container is CellContainer) return (doc.items.indexOfFirst { it.id == selection.container.table } + 1) to false
            val range = textRange() ?: return doc.items.size to false
            val index = range.run.start + range.run.locate(range.from).first
            val block = doc.items[index] as Block
            return if (block.isEmptyParagraph) index to true else (index + 1) to false
        }
    }
}

/** Inserts a frame at the insertion point, keeping the frame rule, and returns the new document. */
private fun EditorState.insertFrame(frame: Frame): Doc {
    val (at, replace) = insertionPoint()
    return Doc(framed(replaceRange(doc.items, at, if (replace) at + 1 else at, listOf(frame))))
}

/**
 * Inserts a code block: in place of an empty paragraph or after the current block; the selected
 * lines of top-level paragraphs and headings become its lines, a line the selection only touches
 * at its start or end not taken. A selection holding anything else does not become a code block.
 */
fun EditorState.insertCodeBlock(): EditorState? {
    if (selection is CodeSelection) return null
    val range = textRange() ?: return null
    if (range.collapsed) {
        val code = CodeBlock()
        return EditorState(insertFrame(code), CodeSelection(code.id, 0, 0))
    }
    if (range.run.container != RootContainer) return null
    val run = range.run
    var (first, firstOffset) = run.locate(range.from)
    var (last, lastOffset) = run.locate(range.to)
    if (last > first && lastOffset == 0) last--
    if (last > first && firstOffset == run.blocks[first].length) first++
    val blocks = run.blocks.subList(first, last + 1)
    if (blocks.any { it.list != null || it.quote || it.hr }) return null
    val text = blocks.joinToString("\n") { block -> block.inline.filterIsInstance<TextRun>().joinToString("") { it.text }.replace(LineSeparator, '\n') }
    val code = CodeBlock(text = text)
    val items = Doc(framed(replaceRange(doc.items, run.start + first, run.start + last + 1, listOf(code))))
    return EditorState(items, CodeSelection(code.id, text.length, text.length))
}

/** Inserts a divider after the current block, followed by an empty paragraph where nothing else can take the cursor. */
fun EditorState.insertDivider(): EditorState? {
    val (at, replace) = insertionPoint()
    val end = if (replace) at + 1 else at
    val next = doc.items.getOrNull(end)
    val paragraph = if (replace || next !is Block) Block() else null
    val inserted = listOfNotNull(Block(hr = true), paragraph)
    val newDoc = Doc(framed(replaceRange(doc.items, at, end, inserted)))
    val target = paragraph ?: next as Block
    return EditorState(newDoc, TextSelection(RootContainer, Point(target.id, 0), Point(target.id, 0)))
}

/** Inserts a table of a size whose columns share the width equally; the cursor goes into its first cell. */
fun EditorState.insertTable(rows: Int, columns: Int, header: Boolean): EditorState {
    val table = Table(headerRows = if (header) 1 else 0, cells = List(rows) { List(columns) { listOf(Block()) } })
    val block = table.cells[0][0][0]
    return EditorState(insertFrame(table), TextSelection(CellContainer(table.id, 0, 0), Point(block.id, 0), Point(block.id, 0)))
}

/** The table menu's commands. */
enum class TableAction { AddRowBefore, AddRowAfter, AddColumnBefore, AddColumnAfter, DeleteRow, DeleteColumn, ToggleHeaderRow, DeleteTable, EqualColumns, FullWidth }

/** Returns the cell the selection is in. */
fun EditorState.currentCell(): CellContainer? = (selection as? TextSelection)?.container as? CellContainer

/** Returns the cursor at the start of a cell. */
private fun cellCursor(table: Table, row: Int, column: Int): TextSelection {
    val block = table.cells[row][column].first()
    return TextSelection(CellContainer(table.id, row, column), Point(block.id, 0), Point(block.id, 0))
}

/** Inserts the share of a new column into widths: the others keep their proportions. */
private fun addShare(widths: List<Int>, at: Int): List<Double> {
    val share = 100.0 / (widths.size + 1)
    val scaled = widths.map { it * (100 - share) / 100 }.toMutableList()
    scaled.add(at, share)
    return scaled
}

/** Runs a command of the table menu on the table at the cursor; each change is one undo step. */
fun EditorState.tableAction(action: TableAction): EditorState? {
    val cell = currentCell() ?: return null
    val table = doc.tableById(cell.table) ?: return null
    fun with(changed: Table, row: Int, column: Int) = EditorState(doc.withTable(table.id) { changed }, cellCursor(changed, row, column))
    fun emptyRow() = List(table.columns) { listOf(Block()) }
    return when (action) {
        TableAction.AddRowBefore -> {
            val header = if (cell.row < table.headerRows) table.headerRows + 1 else table.headerRows
            with(table.copy(cells = replaceRange(table.cells, cell.row, cell.row, listOf(emptyRow())), headerRows = header), cell.row + 1, cell.column)
        }
        TableAction.AddRowAfter -> {
            val header = if (cell.row + 1 < table.headerRows) table.headerRows + 1 else table.headerRows
            with(table.copy(cells = replaceRange(table.cells, cell.row + 1, cell.row + 1, listOf(emptyRow())), headerRows = header), cell.row, cell.column)
        }
        TableAction.AddColumnBefore, TableAction.AddColumnAfter -> {
            val at = if (action == TableAction.AddColumnBefore) cell.column else cell.column + 1
            val cells = table.cells.map { row -> replaceRange(row, at, at, listOf(listOf(Block()))) }
            val widths = table.widths?.let { setColumnWidths(table.columns + 1, addShare(it, at)) }
            with(table.copy(cells = cells, widths = widths), cell.row, if (at <= cell.column) cell.column + 1 else cell.column)
        }
        TableAction.DeleteRow -> if (table.rows == 1) deleteTable(table) else {
            val header = if (cell.row < table.headerRows) table.headerRows - 1 else table.headerRows
            with(table.copy(cells = replaceRange(table.cells, cell.row, cell.row + 1, emptyList()), headerRows = header), minOf(cell.row, table.rows - 2), cell.column)
        }
        TableAction.DeleteColumn -> if (table.columns == 1) deleteTable(table) else {
            val cells = table.cells.map { row -> replaceRange(row, cell.column, cell.column + 1, emptyList()) }
            val widths = table.widths?.let { w -> setColumnWidths(table.columns - 1, w.filterIndexed { i, _ -> i != cell.column }.map { it.toDouble() }) }
            with(table.copy(cells = cells, widths = widths), cell.row, minOf(cell.column, table.columns - 2))
        }
        TableAction.ToggleHeaderRow -> with(table.copy(headerRows = if (table.headerRows > 0) 0 else 1), cell.row, cell.column)
        TableAction.DeleteTable -> deleteTable(table)
        TableAction.EqualColumns -> copy(doc = doc.withTable(table.id) { it.copy(widths = null) })
        TableAction.FullWidth -> copy(doc = doc.withTable(table.id) { it.copy(width = 0) })
    }
}

/**
 * Removes a table. The blocks around it meet; an empty paragraph that only kept the frame rule
 * goes with it, and the cursor goes where they meet.
 */
private fun EditorState.deleteTable(table: Table): EditorState {
    val at = doc.items.indexOfFirst { it.id == table.id }
    var items = replaceRange(doc.items, at, at + 1, emptyList())
    val before = items.getOrNull(at - 1) as? Block
    val after = items.getOrNull(at) as? Block
    var cursor = after?.let { Point(it.id, 0) }
    if (before != null && after != null) {
        if (after.isEmptyParagraph) {
            items = replaceRange(items, at, at + 1, emptyList())
            cursor = Point(before.id, before.length)
        } else if (before.isEmptyParagraph) {
            items = replaceRange(items, at - 1, at, emptyList())
        }
    }
    val newDoc = Doc(framed(items))
    return EditorState(newDoc, cursor?.let { TextSelection(RootContainer, it, it) } ?: startOf(newDoc))
}

/** Sets a table's column widths, as dragging a line between columns does. */
fun EditorState.withColumnWidths(table: Long, widths: List<Int>): EditorState = copy(doc = doc.withTable(table) { it.copy(widths = widths) })

/** Sets a table's width in percent, 0 for full width, as dragging its right edge does. */
fun EditorState.setTableWidth(table: Long, width: Int): EditorState = copy(doc = doc.withTable(table) { it.copy(width = width) })

/**
 * Moves to the next or the previous cell, selecting its text, for Tab and Shift+Tab; Tab in the
 * last cell adds a row and moves into it.
 */
fun EditorState.goToCell(step: Int): EditorState? {
    val cell = currentCell() ?: return null
    var table = doc.tableById(cell.table) ?: return null
    var index = cell.row * table.columns + cell.column + step
    var newDoc = doc
    if (index < 0) return null
    if (index >= table.rows * table.columns) {
        if (step < 0) return null
        table = table.copy(cells = table.cells + listOf(List(table.columns) { listOf(Block()) }))
        newDoc = doc.withTable(table.id) { table }
        index = (table.rows - 1) * table.columns
    }
    val row = index / table.columns
    val column = index % table.columns
    val blocks = table.cells[row][column]
    val container = CellContainer(table.id, row, column)
    val first = blocks.first()
    val last = blocks.last()
    return EditorState(newDoc, TextSelection(container, Point(first.id, 0), Point(last.id, last.length)))
}

/** Sets the language of a code block. */
fun EditorState.setCodeLanguage(code: Long, language: String): EditorState = copy(doc = doc.withCode(code) { it.copy(language = language) })

/** Sets the title of a code block. */
fun EditorState.setCodeTitle(code: Long, title: String): EditorState = copy(doc = doc.withCode(code) { it.copy(title = title) })

/**
 * Replaces text of a code block, which is plain text.
 *
 * @return The state with the cursor after the new text.
 */
fun EditorState.replaceCode(code: Long, from: Int, to: Int, text: String): EditorState? {
    val block = doc.codeById(code) ?: return null
    val start = minOf(from, to).coerceIn(0, block.text.length)
    val end = maxOf(from, to).coerceIn(0, block.text.length)
    val clean = text.replace("\r\n", "\n").replace('\r', '\n')
    val changed = block.text.substring(0, start) + clean + block.text.substring(end)
    return EditorState(doc.withCode(code) { it.copy(text = changed) }, CodeSelection(code, start + clean.length, start + clean.length))
}

/** Returns the starts of the lines of a code block a range touches. */
private fun lineStarts(text: String, from: Int, to: Int): List<Int> {
    val starts = ArrayList<Int>()
    var offset = text.lastIndexOf('\n', from - 1) + 1
    while (offset <= to) {
        starts += offset
        val next = text.indexOf('\n', offset)
        if (next < 0) break
        offset = next + 1
    }
    return starts
}

/** Indents the selected lines of a code block by four spaces, or inserts four spaces at the cursor. */
fun EditorState.codeIndent(): EditorState? {
    val selection = selection as? CodeSelection ?: return null
    val code = doc.codeById(selection.code) ?: return null
    val from = minOf(selection.anchor, selection.head)
    val to = maxOf(selection.anchor, selection.head)
    if (from == to) return replaceCode(code.id, from, to, "    ")
    var text = code.text
    val starts = lineStarts(text, from, to)
    for (start in starts.asReversed()) text = text.substring(0, start) + "    " + text.substring(start)
    return EditorState(doc.withCode(code.id) { it.copy(text = text) }, CodeSelection(code.id, from + 4, to + 4 * starts.size))
}

/** Removes up to four spaces of indentation from the selected lines of a code block. */
fun EditorState.codeOutdent(): EditorState? {
    val selection = selection as? CodeSelection ?: return null
    val code = doc.codeById(selection.code) ?: return null
    val from = minOf(selection.anchor, selection.head)
    val to = maxOf(selection.anchor, selection.head)
    var text = code.text
    var removed = 0
    var firstRemoved = 0
    for (start in lineStarts(text, from, to).asReversed()) {
        val spaces = text.substring(start, minOf(start + 4, text.length)).takeWhile { it == ' ' }.length
        text = text.substring(0, start) + text.substring(start + spaces)
        removed += spaces
        firstRemoved = spaces
    }
    return EditorState(doc.withCode(code.id) { it.copy(text = text) }, CodeSelection(code.id, maxOf(0, from - firstRemoved), maxOf(0, to - removed)))
}

/** Starts a new line in a code block with the indentation of the current one. */
fun EditorState.codeNewline(): EditorState? {
    val selection = selection as? CodeSelection ?: return null
    val code = doc.codeById(selection.code) ?: return null
    val from = minOf(selection.anchor, selection.head)
    val lineStart = code.text.lastIndexOf('\n', from - 1) + 1
    val indentation = code.text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }.take(maxOf(0, from - lineStart))
    return replaceCode(code.id, from, maxOf(selection.anchor, selection.head), "\n$indentation")
}

/** Turns an empty code block back into a paragraph, for Backspace in it. */
fun EditorState.codeToParagraph(): EditorState? {
    val selection = selection as? CodeSelection ?: return null
    val code = doc.codeById(selection.code)?.takeIf { it.text.isEmpty() } ?: return null
    val paragraph = Block()
    val at = doc.items.indexOfFirst { it.id == code.id }
    return EditorState(Doc(replaceRange(doc.items, at, at + 1, listOf(paragraph))), TextSelection(RootContainer, Point(paragraph.id, 0), Point(paragraph.id, 0)))
}

/** Leaves a code block for a new paragraph after it, for Ctrl+Enter. */
fun EditorState.leaveCode(): EditorState? {
    val selection = selection as? CodeSelection ?: return null
    val at = doc.items.indexOfFirst { it.id == selection.code }
    if (at < 0) return null
    val paragraph = Block()
    return EditorState(Doc(replaceRange(doc.items, at + 1, at + 1, listOf(paragraph))), TextSelection(RootContainer, Point(paragraph.id, 0), Point(paragraph.id, 0)))
}
