package io.github.nir0k.jotter.designsystem.icons

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * IconEntry describes one icon of the catalogue.
 *
 * @param name Property name in `AppIcons`.
 * @param use What the icon stands for, as in icons/catalog.md.
 * @param icon The vector.
 */
class IconEntry(val name: String, val use: String, val icon: ImageVector)

/**
 * IconPath is one SVG path of a generated icon.
 *
 * @param data SVG path data.
 * @param evenOdd Whether the path uses the even-odd fill rule.
 * @param stroke Stroke width in viewport units for outline drawings, or null for filled paths.
 * @param filled Whether the path is filled; outline drawings set it to false.
 */
internal class IconPath(
    val data: String,
    val evenOdd: Boolean = false,
    val stroke: Float? = null,
    val filled: Boolean = true,
)

/**
 * icon builds an ImageVector from generated SVG geometry. Paths are drawn in black so the
 * `Icon` composable's tint gives them the semantic colour.
 *
 * @param name The icon name, used for debugging.
 * @param width Viewport width of the source SVG.
 * @param height Viewport height of the source SVG.
 * @param autoMirror Whether the icon flips in right-to-left layouts.
 * @param paths The icon's paths in drawing order.
 * @return The built vector with a 24 dp default size.
 */
internal fun icon(name: String, width: Float, height: Float, vararg paths: IconPath, autoMirror: Boolean = false): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = width,
        viewportHeight = height,
        autoMirror = autoMirror,
    ).apply {
        paths.forEach { path ->
            addPath(
                pathData = addPathNodes(path.data),
                pathFillType = if (path.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = if (path.filled) SolidColor(Color.Black) else null,
                stroke = path.stroke?.let { SolidColor(Color.Black) },
                strokeLineWidth = path.stroke ?: 0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
