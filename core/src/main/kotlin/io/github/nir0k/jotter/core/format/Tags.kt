package io.github.nir0k.jotter.core.format

/**
 * One of the fixed colours for things people colour themselves: its storage key and its base
 * value.
 *
 * @param key The key library files store.
 * @param rgb The colour as `0xRRGGBB`.
 */
data class TagColorValue(val key: String, val rgb: Int)

/**
 * The tag colours in their order: the design system's, then Jotter's additions from maroon
 * on. The note style sheet gives browsers one rule per colour.
 */
val TagColorValues: List<TagColorValue> = listOf(
    TagColorValue("red", 0xDC2626),
    TagColorValue("orange", 0xEA580C),
    TagColorValue("amber", 0xD97706),
    TagColorValue("yellow", 0xCA8A04),
    TagColorValue("lime", 0x65A30D),
    TagColorValue("green", 0x16A34A),
    TagColorValue("emerald", 0x059669),
    TagColorValue("teal", 0x0D9488),
    TagColorValue("cyan", 0x0891B2),
    TagColorValue("sky", 0x0284C7),
    TagColorValue("blue", 0x2563EB),
    TagColorValue("indigo", 0x4F46E5),
    TagColorValue("violet", 0x7C3AED),
    TagColorValue("purple", 0x9333EA),
    TagColorValue("fuchsia", 0xC026D3),
    TagColorValue("pink", 0xDB2777),
    TagColorValue("rose", 0xE11D48),
    TagColorValue("brown", 0x92400E),
    TagColorValue("gray", 0x6B7280),
    TagColorValue("maroon", 0x7F1D1D),
    TagColorValue("coral", 0xF2705E),
    TagColorValue("gold", 0xD4AF37),
    TagColorValue("beige", 0xC2A47C),
    TagColorValue("olive", 0x708238),
    TagColorValue("mint", 0x34D399),
    TagColorValue("navy", 0x1E3A8A),
    TagColorValue("lavender", 0xA78BFA),
    TagColorValue("slate", 0x475569),
    TagColorValue("graphite", 0x3F3F46),
)

private val CustomColor = Regex("#[0-9a-fA-F]{6}")

/** Tells whether a stored colour key is a custom colour, written as `#rrggbb`, rather than a tag colour's name. */
fun isCustomColor(key: String): Boolean = CustomColor.matches(key)

/**
 * Returns the highlight of a colour in a note file read in a browser: the colour at 30% over
 * white, so black text stays readable on it.
 *
 * @param rgb The colour as `0xRRGGBB`.
 * @return The highlight as `0xRRGGBB`.
 */
fun paperHighlight(rgb: Int): Int {
    var result = 0
    for (shift in intArrayOf(16, 8, 0)) {
        val channel = (rgb shr shift) and 0xFF
        result = result or (((channel * 3 + 255 * 7 + 5) / 10) shl shift)
    }
    return result
}

/**
 * Turns a typed tag into its stored form: without a leading `#`, without empty path parts
 * (`a//b`, `/a`, `a/`) and without spaces around each part.
 *
 * @param typed The tag as typed.
 * @return The tag, or an empty string when nothing is left.
 */
fun normalizedTag(typed: String): String =
    typed.trim().trimStart('#').split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

/**
 * Returns the key tags are compared by: case-folded per code point, as Qt's `toCaseFolded` does
 * for all but a few characters with special folding rules (such as Cherokee letters).
 */
fun tagKey(tag: String): String = buildString(tag.length) {
    tag.codePoints().forEach { appendCodePoint(Character.toLowerCase(Character.toUpperCase(it))) }
}

/**
 * Returns the colour of a tag: its own or, without one, its nearest parent's.
 *
 * @param colors Tag colour keys by tag key, as `library.json` holds them.
 * @param tag The tag.
 * @return The colour key, or null for a tag without colour.
 */
fun tagColor(colors: Map<String, String>, tag: String): String? {
    var key = tagKey(tag)
    while (key.isNotEmpty()) {
        colors[key]?.let { return it }
        key = key.substring(0, maxOf(key.lastIndexOf('/'), 0))
    }
    return null
}

/** Tells whether a tag key is a tag or one of its children (`work`, `work/urgent` within `work`). */
fun isTagWithin(key: String, tag: String): Boolean {
    val parent = tagKey(tag)
    return key == parent || key.startsWith("$parent/")
}

/**
 * Returns the name a tag takes when another tag is renamed: the renamed tag itself takes the new
 * name and its children keep their parts under it.
 *
 * @return The new name, or null for a tag that the rename does not touch.
 */
fun renamedTag(tag: String, from: String, to: String): String? {
    val key = tagKey(tag)
    val fromKey = tagKey(from)
    return when {
        key == fromKey -> to
        // The child keeps the parts after those of the renamed tag, in its own spelling.
        key.startsWith("$fromKey/") -> (listOf(to) + tag.split('/').drop(from.split('/').size)).joinToString("/")
        else -> null
    }
}

/**
 * Lists the library's tags offered for a note while a tag is typed: those matching anywhere in the
 * name, the ones starting with the typed text first and otherwise in the library's order, without
 * the note's own tags.
 *
 * @param known The library's tags.
 * @param applied The note's tags, compared without case.
 * @param query The typed text; a leading `#` is ignored and an empty text matches every tag.
 * @return At most eight tags.
 */
fun tagSuggestions(known: List<String>, applied: List<String>, query: String): List<String> {
    val typed = query.trim().trimStart('#')
    val taken = applied.map(::tagKey).toSet()
    return known.filter { tagKey(it) !in taken && it.contains(typed, ignoreCase = true) }
        .sortedBy { !it.startsWith(typed, ignoreCase = true) }
        .take(8)
}

/**
 * Adds tags to a note's tags: a tag already there in any case is not added again, and
 * duplicates already there go, keeping the first spelling and the order.
 */
fun mergeTags(tags: List<String>, add: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    return (tags + add).filter { it.isNotEmpty() && seen.add(tagKey(it)) }
}
