package io.github.nir0k.jotter.core.format

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The note format version this build reads and writes. */
const val NoteFormat = 1

/**
 * Coordinates attached to a note when it was created.
 *
 * @param accuracy Accuracy in metres, when the position service reported one.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val captured: Instant,
)

/**
 * The metadata block of a note file (desktop spec section 3.3).
 *
 * @param icon Glyph name from the note icon set.
 * @param color Tag colour key or custom colour of the title.
 * @param collections Identifiers of the collections the note belongs to.
 * @param tags Tags in their order, with `/` between the parts of a nested tag.
 * @param extra Fields this build does not know, kept so that rewriting a note does not lose them.
 */
data class NoteMeta(
    val id: String,
    val title: String = "",
    val icon: String? = null,
    val color: String? = null,
    val created: Instant,
    val updated: Instant,
    val collections: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val archived: Instant? = null,
    val trashed: Instant? = null,
    val location: GeoLocation? = null,
    val extra: JsonObject = JsonObject(emptyMap()),
)

/**
 * A note: its metadata and the HTML of its body, the content of `<article class="jotter-body">`.
 */
data class Note(val meta: NoteMeta, val body: String)

/** Creates an empty note with a new identifier, created and updated now. */
fun newNote(): Note {
    val time = now()
    return Note(NoteMeta(id = newId(), created = time, updated = time), body = "")
}

private const val MetaOpen = """<script type="application/json" id="jotter-meta">"""
private const val MetaClose = "</script>"
private const val BodyOpen = """<article class="jotter-body">"""
private const val BodyClose = "</article>"

private val KnownMetaFields = setOf(
    "format", "id", "title", "icon", "color", "created", "updated", "collections", "tags",
    "archived", "trashed", "location",
)

// Readable defaults for a browser in the light Warm palette: typography, quotes, checklists, code
// block titles, tables, image wrapping, attachment chips and the tag colours of text and
// highlights (desktop spec section 3.3). Identical to the desktop's, byte for byte.
private const val BaseStyleSheet =
    "body{max-width:46rem;margin:2rem auto;padding:0 1rem;" +
        "font-family:system-ui,sans-serif;line-height:1.5;color:#18181b;background:#fff}" +
        ".jotter-header{border-bottom:1px solid #eeeae7;margin-bottom:1.5rem}" +
        ".jotter-header p{color:#5d5d5f}" +
        "blockquote{margin:1rem 0;padding:.25rem 1rem;border-left:3px solid #bdb6af;color:#4d4d50}" +
        "ul.checklist{list-style:none;padding-left:1.25rem}" +
        "ul.checklist>li::before{content:'\\2610';margin:0 .4rem 0 -1.25rem}" +
        "ul.checklist>li[data-checked=true]::before{content:'\\2611'}" +
        "ul.checklist>li[data-checked=true]{text-decoration:line-through;color:#6b6b6e}" +
        "mark{color:inherit}" +
        "pre{background:#faf8f6;border:1px solid #eeeae7;border-radius:.375rem;padding:.75rem;" +
        "overflow-x:auto}" +
        "pre[data-title]::before{content:attr(data-title);display:block;margin-bottom:.5rem;" +
        "font:600 .875rem system-ui,sans-serif}" +
        "table{border-collapse:collapse;width:100%;table-layout:fixed}" +
        "td,th{border:1px solid #bdb6af;padding:.25rem .5rem;vertical-align:top;text-align:left;" +
        "overflow-wrap:anywhere}" +
        "th{background:#faf8f6}td>p,th>p{margin:0}" +
        "img{max-width:100%;vertical-align:bottom}" +
        "img[data-wrap=square]{float:left;margin:0 .75rem .75rem 0}" +
        "img[data-wrap=top-bottom]{display:block;margin-bottom:.75rem}" +
        "a.attachment{display:inline-block;padding:0 .5rem;border:1px solid #eeeae7;" +
        "border-radius:.375rem;background:#faf8f6;color:inherit;text-decoration:none}" +
        "a.attachment::before{content:'\\1F4CE\\FE0E';margin-right:.25rem}"

/** The note style sheet: the base rules and one text and one highlight rule per tag colour. */
private val NoteStyleSheet: String = buildString {
    append(BaseStyleSheet)
    for ((key, rgb) in TagColorValues) {
        append("[data-color=$key]{color:#${hex(rgb)}}mark[data-color=$key]{background:#${hex(paperHighlight(rgb))};color:inherit}")
    }
}

private fun hex(rgb: Int): String = rgb.toString(16).padStart(6, '0')

/**
 * Returns the rules that give a browser the widths of a body's images and tables, one per element
 * and width used, in increasing width.
 */
