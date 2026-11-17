package io.github.nir0k.jotter.core.organize

import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.ErrorCode
import io.github.nir0k.jotter.core.format.FavoriteRef
import io.github.nir0k.jotter.core.format.FormatException
import io.github.nir0k.jotter.core.format.Library
import io.github.nir0k.jotter.core.format.LibraryInfo
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.format.Rule
import io.github.nir0k.jotter.core.format.Tombstone
import io.github.nir0k.jotter.core.format.collectionSubtree
import io.github.nir0k.jotter.core.format.copyNoteAttachments
import io.github.nir0k.jotter.core.format.fileTransaction
import io.github.nir0k.jotter.core.format.isTagWithin
import io.github.nir0k.jotter.core.format.isValidId
import io.github.nir0k.jotter.core.format.listDirectory
import io.github.nir0k.jotter.core.format.mergeTags
import io.github.nir0k.jotter.core.format.newId
import io.github.nir0k.jotter.core.format.now
import io.github.nir0k.jotter.core.format.removePath
import io.github.nir0k.jotter.core.format.renamedTag
import io.github.nir0k.jotter.core.format.tagKey
import io.github.nir0k.jotter.core.format.unexpiredTombstones
import io.github.nir0k.jotter.core.index.Index
import io.github.nir0k.jotter.core.index.NoteFilter
import io.github.nir0k.jotter.core.index.NoteSort
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Duration
import java.time.Instant
import kotlin.io.path.name

/** How long an attachment file stays after the last reference to it went (desktop spec section 3.5). */
val UnreferencedPeriod: Duration = Duration.ofDays(30)

/**
 * Organizer changes how a library's notes are organised: their collections, tags, favourites,
 * archive and trash, the collection tree and the tags themselves, and removes what the library
 * keeps no longer: trashed notes after the retention period and attachment files no note refers
 * to. Notes created or given other tags are filed by the rules. Every change writes the files
 * atomically; the caller scans the index afterwards so lists follow.
 *
 * @param library The open library.
 * @param index Its index, for the notes a change touches.
 */
class Organizer(private val library: Library, private val index: Index) {
    // Read once per operation, and only by a change that runs the rules. A rules file that cannot
    // be read files nothing rather than stopping notes from being saved.
    private val rules by lazy {
        try {
            library.readRules()
        } catch (_: FormatException) {
            emptyList()
        }
    }
    private val collectionIds by lazy { library.readCollections().map { it.id }.toSet() }

    /** What the rules changed during this organizer's operations, announced with Undo. */
    val filed = mutableListOf<RuleChange>()

    /**
     * Runs the rules on a note that is new or whose tags changed, compared without case and order,
     * and records what they changed.
     *
     * @param before The note's tags before the change, or null for a new note.
     * @return The note's metadata with the rules' changes.
     */
    private fun file(before: List<String>?, meta: NoteMeta): NoteMeta {
        if (before != null && before.map(::tagKey).toSet() == meta.tags.map(::tagKey).toSet()) return meta
        if (rules.isEmpty()) return meta
        val filedMeta = applyRules(rules, before, meta, collectionIds)
        RuleChange.of(meta, filedMeta)?.let(filed::add)
        return filedMeta
    }

    /**
     * Writes a note as its editor saves it or as it is created, filed by the rules when it is new
     * or its tags changed.
     *
     * @param before The note as last written, or null for a new note.
     * @param expected The file's contents the change is based on, or null to replace whatever is there.
     * @return The note as written.
     * @throws FormatException When the note cannot be written; Deferred when the file is another
     *   version than [expected], which leaves it as it is.
     */
    fun writeNote(note: Note, before: NoteMeta?, expected: ByteArray? = null): Note {
        val written = note.copy(meta = file(before?.tags, note.meta))
        library.writeNote(written, expected)
        return written
    }

    /**
     * Writes a new note that copies another's body, with its own duplicates of the files the body
     * refers to, as a conflict or normalised copy is. The sources stay as they are.
     *
     * @param note The copy, under its new identifier.
     * @param before The value of [writeNote]: null lets the rules file the copy as a new note.
     * @return The note as written.
     * @throws FormatException When a file cannot be copied or the note written; nothing of the
     *   copy is left then.
     */
    fun writeCopy(note: Note, before: NoteMeta?): Note {
        val copied = copyNoteAttachments(library.root, note)
        try {
            return writeNote(copied.note, before)
        } catch (e: FormatException) {
            runCatching { removePath(library.attachmentsDir(note.meta.id)) }
            throw e
        }
    }

