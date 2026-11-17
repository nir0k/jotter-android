package io.github.nir0k.jotter.core.format

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The library format version this build reads and writes. */
const val LibraryFormat = 1

/** An entry of the Favourites section: a collection or a note, in the person's order. */
data class FavoriteRef(val kind: Kind, val id: String) {
    enum class Kind { Collection, Note }
}

/**
 * A permanently deleted note, remembered so that synchronisation does not bring it back from a
 * device that still has it (desktop spec section 12.4).
 */
data class Tombstone(val id: String, val deleted: Instant)

/** Days a tombstone is kept (desktop spec section 12.4). */
const val TombstoneDays = 90L

/**
 * Leaves out the tombstones older than [TombstoneDays].
 *
 * @param now The current time.
 */
fun unexpiredTombstones(tombstones: List<Tombstone>, now: Instant): List<Tombstone> {
    val limit = now.minus(Duration.ofDays(TombstoneDays))
    return tombstones.filterNot { it.deleted < limit }
}

/**
 * The contents of `library.json` (desktop spec section 3.2).
 *
 * @param tagColors Tag colour keys by tag key (case-folded name).
 * @param trashRetentionDays Days a trashed note is kept; null keeps it until the trash is emptied.
 * @param tombstones Notes deleted permanently within the last 90 days, ordered by time of deletion.
 * @param extra Top-level fields this build does not know, written back unchanged.
 * @param extraSettings Fields of `settings` this build does not know, written back unchanged.
 */
data class LibraryInfo(
    val id: String,
    val created: Instant,
    val favorites: List<FavoriteRef> = emptyList(),
    val tagColors: Map<String, String> = emptyMap(),
    val trashRetentionDays: Int? = 30,
    val tombstones: List<Tombstone> = emptyList(),
    val extra: JsonObject = JsonObject(emptyMap()),
    val extraSettings: JsonObject = JsonObject(emptyMap()),
)

/**
 * A collection of `collections.json` (desktop spec section 3.4).
 *
 * @param parent The parent collection, or null for a top-level collection.
 * @param icon Glyph name; collections are created with `Folder`.
 * @param color Tag colour key or custom colour of the name.
 * @param extra Fields this build does not know, written back unchanged.
 */
data class Collection(
    val id: String,
    val parent: String?,
    val name: String,
    val icon: String = "Folder",
    val color: String? = null,
    val created: Instant,
    val updated: Instant,
    val extra: JsonObject = JsonObject(emptyMap()),
)

/**
 * A condition of a rule, with the field and comparison `rules.json` writes it with. Those on the title
 * and with a regular expression come from earlier versions: such a rule is unsupported, never run, and
 * its conditions are written back unchanged (desktop spec section 3.4).
 */
enum class RuleConditionKind(val field: String, val op: String) {
    TitleMatches("title", "matches"),
    TitleContains("title", "contains"),
    TitleEquals("title", "equals"),
    TagMatches("tag", "matches"),
    TagEquals("tag", "equals"),
    NoTags("tags", "none"),
}

/**
 * A rule's condition.
 *
 * @param value The tag, or in a rule of an earlier version the text or regular expression; unused
 *   by [RuleConditionKind.NoTags].
 */
data class RuleCondition(val kind: RuleConditionKind, val value: String = "")

/**
 * What a rule does with a note that starts matching it.
 *
 * @param move Whether the note moves into the first collection, leaving all others, instead of
 *   being added to the collections.
 */
data class RuleAction(val move: Boolean, val collections: List<String>)

/**
 * A rule of `rules.json`: conditions joined by all or any, and an action.
 *
 * @param matchAll Whether all conditions must hold rather than one.
 * @param stop Whether rules after this one are skipped for a note that matches it.
 * @param extra Fields this build does not know, written back unchanged, such as the `caseSensitive`
 *   of earlier versions.
 */
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val matchAll: Boolean = true,
    val conditions: List<RuleCondition> = emptyList(),
    val action: RuleAction,
    val stop: Boolean = false,
    val created: Instant,
    val updated: Instant,
    val extra: JsonObject = JsonObject(emptyMap()),
)

