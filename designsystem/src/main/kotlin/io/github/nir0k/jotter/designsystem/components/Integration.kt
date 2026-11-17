package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.AppColors
import io.github.nir0k.jotter.designsystem.theme.Dimens

/**
 * IntegrationStatus is the health of one external service.
 *
 * - [Ok]: enabled and the last synchronization succeeded.
 * - [Warning]: needs attention but still configured — disabled, the last synchronization was
 *   partial, or the credential expires soon.
 * - [Error]: the last synchronization or the credential check failed.
 * - [NotConfigured]: never connected.
 */
enum class IntegrationStatus { NotConfigured, Ok, Warning, Error }

/**
 * IntegrationStatusIcon draws the filled status mark: a green check circle, an amber warning
 * triangle or a red cross circle. Nothing is drawn for a service that is not configured.
 *
 * @param size Glyph size; 20 dp in lists, 28 dp in the connection diagram.
 */
@Composable
fun IntegrationStatusIcon(status: IntegrationStatus, modifier: Modifier = Modifier, size: Dp = Dimens.IconMenu) {
    val (icon, tint, label) = when (status) {
        IntegrationStatus.Ok -> Triple(AppIcons.StatusOk, AppColors.status.success, R.string.ds_status_ok)
        IntegrationStatus.Warning -> Triple(AppIcons.StatusWarning, AppColors.status.warning, R.string.ds_status_warning)
        IntegrationStatus.Error -> Triple(AppIcons.StatusError, MaterialTheme.colorScheme.error, R.string.ds_status_error)
        IntegrationStatus.NotConfigured -> return
    }
    Icon(icon, stringResource(label), tint = tint, modifier = modifier.size(size))
}

/**
 * IntegrationMark is a service's glyph drawn white on its brand colour in a rounded square.
 *
 * @param size Square size; 36 dp in lists, 56 dp in the connection diagram.
 */
@Composable
fun IntegrationMark(icon: ImageVector, brandColor: Color, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 4.5f)).background(brandColor),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.6f)) }
}

/**
 * IntegrationDiagram is the head of a service screen: the application's mark, a dotted line,
 * the status mark in the middle, a dotted line and the service's mark. A service that is not
 * configured shows the line without a status mark, in the outline colour.
 *
 * @param appIcon The application's glyph, drawn on the primary container.
 * @param serviceIcon The service glyph, drawn on [brandColor].
 */
@Composable
fun IntegrationDiagram(
    appIcon: ImageVector,
    serviceIcon: ImageVector,
    brandColor: Color,
    status: IntegrationStatus,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val lineColor = if (status == IntegrationStatus.NotConfigured) scheme.outline else scheme.onSurfaceVariant
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(appIcon, null, tint = scheme.primary, modifier = Modifier.size(32.dp)) }
        DottedLine(lineColor, Modifier.weight(1f))
        if (status != IntegrationStatus.NotConfigured) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(scheme.background), contentAlignment = Alignment.Center) {
                IntegrationStatusIcon(status, size = 28.dp)
            }
            DottedLine(lineColor, Modifier.weight(1f))
        }
        IntegrationMark(serviceIcon, brandColor, size = 56.dp)
    }
}

// DottedLine draws the round-dotted connection between two marks.
@Composable
private fun DottedLine(color: Color, modifier: Modifier) {
    Canvas(modifier.height(4.dp).padding(horizontal = 6.dp)) {
        drawLine(
            color,
            Offset(0f, size.height / 2),
            Offset(size.width, size.height / 2),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 8.dp.toPx())),
        )
    }
}

/**
 * SyncLogRow is one synchronization run in a service's log: time, and either the success summary
 * or the failure message in the error colour.
 *
 * @param time Formatted time of the run.
 * @param text Localized summary ("12 activities imported") or failure reason.
 * @param success Whether the run succeeded.
 */
@Composable
fun SyncLogRow(time: String, text: String, success: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (success) AppIcons.StatusOk else AppIcons.StatusError,
            null,
            tint = if (success) AppColors.status.success else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(Dimens.IconInline),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(time, style = MaterialTheme.typography.bodyMedium)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}
