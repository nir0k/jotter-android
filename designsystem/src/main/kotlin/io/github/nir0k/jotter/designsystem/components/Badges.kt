package io.github.nir0k.jotter.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nir0k.jotter.designsystem.theme.Shapes

/**
 * Tag is a short coloured label: a status, a role or a user-defined tag.
 *
 * @param text Localized label or user-authored tag name.
 * @param color The semantic or tag colour.
 */
data class Tag(val text: String, val color: Color)

/**
 * TintedBadge is the default descriptive label: full rounding, a 14% fill, a 45% border and
 * text blended 65% toward the colour. It is not interactive.
 */
@Composable
fun TintedBadge(tag: Tag, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = Shapes.Pill,
        color = tag.color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tag.color.copy(alpha = 0.45f)),
    ) {
        Text(
            tag.text,
            style = MaterialTheme.typography.labelSmall,
            color = lerp(MaterialTheme.colorScheme.onSurface, tag.color, 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * CompactTag is the borderless tag of dense lists: 16% fill, semibold text in the tag colour,
 * 6/1 dp padding. Inside `DenseContent` its text uses the compact scale.
 */
@Composable
fun CompactTag(tag: Tag, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = Shapes.Pill, color = tag.color.copy(alpha = 0.16f)) {
        Text(
            tag.text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tag.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * CompactTagRow is the single line of compact tags in a dense card. Tags that do not fit are
 * clipped, so the card keeps its height; the full set is shown on the detail screen.
 */
@Composable
fun CompactTagRow(tags: List<Tag>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clipToBounds(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { tags.forEach { CompactTag(it) } }
}