private val KnownInfoFields = setOf("format", "id", "created", "favorites", "tagColors", "settings", "tombstones")
private val KnownCollectionFields = setOf("id", "parent", "name", "icon", "color", "created", "updated")
private val KnownRuleFields = setOf(
    "id", "name", "enabled", "match", "conditions", "action", "stop", "created", "updated",
)

/**
 * Lists a collection and its descendants, the collection first; a cycle in a damaged file is
 * followed only once.
 *
 * @param collections The collection tree.
 * @param id The collection.
 * @return The identifiers of the subtree.
 */
fun collectionSubtree(collections: List<Collection>, id: String): List<String> {
    val children = collections.groupBy({ it.parent.orEmpty() }, { it.id })
    val ids = mutableListOf(id)
    val seen = mutableSetOf(id)
    var i = 0
    while (i < ids.size) {
        for (child in children[ids[i]].orEmpty()) {
            if (seen.add(child)) ids += child
        }
        i++
    }
    return ids
}

/**
 * Reads the format version of a library file, refusing a newer one.
 *
 * @throws FormatException NewerFormat when the file is newer than this build.
 */
private fun checkFormat(json: JsonObject, path: String) {
    if (json["format"].qtInt(0) > LibraryFormat) throw FormatException(ErrorCode.NewerFormat, path)
}

/**
 * Returns the named array of a list file, which needs an integer format of at least 1 and the array.
 *
 * @throws FormatException NewerFormat, or Malformed for anything else.
 */
private fun listDocument(json: JsonObject, key: String, path: String): JsonArray {
    checkFormat(json, path)
    val list = json[key]
    if (json["format"].qtInt(0) < 1 || list !is JsonArray) throw FormatException(ErrorCode.Malformed, path, "invalid $key document")
    return list
}

/**
 * Parses the fields of `library.json`.
 *
 * @param newer Whether the file is of a newer format: only the identity is read, so the library
 *   can still be shown read-only.
 * @throws FormatException Malformed.
 */
internal fun libraryInfoFromJson(json: JsonObject, path: String, newer: Boolean): LibraryInfo {
    val id = json["id"].qtString()
    val created = parseTime(json["created"].qtString())
    if (!isValidId(id) || created == null) throw FormatException(ErrorCode.Malformed, path, "invalid id or created time")
    if (newer) return LibraryInfo(id, created)
    // A present value of the wrong type is damage, never an empty list or default settings.
    for (key in listOf("favorites", "tombstones")) {
        if (json[key] != null && json[key] !is JsonArray) throw FormatException(ErrorCode.Malformed, path, "invalid field $key")
    }
    for (key in listOf("tagColors", "settings")) {
        if (json[key] != null && json[key] !is JsonObject) throw FormatException(ErrorCode.Malformed, path, "invalid field $key")
    }

    val favorites = json["favorites"].qtArray().map { value ->
        val entry = value.qtObject()
        val kind = when (entry["kind"].qtString()) {
            "collection" -> FavoriteRef.Kind.Collection
            "note" -> FavoriteRef.Kind.Note
            else -> null
        }
        val favoriteId = entry["id"].qtString()
        if (kind == null || !isValidId(favoriteId)) throw FormatException(ErrorCode.Malformed, path, "invalid favourite")
        FavoriteRef(kind, favoriteId)
    }
    val tagColors = LinkedHashMap<String, String>()
    // Keys in their stored order, so that two spellings of one tag resolve as on the desktop.
    val colors = json["tagColors"].qtObject()
    for (key in colors.keys.sorted()) {
        val value = colors.getValue(key)
        val tag = normalizedTag(key)
        if (!value.isString || tag.isEmpty()) throw FormatException(ErrorCode.Malformed, path, "invalid tag colour")
        tagColors[tagKey(tag)] = value.qtString()
    }
    val tombstones = json["tombstones"].qtArray().map { value ->
        val entry = value.qtObject()
        val tombstoneId = entry["id"].qtString()
        val deleted = parseTime(entry["deleted"].qtString())
        if (!isValidId(tombstoneId) || deleted == null) throw FormatException(ErrorCode.Malformed, path, "invalid tombstone")
        Tombstone(tombstoneId, deleted)
    }
    val settings = json["settings"].qtObject()
    val retention = settings["trashRetentionDays"]
    val trashRetentionDays = when {
        retention.isNull -> null
        retention.isNumber && retention.qtInt(-1) > 0 -> retention.qtInt(-1)
        retention == null -> 30
        else -> throw FormatException(ErrorCode.Malformed, path, "invalid trashRetentionDays")
    }
    return LibraryInfo(
        id = id,
        created = created,
        favorites = favorites,
        tagColors = tagColors,
        trashRetentionDays = trashRetentionDays,
        tombstones = tombstones,
        extra = unknownFields(json, KnownInfoFields),
        extraSettings = unknownFields(settings, setOf("trashRetentionDays")),
    )
}

