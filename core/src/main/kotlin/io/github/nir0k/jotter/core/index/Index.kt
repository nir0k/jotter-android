package io.github.nir0k.jotter.core.index

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.ContentsNote
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.bodyAttachments
import io.github.nir0k.jotter.core.format.bodyNoteLinks
import io.github.nir0k.jotter.core.format.collectionSubtree
import io.github.nir0k.jotter.core.format.isQtSpace
import io.github.nir0k.jotter.core.format.tagKey
import io.github.nir0k.jotter.core.search.Search
import io.github.nir0k.jotter.core.search.SearchScope
import io.github.nir0k.jotter.core.search.SearchTerm
import io.github.nir0k.jotter.core.search.Stemmer
import io.github.nir0k.jotter.core.search.TermMatcher
import io.github.nir0k.jotter.core.search.TextSpan
import io.github.nir0k.jotter.core.search.parseQuery
import io.github.nir0k.jotter.core.search.plainTextFromHtml
import io.github.nir0k.jotter.core.search.snippet
import io.github.nir0k.jotter.core.search.splitWords
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name

/**
 * One note as the note list shows it, read from the index.
 *
 * @param snippet The start of the text on one line; in search results the fragment around the
 *   matches.
 * @param matches In search results, the matching words of the snippet.
 */
data class NoteSummary(
    val id: String,
    val title: String,
    val icon: String?,
    val color: String?,
    val created: Instant,
    val updated: Instant,
    val tags: List<String>,
    val snippet: String,
    val matches: List<TextSpan> = emptyList(),
)

/**
 * Which notes a list shows.
 *
 * @param ids The notes of [View.Notes], or the collections of [View.Collections] with their
 *   descendants, which the caller adds.
 * @param tag The tag of [View.Tag]; its children are included.
 */
data class NoteFilter(val view: View = View.All, val ids: List<String> = emptyList(), val tag: String = "") {
    enum class View {
        /** Every note that is neither archived nor trashed. */
        All,

        /** Active notes in no collection. */
        Unfiled,

        /** The notes listed in `ids`, such as the favourites. */
        Notes,
        Collections,
        Tag,
        Archive,
        Trash,
    }
}

/** The order of a note list. */
data class NoteSort(val key: Key = Key.Updated, val descending: Boolean = true) {
    enum class Key { Updated, Created, Title }
}

/**
 * The tags of active notes with their counts.
 *
 * @param names The tags in the collator's order, each in the spelling it was first used with.
 * @param counts The number of active notes with each tag path or one of its children, by the
 *   path's tag key; parent parts that no note carries itself, such as `work` of `work/urgent`,
 *   are counted too.
 */
data class TagList(val names: List<String>, val counts: Map<String, Int>)

/**
 * A note file the index could not read, for the Problems screen of Settings.
 *
 * @param fileName The file name inside the library's `notes` folder.
 */
data class Problem(val fileName: String, val code: ErrorCode, val detail: String)

/**
 * What a scan changed.
 *
 * @param removed Notes that left the index and problem files that are gone, so the lists and the
 *   Problems screen follow.
 */
data class ScanReport(val added: Int, val updated: Int, val removed: Int)

/** Bumped whenever the tables change; an index with another version is rebuilt. */
private const val SchemaVersion = 5

private const val SnippetLength = 200

/** The words of a search result's fragment, as the desktop's `snippet()` call takes. */
private const val FragmentWords = 24

/** Separates list values inside a group_concat result; it cannot occur in identifiers or tags. */
private const val ListSeparator = '\u001F'

