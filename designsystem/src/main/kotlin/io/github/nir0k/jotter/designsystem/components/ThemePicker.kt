package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.AppColors
import io.github.nir0k.jotter.designsystem.theme.PaletteTheme
import io.github.nir0k.jotter.designsystem.theme.Shapes
import io.github.nir0k.jotter.designsystem.theme.ThemeMode
import io.github.nir0k.jotter.designsystem.theme.ThemePalette

/**
 * ThemeModeSelector is the System / Light / Dark [SegmentedChoice]. For a palette with a single
 * variant it is disabled and the caller explains why.
 *
 * @param mode The current choice.
 * @param onModeChange Receives the new choice.
 * @param label Localized name above the buttons, or null.
 * @param enabled Whether the current palette has both variants.
 */
@Composable
fun ThemeModeSelector(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    SegmentedChoice(
        label,
        listOf(
            Choice(ThemeMode.System, stringResource(R.string.ds_theme_system)),
            Choice(ThemeMode.Light, stringResource(R.string.ds_theme_light)),
            Choice(ThemeMode.Dark, stringResource(R.string.ds_theme_dark)),
        ),
        mode,
        onModeChange,
        modifier,
        enabled,
    )
}

/**
 * ThemePaletteCard shows one palette in its own colours so the reader can compare before
 * choosing: the name, a strip of its main colours and a small sample (title, text, a pill, a
 * badge and a switch). The current palette is outlined in the app's primary colour and marked
 * with a check.
 *
 * @param palette The palette to show.
 * @param dark Which variant to show; a palette with one variant always shows it.
 * @param selected Whether this is the current palette.
 * @param onSelect Applies the palette.
 */
@Composable
fun ThemePaletteCard(palette: ThemePalette, dark: Boolean, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val outer = MaterialTheme.colorScheme.primary
    val variant = palette.variant(dark)
    PaletteTheme(variant) {
        val scheme = MaterialTheme.colorScheme
        Card(
            modifier = modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
            shape = Shapes.Card,
            colors = CardDefaults.cardColors(containerColor = scheme.background),
            border = if (selected) BorderStroke(2.dp, outer) else BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(palette.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onBackground)
                        if (!palette.hasBothModes) {
                            Text(stringResource(R.string.ds_theme_dark_only), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    if (selected) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(outer), contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                val status = AppColors.status
                Row(
                    Modifier.fillMaxWidth().height(20.dp).clip(Shapes.Pill).border(1.dp, scheme.outlineVariant, Shapes.Pill),
                ) {
                    listOf(scheme.background, scheme.surface, scheme.primary, scheme.onSurface, status.success, status.warning, scheme.error).forEach {
                        Box(Modifier.weight(1f).height(20.dp).background(it))
                    }
                }
                Card(
                    shape = Shapes.Card,
                    colors = CardDefaults.cardColors(containerColor = scheme.surface),
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.ds_theme_sample_title), style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                            Text(stringResource(R.string.ds_theme_sample_text), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.clip(Shapes.Pill).background(scheme.primary).padding(horizontal = 12.dp, vertical = 4.dp)) {
                                    Text(stringResource(R.string.ds_save), style = MaterialTheme.typography.labelMedium, color = scheme.onPrimary)
                                }
                                TintedBadge(Tag(stringResource(R.string.ds_theme_sample_badge), status.success))
                            }
                        }
                        Switch(checked = true, onCheckedChange = null)
                    }
                }
            }
        }
    }
}