/**
 * Parses `library.json` of a library this build can write.
 *
 * @param data The file's contents.
 * @param path The file, for errors.
 * @throws FormatException Malformed or NewerFormat.
 */
fun parseLibraryInfo(data: ByteArray, path: String): LibraryInfo {
    val json = parseJsonObject(data, path)
    if (json["format"].qtInt(0) < 1) throw FormatException(ErrorCode.Malformed, path, "invalid format")
    checkFormat(json, path)
    return libraryInfoFromJson(json, path, newer = false)
}

/** Serialises the library information into the contents of `library.json`. */
fun serializeLibraryInfo(info: LibraryInfo): ByteArray {
    val favorites = info.favorites.map {
        val kind = if (it.kind == FavoriteRef.Kind.Collection) "collection" else "note"
        JsonObject(mapOf("kind" to JsonPrimitive(kind), "id" to JsonPrimitive(it.id)))
    }
    val tombstones = info.tombstones.map {
        JsonObject(mapOf("id" to JsonPrimitive(it.id), "deleted" to JsonPrimitive(formatTime(it.deleted))))
    }
    val settings = info.extraSettings +
        ("trashRetentionDays" to (info.trashRetentionDays?.let(::JsonPrimitive) ?: JsonNull))
    return toJsonText(
        JsonObject(
            info.extra + mapOf(
                "format" to JsonPrimitive(LibraryFormat),
                "id" to JsonPrimitive(info.id),
                "created" to JsonPrimitive(formatTime(info.created)),
                "favorites" to JsonArray(favorites),
                "tagColors" to JsonObject(info.tagColors.mapValues { JsonPrimitive(it.value) }),
                "settings" to JsonObject(settings),
                "tombstones" to JsonArray(tombstones),
            ),
        ),
    )
}

/**
 * Parses `collections.json`.
 *
 * @param data The file's contents.
 * @param path The file, for errors.
 * @return The collections in their order within each parent.
 * @throws FormatException Malformed or NewerFormat.
 */
fun parseCollections(data: ByteArray, path: String): List<Collection> {
    return listDocument(parseJsonObject(data, path), "collections", path).map { value ->
        val entry = value.qtObject()
        val id = entry["id"].qtString()
        val parent = entry["parent"].takeIf { it.isString }?.qtString()
        val created = parseTime(entry["created"].qtString())
        val updated = parseTime(entry["updated"].qtString())
        if (!isValidId(id) || (parent != null && !isValidId(parent)) || !entry["name"].isString ||
            created == null || updated == null
        ) {
            throw FormatException(ErrorCode.Malformed, path, "invalid collection $id")
        }
        Collection(
            id = id,
            parent = parent,
            name = entry["name"].qtString(),
            icon = entry["icon"].qtString("Folder"),
            color = entry["color"].takeIf { it.isString }?.qtString(),
            created = created,
            updated = updated,
            extra = unknownFields(entry, KnownCollectionFields),
        )
    }
}

/** Serialises the collections into the contents of `collections.json`. */
fun serializeCollections(collections: List<Collection>): ByteArray {
    val list = collections.map {
        JsonObject(
            it.extra + mapOf(
                "id" to JsonPrimitive(it.id),
                "parent" to jsonString(it.parent),
                "name" to JsonPrimitive(it.name),
                "icon" to JsonPrimitive(it.icon),
                "color" to jsonString(it.color),
                "created" to JsonPrimitive(formatTime(it.created)),
                "updated" to JsonPrimitive(formatTime(it.updated)),
            ),
        )
    }
    return toJsonText(JsonObject(mapOf("format" to JsonPrimitive(LibraryFormat), "collections" to JsonArray(list))))
}

