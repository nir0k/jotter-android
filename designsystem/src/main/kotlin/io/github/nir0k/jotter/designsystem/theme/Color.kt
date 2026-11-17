package io.github.nir0k.jotter.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

// The Warm palette: warm orange actions, a warm light page with white cards, and slate
// surfaces in the dark variant. Wallpaper-derived dynamic colour is deliberately not used.

private val LightPrimary = Color(0xFFC2410C)
private val LightContent = Color(0xFF18181B)
private val LightCard = Color(0xFFFFFFFF)
private val LightPage = Color(0xFFFAF8F6)
private val LightEdge = Color(0xFFEEEAE7)

private val DarkPrimary = Color(0xFFFB923C)
private val DarkContent = Color(0xFFECF9FF)
private val DarkCard = Color(0xFF1D232A)
private val DarkPage = Color(0xFF191E24)
private val DarkEdge = Color(0xFF15191E)
private val DarkRaised = Color(0xFF2A323B)

// mix composites a colour at the given opacity over a base colour.
private fun mix(color: Color, alpha: Float, over: Color): Color = color.copy(alpha = alpha).compositeOver(over)

/**
 * WarmLightColors is the light variant of the default palette. The page uses `background`,
 * cards use `surface`, and `outlineVariant` draws the subtle card border.
 */
val WarmLightColors: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = mix(LightPrimary, 0.14f, LightCard),
    onPrimaryContainer = mix(LightPrimary, 0.85f, LightContent),
    // Secondary roles repeat the primary ones, so Material's selected states (navigation
    // indicator, selected chips) are orange like every other active element.
    secondary = LightPrimary,
    onSecondary = Color.White,
    secondaryContainer = mix(LightPrimary, 0.14f, LightCard),
    onSecondaryContainer = mix(LightPrimary, 0.85f, LightContent),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = mix(Color(0xFFB45309), 0.12f, LightCard),
    onTertiaryContainer = Color(0xFF7C3A06),
    background = LightPage,
    onBackground = LightContent,
    surface = LightCard,
    onSurface = LightContent,
    surfaceVariant = LightPage,
    onSurfaceVariant = mix(LightContent, 0.7f, LightCard),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = LightCard,
    surfaceContainerLow = LightCard,
    surfaceContainer = LightCard,
    surfaceContainerHigh = LightPage,
    surfaceContainerHighest = LightEdge,
    outline = Color(0xFFBDB6AF),
    outlineVariant = LightEdge,
    error = Color(0xFFB91C1C),
    onError = Color.White,
    errorContainer = mix(Color(0xFFB91C1C), 0.10f, LightCard),
    onErrorContainer = Color(0xFF7F1D1D),
)

/** WarmDarkColors is the dark variant of the default palette, built on slate surfaces. */
val WarmDarkColors: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF1C1917),
    primaryContainer = mix(DarkPrimary, 0.20f, DarkCard),
    onPrimaryContainer = mix(DarkPrimary, 0.9f, DarkContent),
    secondary = DarkPrimary,
    onSecondary = Color(0xFF1C1917),
    secondaryContainer = mix(DarkPrimary, 0.20f, DarkCard),
    onSecondaryContainer = mix(DarkPrimary, 0.9f, DarkContent),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF1C1917),
    tertiaryContainer = mix(Color(0xFFFBBF24), 0.18f, DarkCard),
    onTertiaryContainer = Color(0xFFFDE08A),
    background = DarkPage,
    onBackground = DarkContent,
    surface = DarkCard,
    onSurface = DarkContent,
    surfaceVariant = DarkPage,
    onSurfaceVariant = mix(DarkContent, 0.7f, DarkCard),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = DarkEdge,
    surfaceContainerLow = DarkPage,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkPage,
    surfaceContainerHighest = DarkRaised,
    outline = Color(0xFF55606C),
    outlineVariant = DarkRaised,
    error = Color(0xFFFF627D),
    onError = Color(0xFF4D0218),
    errorContainer = mix(Color(0xFFFF627D), 0.18f, DarkCard),
    onErrorContainer = Color(0xFFFFB3C0),
)