    /**
     * Changes the metadata of notes, each with the time of the change; a note the change leaves
     * as it was is not written. A note whose tags change is filed by the rules.
     *
     * @param change Returns a note's new metadata.
     * @return The metadata of the changed notes before the change, for [restore].
     * @throws FormatException When a note cannot be read or written; notes changed before stay changed.
     */
    fun updateNotes(ids: List<String>, change: (NoteMeta) -> NoteMeta): List<NoteMeta> {
        val before = mutableListOf<NoteMeta>()
        for (id in ids) {
            library.changeNote(id) { note ->
                val meta = file(note.meta.tags, change(note.meta))
                if (meta == note.meta) return@changeNote null
                before += note.meta
                note.copy(meta = meta.copy(updated = now()))
            }
        }
        return before
    }

    /**
     * Gives notes back the organisation they had, as Undo does: collections, tags, archive and
     * trash, icon and colour. Title, body and other changes made since stay.
     *
     * @param previous The metadata [updateNotes] returned.
     * @throws FormatException When a note cannot be read or written.
     */
    fun restore(previous: List<NoteMeta>) {
        val byId = previous.associateBy { it.id }
        updateNotes(previous.map { it.id }) { meta ->
            val old = byId.getValue(meta.id)
            meta.copy(
                collections = old.collections, tags = old.tags, archived = old.archived,
                trashed = old.trashed, icon = old.icon, color = old.color,
            )
        }
    }

    /**
     * Changes the rules, in the order they run, as they are stored now, so a change synchronised
     * meanwhile stays: a new rule gets its creation time, and every rule that differs from the
     * stored one its update time.
     *
     * @param change Returns the new list from the stored one.
     * @throws FormatException ReadOnly, Io, or Malformed for a rules file that cannot be read.
     */
    fun updateRules(change: (List<Rule>) -> List<Rule>) {
        val time = now()
        library.changeRules { current ->
            val stored = current.associateBy { it.id }
            change(current).map { rule ->
                when (val old = stored[rule.id]) {
                    null -> rule.copy(created = time, updated = time)
                    rule -> rule
                    else -> rule.copy(created = old.created, updated = time)
                }
            }
        }
    }

    /**
     * Lists the notes Apply to existing notes would change: active notes that match the rule and
     * whose collections its action changes, whether or not the rule is enabled. A note whose file
     * cannot be read is left out.
     */
    fun notesForRule(rule: Rule): List<String> =
        index.notes(NoteFilter(), NoteSort()).filter { ruleMatches(rule, it.tags) }.mapNotNull { summary ->
            val meta = try {
                library.readNote(summary.id).meta
            } catch (_: FormatException) {
                return@mapNotNull null
            }
            summary.id.takeIf { fileByRule(rule, meta, collectionIds).collections != meta.collections }
        }

    /**
     * Carries out a rule's action on notes, as Apply to existing notes does.
     *
     * @param ids The notes [notesForRule] listed.
     * @return What the rule changed, for the message with Undo.
     */
    fun applyRule(rule: Rule, ids: List<String>): List<RuleChange> {
        val changes = mutableListOf<RuleChange>()
        updateNotes(ids) { meta -> fileByRule(rule, meta, collectionIds).also { RuleChange.of(meta, it)?.let(changes::add) } }
        return changes
    }

    /** Gives notes back the collections they had before rules changed them (Undo). */
    fun undoRules(changes: List<RuleChange>) {
        val byId = changes.associateBy { it.before.id }
        updateNotes(byId.keys.toList()) { meta ->
            val old = byId.getValue(meta.id).before
            meta.copy(collections = old.collections)
        }
    }

    /**
     * Adds notes to collections and removes them from others.
     *
     * @return The metadata before the change, for Undo.
     */
    fun changeCollections(ids: List<String>, add: List<String>, remove: List<String> = emptyList()): List<NoteMeta> =
        updateNotes(ids) { meta ->
            val collections = meta.collections.filter { it !in remove } + add.filter { it !in meta.collections }
            meta.copy(collections = collections)
        }

    /**
     * Adds tags to notes and removes others; tags compare case-folded.
     *
     * @return The metadata before the change, for Undo.
     */
    fun changeTags(ids: List<String>, add: List<String>, remove: List<String> = emptyList()): List<NoteMeta> {
        val removed = remove.map(::tagKey).toSet()
        return updateNotes(ids) { meta -> meta.copy(tags = mergeTags(meta.tags.filter { tagKey(it) !in removed }, add)) }
    }

    /**
     * Archives notes or returns them from the archive.
     *
     * @return The metadata before the change, for Undo.
     */
    fun setArchived(ids: List<String>, archived: Boolean): List<NoteMeta> {
        val time = now()
        return updateNotes(ids) { it.copy(archived = if (archived) it.archived ?: time else null) }
    }

