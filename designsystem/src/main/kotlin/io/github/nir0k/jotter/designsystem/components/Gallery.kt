package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.nir0k.jotter.designsystem.R
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens

/**
 * GalleryPhoto is one image of a gallery.
 *
 * @param id Stable identity; selection and group actions use it, never the position.
 * @param description Localized or user-authored description for accessibility.
 * @param favorite Whether the photo is a favourite (marker shown).
 * @param private Whether the photo is hidden from other members (marker shown).
 */
data class GalleryPhoto<ID>(val id: ID, val description: String, val favorite: Boolean = false, val private: Boolean = false)

/**
 * SelectionBar replaces the gallery's bottom area while selecting: Clear selection, the count,
 * Select all, the favourite and privacy toggles and an overflow menu of the remaining valid
 * group actions. Actions apply to the ids captured when they are invoked.
 *
 * @param count Number of selected photos.
 * @param allFavorite Whether every selected photo is a favourite; the toggle then removes it.
 * @param allPrivate Whether every selected photo is private; the toggle then reveals them.
 * @param onPrivate Privacy toggle; null when the product has no private photos.
 * @param menu Overflow entries: single-photo entries only when [count] is 1, delete last.
 */
@Composable
fun SelectionBar(
    count: Int,
    allFavorite: Boolean,
    allPrivate: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    onPrivate: ((Boolean) -> Unit)?,
    menu: List<MenuAction>,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(AppIcons.Close, stringResource(R.string.ds_clear_selection), onClear, tint = MaterialTheme.colorScheme.onSurface)
            Text(
                pluralStringResource(R.plurals.ds_selected_count, count, count),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconAction(AppIcons.SelectAll, stringResource(R.string.ds_select_all), onSelectAll, tint = MaterialTheme.colorScheme.onSurface)
            IconAction(
                if (allFavorite) AppIcons.FavoriteFilled else AppIcons.Favorite,
                stringResource(if (allFavorite) R.string.ds_unmark_favorite else R.string.ds_mark_favorite),
                { onFavorite(!allFavorite) },
                tint = MaterialTheme.colorScheme.onSurface,
            )
            onPrivate?.let {
                IconAction(
                    if (allPrivate) AppIcons.Show else AppIcons.Hide,
                    stringResource(if (allPrivate) R.string.ds_make_visible else R.string.ds_make_private),
                    { it(!allPrivate) },
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (menu.isNotEmpty()) OverflowMenuButton(menu, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * PhotoViewer is the full-screen black viewer opened at the tapped photo. Photos are fitted, not
 * cropped; a horizontal swipe moves through the same order as the grid. The top row shows the
 * counter, the current photo's own menu and Close. Back closes the viewer.
 *
 * @param photos Photos in grid order.
 * @param startIndex Index of the tapped photo.
 * @param onClose Closes the viewer.
 * @param photoMenu Entries of the current photo's menu.
 * @param image Draws one photo fitted inside the screen.
 */
@Composable
fun <ID> PhotoViewer(
    photos: List<GalleryPhoto<ID>>,
    startIndex: Int,
    onClose: () -> Unit,
    photoMenu: @Composable (GalleryPhoto<ID>) -> List<MenuAction>,
    image: @Composable BoxScope.(GalleryPhoto<ID>) -> Unit,
) {
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { photos[it].id.toString() }) { page ->
                Box(Modifier.fillMaxSize().semantics { contentDescription = photos[page].description }) { image(photos[page]) }
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp).align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.ds_viewer_counter, pager.currentPage + 1, photos.size),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                photos.getOrNull(pager.currentPage)?.let { current ->
                    val menu = photoMenu(current)
                    if (menu.isNotEmpty()) OverflowMenuButton(menu, tint = Color.White, contentDescription = stringResource(R.string.ds_photo_actions))
                }
                IconButton(onClick = onClose) { Icon(AppIcons.Close, stringResource(R.string.ds_close), tint = Color.White, modifier = Modifier.size(Dimens.IconToolbar)) }
            }
        }
    }
}
