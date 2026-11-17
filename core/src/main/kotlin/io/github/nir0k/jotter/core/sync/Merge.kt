package io.github.nir0k.jotter.core.sync

import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.FavoriteRef
import io.github.nir0k.jotter.core.format.LibraryInfo
import io.github.nir0k.jotter.core.format.Note
import io.github.nir0k.jotter.core.format.NoteMeta
import io.github.nir0k.jotter.core.format.Rule
import io.github.nir0k.jotter.core.format.Tombstone
import io.github.nir0k.jotter.core.format.isLetterOrNumber
import io.github.nir0k.jotter.core.format.isQtSpace
import io.github.nir0k.jotter.core.format.tagKey
import io.github.nir0k.jotter.core.format.unexpiredTombstones
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * The result of merging a note changed on two devices (desktop spec section 12.4).
 *
 * @param note The note both sides keep.
 * @param conflict Whether both sides changed the same part of the body. The note then has the
 *   remote body and the local note must be kept as a conflict copy.
 */
data class NoteMerge(val note: Note, val conflict: Boolean)

/**
 * The changed middle parts of two block lists are matched only up to this many table cells; larger
 * rewrites are treated as having nothing in common, which makes a change on both sides a conflict
 * rather than a slow merge.
 */
private const val MaxMatchCells = 1_000_000L

/** Elements that have no end tag. */
private val VoidElements = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")

/**
 * Pairs equal blocks of two lists along their longest common subsequence.
 *
 * @return For each block of [a], the index of its partner in [b], or -1; partners increase with
 *   the index.
 */
private fun matchBlocks(a: List<String>, b: List<String>): IntArray {
    val match = IntArray(a.size) { -1 }
    // Most edits touch a few blocks; the common start and end are matched without the table.
    var head = 0
    while (head < a.size && head < b.size && a[head] == b[head]) {
        match[head] = head
        head++
    }
    var tail = 0
    while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) {
        match[a.size - 1 - tail] = b.size - 1 - tail
        tail++
    }
    val n = a.size - head - tail
    val m = b.size - head - tail
    if (n == 0 || m == 0 || n.toLong() * m > MaxMatchCells) return match
    // lengths[x * (m + 1) + y] is the length of the common subsequence of the middle parts from x and y.
    val lengths = IntArray((n + 1) * (m + 1))
    fun length(x: Int, y: Int) = lengths[x * (m + 1) + y]
    for (x in n - 1 downTo 0) {
        for (y in m - 1 downTo 0) {
            lengths[x * (m + 1) + y] =
                if (a[head + x] == b[head + y]) length(x + 1, y + 1) + 1 else maxOf(length(x + 1, y), length(x, y + 1))
        }
    }
    var x = 0
    var y = 0
    while (x < n && y < m) {
        when {
            a[head + x] == b[head + y] -> {
                match[head + x] = head + y
                x++
                y++
            }
            length(x + 1, y) >= length(x, y + 1) -> x++
            else -> y++
        }
    }
    return match
}

/**
 * A change of one side against the base: the base blocks from [start] to [end] (an empty range
 * for an insertion) replaced by [blocks].
 */
private data class Hunk(val start: Int, val end: Int, val blocks: List<String>)

/**
 * Lists the changes of one side against the base, in base order.
 *
 * @param match The partners of the base blocks in [side], from [matchBlocks].
 * @return The hunks; their base ranges do not overlap.
 */
private fun hunks(base: List<String>, side: List<String>, match: IntArray): List<Hunk> {
    val result = mutableListOf<Hunk>()
    var b = 0
    var s = 0
    for (i in 0..base.size) {
        if (i < base.size && match[i] < 0) continue
        val sideEnd = if (i < base.size) match[i] else side.size
        if (b < i || s < sideEnd) result += Hunk(b, i, side.subList(s, sideEnd))
        b = i + 1
        s = sideEnd + 1
    }
    return result
}

/**
 * Tells whether two changes touch the same part of the base: overlapping ranges, an insertion
 * inside the other's range, or two insertions at one place. A change next to another is not a
 * conflict.
 */
private fun overlaps(a: Hunk, b: Hunk): Boolean = when {
    a.start == a.end && b.start == b.end -> a.start == b.start
    a.start == a.end -> b.start < a.start && a.start < b.end
    b.start == b.end -> a.start < b.start && b.start < a.end
    else -> a.start < b.end && b.start < a.end
}

/**
 * Merges block lists three ways: the changes of both sides against the base are applied together,
 * and the same change made on both sides is applied once.
 *
 * @return The merged blocks, or null when both sides changed the same part differently.
 */
