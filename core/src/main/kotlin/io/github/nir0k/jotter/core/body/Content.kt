package io.github.nir0k.jotter.core.body

import io.github.nir0k.jotter.core.format.attachmentSource
import io.github.nir0k.jotter.core.format.noteLinkTarget

// Images and attachment chips, find, the counts of the information sheet, note links and the clipboard: what is copied and how a paste goes in.

/**
 * A file stored in the note's folder, to insert.
 *
 * @param image Whether it is shown as an image rather than as an attachment chip.
 */
data class InsertedFile(val file: String, val size: Long, val image: Boolean)

/** Returns the selected image or chip: a selection of exactly one object. */
fun EditorState.selectedObject(): InlineObject? {
    val range = textRange() ?: return null
    if (range.to - range.from != 1) return null
    val (index, offset) = range.run.locate(range.from)
    val block = range.run.blocks[index]
    if (offset >= block.length) return null
    return slice(block.inline, offset, offset + 1).singleOrNull() as? InlineObject
}

/** Inserts stored files at the cursor, separated by spaces, as images or chips; not in a code block. */
fun EditorState.insertFiles(note: String, files: List<InsertedFile>): EditorState? {
    if (files.isEmpty()) return null
    val range = textRange() ?: return null
    val pieces = ArrayList<Inline>()
    val format = cursorFormat()
    for (file in files) {
        if (pieces.isNotEmpty()) pieces += TextRun(" ", format)
        pieces += if (file.image) ImageObject(note, file.file) else AttachmentObject(note, file.file, file.size, file.file)
    }
    return insertInline(range, pieces)
}

/** Replaces a range of a run with inline content; the cursor goes after it. */
fun EditorState.insertInline(range: RunRange, pieces: List<Inline>): EditorState? {
    val cleared = replaceText(range.run.container, range.run.key, range.from, range.to, "") ?: return null
    val at = cleared.textRange() ?: return null
    val (index, offset) = at.run.locate(at.from)
    val block = at.run.blocks[index]
    val changed = block.withInline(slice(block.inline, 0, offset) + pieces + slice(block.inline, offset, block.length))
    val doc = cleared.doc.mapBlocks(at.run.container, setOf(block.id)) { changed }
    val point = Point(block.id, offset + pieces.sumOf { it.length })
    return cleared.copy(doc = doc, selection = TextSelection(at.run.container, point, point))
}

/** Replaces the selected object, keeping it selected. */
private fun EditorState.replaceSelectedObject(change: (InlineObject) -> InlineObject): EditorState? {
    val item = selectedObject() ?: return null
    val range = textRange()!!
    val (index, offset) = range.run.locate(range.from)
    val block = range.run.blocks[index]
    val changed = block.withInline(slice(block.inline, 0, offset) + change(item) + slice(block.inline, offset + 1, block.length))
    return copy(doc = doc.mapBlocks(range.run.container, setOf(block.id)) { changed })
}

/**
 * Changes the selected image's width (10 to 99 percent, 0 for full width) or wrapping;
 * wrapping a full-width image square makes it Medium, as no text would fit beside it. The
 * image stays selected.
 */
fun EditorState.setImage(width: Int? = null, wrap: String? = null): EditorState? {
    if (selectedObject() !is ImageObject) return null
    return replaceSelectedObject { item ->
        item as ImageObject
        var changed = item.copy(width = width ?: item.width, wrap = wrap ?: item.wrap)
        if (wrap == "square" && changed.width == 0) changed = changed.copy(width = 50)
        changed
    }
}

/**
 * Moves the selected image or chip to a place in the text, in any run of the document, table cells
 * included; it keeps its file, width and wrapping and stays selected.
 *
 * @param target A cursor where the object goes; a cursor at either side of the object or on a
 * divider moves nothing.
 * @return The state with the object moved, or null when nothing moves.
 */
