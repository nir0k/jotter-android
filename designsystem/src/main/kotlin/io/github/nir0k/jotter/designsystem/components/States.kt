package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.AppColors
import io.github.nir0k.jotter.designsystem.theme.Shapes
import io.github.nir0k.jotter.designsystem.theme.Dimens

/** LoadingState fills an empty content region during the first load. Refreshes keep content instead. */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.ds_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * EmptyState explains an empty region: an empty collection ("No trips yet" with a create
 * action) or no search results ("No matches" with a clear action). The two are distinct.
 *
 * @param icon A 40 dp glyph naming the content.
 * @param title Localized short title.
 * @param message Localized explanation or next step, or null.
 * @param actionText Optional action label; shown only when the reader may perform it.
 * @param onAction The action.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        if (actionText != null && onAction != null) {
            SecondaryButton(actionText, onAction, Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * ErrorState replaces a region whose first load failed: the reason and Retry. When saved
 * content exists, show it with an [InlineError] or [StatusBanner] instead.
 */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        icon = AppIcons.Error,
        title = message,
        message = null,
        actionText = stringResource(R.string.ds_retry),
        onAction = onRetry,
        modifier = modifier,
    )
}

/** InlineError is a failure next to the control that caused it (form, section, card). */
@Composable
fun InlineError(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(AppIcons.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 1.dp).size(Dimens.IconMetric))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * StatusBanner is the thin strip above content for a state that concerns the whole screen:
 * offline mode, pending changes, a failed refresh. Tapping it may open the related settings.
 *
 * @param text Localized state description.
 * @param icon Leading glyph; offline by default.
 * @param actionText Optional action label at the end of the strip.
 */
@Composable
fun StatusBanner(
    modifier: Modifier = Modifier,
    text: String = stringResource(R.string.ds_offline),
    icon: ImageVector = AppIcons.Offline,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimens.IconInline))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (actionText != null && onAction != null) {
                androidx.compose.material3.TextButton(onClick = onAction) { Text(actionText, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/** NoticeKind is the severity of a [NoticePanel]. */
enum class NoticeKind { Info, Warning, Error }

/**
 * NoticePanel explains a state that needs the reader's attention in more words than an inline
 * error: what happened, what it means and what to do. The panel is tinted yellow for warnings,
 * red for errors and blue for information.
 *
 * @param title Localized one-line summary ("The token expires in 5 days").
 * @param text Localized explanation and next step.
 */
@Composable
fun NoticePanel(kind: NoticeKind, title: String, text: String, modifier: Modifier = Modifier) {
    val (color, icon) = when (kind) {
        NoticeKind.Info -> AppColors.status.info to AppIcons.Info
        NoticeKind.Warning -> AppColors.status.warning to AppIcons.StatusWarning
        NoticeKind.Error -> MaterialTheme.colorScheme.error to AppIcons.StatusError
    }
    Surface(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = Shapes.Card,
        color = color.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(Dimens.IconMenu))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