private fun mergeBlocks(base: List<String>, local: List<String>, remote: List<String>): List<String>? {
    val localHunks = hunks(base, local, matchBlocks(base, local))
    val remoteHunks = hunks(base, remote, matchBlocks(base, remote))
    val merged = mutableListOf<String>()
    var position = 0
    fun apply(hunk: Hunk) {
        merged += base.subList(position, hunk.start)
        merged += hunk.blocks
        position = hunk.end
    }
    var l = 0
    var r = 0
    while (l < localHunks.size || r < remoteHunks.size) {
        if (l == localHunks.size) {
            apply(remoteHunks[r++])
            continue
        }
        if (r == remoteHunks.size) {
            apply(localHunks[l++])
            continue
        }
        val a = localHunks[l]
        val b = remoteHunks[r]
        when {
            a == b -> {
                apply(a)
                l++
                r++
            }
            overlaps(a, b) -> return null
            // At one place an insertion comes before a change of the blocks that follow it.
            a.start < b.start || (a.start == b.start && a.start == a.end) -> apply(localHunks[l++])
            else -> apply(remoteHunks[r++])
        }
    }
    merged += base.subList(position, base.size)
    return merged
}

/**
 * Chooses between two versions of a value: the changed one when only one side changed it,
 * otherwise the local one only when [localWins].
 */
private fun <T> pick(base: T, local: T, remote: T, localWins: Boolean): T = when {
    local == remote || remote == base -> local
    local == base -> remote
    localWins -> local
    else -> remote
}

/**
 * Merges two lists as sets against their base: an element both sides have, or that the side having
 * it added, is kept. Elements keep the remote order, followed by those only the local side has.
 *
 * @param keyOf Returns the identity of an element, such as the case-folded tag.
 */
private fun mergeSet(base: List<String>, local: List<String>, remote: List<String>, keyOf: (String) -> String): List<String> {
    val baseKeys = base.mapTo(HashSet(), keyOf)
    val localKeys = local.mapTo(HashSet(), keyOf)
    val remoteKeys = remote.mapTo(HashSet(), keyOf)
    val merged = mutableListOf<String>()
    val added = HashSet<String>()
    for (element in remote) {
        val key = keyOf(element)
        if ((key in localKeys || key !in baseKeys) && added.add(key)) merged += element
    }
    for (element in local) {
        val key = keyOf(element)
        if (key !in remoteKeys && key !in baseKeys && added.add(key)) merged += element
    }
    return merged
}

/**
 * Merges key-value maps against their base key by key; a key both sides changed keeps the remote
 * value. The result is ordered by key, as the desktop's maps are.
 */
private fun <V> mergeKeys(base: Map<String, V>, local: Map<String, V>, remote: Map<String, V>): Map<String, V> {
    val merged = remote.toSortedMap()
    fun remoteChanged(key: String) = (key in base) != (key in remote) || (key in remote && remote[key] != base[key])
    for ((key, value) in local) {
        if (!remoteChanged(key)) merged[key] = value
    }
    for (key in remote.keys) {
        if (key !in local && !remoteChanged(key)) merged.remove(key)
    }
    return merged
}

/** Merges JSON objects of unknown fields key by key, as [mergeKeys]. */
private fun mergeObjects(base: JsonObject, local: JsonObject, remote: JsonObject) = JsonObject(mergeKeys(base, local, remote))

/**
 * Merges lists of identified entries, such as collections or rules, against their base.
 *
 * An entry changed on one side takes that side, also when that side deleted it; an entry changed
 * on one side and deleted on the other is kept, so no change is lost; an entry changed on both
 * sides keeps the local one only when [localWins] says so. The order is the remote one unless only
 * the local side reordered the entries all three versions have; entries missing from that order
 * follow their predecessor on their own side.
 *
 * @param keyOf Returns an entry's identifier.
 * @param localWins Tells whether the local entry wins over a remote one both sides changed.
 */
private fun <T> mergeEntries(
    base: List<T>,
    local: List<T>,
    remote: List<T>,
    keyOf: (T) -> String,
    localWins: (T, T) -> Boolean,
): List<T> {
    val baseEntries = base.associateBy(keyOf)
    val localEntries = local.associateBy(keyOf)
    val remoteEntries = remote.associateBy(keyOf)
    fun kept(key: String): T? {
        val baseEntry = baseEntries[key]
        val localEntry = localEntries[key]
        val remoteEntry = remoteEntries[key]
        return when {
            localEntry == remoteEntry || remoteEntry == baseEntry -> localEntry
            localEntry == baseEntry -> remoteEntry
            localEntry == null || remoteEntry == null -> localEntry ?: remoteEntry
            localWins(localEntry, remoteEntry) -> localEntry
            else -> remoteEntry
        }
    }
    fun commonOrder(list: List<T>) = list.map(keyOf).filter { it in baseEntries && it in localEntries && it in remoteEntries }
    val baseOrder = commonOrder(base)
    val localOrder = commonOrder(remote) == baseOrder && commonOrder(local) != baseOrder
    val first = if (localOrder) local else remote
    val second = if (localOrder) remote else local
    val merged = mutableListOf<T>()
    val placed = HashMap<String, Int>()
    for (entry in first) {
        val key = keyOf(entry)
        kept(key)?.let {
            placed[key] = merged.size
            merged += it
        }
    }
    var insertAt = 0
    for (entry in second) {
        val key = keyOf(entry)
        val at = placed[key]
        if (at != null) {
            insertAt = at + 1
            continue
        }
        val chosen = kept(key) ?: continue
        merged.add(insertAt, chosen)
        for (other in placed.keys) {
            val position = placed.getValue(other)
            if (position >= insertAt) placed[other] = position + 1
        }
        placed[key] = insertAt
        insertAt++
    }
    return merged
}