fun EditorState.moveSelectedObject(target: TextSelection): EditorState? {
    val item = selectedObject() ?: return null
    val source = textRange()!!
    val (sourceIndex, sourceOffset) = source.run.locate(source.from)
    val sourceBlock = source.run.blocks[sourceIndex].id
    val point = target.head
    if (doc.block(target.container, point.block)?.hr != false) return null
    if (point.block == sourceBlock && (point.offset == sourceOffset || point.offset == sourceOffset + 1)) return null
    val removed = deleteSelection() ?: return null
    // Removing the object shortens its block, so a place after it in the same block moves back.
    val place = if (point.block == sourceBlock && point.offset > sourceOffset) point.copy(offset = point.offset - 1) else point
    val run = removed.doc.runOf(target.container, place.block) ?: return null
    val at = run.offsetOf(place) ?: return null
    val inserted = removed.insertInline(RunRange(run, at, at), listOf(item)) ?: return null
    val after = (inserted.selection as TextSelection).head
    return inserted.copy(selection = TextSelection(target.container, after.copy(offset = after.offset - 1), after))
}

/** Removes the selected image or chip; its file stays until it has been unreferenced for 30 days. */
fun EditorState.removeObject(): EditorState? {
    if (selectedObject() == null) return null
    return deleteSelection()
}

/** Calls a function for every block of the document with its container, in document order. */
fun Doc.forEachBlock(visit: (Container, Block) -> Unit) {
    for (item in items) {
        when (item) {
            is Block -> visit(RootContainer, item)
            is Table -> item.cells.forEachIndexed { r, row -> row.forEachIndexed { c, cell -> cell.forEach { visit(CellContainer(item.id, r, c), it) } } }
            is CodeBlock -> Unit
        }
    }
}

/** Returns the document with every block changed; unchanged blocks stay the same objects. */
fun Doc.mapAllBlocks(change: (Block) -> Block): Doc = Doc(items.map { item ->
    when (item) {
        is Block -> change(item)
        is Table -> item.copy(cells = item.cells.map { row -> row.map { cell -> cell.map(change) } })
        is CodeBlock -> item
    }
})

/** Returns the files of the note's images in body order, each once, as the full-screen viewer walks them. */
fun Doc.noteImages(note: String): List<String> {
    val files = LinkedHashSet<String>()
    forEachBlock { _, block ->
        block.inline.forEach { if (it is ImageObject && it.pending.isEmpty() && it.note == note) files += it.file }
    }
    return files.toList()
}

/**
 * Puts a stored file in place of the objects waiting for it under a token: an image keeps its
 * width and wrapping and a chip its name; a picture stored as an attachment, as one too large to
 * show, becomes a chip. Without a file the objects go.
 */
fun Doc.resolvePending(token: String, note: String, stored: InsertedFile?): Doc = mapAllBlocks { block ->
    if (block.inline.none { it is InlineObject && it.pending == token }) return@mapAllBlocks block
    block.withInline(block.inline.mapNotNull { piece ->
        if (piece !is InlineObject || piece.pending != token) return@mapNotNull piece
        when {
            stored == null -> null
            stored.image -> if (piece is ImageObject) ImageObject(note, stored.file, piece.width, piece.wrap) else ImageObject(note, stored.file)
            else -> AttachmentObject(note, stored.file, stored.size, if (piece is AttachmentObject) piece.name else stored.file)
        }
    })
}

/**
 * A match of find in the note.
 *
 * @param container Where the block is, or null for a code block.
 * @param item The block or the code block.
 */
data class Match(val container: Container?, val item: Long, val from: Int, val to: Int)

/**
 * Finds the matches of a query, ignoring case, in document order; matches lie within one block of
 * text or one code block, as the desktop's do. A block whose text changes length when lower-cased
 * is skipped.
 */
fun Doc.findMatches(query: String): List<Match> {
    if (query.isEmpty()) return emptyList()
    val needle = query.lowercase()
    val matches = ArrayList<Match>()
    fun search(container: Container?, id: Long, text: String) {
        val haystack = text.lowercase()
        if (haystack.length != text.length) return
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            matches += Match(container, id, i, i + needle.length)
            i = haystack.indexOf(needle, i + maxOf(1, needle.length))
        }
    }
    for (item in items) {
        when (item) {
            is Block -> search(RootContainer, item.id, item.text)
            is CodeBlock -> search(null, item.id, item.text)
            is Table -> item.cells.forEachIndexed { r, row ->
                row.forEachIndexed { c, cell -> cell.forEach { search(CellContainer(item.id, r, c), it.id, it.text) } }
            }
        }
    }
    return matches
}