/**
 * StatusColors holds the semantic colours Material's scheme has no role for. Components use
 * them for state (success, warning, information, ratings) and never as decoration.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val warning: Color,
    val info: Color,
)

/** WarmLightStatus holds the status colours of the light variant. */
val WarmLightStatus = StatusColors(
    success = Color(0xFF15803D),
    warning = Color(0xFFFBBF24),
    info = Color(0xFF0369A1),
)

/** WarmDarkStatus holds the status colours of the dark variant. */
val WarmDarkStatus = StatusColors(
    success = Color(0xFF00D390),
    warning = Color(0xFFFCB700),
    info = Color(0xFF00BAFE),
)

/**
 * TagColor lists the fixed colours people choose for their own things: user tags, item titles,
 * text colour and highlights. Products store [key], not the value, so the palette can be tuned
 * without migrating data. The order is the order of every colour grid: nineteen hues, then the
 * ten colours added in 3.2.0 (maroon to graphite).
 */
enum class TagColor(val color: Color) {
    Red(Color(0xFFDC2626)),
    Orange(Color(0xFFEA580C)),
    Amber(Color(0xFFD97706)),
    Yellow(Color(0xFFCA8A04)),
    Lime(Color(0xFF65A30D)),
    Green(Color(0xFF16A34A)),
    Emerald(Color(0xFF059669)),
    Teal(Color(0xFF0D9488)),
    Cyan(Color(0xFF0891B2)),
    Sky(Color(0xFF0284C7)),
    Blue(Color(0xFF2563EB)),
    Indigo(Color(0xFF4F46E5)),
    Violet(Color(0xFF7C3AED)),
    Purple(Color(0xFF9333EA)),
    Fuchsia(Color(0xFFC026D3)),
    Pink(Color(0xFFDB2777)),
    Rose(Color(0xFFE11D48)),
    Brown(Color(0xFF92400E)),
    Gray(Color(0xFF6B7280)),
    Maroon(Color(0xFF7F1D1D)),
    Coral(Color(0xFFF2705E)),
    Gold(Color(0xFFD4AF37)),
    Beige(Color(0xFFC2A47C)),
    Olive(Color(0xFF708238)),
    Mint(Color(0xFF34D399)),
    Navy(Color(0xFF1E3A8A)),
    Lavender(Color(0xFFA78BFA)),
    Slate(Color(0xFF475569)),
    Graphite(Color(0xFF3F3F46)),
    ;

    /** The stored key: the lower-case name, the same on every platform ("red", "graphite"). */
    val key: String get() = name.lowercase()

    companion object {
        /**
         * fromKey finds the tag colour of a stored key.
         *
         * @param key The stored key.
         * @return The colour, or null for a custom colour, an unknown key or none.
         */
        fun fromKey(key: String?): TagColor? = entries.firstOrNull { it.key == key }
    }
}

/**
 * ChosenColor draws a colour a person chose — a [TagColor] key or a custom `#rrggbb` value — in
 * the current palette and mode with the mixes of foundations/colour.md, so every colour stays
 * readable in light and dark palettes.
 */
object ChosenColor {
    private val CustomPattern = Regex("^#[0-9a-fA-F]{6}$")

    /**
     * isCustom tells whether a stored value is a custom colour rather than a key.
     *
     * @param key The stored value.
     * @return True for `#rrggbb` in either case.
     */
    fun isCustom(key: String?): Boolean = key != null && CustomPattern.matches(key)

    /**
     * resolve returns the base colour of a stored value.
     *
     * @param key A tag colour key, a custom `#rrggbb` value or null.
     * @return The colour, or null for none or an unknown key, which is drawn without colour.
     */
    fun resolve(key: String?): Color? = when {
        key == null -> null
        isCustom(key) -> Color(0xFF000000 or key.substring(1).toLong(16))
        else -> TagColor.fromKey(key)?.color
    }