private val Schema = listOf(
    // `seq` is the stable row number of the full-text table; `text` is the note's plain text,
    // stored once, for the fragments of search results.
    "CREATE TABLE notes (" +
        " seq INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, file TEXT NOT NULL UNIQUE," +
        " size INTEGER NOT NULL, mtime INTEGER NOT NULL, title TEXT NOT NULL, icon TEXT, color TEXT," +
        " created INTEGER NOT NULL, updated INTEGER NOT NULL, archived INTEGER, trashed INTEGER," +
        " has_location INTEGER NOT NULL, attachments INTEGER NOT NULL," +
        " snippet TEXT NOT NULL, text TEXT NOT NULL)",
    // The full-text table holds only the index of the stems, written by the Stemmer and split on
    // spaces by the `ascii` tokenizer, so queries stemmed the same way meet every word form.
    "CREATE VIRTUAL TABLE notes_fts USING fts5(title, text, tags, content='', contentless_delete=1," +
        " tokenize='ascii')",
    "CREATE TABLE note_collections (" +
        " note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE," +
        " collection_id TEXT NOT NULL, PRIMARY KEY (note_id, collection_id))",
    "CREATE INDEX note_collections_collection ON note_collections(collection_id)",
    "CREATE TABLE note_tags (" +
        " note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE," +
        " tag TEXT NOT NULL, tag_key TEXT NOT NULL, PRIMARY KEY (note_id, tag_key))",
    "CREATE INDEX note_tags_key ON note_tags(tag_key)",
    // The notes each note links to, for backlinks.
    "CREATE TABLE note_links (" +
        " note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE," +
        " target_id TEXT NOT NULL, PRIMARY KEY (note_id, target_id))",
    "CREATE INDEX note_links_target ON note_links(target_id)",
    // The attachment files each note refers to, by the folder (note) that holds them.
    "CREATE TABLE note_files (" +
        " note_id TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE," +
        " owner TEXT NOT NULL, file TEXT NOT NULL, PRIMARY KEY (note_id, owner, file))",
    "CREATE INDEX note_files_owner ON note_files(owner)",
    // Since when an attachment file is referenced by no note (desktop spec section 3.5); device
    // state, so losing the index only delays a removal.
    "CREATE TABLE unreferenced (owner TEXT NOT NULL, file TEXT NOT NULL, since INTEGER NOT NULL, PRIMARY KEY (owner, file))",
    "CREATE TABLE problems (" +
        " file TEXT PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL," +
        " code TEXT NOT NULL, detail TEXT NOT NULL)",
    // Facts about how the index was built, such as the stemmers' fingerprint.
    "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
)

/** Every table of this and earlier schemas, dropped before the index is built again. */
private val DroppedTables = listOf("notes_fts", "note_links", "note_files", "unreferenced", "note_tags", "note_collections", "notes", "problems", "meta")

/** Weights of the title, text and tags columns in the relevance of a match. */
private const val Relevance = "bm25(notes_fts, 10.0, 1.0, 5.0)"

private const val SummaryColumns =
    "n.id, n.title, n.icon, n.color, n.created, n.updated, n.snippet," +
        // Tags keep the note's order: they are inserted in it each time the note is written.
        " (SELECT group_concat(tag, char(31) ORDER BY rowid) FROM note_tags WHERE note_id = n.id)"

/** A file's size and modification time, which tell a scan whether to read it again. */
private data class FileStamp(val size: Long, val mtime: Long)

/** A condition of a WHERE clause with its bound values in their order. */
private data class Condition(val sql: String, val values: List<Any?> = emptyList())

private fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")

/**
 * Builds a condition on notes with any of the tags or their children; with [prefix], also tags
 * that merely start with one, for a tag still being typed in a query.
 */
private fun tagCondition(tags: List<String>, prefix: Boolean): Condition {
    val parts = mutableListOf<String>()
    val values = mutableListOf<Any?>()
    for (tag in tags) {
        val key = tagKey(tag)
        // substr() counts characters, not UTF-16 code units.
        val length = key.codePointCount(0, key.length)
        if (prefix) {
            parts += "substr(t.tag_key, 1, ?) = ?"
            values.addAll(listOf(length, key))
        } else {
            parts += "t.tag_key = ? OR substr(t.tag_key, 1, ?) = ?"
            values.addAll(listOf(key, length + 1, "$key/"))
        }
    }
    return Condition(
        "EXISTS (SELECT 1 FROM note_tags t WHERE t.note_id = n.id AND (${parts.joinToString(" OR ")}))",
        values,
    )
}

/** Builds a condition on notes in any of the collections. */
private fun collectionCondition(ids: List<String>) = Condition(
    "EXISTS (SELECT 1 FROM note_collections c WHERE c.note_id = n.id AND c.collection_id IN (${placeholders(ids.size)}))",
    ids,
)

/**
 * Builds the WHERE clause of a view.
 *
 * @param includeArchived Whether views of active notes also take archived ones, for a search
 *   with Include archived.
 */