/** Returns the selection of a match. */
fun Match.selection(): Selection =
    if (container == null) CodeSelection(item, from, to) else TextSelection(container, Point(item, from), Point(item, to))

/** The counts the information sheet shows. */
data class TextCounts(val words: Int, val characters: Int, val attachments: Int)

/**
 * Counts the words, characters and attached files of a document as the desktop's information
 * panel does: a word is a run of letters, digits and apostrophes; characters are UTF-16 code units
 * without line breaks; a file shown twice counts once.
 */
fun Doc.counts(): TextCounts {
    var words = 0
    var characters = 0
    val files = HashSet<String>()
    fun count(text: String) {
        var inWord = false
        for (c in text) {
            // Lines of a code block are blocks of their own on the desktop.
            if (c == '\n') {
                inWord = false
                continue
            }
            characters++
            val letter = c.isLetterOrDigit() || c == '\'' || c == '’'
            if (letter && !inWord) words++
            inWord = letter
        }
    }
    for (item in items) if (item is CodeBlock) count(item.text)
    forEachBlock { _, block ->
        var text = StringBuilder()
        for (piece in block.inline) {
            if (piece is TextRun) {
                text.append(piece.text)
            } else {
                count(text.toString())
                text = StringBuilder()
                piece as InlineObject
                if (piece.pending.isEmpty()) files += "${piece.note}/${piece.file}"
            }
        }
        count(text.toString())
    }
    return TextCounts(words, characters, files.size)
}

/** Returns the notes the document links to, each once. */
fun Doc.noteLinkTargets(): Set<String> {
    val ids = LinkedHashSet<String>()
    forEachBlock { _, block ->
        block.inline.forEach { piece -> (piece as? TextRun)?.format?.href?.let(::noteLinkTarget)?.let(ids::add) }
    }
    return ids
}

/** Tells whether a document is an empty body: one paragraph with nothing in it. */
fun Doc.isEmptyBody(): Boolean = items.size == 1 && (items[0] as? Block)?.isEmptyParagraph == true

/** Returns the selected part of the document as a document of its own, for copying; null for a cursor. */
fun EditorState.selectedFragment(): Doc? {
    when (val selection = selection) {
        is CodeSelection -> {
            val code = doc.codeById(selection.code) ?: return null
            val from = minOf(selection.anchor, selection.head)
            val to = maxOf(selection.anchor, selection.head)
            if (from == to) return null
            return Doc(listOf(Block(inline = listOf(TextRun(code.text.substring(from, to).replace('\n', LineSeparator), TextFormat.Plain)))))
        }
        is TextSelection -> {
            val range = textRange() ?: return null
            if (range.collapsed) return null
            val (first, firstOffset) = range.run.locate(range.from)
            val (last, lastOffset) = range.run.locate(range.to)
            val blocks = (first..last).map { i ->
                val block = range.run.blocks[i]
                block.withInline(slice(block.inline, if (i == first) firstOffset else 0, if (i == last) lastOffset else block.length))
            }
            return Doc(blocks)
        }
    }
}

/** Returns the plain text of a document, blocks on lines of their own, as other applications get it. */
fun Doc.plainText(): String = items.joinToString("\n") { item ->
    when (item) {
        is Block -> item.inline.filterIsInstance<TextRun>().joinToString("") { it.text }.replace(LineSeparator, '\n')
        is CodeBlock -> item.text
        is Table -> item.cells.joinToString("\n") { row -> row.joinToString("\t") { cell -> Doc(cell).plainText().replace('\n', ' ') } }
    }
}

/**
 * A pasted object to store in the open note's folder before it is part of the note.
 *
 * @param token What the object waits under in `pending`.
 * @param source A `data:` address or the address of another note's file.
 */
data class StoreRequest(val token: String, val source: String, val image: Boolean)

/**
 * Prepares pasted content: pictures in `data:` addresses and files of other notes become objects
 * waiting under a token, which the application stores in this note's folder.
 *
 * @param token Returns a new token.
 * @return The content and what to store.
 */