    /**
     * Moves notes to the trash or restores them; a trashed note keeps whether it was archived, so
     * Restore brings it back where it was (desktop spec section 3.3).
     *
     * @return The metadata before the change, for Undo.
     */
    fun setTrashed(ids: List<String>, trashed: Boolean): List<NoteMeta> {
        val time = now()
        return updateNotes(ids) { it.copy(trashed = if (trashed) it.trashed ?: time else null) }
    }

    /**
     * Adds favourites at the end of the list or removes them.
     *
     * @throws FormatException ReadOnly or Io.
     */
    fun setFavorite(refs: List<FavoriteRef>, favorite: Boolean) = library.changeInfo { info ->
        info.copy(favorites = if (favorite) info.favorites + refs.filter { it !in info.favorites } else info.favorites - refs.toSet())
    }

    /**
     * Moves a favourite before another, or to the end, as dragging it in the Favourites list does;
     * the other entries keep their order, so collections and notes stay where the desktop shows them.
     *
     * @param before The entry it goes before, or null for the end.
     * @throws FormatException ReadOnly or Io.
     */
    fun moveFavorite(ref: FavoriteRef, before: FavoriteRef?) = library.changeInfo { info ->
        if (ref !in info.favorites || ref == before) return@changeInfo info
        val rest = info.favorites - ref
        val at = rest.indexOf(before).let { if (it < 0) rest.size else it }
        info.copy(favorites = rest.take(at) + ref + rest.drop(at))
    }

    /**
     * Deletes notes permanently: their files and attachment folders go, each leaves a tombstone
     * so synchronisation does not bring it back, and they leave the favourites.
     *
     * @return The number of notes deleted.
     * @throws FormatException When a file cannot be deleted or `library.json` written.
     */
    fun deleteNotes(ids: List<String>): Int {
        val time = now()
        // An identifier always names one note; a malformed one would name the whole attachments folder.
        val valid = ids.filter(::isValidId).distinct()
        for (id in valid) {
            fileTransaction { Files.deleteIfExists(library.notePath(id)) }
            removePath(library.attachmentsDir(id))
        }
        library.changeInfo { info ->
            val tombstones = info.tombstones.filter { it.id !in valid } + valid.map { Tombstone(it, time) }
            info.copy(
                tombstones = unexpiredTombstones(tombstones, time),
                favorites = info.favorites.filterNot { it.kind == FavoriteRef.Kind.Note && it.id in valid },
            )
        }
        return valid.size
    }

    /**
     * Empties the trash.
     *
     * @return The number of notes deleted.
     */
    fun emptyTrash(): Int = deleteNotes(index.notes(NoteFilter(NoteFilter.View.Trash), NoteSort()).map { it.id })

    /**
     * Deletes the notes whose time in the trash exceeds the library's retention period; a library
     * that keeps trashed notes until the trash is emptied, or that is read-only, keeps them.
     *
     * @return The number of notes deleted.
     */
    fun purgeTrash(now: Instant = now()): Int {
        val days = library.info.trashRetentionDays ?: return 0
        if (library.isReadOnly) return 0
        val expired = index.trashedBefore(now.minus(Duration.ofDays(days.toLong())))
        return if (expired.isEmpty()) 0 else deleteNotes(expired)
    }

    /**
     * Removes the attachment files that no note has referred to for [UnreferencedPeriod]
     * (desktop spec section 3.5), so undo and an accidental deletion can bring a file back within
     * that time; a folder left empty goes too. Scan the index first, so it knows every note's files.
     *
     * @return The number of files removed.
     */
    fun purgeAttachments(now: Instant = now()): Int {
        if (library.isReadOnly) return 0
        var removed = 0
        // A folder that cannot be listed stops the run: its files are unknown, not gone.
        for (folder in listDirectory(library.root.resolve("attachments")).filter { isValidId(it.name) }) {
            val files = listDirectory(folder).filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            val due = index.unreferencedFiles(folder.name, files.map { it.name }, now, now.minus(UnreferencedPeriod)).toSet()
            for (file in files) {
                if (file.name in due && runCatching { Files.delete(file) }.isSuccess) removed++
            }
            if (listDirectory(folder).isEmpty()) runCatching { Files.delete(folder) }
        }
        return removed
    }

    /**
     * Creates a collection at the end of its parent's children.
     *
     * @param parent The parent collection, or null for a top-level one.
     * @return The new collection.
     */
    fun createCollection(name: String, parent: String?): Collection {
        val time = now()
        val collection = Collection(id = newId(), parent = parent, name = name, created = time, updated = time)
        library.changeCollections { it + collection }
        return collection
    }

