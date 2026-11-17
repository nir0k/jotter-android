package io.github.nir0k.jotter.editor

import androidx.compose.ui.graphics.Color
import io.github.nir0k.jotter.core.format.TagColorValues
import io.github.nir0k.jotter.core.format.isCustomColor
import io.github.nir0k.jotter.core.highlight.CodeTheme
import kotlin.math.roundToInt

/**
 * NoteColors are the palette roles a note is drawn with, as `0xRRGGBB` values.
 *
 * @param background The page.
 * @param text Ordinary text.
 * @param muted Quotes, checked items, line numbers.
 * @param link Links.
 * @param raised Code blocks, table headers and attachment chips.
 * @param outline Table and quote lines.
 * @param edge The borders of code blocks and chips.
 * @param primary Checked boxes, handles and the selected object.
 * @param onPrimary The check mark on a checked box.
 * @param dark Whether the palette is dark, which strengthens highlights.
 * @param codeTheme The highlighting theme of code blocks: `breeze-light`, `breeze-dark` or `monokai`.
 */
data class NoteColors(
    val background: Int,
    val text: Int,
    val muted: Int,
    val link: Int,
    val raised: Int,
    val outline: Int,
    val edge: Int,
    val primary: Int,
    val onPrimary: Int,
    val dark: Boolean,
    val codeTheme: String,
) {
    /** The highlighting theme of code blocks. */
    val theme: CodeTheme get() = CodeTheme.named(codeTheme)
}

/** Returns a `0xRRGGBB` value as an opaque colour. */
internal fun rgb(value: Int): Color = Color(0xFF000000.toInt() or (value and 0xFFFFFF))

private val TagColors: Map<String, Int> = TagColorValues.associate { it.key to it.rgb }

/** Returns the value of a stored colour key: a tag colour or `#rrggbb`; null for a key the application does not know. */
private fun colorValue(key: String): Int? = if (isCustomColor(key)) key.substring(1).toInt(16) else TagColors[key]

/** Mixes a colour over another at a share of the first, channel by channel. */
private fun mix(color: Int, share: Double, over: Int): Int {
    var result = 0
    for (shift in intArrayOf(16, 8, 0)) {
        val value = (((color shr shift) and 0xFF) * share + ((over shr shift) and 0xFF) * (1 - share)).roundToInt()
        result = result or (value shl shift)
    }
    return result
}

/**
 * Returns the colour of text in a stored colour, in the shade of the palette as the desktop draws
 * it: the colour mixed 65% with the text colour; null to keep the text's own.
 */
internal fun NoteColors.textShade(key: String): Color? = colorValue(key)?.let { rgb(mix(it, 0.65, text)) }

/** Returns the colour of a highlight: the colour at 28% (38% in dark palettes) over the page; null for none. */
internal fun NoteColors.highlightShade(key: String): Color? = colorValue(key)?.let { rgb(mix(it, if (dark) 0.38 else 0.28, background)) }