/**
 * Splits a note body into its top-level blocks (paragraphs, lists, tables, code blocks and the
 * like), without the line breaks between them: the units of the three-way body merge.
 *
 * @param body The body HTML.
 * @return The blocks in document order; text outside any element forms blocks of its own.
 */
fun bodyBlocks(body: String): List<String> {
    val blocks = mutableListOf<String>()
    val size = body.length
    // The start of the block being read, or -1 between blocks.
    var start = -1
    // The nesting depth of elements inside the block; 0 while reading text outside elements.
    var depth = 0
    fun close(end: Int) {
        if (start >= 0) {
            val block = body.substring(start, end).trim { it.isQtSpace() }
            if (block.isNotEmpty()) blocks += block
            start = -1
        }
    }
    var i = 0
    while (i < size) {
        val tag = body[i] == '<' && i + 1 < size && (body[i + 1].isLetter() || body[i + 1] == '/' || body[i + 1] == '!')
        if (!tag) {
            if (start < 0 && !body[i].isQtSpace()) start = i
            i++
            continue
        }
        // The end of the tag, past quoted attribute values that may hold `>`.
        var end = i + 1
        if (body.startsWith("<!--", i)) {
            val commentEnd = body.indexOf("-->", i + 4)
            end = if (commentEnd < 0) size else commentEnd + 3
        } else {
            var quote: Char? = null
            while (end < size && (quote != null || body[end] != '>')) {
                if (quote == null && (body[end] == '"' || body[end] == '\'')) {
                    quote = body[end]
                } else if (body[end] == quote) {
                    quote = null
                }
                end++
            }
            end = minOf(end + 1, size)
        }
        val closing = body[i + 1] == '/'
        val nameStart = i + if (closing) 2 else 1
        var nameEnd = nameStart
        while (nameEnd < end && body[nameEnd].isLetterOrNumber()) nameEnd++
        val name = body.substring(nameStart, nameEnd).lowercase()
        val single = body[i + 1] == '!' || name in VoidElements || (end - i >= 2 && body.startsWith("/>", end - 2))
        if (depth == 0) {
            // Text outside elements ends where an element starts.
            close(i)
            start = i
            if (closing || single) close(end) else depth = 1
        } else if (closing) {
            if (--depth == 0) close(end)
        } else if (!single) {
            depth++
        }
        i = end
    }
    close(size)
    return blocks
}

/**
 * Merges a note changed locally and remotely against the version both started from.
 *
 * Metadata merges field by field: a field changed on one side takes that side, a field changed on
 * both sides keeps the side with the later `updated`, and collections and tags merge as sets. The
 * body merges by top-level blocks; changes to different blocks combine. Without a base (the first
 * connection, desktop spec section 12.6) notes with different bodies are a conflict, and notes with
 * equal bodies keep the later one.
 *
 * @param base The last synchronised version, or null when there is none.
 * @param local The version in the library.
 * @param remote The version in the sync storage.
 * @return The merged note and whether it is a conflict.
 */
fun mergeNote(base: Note?, local: Note, remote: Note): NoteMerge {
    if (local == remote) return NoteMerge(local, conflict = false)
    val localLater = local.meta.updated > remote.meta.updated
    if (base == null) {
        if (local.body != remote.body) return NoteMerge(remote, conflict = true)
        return NoteMerge(if (localLater) local else remote, conflict = false)
    }
    if (local == base) return NoteMerge(remote, conflict = false)
    if (remote == base) return NoteMerge(local, conflict = false)
    val b = base.meta
    val l = local.meta
    val r = remote.meta
    val meta = NoteMeta(
        id = r.id,
        title = pick(b.title, l.title, r.title, localLater),
        icon = pick(b.icon, l.icon, r.icon, localLater),
        color = pick(b.color, l.color, r.color, localLater),
        created = pick(b.created, l.created, r.created, localLater),
        updated = maxOf(l.updated, r.updated),
        collections = mergeSet(b.collections, l.collections, r.collections) { it },
        tags = mergeSet(b.tags, l.tags, r.tags, ::tagKey),
        archived = pick(b.archived, l.archived, r.archived, localLater),
        trashed = pick(b.trashed, l.trashed, r.trashed, localLater),
        location = pick(b.location, l.location, r.location, localLater),
        extra = pick(b.extra, l.extra, r.extra, localLater),
    )
    if (local.body == remote.body || remote.body == base.body) return NoteMerge(Note(meta, local.body), conflict = false)
    if (local.body == base.body) return NoteMerge(Note(meta, remote.body), conflict = false)
    val blocks = mergeBlocks(bodyBlocks(base.body), bodyBlocks(local.body), bodyBlocks(remote.body))
        ?: return NoteMerge(Note(meta, remote.body), conflict = true)
    return NoteMerge(Note(meta, blocks.joinToString("\n")), conflict = false)
}

