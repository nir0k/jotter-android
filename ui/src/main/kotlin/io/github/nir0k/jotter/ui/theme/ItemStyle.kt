package io.github.nir0k.jotter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.ChosenColor

/** Holds the resource with the names of Jotter's icon set. */
private object IconNamesResource

/**
 * The glyph names a note or collection may show: those of the desktop application's icon manifest,
 * which the library format names (desktop spec section 3.9), read from `desktop-icon-names.txt`.
 */
internal val JotterIconNames: Set<String> by lazy {
    IconNamesResource::class.java.getResourceAsStream("/desktop-icon-names.txt")!!.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
    }
}

/**
 * The glyphs a note or collection may carry by the name its file stores: the design system's glyphs
 * of Jotter's icon set, not only those of the icon picker. A name outside the set shows no glyph,
 * also when the design system keeps that glyph for other products, so a note looks the same as on
 * the desktop; the stored name itself is kept unchanged (desktop spec section 3.9).
 */
val ItemIcons: Map<String, ImageVector> by lazy {
    AppIcons.Groups.flatMap { it.second }.filter { it.name in JotterIconNames }.associate { it.name to it.icon }
}

/** Returns the glyph of a collection: the one its file stores when Jotter has it, otherwise `Folder`. */
fun collectionIcon(name: String): ImageVector = ItemIcons[name] ?: AppIcons.Folder

/**
 * Returns the colour of a title or glyph drawn in a stored colour (a tag colour key or
 * `#rrggbb`), mixed with the palette's text colour by the design system's rule so it stays
 * readable in light and dark palettes.
 *
 * @param key The stored key, or null for no colour.
 * @return The colour, or null for no colour or an unknown key.
 */
@Composable
fun itemTextColor(key: String?): Color? {
    val scheme = MaterialTheme.colorScheme
    return ChosenColor.resolve(key)?.let { ChosenColor.text(it, scheme) }
}
