package io.github.nir0k.jotter.ui.theme

import androidx.compose.ui.graphics.toArgb
import io.github.nir0k.jotter.core.format.TagColorValues
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.TagColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks that the names and colour keys library files store resolve as the desktop resolves them. */
class ItemStyleTest {
    /**
     * Every glyph name of the desktop's icon manifest shows its glyph, not only the picker's: a
     * note synchronised from the desktop may store any of them (desktop spec section 3.9).
     */
    @Test
    fun everyDesktopIconNameResolves() {
        assertEquals(185, JotterIconNames.size)
        val missing = JotterIconNames.filter { it !in ItemIcons }
        assertTrue("Unknown glyphs: $missing", missing.isEmpty())
        assertEquals(JotterIconNames, ItemIcons.keys)
        // Retired from the picker, but drawn by the interface, so stored names keep them.
        assertNotNull(ItemIcons["Favorite"])
        assertNotNull(ItemIcons["Settings"])
    }

    /**
     * A name Jotter no longer has shows no glyph and stays as it is stored, also when the design
     * system keeps the glyph for other products (`Office`) or has deleted it (`Bin`); a collection
     * then shows `Folder`.
     */
    @Test
    fun removedIconNamesShowNoGlyph() {
        for (name in listOf("Office", "Bin", "NoSuchGlyph")) {
            assertNull(name, ItemIcons[name])
            assertSame(AppIcons.Folder, collectionIcon(name))
        }
        assertNotNull(AppIcons.Groups.flatMap { it.second }.firstOrNull { it.name == "Office" })
        assertSame(ItemIcons["Book"], collectionIcon("Book"))
    }

    /**
     * The tag colours `core` writes into note style sheets are the design system's, by key, value
     * and order, so a colour drawn in the application and in an exported file is the same.
     */
    @Test
    fun coreTagColoursMatchDesignSystem() {
        assertEquals(TagColor.entries.map { it.key }, TagColorValues.map { it.key })
        assertEquals(TagColor.entries.map { it.color.toArgb() and 0xFFFFFF }, TagColorValues.map { it.rgb })
    }
}