/**
 * Merges `library.json`: favourites by entry, tag colours by tag, settings and unknown fields by
 * key, and the tombstones of both sides, dropping expired ones and favourites of deleted notes.
 * Without a base, or when both sides changed the same value, the remote value wins, as these values
 * carry no time of change.
 *
 * @param base The last synchronised version, or null when there is none.
 * @param local The version in the library.
 * @param remote The version in the sync storage.
 * @param now The current time, for expiring tombstones.
 * @return The merged library information, with the remote identifier.
 */
fun mergeLibraryInfo(base: LibraryInfo?, local: LibraryInfo, remote: LibraryInfo, now: Instant): LibraryInfo {
    // Without a base, values that differ take the remote side: the local one serves as the base.
    // Lists and maps merge with an empty base instead, which keeps what either side has.
    val b = base ?: local
    val deleted = HashMap<String, Instant>()
    for (tombstone in local.tombstones + remote.tombstones) {
        val known = deleted[tombstone.id]
        if (known == null || tombstone.deleted < known) deleted[tombstone.id] = tombstone.deleted
    }
    val tombstones = deleted.map { Tombstone(it.key, it.value) }.sortedWith(compareBy({ it.deleted }, { it.id }))
    val favorites = mergeEntries(
        base?.favorites.orEmpty(),
        local.favorites,
        remote.favorites,
        { (if (it.kind == FavoriteRef.Kind.Note) "n" else "c") + it.id },
        { _, _ -> false },
    ).filterNot { it.kind == FavoriteRef.Kind.Note && it.id in deleted }
    return remote.copy(
        favorites = favorites,
        tagColors = mergeKeys(base?.tagColors.orEmpty(), local.tagColors, remote.tagColors),
        trashRetentionDays = pick(b.trashRetentionDays, local.trashRetentionDays, remote.trashRetentionDays, false),
        tombstones = unexpiredTombstones(tombstones, now),
        extra = mergeObjects(b.extra, local.extra, remote.extra),
        extraSettings = mergeObjects(b.extraSettings, local.extraSettings, remote.extraSettings),
    )
}

/**
 * Merges `collections.json` by collection identifier; a collection changed on both sides keeps the
 * later `updated`, and one changed on one side and deleted on the other is kept. The order is the
 * remote one unless only the local side reordered; collections missing from it follow their
 * predecessor on their own side. A collection left outside the tree moves to the top level.
 *
 * @param base The last synchronised version; empty when there is none.
 * @return The merged collections in their order.
 */
fun mergeCollections(base: List<Collection>, local: List<Collection>, remote: List<Collection>): List<Collection> {
    val merged = mergeEntries(base, local, remote, { it.id }, { l, r -> l.updated > r.updated })
    // A parent deleted on one side while the other added a child, or each side moving one
    // collection under the other, would leave collections outside the tree; they move to the top.
    val byId = merged.associateBy { it.id }
    val detached = HashSet<String>()
    for (collection in merged) {
        if (collection.parent != null && collection.parent !in byId) {
            detached += collection.id
            continue
        }
        // A collection on a cycle comes back to itself; one hanging below a cycle does not and
        // keeps its parent.
        val path = HashSet<String>()
        var parent = collection.parent
        while (parent != null && parent in byId && parent !in path) {
            if (parent == collection.id) {
                detached += collection.id
                break
            }
            path += parent
            parent = byId.getValue(parent).parent
        }
    }
    return merged.map { if (it.id in detached) it.copy(parent = null) else it }
}

/**
 * Merges `rules.json` by rule identifier, with the rules of [mergeCollections] for entries and
 * their order.
 *
 * @param base The last synchronised version; empty when there is none.
 * @return The merged rules in the order they run in.
 */
fun mergeRules(base: List<Rule>, local: List<Rule>, remote: List<Rule>): List<Rule> =
    mergeEntries(base, local, remote, { it.id }, { l, r -> l.updated > r.updated })
