package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.theme.Dimens

/**
 * ProgressButton is the filled pill for the primary action of a form or section. While [busy]
 * it shows a spinner instead of the icon and refuses repeated taps.
 *
 * @param text Localized action label.
 * @param onClick Starts the action.
 * @param busy Whether the action is running.
 * @param enabled Whether the action is allowed apart from running.
 * @param icon Optional 16 dp leading glyph.
 * @param destructive Whether the action deletes; the pill then uses the error colour.
 */
@Composable
fun ProgressButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val colors = if (destructive) {
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier, colors = colors) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(Dimens.IconInline),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            icon != null -> Icon(icon, null, modifier = Modifier.size(Dimens.IconInline))
        }
        if (busy || icon != null) Box(Modifier.padding(start = 6.dp))
        Text(text)
    }
}

/**
 * SecondaryButton is the outlined pill for secondary actions (Edit, Scan QR code, Set up). Its
 * border uses the `outline` role so it stays visible on the page and on cards; Material's own
 * `OutlinedButton` border is too faint in the Warm palette and is not used directly.
 *
 * @param text Localized action label.
 * @param icon Optional 16 dp leading glyph.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.38f)),
    ) {
        icon?.let {
            Icon(it, null, modifier = Modifier.size(Dimens.IconInline))
            Box(Modifier.padding(start = 6.dp))
        }
        Text(text)
    }
}

/**
 * DangerButton is the outlined pill in the error colour that starts a destructive flow. It
 * never performs the deletion itself; the caller opens a confirmation.
 *
 * @param text Localized action label.
 * @param onClick Opens the confirmation.
 */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(text) }
}

/**
 * InlineAction is the compact text action placed inside cards ("Add time", "Add cost",
 * "Attach"). Once a value exists, the label shows the value instead of the invitation.
 *
 * @param icon The 16 dp glyph.
 * @param text The invitation, or the current value.
 * @param onClick Opens the editor for this value.
 */
@Composable
fun InlineAction(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(Dimens.IconInline))
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
    }
}

/** InlineActionRow lays inline actions out in a wrapping row with 2 dp gaps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InlineActionRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

/**
 * IconAction is a standalone icon button with a small glyph inside a full 48 dp target.
 *
 * @param icon The glyph.
 * @param contentDescription Localized action name; required because there is no visible label.
 * @param onClick The action.
 * @param iconSize Visible glyph size (18–22 dp by context).
 * @param tint Glyph colour when enabled.
 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = Dimens.IconMenu,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            icon,
            contentDescription,
            tint = if (enabled) tint else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(iconSize),
        )
    }
}
