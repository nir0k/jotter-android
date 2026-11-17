package io.github.nir0k.jotter.designsystem.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import java.util.Locale
import androidx.core.net.toUri

/**
 * CoordinateActions is the pair of icon actions shown next to a located item: open the point in
 * the platform map handler and copy "lat, lon". Both are disabled until both coordinates exist.
 * Without an application for map links, opening says so in a short toast.
 * The system confirms the copy itself.
 *
 * @param latitude Latitude in degrees, or null.
 * @param longitude Longitude in degrees, or null.
 * @param label Point name passed to the map application.
 */
@Composable
fun CoordinateActions(latitude: Double?, longitude: Double?, label: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val located = latitude != null && longitude != null
    val noMapApp = stringResource(R.string.ds_no_map_app)
    val tint = MaterialTheme.colorScheme.primary
    Row(modifier) {
        IconAction(
            AppIcons.Place,
            stringResource(R.string.ds_open_on_map),
            onClick = { openMap(context, latitude!!, longitude!!, label, noMapApp) },
            enabled = located,
            iconSize = Dimens.IconMenu,
            tint = tint,
        )
        IconAction(
            AppIcons.Copy,
            stringResource(R.string.ds_copy_coordinates),
            onClick = { copyCoordinates(context, latitude!!, longitude!!) },
            enabled = located,
            iconSize = Dimens.IconSecondary,
            tint = tint,
        )
    }
}

// openMap hands the point to whichever map application handles geo: URIs; when none is
// installed it shows the missing message instead of doing nothing.
private fun openMap(context: Context, latitude: Double, longitude: Double, label: String, missing: String) {
    val point = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
    val uri = "geo:$point?q=$point(${Uri.encode(label)})".toUri()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
    }
}

// copyCoordinates puts "lat, lon" on the clipboard, which the system confirms.
private fun copyCoordinates(context: Context, latitude: Double, longitude: Double) {
    val text = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", text))
}
