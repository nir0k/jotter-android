package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.designsystem.theme.Shapes

/**
 * SettingsSection groups related settings in a card with its semibold title inside the card,
 * above the rows. Every profile, settings and detail screen is a column of these sections.
 *
 * @param title Localized section title, or null for an untitled group.
 * @param content The rows and fields, laid out with 10 dp gaps.
 */
@Composable
fun SettingsSection(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = Shapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Dimens.SectionPadding), verticalArrangement = Arrangement.spacedBy(Dimens.SectionFieldGap)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            content()
        }
    }
}

/**
 * ValueRow shows one saved, read-only value: the label on the left, the value on the right.
 *
 * @param muted Whether the value cannot be changed (for example the sign-in e-mail).
 */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp).weight(1.3f),
        )
    }
}

/**
 * SwitchRow is an on/off setting that applies immediately: title, optional explanation and a
 * switch. The whole row toggles.
 *
 * @param title Localized setting name.
 * @param subtitle Optional explanation of the consequence.
 * @param checked Current value.
 * @param onCheckedChange Receives the new value.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TouchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * ChoiceRow is a setting with more options than fit as chips: it shows the current option and
 * opens a [ChoiceDialog]; the choice applies immediately.
 *
 * @param title Localized setting name.
 * @param options The options.
 * @param selected Current key.
 * @param onSelect Receives the new key.
 * @param icon Optional leading glyph.
 */
@Composable
fun <K> ChoiceRow(
    title: String,
    options: List<Choice<K>>,
    selected: K,
    onSelect: (K) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    var open by remember { mutableStateOf(false) }
    NavigationRow(
        title = title,
        value = options.firstOrNull { it.key == selected }?.label,
        onClick = { open = true },
        modifier = modifier,
        icon = icon,
        trailing = AppIcons.ChevronDown,
    )
    if (open) ChoiceDialog(title, options, selected, onSelect) { open = false }
}

/**
 * NavigationRow opens another screen or dialog: optional icon, title, optional subtitle, the
 * current value on the right and a chevron.
 *
 * @param title Localized row title.
 * @param onClick Opens the target.
 * @param subtitle Optional second line.
 * @param value Optional current value shown before the chevron.
 * @param trailing Trailing glyph; a right chevron for screens, a down chevron for choices.
 */
@Composable
fun NavigationRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    value: String? = null,
    trailing: ImageVector = AppIcons.ChevronRight,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.TouchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = muted, modifier = Modifier.size(Dimens.IconMenu)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
        Icon(trailing, null, tint = muted, modifier = Modifier.size(Dimens.IconInline))
    }
}
