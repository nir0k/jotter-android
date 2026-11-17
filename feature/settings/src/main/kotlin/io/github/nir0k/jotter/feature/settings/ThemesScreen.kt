package io.github.nir0k.jotter.feature.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.nir0k.jotter.ui.SectionScreen
import io.github.nir0k.jotter.designsystem.components.ThemePaletteCard
import io.github.nir0k.jotter.designsystem.theme.ThemeMode
import io.github.nir0k.jotter.designsystem.theme.ThemePalette
import io.github.nir0k.jotter.designsystem.theme.isDark

/**
 * ThemesScreen is the theme picker: one card per palette in its own colours, in the reader's
 * light or dark mode. Tapping a card applies the palette at once.
 *
 * @param palette The palette in use.
 * @param mode The reader's light or dark choice, which decides the variant the cards show.
 * @param onPalette Stores the chosen palette.
 * @param onBack Returns to Settings.
 */
@Composable
fun ThemesScreen(palette: ThemePalette, mode: ThemeMode, onPalette: (ThemePalette) -> Unit, onBack: () -> Unit) {
    val dark = mode.isDark(isSystemInDarkTheme())
    SectionScreen(stringResource(R.string.settings_theme), onBack) {
        item {
            Text(stringResource(R.string.themes_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ThemePalette.BuiltIn.forEach { option ->
            item(key = option.id) { ThemePaletteCard(option, dark, option.id == palette.id, { onPalette(option) }) }
        }
    }
}