/** Reads one rule; returns null when it does not follow the format. */
private fun ruleFromJson(entry: JsonObject): Rule? {
    val id = entry["id"].qtString()
    val match = entry["match"].qtString()
    val created = parseTime(entry["created"].qtString())
    val updated = parseTime(entry["updated"].qtString())
    if (!isValidId(id) || created == null || updated == null || (match != "all" && match != "any")) return null
    val conditionList = entry["conditions"] as? JsonArray ?: return null
    val targets = (entry["action"] as? JsonObject)?.get("collections") as? JsonArray ?: return null
    if (targets.any { !it.isString || !isValidId(it.qtString()) }) return null
    if (listOf("enabled", "stop").any { entry[it] != null && !entry[it].isBoolean }) return null
    val conditions = conditionList.map { value ->
        val condition = value.qtObject()
        val field = condition["field"].qtString()
        val op = condition["op"].qtString()
        val kind = RuleConditionKind.entries.firstOrNull { it.field == field && it.op == op } ?: return null
        RuleCondition(kind, condition["value"].qtString())
    }
    val action = entry["action"].qtObject()
    val kind = action["kind"].qtString()
    if (kind != "add" && kind != "move") return null
    return Rule(
        id = id,
        name = entry["name"].qtString(),
        enabled = entry["enabled"].qtBoolean(true),
        matchAll = match != "any",
        conditions = conditions,
        action = RuleAction(move = kind == "move", collections = targets.map { it.qtString() }),
        stop = entry["stop"].qtBoolean(false),
        created = created,
        updated = updated,
        extra = unknownFields(entry, KnownRuleFields),
    )
}

/**
 * Parses `rules.json` (desktop spec section 3.4); a rule with an unknown condition or action makes
 * the file unreadable.
 *
 * @param data The file's contents.
 * @param path The file, for errors.
 * @return The rules in the order they run.
 * @throws FormatException Malformed or NewerFormat.
 */
fun parseRules(data: ByteArray, path: String): List<Rule> {
    return listDocument(parseJsonObject(data, path), "rules", path).map { value ->
        ruleFromJson(value.qtObject())
            ?: throw FormatException(ErrorCode.Malformed, path, "invalid rule ${value.qtObject()["id"].qtString()}")
    }
}

/** Serialises rules as the contents of `rules.json`. */
fun serializeRules(rules: List<Rule>): ByteArray {
    val list = rules.map { rule ->
        val conditions = rule.conditions.map {
            val fields = mutableMapOf("field" to JsonPrimitive(it.kind.field), "op" to JsonPrimitive(it.kind.op))
            if (it.kind != RuleConditionKind.NoTags) fields["value"] = JsonPrimitive(it.value)
            JsonObject(fields)
        }
        val action = JsonObject(
            mapOf(
                "kind" to JsonPrimitive(if (rule.action.move) "move" else "add"),
                "collections" to JsonArray(rule.action.collections.map(::JsonPrimitive)),
            ),
        )
        JsonObject(
            rule.extra + mapOf(
                "id" to JsonPrimitive(rule.id),
                "name" to JsonPrimitive(rule.name),
                "enabled" to JsonPrimitive(rule.enabled),
                "match" to JsonPrimitive(if (rule.matchAll) "all" else "any"),
                "conditions" to JsonArray(conditions),
                "action" to action,
                "stop" to JsonPrimitive(rule.stop),
                "created" to JsonPrimitive(formatTime(rule.created)),
                "updated" to JsonPrimitive(formatTime(rule.updated)),
            ),
        )
    }
    return toJsonText(JsonObject(mapOf("format" to JsonPrimitive(LibraryFormat), "rules" to JsonArray(list))))
}

/** Returns the contents of an empty list file such as `rules.json`. */
internal fun emptyListFile(key: String): ByteArray =
    toJsonText(JsonObject(mapOf("format" to JsonPrimitive(LibraryFormat), key to JsonArray(emptyList()))))