private fun whereClause(filter: NoteFilter, includeArchived: Boolean = false): Condition {
    val active = if (includeArchived) "n.trashed IS NULL" else "n.archived IS NULL AND n.trashed IS NULL"
    return when (filter.view) {
        NoteFilter.View.All -> Condition(active)
        NoteFilter.View.Unfiled ->
            Condition("$active AND NOT EXISTS (SELECT 1 FROM note_collections c WHERE c.note_id = n.id)")
        NoteFilter.View.Notes -> Condition("n.trashed IS NULL AND n.id IN (${placeholders(filter.ids.size)})", filter.ids)
        NoteFilter.View.Collections -> collectionCondition(filter.ids).let { Condition("$active AND ${it.sql}", it.values) }
        NoteFilter.View.Tag -> tagCondition(listOf(filter.tag), false).let { Condition("$active AND ${it.sql}", it.values) }
        NoteFilter.View.Archive -> Condition("n.archived IS NOT NULL AND n.trashed IS NULL")
        NoteFilter.View.Trash -> Condition("n.trashed IS NOT NULL")
    }
}

/** Returns the FTS5 column filter of the scopes, or null when no column is searched. */
private fun ftsColumns(scopes: Set<SearchScope>): String? {
    val columns = listOf(SearchScope.Title to "title", SearchScope.Text to "text", SearchScope.Tags to "tags")
        .filter { it.first in scopes }.map { it.second }
    return if (columns.isEmpty()) null else "{${columns.joinToString(" ")}} : "
}

/** Lists the collections whose names match a term, with their descendants. */
private fun collectionsMatching(term: SearchTerm, collections: List<Collection>): List<String> {
    val matcher = TermMatcher(listOf(term.copy(exclude = false)))
    return collections.filter { matcher.find(it.name).isNotEmpty() }
        .flatMap { collectionSubtree(collections, it.id) }.distinct()
}

/**
 * Builds the fragment of a search result around the first matching words of its text: up to
 * [FragmentWords] words with the matches centred where they fit, on one line, with an ellipsis
 * where the text goes on, as the desktop's FTS5 `snippet()` shows it.
 *
 * @param text The note's plain text.
 * @param matcher The query's matcher.
 * @return The fragment and its matching words, or null when the text holds no match.
 */
internal fun fragment(text: String, matcher: TermMatcher): Pair<String, List<TextSpan>>? {
    val spans = matcher.find(text)
    if (spans.isEmpty()) return null
    val words = splitWords(text)
    val wordAt = words.withIndex().associate { it.value.start to it.index }
    val matched = spans.map { wordAt.getValue(it.start) }
    val first = matched.first()
    val last = matched.last { it < first + FragmentWords }
    val start = (first - (FragmentWords - (last - first + 1)) / 2).coerceIn(0, maxOf(0, words.size - FragmentWords))
    val end = minOf(words.size, start + FragmentWords)

    val out = StringBuilder()
    if (start > 0) out.append('…')
    val positions = IntArray(text.length)
    var space = false
    // A fragment that reaches either end of the text keeps the punctuation there.
    val from = if (start == 0) 0 else words[start].start
    val until = if (end == words.size) text.length else words[end - 1].end
    for (i in from until until) {
        val c = text[i]
        if (c.isQtSpace()) {
            space = true
            continue
        }
        if (space && out.isNotEmpty()) out.append(' ')
        space = false
        positions[i] = out.length
        out.append(c)
    }
    if (end < words.size) out.append('…')
    val inside = matched.zip(spans).filter { it.first in start until end }.map { TextSpan(positions[it.second.start], it.second.length) }
    return out.toString() to inside
}

/**
 * Index is the local search index of a library (desktop spec section 3.8): a SQLite database with
 * FTS5 derived from the note files, used for lists, counts and search. It holds nothing the
 * library does not and is rebuilt whenever its schema or the stemmers change.
 *
 * One connection serves every call; calls are serialised, and the application makes them off the
 * main thread.
 *
 * @param collator The order of titles and tags in lists: the person's locale, ignoring case and
 *   reading numbers by their value.
 */