    /**
     * Changes a collection: its name, icon or colour.
     *
     * @throws FormatException NotFound for a collection that does not exist.
     */
    fun updateCollection(id: String, change: (Collection) -> Collection) {
        library.changeCollections { collections ->
            if (collections.none { it.id == id }) throw FormatException(ErrorCode.NotFound, id, "no such collection")
            collections.map { if (it.id == id) change(it).copy(updated = now()) else it }
        }
    }

    /**
     * Moves a collection under another parent, before one of its new siblings or at the end; the
     * order of the list is the order within each parent. A collection cannot be moved into itself
     * or its descendants.
     *
     * @param parent The new parent, or null for the top level.
     * @param before The sibling it goes before, or null for the end.
     * @throws FormatException Malformed for a move into its own subtree, NotFound for an unknown collection.
     */
    fun moveCollection(id: String, parent: String?, before: String?) {
        library.changeCollections { collections ->
            if (parent != null && parent in collectionSubtree(collections, id)) throw FormatException(ErrorCode.Malformed, id, "a collection cannot hold itself")
            val moved = collections.firstOrNull { it.id == id } ?: throw FormatException(ErrorCode.NotFound, id, "no such collection")
            val rest = collections.filter { it.id != id }
            val at = rest.indexOfFirst { it.id == before }.let { if (it < 0) rest.size else it }
            rest.take(at) + moved.copy(parent = parent, updated = now()) + rest.drop(at)
        }
    }

    /**
     * What deleting a collection touches, for its confirmation.
     *
     * @param collections The collection's descendants.
     * @param notes The notes in it or its descendants, in any state.
     */
    data class CollectionDeletion(val collections: Int, val notes: Int)

    /** Counts what deleting a collection touches. */
    fun collectionDeletion(id: String): CollectionDeletion {
        val subtree = collectionSubtree(library.readCollections(), id)
        return CollectionDeletion(subtree.size - 1, index.notesInCollections(subtree).size)
    }

    /**
     * Deletes a collection with its descendants: its notes lose these memberships and stay in
     * their other collections or in All notes; with [trashOrphans] those left in no collection
     * go to the trash. The collections leave the favourites.
     */
    fun deleteCollection(id: String, trashOrphans: Boolean) {
        val subtree = collectionSubtree(library.readCollections(), id).toSet()
        val time = now()
        updateNotes(index.notesInCollections(subtree.toList())) { meta ->
            val left = meta.collections.filter { it !in subtree }
            meta.copy(collections = left, trashed = if (trashOrphans && left.isEmpty()) meta.trashed ?: time else meta.trashed)
        }
        library.changeCollections { collections -> collections.filter { it.id !in subtree } }
        library.changeInfo { info -> info.copy(favorites = info.favorites.filterNot { it.kind == FavoriteRef.Kind.Collection && it.id in subtree }) }
    }

    /**
     * Renames a tag and its children in every note, merging with a tag of the new name; their
     * colours move with them, and a tag merged into one with a colour keeps that colour.
     *
     * @return The number of notes changed.
     */
    fun renameTag(from: String, to: String): Int {
        val changed = updateNotes(index.notesTagged(from)) { meta ->
            meta.copy(tags = mergeTags(meta.tags.map { renamedTag(it, from, to) ?: it }, emptyList()))
        }
        library.changeInfo { info ->
            val moved = info.tagColors.filterKeys { isTagWithin(it, from) }
            if (moved.isEmpty()) return@changeInfo info
            val kept = info.tagColors - moved.keys
            info.copy(tagColors = kept + moved.mapKeys { (key, _) -> tagKey(renamedTag(key, from, to)!!) }.filterKeys { it !in kept })
        }
        return changed.size
    }

    /**
     * Removes a tag and its children from every note, with their colours.
     *
     * @return The number of notes changed.
     */
    fun deleteTag(tag: String): Int {
        val changed = updateNotes(index.notesTagged(tag)) { meta -> meta.copy(tags = meta.tags.filterNot { isTagWithin(tagKey(it), tag) }) }
        library.changeInfo { info -> info.copy(tagColors = info.tagColors.filterKeys { !isTagWithin(it, tag) }) }
        return changed.size
    }

    /**
     * Sets a tag's own colour or removes it (null), at once; notes do not change.
     *
     * @param color A tag colour key or a custom colour `#rrggbb`.
     */
    fun setTagColor(tag: String, color: String?) = library.changeInfo { info ->
        val key = tagKey(tag)
        info.copy(tagColors = if (color == null) info.tagColors - key else info.tagColors + (key to color))
    }
}