    /**
     * custom formats a colour as the stored custom value.
     *
     * @param color The colour; its alpha is ignored.
     * @return `#rrggbb` in lower case.
     */
    fun custom(color: Color): String = "#%06x".format(color.toArgb() and 0xFFFFFF)

    /** text returns text and glyphs in the colour: 65% of it over `onSurface`. */
    fun text(color: Color, scheme: ColorScheme): Color = mix(color, 0.65f, scheme.onSurface)

    /** fill returns a pill's fill: 14% of the colour over `surface`. */
    fun fill(color: Color, scheme: ColorScheme): Color = mix(color, 0.14f, scheme.surface)

    /** border returns a pill's border: 45% of the colour over `surface`. */
    fun border(color: Color, scheme: ColorScheme): Color = mix(color, 0.45f, scheme.surface)

    /** highlight returns a highlight behind ordinary text: 28% over `surface` in light variants, 38% in dark ones. */
    fun highlight(color: Color, scheme: ColorScheme): Color =
        mix(color, if (scheme.surface.luminance() < 0.5f) 0.38f else 0.28f, scheme.surface)
}

/**
 * PaletteColors is the short list of colours a non-Warm palette is defined by; the Material roles
 * are derived from it by [schemeOf] with the same rules as Warm.
 */
private class PaletteColors(
    val dark: Boolean,
    val page: Color,
    val card: Color,
    val edge: Color,
    val raised: Color,
    val outline: Color,
    val content: Color,
    val primary: Color,
    val onPrimary: Color,
    val tertiary: Color,
    val error: Color,
    val onAccent: Color = if (dark) Color(0xFF0D1117) else Color.White,
)

// schemeOf derives a Material scheme from palette colours. Secondary roles repeat the primary
// ones, as in Warm, so every selected state uses the palette's main colour.
private fun schemeOf(p: PaletteColors): ColorScheme {
    val onAccent = p.onAccent
    val primaryContainer = mix(p.primary, 0.18f, p.card)
    val onPrimaryContainer = mix(p.primary, 0.9f, p.content)
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.primary, onPrimary = p.onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
        secondary = p.primary, onSecondary = p.onPrimary, secondaryContainer = primaryContainer, onSecondaryContainer = onPrimaryContainer,
        tertiary = p.tertiary, onTertiary = onAccent, tertiaryContainer = mix(p.tertiary, 0.16f, p.card), onTertiaryContainer = mix(p.tertiary, 0.9f, p.content),
        background = p.page, onBackground = p.content, surface = p.card, onSurface = p.content,
        surfaceVariant = p.page, onSurfaceVariant = mix(p.content, 0.7f, p.card), surfaceTint = Color.Transparent,
        surfaceContainerLowest = if (p.dark) p.edge else p.card, surfaceContainerLow = if (p.dark) p.page else p.card,
        surfaceContainer = p.card, surfaceContainerHigh = p.page, surfaceContainerHighest = p.raised,
        outline = p.outline, outlineVariant = if (p.dark) p.raised else p.edge,
        error = p.error, onError = onAccent, errorContainer = mix(p.error, 0.16f, p.card), onErrorContainer = mix(p.error, 0.9f, p.content),
    )
}

/** GitHubLightColors is the light variant of the GitHub palette: blue accents on cool greys. */
val GitHubLightColors: ColorScheme = schemeOf(
    PaletteColors(
        dark = false, page = Color(0xFFF6F8FA), card = Color(0xFFFFFFFF), edge = Color(0xFFD0D7DE), raised = Color(0xFFEAEEF2), outline = Color(0xFF8C959F),
        content = Color(0xFF1F2328), primary = Color(0xFF0969DA), onPrimary = Color.White, tertiary = Color(0xFFBC4C00), error = Color(0xFFCF222E),
    ),
)