class Index private constructor(
    private val db: SQLiteConnection,
    private val path: String,
    private val collator: Comparator<String>,
) : AutoCloseable {
    private val stemmer = Stemmer()
    private val lock = Any()

    /** Serialises a call and reports database failures as Io errors of the index file. */
    private fun <T> locked(block: () -> T): T = synchronized(lock) { databaseCall(path, block) }

    /** Returns the stems of a text, separated by spaces, as the full-text table stores them. */
    private fun stemmed(text: String) = stemmer.stems(text).filter { it.isNotEmpty() }.joinToString(" ")

    /** Writes one note with its collections, tags, links and files, replacing what the index had for it. */
    private fun storeNote(note: Note, file: String, stamp: FileStamp) {
        val meta = note.meta
        val text = plainTextFromHtml(note.body)
        val files = bodyAttachments(note.body).map { it.noteId to it.fileName }.toSet()
        val attachments = files.size
        // An upsert keeps the row number the full-text table refers to.
        val seq = db.query(
            "INSERT INTO notes (id, file, size, mtime, title, icon, color, created, updated, archived, trashed," +
                " has_location, attachments, snippet, text) VALUES (${placeholders(15)})" +
                " ON CONFLICT (id) DO UPDATE SET file = excluded.file, size = excluded.size, mtime = excluded.mtime," +
                " title = excluded.title, icon = excluded.icon, color = excluded.color, created = excluded.created," +
                " updated = excluded.updated, archived = excluded.archived, trashed = excluded.trashed," +
                " has_location = excluded.has_location, attachments = excluded.attachments," +
                " snippet = excluded.snippet, text = excluded.text RETURNING seq",
            listOf(
                meta.id, file, stamp.size, stamp.mtime, meta.title, meta.icon, meta.color,
                meta.created.toEpochMilli(), meta.updated.toEpochMilli(), meta.archived?.toEpochMilli(),
                meta.trashed?.toEpochMilli(), meta.location != null, attachments,
                snippet(text, SnippetLength), text,
            ),
        ) { it.getLong(0) }.single()
        db.execute("DELETE FROM notes_fts WHERE rowid = ?", seq)
        db.execute(
            "INSERT INTO notes_fts (rowid, title, text, tags) VALUES (?, ?, ?, ?)",
            seq, stemmed(meta.title), stemmed(text), stemmed(meta.tags.joinToString("\n")),
        )
        db.execute("DELETE FROM note_collections WHERE note_id = ?", meta.id)
        db.execute("DELETE FROM note_tags WHERE note_id = ?", meta.id)
        db.execute("DELETE FROM note_links WHERE note_id = ?", meta.id)
        db.execute("DELETE FROM note_files WHERE note_id = ?", meta.id)
        for ((owner, file) in files) db.execute("INSERT INTO note_files VALUES (?, ?, ?)", meta.id, owner, file)
        for (target in bodyNoteLinks(note.body)) db.execute("INSERT INTO note_links VALUES (?, ?)", meta.id, target)
        for (collection in meta.collections) db.execute("INSERT OR IGNORE INTO note_collections VALUES (?, ?)", meta.id, collection)
        for (tag in meta.tags) db.execute("INSERT OR IGNORE INTO note_tags VALUES (?, ?, ?)", meta.id, tag, tagKey(tag))
    }

    /** Forgets the note stored from a file, with its full-text entry, collections and tags. */
    private fun removeNote(file: String) {
        for (seq in db.query("SELECT seq FROM notes WHERE file = ?", listOf(file)) { it.getLong(0) }) {
            db.execute("DELETE FROM notes_fts WHERE rowid = ?", seq)
            db.execute("DELETE FROM notes WHERE seq = ?", seq)
        }
    }

    /**
     * Brings the index up to date with the library's note files: files whose size or modification
     * time changed are read again, vanished files are removed, and unreadable files are recorded
     * as problems.
     *
     * @return What changed.
     * @throws FormatException Io.
     */
    fun scan(library: Library): ScanReport = locked {
        val stamps = { table: String ->
            db.query("SELECT file, size, mtime FROM $table") { it.getText(0) to FileStamp(it.getLong(1), it.getLong(2)) }.toMap()
        }
        val knownNotes = stamps("notes")
        val knownProblems = stamps("problems")
        var added = 0
        var updated = 0
        var removed = 0
        val present = mutableSetOf<String>()
        db.transaction {
            for (path in library.noteFiles()) {
                val file = path.name
                present += file
                val stamp = try {
                    FileStamp(Files.size(path), Files.getLastModifiedTime(path).toMillis())
                } catch (_: IOException) {
                    continue // The file vanished since it was listed; the next scan sees it gone.
                }
                if (knownNotes[file] == stamp || knownProblems[file] == stamp) continue
                val note = try {
                    library.readNote(file.substringBeforeLast('.'))
                } catch (e: FormatException) {
                    removeNote(file)
                    db.execute("INSERT OR REPLACE INTO problems VALUES (?, ?, ?, ?, ?)", file, stamp.size, stamp.mtime, e.code.name, e.detail)
                    if (file in knownNotes) removed++
                    continue
                }
                storeNote(note, file, stamp)
                db.execute("DELETE FROM problems WHERE file = ?", file)
                if (file in knownNotes) updated++ else added++
            }
            for (file in knownNotes.keys - present) {
                removeNote(file)
                removed++
            }
            for (file in knownProblems.keys - present) {
                db.execute("DELETE FROM problems WHERE file = ?", file)
                removed++
            }
        }
        ScanReport(added, updated, removed)
    }

    /**
     * Removes everything from the index, for Rebuild index; the next scan reads every file again.
     *
     * @throws FormatException Io.
     */
    fun clear(): Unit = locked {
        db.transaction {
            for (table in listOf("notes_fts", "note_tags", "note_collections", "notes", "problems")) db.execute("DELETE FROM $table")
        }
    }

    /** Reads a summary from the columns of [SummaryColumns]. */
    private fun summary(row: SQLiteStatement): NoteSummary {
        fun optional(i: Int) = if (row.isNull(i)) null else row.getText(i)
        return NoteSummary(
            id = row.getText(0),
            title = row.getText(1),
            icon = optional(2),
            color = optional(3),
            created = Instant.ofEpochMilli(row.getLong(4)),
            updated = Instant.ofEpochMilli(row.getLong(5)),
            snippet = row.getText(6),
            tags = if (row.isNull(7)) emptyList() else row.getText(7).split(ListSeparator),
        )
    }

    /** Lists the notes matching a condition in the order of a list. */
    private fun listNotes(where: Condition, sort: NoteSort): List<NoteSummary> {
        val order = if (sort.key == NoteSort.Key.Created) "n.created" else "n.updated"
        val direction = if (sort.descending) "DESC" else "ASC"
        val list = db.query("SELECT $SummaryColumns FROM notes n WHERE ${where.sql} ORDER BY $order $direction, n.id", where.values, ::summary)
        if (sort.key != NoteSort.Key.Title) return list
        // SQLite folds only ASCII case, so titles are ordered here by the collator.
        val byTitle = compareBy(collator, NoteSummary::title)
        return list.sortedWith(if (sort.descending) byTitle.reversed() else byTitle)
    }

    /**
     * Lists the notes of a view in the given order.
     *
     * @throws FormatException Io.
     */
    fun notes(filter: NoteFilter, sort: NoteSort): List<NoteSummary> = locked { listNotes(whereClause(filter), sort) }

    /**
     * Searches the notes of a view. Every term must match in one of the scopes, in any word
     * form; excluded terms must not; `#tag` terms and the filters narrow the notes further.
     *
     * @param filter The view searched; [Search.includeArchived] adds its archived notes.
     * @param collections The collection tree, for collection names and the descendants of the
     *   collection filter.
     * @param sort The order when nothing ranks the notes: a query without words to find.
     * @return Notes ranked by relevance (BM25), then by updated time, with the fragment of their
     *   text around the matches.
     * @throws FormatException Io.
     */
    fun search(filter: NoteFilter, search: Search, collections: List<Collection>, sort: NoteSort): List<NoteSummary> = locked {
        val base = whereClause(filter, search.includeArchived)
        val where = StringBuilder(base.sql)
        val values = base.values.toMutableList()
        fun require(condition: Condition) {
            where.append(" AND ").append(condition.sql)
            values.addAll(condition.values)
        }
        for ((time, sql) in listOf(
            search.createdFrom to "n.created >= ?", search.createdUntil to "n.created < ?",
            search.updatedFrom to "n.updated >= ?", search.updatedUntil to "n.updated < ?",
        )) {
            if (time != null) require(Condition(sql, listOf(time.toEpochMilli())))
        }
        if (search.collections.isNotEmpty()) {
            require(collectionCondition(search.collections.flatMap { collectionSubtree(collections, it) }.distinct()))
        }
        if (search.tags.isNotEmpty()) require(tagCondition(search.tags, false))
        if (search.withAttachments) require(Condition("n.attachments > 0"))
        if (search.withLocation) require(Condition("n.has_location = 1"))

        // Every term must match somewhere in the chosen scopes: in the full-text columns or in
        // the name of one of the note's collections.
        val columns = ftsColumns(search.scopes)
        val terms = parseQuery(search.query)
        val ranked = mutableListOf<String>()
        for (term in terms) {
            val match = if (term.kind == SearchTerm.Kind.Tag) {
                tagCondition(listOf(term.text), term.prefix)
            } else {
                val parts = mutableListOf<String>()
                val bound = mutableListOf<Any?>()
                val stems = stemmer.stems(term.text).filter { it.isNotEmpty() }
                if (columns != null && stems.isNotEmpty()) {
                    val expression = columns + "\"" + stems.joinToString(" ") + "\"" + if (term.prefix) "*" else ""
                    parts += "n.seq IN (SELECT rowid FROM notes_fts WHERE notes_fts MATCH ?)"
                    bound += expression
                    if (!term.exclude) ranked += expression
                }
                if (SearchScope.Collections in search.scopes) {
                    val ids = collectionsMatching(term, collections)
                    if (ids.isNotEmpty()) {
                        val condition = collectionCondition(ids)
                        parts += condition.sql
                        bound.addAll(condition.values)
                    }
                }
                Condition(if (parts.isEmpty()) "0" else parts.joinToString(" OR ", "(", ")"), bound)
            }
            require(if (term.exclude) Condition("NOT ${match.sql}", match.values) else match)
        }
        // Filters alone have no relevance: the notes keep the list's order.
        if (ranked.isEmpty()) return@locked listNotes(Condition(where.toString(), values), sort)

        val matcher = TermMatcher(terms)
        db.query(
            "SELECT $SummaryColumns, n.text FROM notes n LEFT JOIN (SELECT rowid, $Relevance AS rank FROM notes_fts" +
                " WHERE notes_fts MATCH ?) r ON r.rowid = n.seq WHERE $where ORDER BY r.rank IS NULL, r.rank, n.updated DESC, n.id",
            listOf(ranked.joinToString(") OR (", "(", ")")) + values,
        ) { row ->
            val note = summary(row)
            // A note found by a collection name or with matches only in its title keeps the start
            // of its text.
            fragment(row.getText(8), matcher)?.let { (text, matches) -> note.copy(snippet = text, matches = matches) } ?: note
        }
    }

    /**
     * Lists the notes that link to a note, most recently updated first; the note itself is left
     * out.
     *
     * @param includeTrashed Whether notes in the trash count too: not for the information sheet,
     *   but for renaming the links when the note's title changes.
     * @throws FormatException Io.
     */
    fun backlinks(id: String, includeTrashed: Boolean): List<NoteSummary> = locked {
        val trash = if (includeTrashed) "" else " AND n.trashed IS NULL"
        listNotes(Condition("n.id != ? AND n.id IN (SELECT note_id FROM note_links WHERE target_id = ?)$trash", listOf(id, id)), NoteSort())
    }

    /**
     * Lists the notes the table of contents shows (desktop spec section 3.6): every note outside
     * the trash with its tags in the note's order, its collections and whether it is archived,
     * ordered by title as the lists order them.
     *
     * @throws FormatException Io.
     */
    fun contentsNotes(): List<ContentsNote> = locked {
        val sql = "SELECT n.id, n.title, n.updated, n.archived IS NOT NULL," +
            " (SELECT group_concat(tag, char(31) ORDER BY rowid) FROM note_tags WHERE note_id = n.id)," +
            " (SELECT group_concat(collection_id, char(31) ORDER BY rowid) FROM note_collections WHERE note_id = n.id)" +
            " FROM notes n WHERE n.trashed IS NULL"
        db.query(sql) { row ->
            fun list(i: Int) = if (row.isNull(i)) emptyList() else row.getText(i).split(ListSeparator)
            ContentsNote(row.getText(0), row.getText(1), Instant.ofEpochMilli(row.getLong(2)), list(4), list(5), row.getLong(3) != 0L)
        }.sortedWith(compareBy(collator, ContentsNote::title))
    }

    /**
     * Lists the notes in the trash since before a time, whose retention period is over.
     *
     * @throws FormatException Io.
     */
    fun trashedBefore(limit: Instant): List<String> = locked {
        db.query("SELECT id FROM notes WHERE trashed IS NOT NULL AND trashed < ?", listOf(limit.toEpochMilli())) { it.getText(0) }
    }

    /**
     * Lists the notes in any of some collections, in any state: archived and trashed ones too.
     *
     * @throws FormatException Io.
     */
    fun notesInCollections(ids: List<String>): List<String> = locked {
        if (ids.isEmpty()) return@locked emptyList()
        db.query("SELECT DISTINCT note_id FROM note_collections WHERE collection_id IN (${placeholders(ids.size)})", ids) { it.getText(0) }
    }

    /**
     * Lists the notes with a tag or one of its children, in any state.
     *
     * @throws FormatException Io.
     */
    fun notesTagged(tag: String): List<String> = locked {
        val condition = tagCondition(listOf(tag), false)
        db.query("SELECT n.id FROM notes n WHERE ${condition.sql}", condition.values) { it.getText(0) }
    }

    /**
     * Follows which files of a note's attachment folder no note refers to (desktop spec section
     * 3.5): a file found unreferenced is remembered with the time, a file referenced again is
     * forgotten. The folder of a note whose file cannot be read is left alone, as its references
     * are unknown.
     *
     * @param owner The note whose folder it is.
     * @param present The files in the folder.
     * @param now The current time.
     * @return The files unreferenced since before [expired], to be removed.
     * @throws FormatException Io.
     */
    fun unreferencedFiles(owner: String, present: List<String>, now: Instant, expired: Instant): List<String> = locked {
        val unreadable = db.query("SELECT 1 FROM problems WHERE file = ?", listOf("$owner.html")) { true }.isNotEmpty()
        if (unreadable) return@locked emptyList()
        val referenced = db.query("SELECT file FROM note_files WHERE owner = ?", listOf(owner)) { it.getText(0) }.toSet()
        val since = db.query("SELECT file, since FROM unreferenced WHERE owner = ?", listOf(owner)) { it.getText(0) to it.getLong(1) }.toMap()
        db.execute("DELETE FROM unreferenced WHERE owner = ?", owner)
        val due = mutableListOf<String>()
        for (file in present.filter { it !in referenced }) {
            val first = since[file] ?: now.toEpochMilli()
            if (first < expired.toEpochMilli()) due += file else db.execute("INSERT INTO unreferenced VALUES (?, ?, ?)", owner, file, first)
        }
        due
    }

    /**
     * Tells which of some notes exist, in any state, so links to the others are drawn as missing.
     *
     * @throws FormatException Io.
     */
    fun existing(ids: Set<String>): Set<String> = locked {
        if (ids.isEmpty()) return@locked emptySet()
        db.query("SELECT id FROM notes WHERE id IN (${placeholders(ids.size)})", ids.toList()) { it.getText(0) }.toSet()
    }

    /**
     * Lists the notes the note picker offers for a link: with an empty query the most recently
     * updated ones, otherwise those whose titles match in any word form, archived ones included;
     * never trashed notes or the note being edited.
     *
     * @param exclude The note being edited.
     * @param limit The most notes listed.
     * @throws FormatException Io.
     */
    fun linkCandidates(query: String, exclude: String, limit: Int): List<NoteSummary> {
        if (parseQuery(query).isNotEmpty()) {
            val found = search(NoteFilter(), Search(query, setOf(SearchScope.Title), includeArchived = true), emptyList(), NoteSort())
            return found.filter { it.id != exclude }.take(limit)
        }
        return locked {
            db.query(
                "SELECT $SummaryColumns FROM notes n WHERE n.trashed IS NULL AND n.id != ? ORDER BY n.updated DESC, n.id LIMIT ?",
                listOf(exclude, limit),
                ::summary,
            )
        }
    }

    /**
     * Counts the notes of a view without listing them.
     *
     * @throws FormatException Io.
     */
    fun count(filter: NoteFilter): Int = locked {
        val where = whereClause(filter)
        db.query("SELECT COUNT(*) FROM notes n WHERE ${where.sql}", where.values) { it.getLong(0).toInt() }.single()
    }

    /**
     * Returns when a note of the library last changed, as the notes themselves say: the time of a
     * file on this device would be that of the download for a note synchronised here.
     *
     * @return The newest updated time of the notes, the archive and the trash included, or null
     *   without notes.
     * @throws FormatException Io.
     */
    fun lastUpdated(): Instant? = locked {
        db.query("SELECT MAX(updated) FROM notes", emptyList()) { row -> if (row.isNull(0)) null else Instant.ofEpochMilli(row.getLong(0)) }.single()
    }

    /**
     * Counts the active notes of every collection with its descendants, as a collection view lists
     * them: a note in several collections of one subtree counts once.
     *
     * @return The counts by collection identifier, collections without notes included.
     * @throws FormatException Io.
     */
    fun collectionCounts(collections: List<Collection>): Map<String, Int> = locked {
        val notes = db.query(
            "SELECT c.collection_id, n.seq FROM note_collections c JOIN notes n ON n.id = c.note_id" +
                " WHERE n.archived IS NULL AND n.trashed IS NULL",
        ) { it.getText(0) to it.getLong(1) }.groupBy({ it.first }, { it.second })
        collections.associate { collection ->
            collection.id to collectionSubtree(collections, collection.id).flatMapTo(mutableSetOf()) { notes[it].orEmpty() }.size
        }
    }

    /**
     * Lists the tags of notes that are neither archived nor trashed with the number of notes of
     * each tag path, as the Tag view lists them: a note with several tags under one path counts
     * once.
     *
     * @throws FormatException Io.
     */
    fun tags(): TagList = locked {
        // Oldest notes first, so each tag keeps the spelling it was first used with.
        val rows = db.query(
            "SELECT t.tag_key, t.tag, n.seq FROM note_tags t JOIN notes n ON n.id = t.note_id" +
                " WHERE n.archived IS NULL AND n.trashed IS NULL ORDER BY n.created, n.id",
        ) { Triple(it.getText(0), it.getText(1), it.getLong(2)) }
        val names = rows.groupBy({ it.first }, { it.second }).values.map { it.first() }.sortedWith(collator)
        // Keys are case-folded per code point, so the key of a parent path is a prefix of its child's.
        val notes = HashMap<String, MutableSet<Long>>()
        for ((key, _, seq) in rows) {
            var end = key.length
            while (end > 0) {
                notes.getOrPut(key.substring(0, end), ::HashSet) += seq
                end = key.lastIndexOf('/', end - 1)
            }
        }
        TagList(names, notes.mapValues { it.value.size })
    }

    /**
     * Lists the note files that could not be read, by file name.
     *
     * @throws FormatException Io.
     */
    fun problems(): List<Problem> = locked {
        db.query("SELECT file, code, detail FROM problems ORDER BY file") {
            Problem(it.getText(0), ErrorCode.valueOf(it.getText(1)), it.getText(2))
        }
    }

    /** Closes the database connection. */
    override fun close() = synchronized(lock) { db.close() }

    companion object {
        /**
         * Opens or creates the index at a path, rebuilding it when its schema or the stemmers'
         * fingerprint is outdated: the index is derived data, filled again by a scan.
         *
         * @param path The database file; missing folders are created.
         * @param collator The order of titles and tags in lists.
         * @throws FormatException Io.
         */
        fun open(path: Path, collator: Comparator<String>): Index {
            val stems = Stemmer().fingerprint()
            val db = openDatabase(
                path,
                listOf("journal_mode = WAL", "foreign_keys = ON", "busy_timeout = 5000", "synchronous = NORMAL"),
                SchemaVersion,
                DroppedTables,
                Schema,
                // Words stemmed differently than when they were indexed could not be removed again.
                current = { query("SELECT value FROM meta WHERE key = 'stems'") { it.getText(0) }.singleOrNull() == stems },
                seed = { execute("INSERT INTO meta VALUES ('stems', ?)", stems) },
            )
            return Index(db, path.toAbsolutePath().toString(), collator)
        }
    }
}
