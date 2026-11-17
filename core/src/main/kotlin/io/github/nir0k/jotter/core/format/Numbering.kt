package io.github.nir0k.jotter.core.format

/** Lists nest up to this many levels (desktop spec section 3.3). */
const val MaxListLevels = 9

/** The numbering style of an `<ol>` without `data-style`: `1.`. */
const val DefaultNumberingStyle = "decimal"

/**
 * A numbering style: how every level of a numbered list counts.
 *
 * @param id The value of the list's `data-style` attribute.
 * @param cssCounter The CSS counter style that draws its numbers.
 * @param includeParents Whether a marker starts with the numbers of the items above it (`1.2.3.`).
 */
data class NumberingStyle(val id: String, val cssCounter: String, val includeParents: Boolean)

/** The numbering styles in the order the interface offers them: `1.`, `A.`, `I.` and Multilevel. */
val NumberingStyles: List<NumberingStyle> = listOf(
    NumberingStyle(DefaultNumberingStyle, "decimal", includeParents = false),
    NumberingStyle("upper-alpha", "upper-latin", includeParents = false),
    NumberingStyle("upper-roman", "upper-roman", includeParents = false),
    NumberingStyle("multilevel", "decimal", includeParents = true),
)

/**
 * Returns a selector for lists at exactly one nesting level: lists sit in the body or in a table
 * cell, and nested lists in an item of the list above.
 */
private fun levelSelector(depth: Int, list: String): String =
    ":is(.jotter-body,td,th)>" + ":is(ul,ol)>li>".repeat(depth - 1) + list

/**
 * Returns the style sheet that makes a browser draw the numbering styles a note body uses with
 * the same markers as the editor: CSS counters per nesting level and a `::marker` rule for each
 * styled list's level. A body with only plain numbered lists, or lists of unknown styles, needs
 * none.
 *
 * @param body The note body.
 * @return The CSS rules, or an empty string.
 */
fun numberingStyleSheet(body: String): String {
    // Ordered as the desktop's sets: styles by their position in NumberingStyles, then depth.
    val styled = sortedSetOf<Pair<Int, Int>>(compareBy({ it.first }, { it.second }))
    val starts = sortedSetOf<Pair<Int, Int>>(compareBy({ it.first }, { it.second }))
    var depth = 0
    var deepest = 0
    for (token in tokenizeHtml(body)) {
        if (token.name != "ul" && token.name != "ol") continue
        if (token.kind == HtmlToken.Kind.End) {
            depth = maxOf(0, depth - 1)
            continue
        }
        if (token.kind != HtmlToken.Kind.Start) continue
        depth++
        if (token.name != "ol") continue
        val start = integerAttribute(token.attribute("start"))
        if (start > 1) starts += depth to start
        val style = NumberingStyles.indexOfFirst { it.id == token.attribute("data-style") }
        if (style <= 0 || depth > MaxListLevels) continue
        styled += style to depth
        deepest = maxOf(deepest, depth)
    }
    if (styled.isEmpty()) return ""

    // Every list level keeps a counter of its own, so a marker can include the numbers of the
    // items above it.
    val css = StringBuilder()
    for (d in 1..deepest) {
        val list = levelSelector(d, ":is(ul,ol)")
        css.append("$list{counter-reset:jotter-$d}$list>li{counter-increment:jotter-$d}")
    }
    for ((d, start) in starts) {
        if (d <= deepest) {
            css.append("${levelSelector(d, "ol[start=\"$start\"]")}{counter-reset:jotter-$d ${start - 1}}")
        }
    }
    for ((index, d) in styled) {
        val style = NumberingStyles[index]
        fun counter(level: Int) = "counter(jotter-$level,${style.cssCounter})"
        val parts = mutableListOf<String>()
        if (style.includeParents) {
            for (i in 1 until d) parts += listOf(counter(i), "\".\"")
        }
        parts += listOf(counter(d), "\". \"")
        css.append("${levelSelector(d, "ol[data-style=\"${style.id}\"]")}>li::marker{content:${parts.joinToString(" ")}}")
    }
    return css.toString()
}