/** GitHubDarkColors is the dark variant of the GitHub palette. */
val GitHubDarkColors: ColorScheme = schemeOf(
    PaletteColors(
        dark = true, page = Color(0xFF0D1117), card = Color(0xFF161B22), edge = Color(0xFF30363D), raised = Color(0xFF21262D), outline = Color(0xFF6E7681),
        content = Color(0xFFE6EDF3), primary = Color(0xFF2F81F7), onPrimary = Color.White, tertiary = Color(0xFFDB6D28), error = Color(0xFFF85149),
    ),
)

/** GitHubDimmedColors is the only (dark) variant of the GitHub Dimmed palette: soft greys, muted blue. */
val GitHubDimmedColors: ColorScheme = schemeOf(
    PaletteColors(
        dark = true, page = Color(0xFF22272E), card = Color(0xFF2D333B), edge = Color(0xFF444C56), raised = Color(0xFF373E47), outline = Color(0xFF768390),
        content = Color(0xFFADBAC7), primary = Color(0xFF539BF5), onPrimary = Color(0xFF22272E), tertiary = Color(0xFFCC6B2C), error = Color(0xFFE5534B),
    ),
)

/**
 * MonokaiColors is the only (dark) variant of the Monokai palette: the surfaces of VS Code's
 * built-in Monokai theme and one syntax colour per accent role, with the editor colour on accents.
 */
val MonokaiColors: ColorScheme = schemeOf(
    PaletteColors(
        dark = true, page = Color(0xFF1E1F1C), card = Color(0xFF272822), edge = Color(0xFF414339), raised = Color(0xFF414339), outline = Color(0xFF75715E),
        content = Color(0xFFF8F8F2), primary = Color(0xFFFD971F), onPrimary = Color(0xFF272822), tertiary = Color(0xFFAE81FF), error = Color(0xFFF92672),
        onAccent = Color(0xFF272822),
    ),
)

/** MonokaiStatus holds the status colours of the Monokai palette. */
val MonokaiStatus = StatusColors(success = Color(0xFFA6E22E), warning = Color(0xFFE6DB74), info = Color(0xFF66D9EF))

/** GitHubLightStatus holds the status colours of the GitHub light variant. */
val GitHubLightStatus = StatusColors(success = Color(0xFF1A7F37), warning = Color(0xFF9A6700), info = Color(0xFF0969DA))

/** GitHubDarkStatus holds the status colours of the GitHub dark variant. */
val GitHubDarkStatus = StatusColors(success = Color(0xFF3FB950), warning = Color(0xFFD29922), info = Color(0xFF58A6FF))

/** GitHubDimmedStatus holds the status colours of the GitHub Dimmed palette. */
val GitHubDimmedStatus = StatusColors(success = Color(0xFF57AB5A), warning = Color(0xFFC69026), info = Color(0xFF6CB6FF))

/**
 * LightChartColors is the categorical palette of charts on a light surface: ten hues in a fixed
 * order, shared by every light variant. Series take them in order and keep their colour when other
 * series are hidden; an eleventh series is folded into "Other", never given a recycled colour. The
 * order keeps neighbouring hues apart for readers with colour-vision deficiencies.
 */
val LightChartColors: List<Color> = listOf(
    Color(0xFF2A78D6),
    Color(0xFFEB6834),
    Color(0xFF1BAF7A),
    Color(0xFFEDA100),
    Color(0xFFE87BA4),
    Color(0xFF008300),
    Color(0xFF6250D6),
    Color(0xFFE34948),
    Color(0xFF00A0B8),
    Color(0xFFA0662C),
)

/** DarkChartColors holds the same ten hues as [LightChartColors], stepped for a dark surface. */
val DarkChartColors: List<Color> = listOf(
    Color(0xFF3987E5),
    Color(0xFFD95926),
    Color(0xFF199E70),
    Color(0xFFC98500),
    Color(0xFFD55181),
    Color(0xFF008300),
    Color(0xFF9085E9),
    Color(0xFFE66767),
    Color(0xFF0F9AB3),
    Color(0xFFB8742E),
)
