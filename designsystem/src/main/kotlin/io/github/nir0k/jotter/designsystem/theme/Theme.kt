package io.github.nir0k.jotter.designsystem.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

private val LocalStatusColors = staticCompositionLocalOf { WarmLightStatus }
private val LocalChartColors = staticCompositionLocalOf { LightChartColors }

/**
 * ThemeMode is the reader's light/dark choice. `System` follows the device setting; the
 * choice is stored on the device, not in the account.
 */
enum class ThemeMode { System, Light, Dark }

/**
 * ThemeVariant is one light or dark look of a palette.
 *
 * @param colors Material roles.
 * @param status Status colours beyond Material's roles.
 * @param chart Categorical series colours of charts; by default the light or dark set that matches
 * the page colour, so a product palette only overrides it for a reason.
 */
@Immutable
class ThemeVariant(
    val colors: ColorScheme,
    val status: StatusColors,
    val chart: List<Color> = if (colors.background.luminance() < 0.5f) DarkChartColors else LightChartColors,
) {
    /** Whether this look is dark; it decides the system bar icons and the chart colours. */
    val isDark: Boolean get() = colors.background.luminance() < 0.5f
}

/**
 * ThemePalette is a named colour theme the reader can choose in Settings. A palette has a light
 * variant, a dark variant or both; with only one, the light/dark choice does not apply. Warm is
 * the default and the identity of the family; the others exist for readers who prefer them.
 *
 * A product adds its own palettes by creating more instances in its own code and passing
 * `BuiltIn + its palettes` to the theme picker and to [resolve]; the design system module is not
 * edited.
 *
 * @param id Stable key that is stored on the device and in the account; never reused or renamed
 * without an entry in the `renamed` map of [resolve].
 * @param title Palette name shown to the reader (a proper name, not translated).
 */
@Immutable
class ThemePalette(val id: String, val title: String, val light: ThemeVariant?, val dark: ThemeVariant?) {
    init {
        require(light != null || dark != null) { "palette $id has no variant" }
    }

    /** Whether the reader's light/dark choice changes this palette. */
    val hasBothModes: Boolean get() = light != null && dark != null

    /**
     * variant returns the look worn for a requested darkness; a palette with one variant always
     * wears it.
     *
     * @param dark Whether the dark look is wanted.
     * @return The matching variant, or the only one the palette has.
     */
    fun variant(dark: Boolean): ThemeVariant = (if (dark) this.dark else light) ?: this.dark ?: light!!

    companion object {
        /** Warm: the default palette with orange accents. */
        val Warm = ThemePalette("warm", "Warm", ThemeVariant(WarmLightColors, WarmLightStatus), ThemeVariant(WarmDarkColors, WarmDarkStatus))

        /** GitHub: blue accents on cool greys, light and dark. */
        val GitHub = ThemePalette("github", "GitHub", ThemeVariant(GitHubLightColors, GitHubLightStatus), ThemeVariant(GitHubDarkColors, GitHubDarkStatus))

        /** GitHub Dimmed: soft greys, dark only. */
        val GitHubDimmed = ThemePalette("github-dimmed", "GitHub Dimmed", null, ThemeVariant(GitHubDimmedColors, GitHubDimmedStatus))

        /** Monokai: VS Code's Monokai colours, dark only. */
        val Monokai = ThemePalette("monokai", "Monokai", null, ThemeVariant(MonokaiColors, MonokaiStatus))

        /** The palettes of the design system in picker order, Warm first. The web uses the same ids. */
        val BuiltIn: List<ThemePalette> = listOf(Warm, GitHub, GitHubDimmed, Monokai)

        /**
         * resolve finds the palette of a stored id. A renamed id is translated first; an id that is
         * unknown (a palette that was removed) falls back to Warm, so a stored choice never breaks
         * the start-up.
         *
         * @param id The stored id, or null when nothing was stored.
         * @param palettes The palettes the product offers.
         * @param renamed Old ids mapped to their current ones ("github-classic" to "github").
         * @return The matching palette, or Warm.
         */
        fun resolve(id: String?, palettes: List<ThemePalette> = BuiltIn, renamed: Map<String, String> = emptyMap()): ThemePalette {
            val current = renamed[id] ?: id
            return palettes.firstOrNull { it.id == current } ?: Warm
        }
    }
}

/**
 * AppTheme applies a palette in the reader's light/dark mode, the ordinary type scale and the
 * status and chart colours. Inside an activity it also draws the app edge to edge and sets the
 * status and navigation bar icons for the variant that is worn, not for the phone's setting: a
 * dark-only palette on a phone set to light still gets light icons.
 *
 * @param mode The reader's light/dark choice.
 * @param palette The reader's palette; Warm by default.
 * @param content The themed content.
 */
@Composable
fun AppTheme(mode: ThemeMode = ThemeMode.System, palette: ThemePalette = ThemePalette.Warm, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val variant = palette.variant(dark)
    SystemBarsEffect(variant.isDark)
    CompositionLocalProvider(LocalStatusColors provides variant.status, LocalChartColors provides variant.chart) {
        MaterialTheme(colorScheme = variant.colors, typography = AppTypography, content = content)
    }
}

// SystemBarsEffect keeps the hosting activity edge to edge with transparent bars whose icons
// suit a light or dark look. Outside an activity (previews) it does nothing.
@Composable
private fun SystemBarsEffect(dark: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity, dark) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
}

/**
 * PaletteTheme applies one fixed variant locally, for previews of other palettes (the theme
 * picker shows each palette in its own colours).
 *
 * @param variant The variant to preview.
 */
@Composable
fun PaletteTheme(variant: ThemeVariant, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides variant.status, LocalChartColors provides variant.chart) {
        MaterialTheme(colorScheme = variant.colors, typography = MaterialTheme.typography, content = content)
    }
}

/** AppColors gives access to the colours that extend Material's scheme. */
object AppColors {
    /** The status colours of the current variant. */
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current

    /** The ten categorical series colours of the current variant, in their fixed order. */
    val chart: List<Color>
        @Composable @ReadOnlyComposable get() = LocalChartColors.current
}

/**
 * Tells whether a light or dark choice draws the dark variant of a palette, as the design
 * system's theme decides it.
 *
 * @param systemDark Whether the system is in dark mode, which [ThemeMode.System] follows.
 */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
