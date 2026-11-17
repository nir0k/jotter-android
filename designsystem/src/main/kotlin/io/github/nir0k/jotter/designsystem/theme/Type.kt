package io.github.nir0k.jotter.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// style builds one text style on the platform sans-serif family.
private fun style(size: Float, line: Float, tracking: Float, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/**
 * AppTypography is the ordinary type scale used by every screen except explicitly dense
 * lists. The values are written out so a Material update cannot silently change them.
 */
val AppTypography = Typography(
    displaySmall = style(36f, 44f, 0f),
    headlineMedium = style(28f, 36f, 0f),
    headlineSmall = style(24f, 32f, 0f),
    titleLarge = style(22f, 28f, 0f),
    titleMedium = style(16f, 24f, 0.15f, FontWeight.Medium),
    titleSmall = style(14f, 20f, 0.1f, FontWeight.Medium),
    bodyLarge = style(16f, 24f, 0.5f),
    bodyMedium = style(14f, 20f, 0.25f),
    bodySmall = style(12f, 16f, 0.4f),
    labelLarge = style(14f, 20f, 0.1f, FontWeight.Medium),
    labelMedium = style(12f, 16f, 0.5f, FontWeight.Medium),
    labelSmall = style(11f, 16f, 0.5f, FontWeight.Medium),
)

/**
 * code returns this style in the platform monospace family, for text read character by character:
 * codes, identifiers, tokens and masked numbers ("•••• 4821"). Size and weight stay those of the role.
 */
fun TextStyle.code(): TextStyle = copy(fontFamily = FontFamily.Monospace)