private fun widthStyleSheet(body: String): String {
    val images = sortedSetOf<Int>()
    val tables = sortedSetOf<Int>()
    for (token in tokenizeHtml(body)) {
        if (token.kind != HtmlToken.Kind.Start || (token.name != "img" && token.name != "table")) continue
        val width = integerAttribute(token.attribute("data-width"))
        if (width in 1..99) (if (token.name == "img") images else tables) += width
    }
    return images.joinToString("") { "img[data-width=\"$it\"]{max-width:$it%}" } +
        tables.joinToString("") { "table[data-width=\"$it\"]{width:$it%}" }
}

/** Builds the metadata object; unknown fields read from the file are written back unchanged. */
private fun metaToJson(meta: NoteMeta): JsonObject {
    val location: JsonElement = meta.location?.let {
        JsonObject(
            mapOf(
                "lat" to JsonPrimitive(it.latitude),
                "lon" to JsonPrimitive(it.longitude),
                "accuracy" to (it.accuracy?.let(::JsonPrimitive) ?: JsonNull),
                "captured" to JsonPrimitive(formatTime(it.captured)),
            ),
        )
    } ?: JsonNull
    return JsonObject(
        meta.extra + mapOf(
            "format" to JsonPrimitive(NoteFormat),
            "id" to JsonPrimitive(meta.id),
            "title" to JsonPrimitive(meta.title),
            "icon" to jsonString(meta.icon),
            "color" to jsonString(meta.color),
            "created" to JsonPrimitive(formatTime(meta.created)),
            "updated" to JsonPrimitive(formatTime(meta.updated)),
            "collections" to JsonArray(meta.collections.map(::JsonPrimitive)),
            "tags" to JsonArray(meta.tags.map(::JsonPrimitive)),
            "archived" to jsonString(meta.archived?.let(::formatTime)),
            "trashed" to jsonString(meta.trashed?.let(::formatTime)),
            "location" to location,
        ),
    )
}

/** Reads metadata fields with type checks; the first wrong field names the error. */
private class MetaReader(private val json: JsonObject) {
    /** The first problem found, null while the metadata is valid. */
    var problem: String? = null
        private set

    fun string(key: String, required: Boolean): String {
        val value = json[key]
        if (value.isString) return value.qtString()
        if (required || value != null) report(key)
        return ""
    }

    fun optionalString(key: String): String? {
        val value = json[key]
        if (value.isString) return value.qtString()
        if (value != null && !value.isNull) report(key)
        return null
    }

    fun time(key: String): Instant = parseTime(json[key].qtString()) ?: Instant.EPOCH.also { report(key) }

    fun optionalTime(key: String): Instant? {
        val value = json[key]
        if (value == null || value.isNull) return null
        return parseTime(value.qtString()) ?: null.also { report(key) }
    }

    fun strings(key: String): List<String> {
        val value = json[key] ?: return emptyList()
        if (value !is JsonArray || value.any { !it.isString }) {
            report(key)
            return emptyList()
        }
        return value.map { it.qtString() }
    }

    fun location(): GeoLocation? {
        val value = json["location"]
        if (value == null || value.isNull) return null
        val location = value.qtObject()
        val lat = location["lat"].qtDouble()
        val lon = location["lon"].qtDouble()
        val accuracy = location["accuracy"]
        val captured = parseTime(location["captured"].qtString())
        if (lat == null || lon == null || kotlin.math.abs(lat) > 90.0 || kotlin.math.abs(lon) > 180.0 ||
            captured == null || (accuracy != null && !accuracy.isNull && !accuracy.isNumber)
        ) {
            report("location")
            return null
        }
        return GeoLocation(lat, lon, accuracy.qtDouble(), captured)
    }

    private fun report(key: String) {
        if (problem == null) problem = "invalid field \"$key\""
    }
}

/**
 * Builds a complete note document: the title, the head block, the style sheet, the generated
 * header and the body.
 *
 * @param meta The note's metadata, for the title and tags.
 * @param body The body HTML.
 * @param head Extra elements of the head, such as the metadata block.
 * @param subtitle The line under the title in an export; empty in a note file.
 * @return The document in UTF-8.
 */
