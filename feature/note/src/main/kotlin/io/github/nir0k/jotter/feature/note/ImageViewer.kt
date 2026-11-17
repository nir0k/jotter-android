package io.github.nir0k.jotter.feature.note

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.nir0k.jotter.designsystem.components.GalleryPhoto
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.components.PhotoViewer
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.ui.R as UiR

/**
 * NoteImageViewer shows the images of a note full screen, as the design system's gallery viewer:
 * fitted on black, swiped through in body order, with a menu to open an image in another
 * application or share it.
 *
 * @param files The note's image files in body order.
 * @param start The index of the image to show first.
 * @param onClose Closes the viewer.
 * @param onOpen Opens an image with the application the system chooses.
 * @param onShare Shares an image.
 */
@Composable
fun NoteImageViewer(files: List<File>, start: Int, onClose: () -> Unit, onOpen: (File) -> Unit, onShare: (File) -> Unit) {
    val photos = remember(files) { files.map { GalleryPhoto(it, it.name) } }
    val open = stringResource(R.string.image_open_with)
    val share = stringResource(R.string.attachment_share)
    PhotoViewer(
        photos,
        start,
        onClose,
        photoMenu = { photo -> listOf(MenuAction(open, AppIcons.OpenExternal) { onOpen(photo.id) }, MenuAction(share, AppIcons.Share) { onShare(photo.id) }) },
    ) { photo -> ViewerImage(photo.id) }
}

/** What the viewer shows of one image. */
private sealed interface ViewerState {
    data object Loading : ViewerState
    data class Shown(val bitmap: ImageBitmap) : ViewerState
    data object Failed : ViewerState
}

/**
 * Draws one image fitted inside the screen; it is decoded off the main thread, scaled down to the
 * screen, so a large photo never takes more memory than the screen shows. A GIF shows its first
 * frame, as on the desktop. A spinner shows while it loads, and an image that cannot be decoded
 * shows the error glyph with Retry.
 */
@Composable
private fun BoxScope.ViewerImage(file: File) {
    val window = LocalWindowInfo.current.containerSize
    val longest = maxOf(window.width, window.height, 1)
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<ViewerState>(ViewerState.Loading, file, attempt) {
        value = ViewerState.Loading
        value = withContext(Dispatchers.IO) { decode(file, longest) }?.let { ViewerState.Shown(it) } ?: ViewerState.Failed
    }
    when (val shown = state) {
        ViewerState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        is ViewerState.Shown -> Image(shown.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        ViewerState.Failed -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(AppIcons.Error, contentDescription = stringResource(R.string.image_failed), tint = Color.White, modifier = Modifier.size(Dimens.IconToolbar))
            TextButton(onClick = { attempt++ }) { Text(stringResource(UiR.string.sync_retry), color = Color.White) }
        }
    }
}

/**
 * Decodes an image scaled down by a power of two until its longer side fits a size, turned as its
 * EXIF orientation says, as camera photos need.
 *
 * @param longest The longest side wanted, in pixels.
 * @return The image, or null when the file cannot be decoded.
 */
private fun decode(file: File, longest: Int): ImageBitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        var sample = 1
        while (maxOf(info.size.width, info.size.height) / (sample * 2) >= longest) sample *= 2
        decoder.setTargetSampleSize(sample)
    }.asImageBitmap()
} catch (_: IOException) {
    null
}