fun Doc.withPendingObjects(note: String, token: () -> String): Pair<Doc, List<StoreRequest>> {
    val requests = ArrayList<StoreRequest>()
    val changed = mapAllBlocks { block ->
        if (block.inline.none { it is InlineObject }) return@mapAllBlocks block
        block.copy(inline = block.inline.map { piece ->
            if (piece !is InlineObject) return@map piece
            val source = when {
                piece.pending.startsWith("data:") -> piece.pending
                piece.pending.isEmpty() && piece.note.isNotEmpty() && piece.note != note -> attachmentSource(piece.note, piece.file)
                else -> return@map piece
            }
            val name = token()
            requests += StoreRequest(name, source, piece is ImageObject)
            when (piece) {
                is ImageObject -> piece.copy(pending = name)
                is AttachmentObject -> piece.copy(pending = name)
            }
        })
    }
    return changed to requests
}

/**
 * Pastes content at the selection as the desktop inserts a fragment: a single paragraph goes into
 * the current block. Otherwise a first pasted paragraph joins the text before the cursor in the
 * current block's format, and the last pasted block, unless a divider, takes the text after the
 * cursor in its own format; a list item, a divider or a frame at the start, and a divider or a
 * frame at the end, stay apart from the current block's text. In a table cell frames become
 * paragraphs, as cells hold none; in a code block the content's plain text is inserted.
 */
fun EditorState.paste(content: Doc): EditorState? {
    (selection as? CodeSelection)?.let { return replaceCode(it.code, it.anchor, it.head, content.plainText()) }
    val single = content.items.singleOrNull() as? Block
    val range = textRange() ?: return null
    if (single != null && single.list == null && !single.hr) return insertInline(range, single.inline)
    val cleared = replaceText(range.run.container, range.run.key, range.from, range.to, "") ?: return null
    val at = cleared.textRange() ?: return null
    val container = at.run.container
    val (index, offset) = at.run.locate(at.from)
    val block = at.run.blocks[index]
    val pasted: List<Item> = if (container == RootContainer) content.items else content.items.flatMap { item ->
        when (item) {
            is Block -> listOf(item)
            is CodeBlock -> item.text.split('\n').map { Block(inline = normalized(listOf(TextRun(it, TextFormat.Plain)))) }
            is Table -> item.cells.flatten().flatten()
        }
    }
    if (pasted.isEmpty()) return cleared
    // A run keeps its key while its first block lives: a paste at the start of a block takes the block's
    // identifier, which goes to the block after a pasted frame when the paste starts with one.
    val keyAfter = offset == 0 && pasted.first() !is Block
    val halves = pasted.mapIndexed { i, item ->
        if (item !is Block) item else item.copy(id = if (i == 0 && offset == 0) block.id else newId())
    }.toMutableList()
    if (offset > 0) {
        val before = slice(block.inline, 0, offset)
        val first = halves.first()
        if (first is Block && first.list == null && !first.hr) halves[0] = block.withInline(normalized(before + first.inline))
        else halves.add(0, block.withInline(normalized(before)))
    }
    // The cursor goes after the pasted content: into the last pasted block, before the text after the cursor.
    val lastIndex = halves.lastIndex
    val last = halves[lastIndex]
    val cursor = (last as? Block)?.length ?: 0
    if (offset < block.length) {
        val after = slice(block.inline, offset, block.length)
        if (last is Block && !last.hr) {
            halves[lastIndex] = last.copy(id = if (keyAfter) block.id else last.id, inline = normalized(last.inline + after))
        } else {
            halves += Block(id = if (keyAfter) block.id else newId(), heading = block.heading, quote = block.quote, list = block.list, inline = normalized(after))
        }
    }
    val items = cleared.doc.itemsIn(container)
    val replaced = replaceRange(items, at.run.start + index, at.run.start + index + 1, halves)
    val newDoc = cleared.doc.withItemsIn(container, if (container == RootContainer) framed(replaced) else replaced)
    // After a pasted frame the cursor goes to the start of the block after it.
    val selection = when (val target = halves[lastIndex]) {
        is Block -> TextSelection(container, Point(target.id, cursor), Point(target.id, cursor))
        else -> {
            val list = newDoc.itemsIn(container)
            val after = list.getOrNull(list.indexOfFirst { it.id == target.id } + 1)
            if (after is Block) TextSelection(container, Point(after.id, 0), Point(after.id, 0)) else endOf(target)
        }
    }
    return EditorState(newDoc, selection, null)
}