private fun noteHtml(meta: NoteMeta, body: String, head: String, subtitle: String = ""): ByteArray {
    val title = htmlEscaped(meta.title)
    val tags = meta.tags.joinToString(" ") { "#" + htmlEscaped(it) }
    val html = StringBuilder()
    html.append("<!doctype html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
    html.append("<title>").append(title).append("</title>\n")
    html.append(head)
    html.append("<style>").append(NoteStyleSheet).append(numberingStyleSheet(body))
        .append(widthStyleSheet(body)).append("</style>\n")
    html.append("</head>\n<body>\n<header class=\"jotter-header\"><h1>").append(title).append("</h1>")
    if (subtitle.isNotEmpty()) html.append("<p>").append(htmlEscaped(subtitle)).append("</p>")
    if (tags.isNotEmpty()) html.append("<p>").append(tags).append("</p>")
    html.append("</header>\n")
    html.append(BodyOpen).append('\n').append(body).append('\n').append(BodyClose).append('\n')
    html.append("</body>\n</html>\n")
    return html.toString().toByteArray(Charsets.UTF_8)
}

/**
 * Parses a note file whose body follows the vocabulary (desktop spec section 3.3).
 *
 * @param data The file's contents.
 * @param path The file, for errors; its name without the extension must equal the note's
 *   identifier.
 * @return The note.
 * @throws FormatException Malformed, with the reason in the detail, or NewerFormat.
 */
fun parseNote(data: ByteArray, path: Path): Note {
    val note = parseNoteForNormalization(data, path)
    bodyProblem(note.body)?.let { throw FormatException(ErrorCode.Malformed, path.toString(), it) }
    return note
}

/**
 * Parses valid metadata and the raw body of a note file, whatever the body holds, for an explicit
 * normalised copy only: the result is never saved over the source file.
 *
 * @param data The file's contents.
 * @param path The file, for errors; its name without the extension must equal the note's
 *   identifier.
 * @return The note.
 * @throws FormatException Malformed, with the reason in the detail, or NewerFormat.
 */
fun parseNoteForNormalization(data: ByteArray, path: Path): Note {
    val file = path.toString()
    val text = String(data, Charsets.UTF_8)
    val metaStart = text.indexOf(MetaOpen)
    val metaEnd = if (metaStart < 0) -1 else text.indexOf(MetaClose, metaStart + MetaOpen.length)
    if (metaEnd < 0) throw FormatException(ErrorCode.Malformed, file, "no metadata block")
    val json = parseJsonObject(text.substring(metaStart + MetaOpen.length, metaEnd).toByteArray(), file)
    val format = json["format"].qtInt(0)
    if (format > NoteFormat) throw FormatException(ErrorCode.NewerFormat, file)
    if (format < 1) throw FormatException(ErrorCode.Malformed, file, "invalid field \"format\"")

    val reader = MetaReader(json)
    val meta = NoteMeta(
        id = reader.string("id", required = true),
        title = reader.string("title", required = false),
        icon = reader.optionalString("icon"),
        color = reader.optionalString("color"),
        created = reader.time("created"),
        updated = reader.time("updated"),
        collections = reader.strings("collections"),
        tags = reader.strings("tags"),
        archived = reader.optionalTime("archived"),
        trashed = reader.optionalTime("trashed"),
        location = reader.location(),
        extra = unknownFields(json, KnownMetaFields),
    )
    reader.problem?.let { throw FormatException(ErrorCode.Malformed, file, it) }
    if (!isValidId(meta.id) || path.fileName.toString().substringBeforeLast('.') != meta.id) {
        throw FormatException(ErrorCode.Malformed, file, "the identifier does not match the file name")
    }

    val bodyStart = text.indexOf(BodyOpen, metaEnd)
    val bodyEnd = text.lastIndexOf(BodyClose)
    if (bodyStart < 0 || bodyEnd < bodyStart) throw FormatException(ErrorCode.Malformed, file, "no body")
    // The writer puts the body on lines of its own; the newlines are not part of the content.
    val body = text.substring(bodyStart + BodyOpen.length, bodyEnd).removePrefix("\n").removeSuffix("\n")
    return Note(meta, body)
}

/**
 * Serialises a note into a complete HTML document: the metadata block, a generated header with
 * the title and tags for reading in a browser, a generated style sheet and the body.
 *
 * @return The file's contents.
 */
fun serializeNote(note: Note): ByteArray {
    // '<' is escaped inside the JSON so that no title or tag can end the script element early.
    val metaText = String(toJsonText(metaToJson(note.meta)), Charsets.UTF_8)
        .replace("<", "\\u003c").removeSuffix("\n")
    return noteHtml(note.meta, note.body, "$MetaOpen\n$metaText\n$MetaClose\n")
}

/**
 * Writes a note as the document of an HTML export: the form of a note file without the metadata
 * block, with a line under the title.
 *
 * @param meta The note's metadata, for the title and tags.
 * @param body The body HTML, already limited to the note vocabulary.
 * @param subtitle The line under the title.
 * @return The document in UTF-8.
 */
fun exportNoteDocument(meta: NoteMeta, body: String, subtitle: String): ByteArray = noteHtml(meta, body, "", subtitle)
