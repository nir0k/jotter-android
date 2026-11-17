package io.github.nir0k.jotter.feature.notes.trees

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.nir0k.jotter.core.format.Collection
import io.github.nir0k.jotter.core.format.tagColor
import io.github.nir0k.jotter.core.format.tagKey
import io.github.nir0k.jotter.ui.theme.collectionIcon
import io.github.nir0k.jotter.ui.theme.itemTextColor
import io.github.nir0k.jotter.designsystem.components.IconAction
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.components.OverflowMenuButton
import io.github.nir0k.jotter.designsystem.icons.AppIcons
import io.github.nir0k.jotter.designsystem.theme.Dimens
import io.github.nir0k.jotter.ui.R as UiR

/**
 * CollectionNode is a collection in the tree with the number of active notes it lists, its
 * descendants' included.
 */
class CollectionNode(val collection: Collection, val count: Int, val children: List<CollectionNode>)

/**
 * TagNode is a part of the tag tree: a tag path such as `work/urgent`, shown by its last part.
 * A node may stand only for the parent part of longer tags.
 *
 * @param path The tag path in the spelling of its first use.
 * @param count The number of active notes with the tag or one of its children.
 */
class TagNode(val path: String, val count: Int, val children: List<TagNode>) {
    /** The last part of the path, which the row shows. */
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Builds the collection tree in the stored order within each parent. A collection whose parent is
 * missing, or that sits in a cycle of a damaged file, is left out, as on the desktop.
 *
 * @param counts The number of notes of each collection with its descendants.
 */
fun collectionTree(collections: List<Collection>, counts: Map<String, Int>): List<CollectionNode> {
    val children = collections.groupBy { it.parent }
    val seen = mutableSetOf<String>()
    fun nodes(parent: String?): List<CollectionNode> = children[parent].orEmpty()
        .filter { seen.add(it.id) }
        .map { CollectionNode(it, counts[it.id] ?: 0, nodes(it.id)) }
    return nodes(null)
}

/**
 * Builds the tag tree from the tags of active notes: every parent part of a path is a node, so
 * `work/urgent` lies under `work` even when no note has `work` itself. Parts compare case-folded
 * and keep the spelling of the first tag that has them.
 *
 * @param tags The tags in the order of the list, each in the spelling of its first use.
 * @param counts The number of notes of each path, by its tag key.
 */
fun tagTree(tags: List<String>, counts: Map<String, Int>): List<TagNode> {
    // Children are found by their parent's key, as a child may spell its parent differently.
    val byParent = tagPaths(tags).groupBy { tagKey(it.substringBeforeLast('/', "")) }
    fun nodes(parentKey: String): List<TagNode> =
        byParent[parentKey].orEmpty().map { TagNode(it, counts[tagKey(it)] ?: 0, nodes(tagKey(it))) }
    return nodes("")
}

/**
 * Lists the tag paths of a tag tree: every tag with its parent parts, once each compared
 * case-folded, in the spelling and order of the first tag that has them.
 */
fun tagPaths(tags: List<String>): List<String> =
    tags.flatMap { tag -> tag.split('/').let { parts -> (1..parts.size).map { parts.take(it).joinToString("/") } } }
        .distinctBy(::tagKey)

/**
 * TreeRow is one row of the collection or tag tree, laid out as the design system's tree row: at
 * least a touch target high, the expand toggle of a node with children (a space for one without),
 * the glyph and the label in the item's colour (the text colour without one), and the number of
 * notes. Tapping the row opens the item's notes. Unlike the design system's row, which opens its
 * menu on a long press, it keeps a ⋮ button and a trailing slot for the drag handles of
 * Favourites.
 *
 * @param level The depth in the tree, 0 for the top.
 * @param color The colour of the glyph and the label, already mixed for text, or null for none.
 * @param expanded Whether the children show, or null for a node without children.
 * @param onToggle Expands or collapses the node.
 * @param onOpen Opens the item's notes.
 * @param menu The row's ⋮ entries; an empty list hides the button.
 * @param trailing Content at the row's end, such as a drag handle.
 */
@Composable
fun TreeRow(
    level: Int,
    icon: ImageVector,
    label: String,
    color: Color?,
    count: Int,
    expanded: Boolean?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    menu: List<MenuAction> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = Dimens.TouchTarget)
            .padding(start = Dimens.ScreenPadding * level, end = Dimens.DenseListHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.CardInnerGap),
    ) {
        if (expanded == null) {
            Spacer(Modifier.size(Dimens.TouchTarget))
        } else {
            IconAction(
                if (expanded) AppIcons.ChevronDown else AppIcons.ChevronRight,
                stringResource(if (expanded) io.github.nir0k.jotter.designsystem.R.string.ds_tree_collapse else io.github.nir0k.jotter.designsystem.R.string.ds_tree_expand, label),
                onToggle,
                iconSize = Dimens.IconChevron,
            )
        }
        Icon(icon, null, tint = color ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(Dimens.IconSecondary))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Dimens.CardInnerGap),
        )
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = muted)
        if (menu.isNotEmpty()) OverflowMenuButton(menu, contentDescription = stringResource(UiR.string.tree_actions, label))
        trailing?.invoke()
    }
}

/**
 * CollectionRow is a collection as a tree row: its glyph (`Folder` by default) and name in its
 * colour, with the number of its notes.
 */
@Composable
fun CollectionRow(
    node: CollectionNode,
    level: Int,
    expanded: Boolean?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    menu: List<MenuAction> = emptyList(),
    trailing: (@Composable () -> Unit)? = null,
) {
    val collection = node.collection
    TreeRow(level, collectionIcon(collection.icon), collection.name, itemTextColor(collection.color), node.count, expanded, onToggle, onOpen, menu, trailing)
}

/** A node of a tree with its depth, as the flat list of visible rows holds it. */
private class Visible<T>(val node: T, val level: Int)

/** Lists the nodes that show: the top level and the children of every expanded node, depth first. */
private fun <T> visible(nodes: List<T>, key: (T) -> String, children: (T) -> List<T>, expanded: Set<String>, level: Int = 0): List<Visible<T>> =
    nodes.flatMap { node ->
        listOf(Visible(node, level)) + if (key(node) in expanded) visible(children(node), key, children, expanded, level + 1) else emptyList()
    }

/**
 * Adds the collection tree with expand and collapse to a list.
 *
 * @param expanded The identifiers of the expanded collections.
 * @param onToggle Expands or collapses a collection.
 * @param onOpen Opens a collection's notes.
 * @param menu Returns a collection's ⋮ entries.
 */
fun LazyListScope.collectionTreeRows(
    nodes: List<CollectionNode>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (Collection) -> Unit,
    menu: @Composable (Collection) -> List<MenuAction> = { emptyList() },
) {
    val rows = visible(nodes, { it.collection.id }, CollectionNode::children, expanded)
    items(rows, key = { "collection:${it.node.collection.id}" }) { row ->
        val node = row.node
        CollectionRow(
            node,
            row.level,
            node.collection.id.takeIf { node.children.isNotEmpty() }?.let { it in expanded },
            { onToggle(node.collection.id) },
            { onOpen(node.collection) },
            menu(node.collection),
        )
    }
}

/**
 * Adds the tag tree with expand and collapse to a list, each tag's glyph in its colour, its own or
 * its nearest coloured parent's.
 *
 * @param tagColors Tag colour keys by tag key, from the library.
 * @param expanded The tag keys of the expanded nodes.
 * @param onToggle Expands or collapses a node by its tag key.
 * @param onOpen Opens the notes of a tag path.
 * @param menu Returns a tag path's ⋮ entries.
 */
fun LazyListScope.tagTreeRows(
    nodes: List<TagNode>,
    tagColors: Map<String, String>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
    menu: @Composable (String) -> List<MenuAction> = { emptyList() },
) {
    val rows = visible(nodes, { tagKey(it.path) }, TagNode::children, expanded)
    items(rows, key = { "tag:${tagKey(it.node.path)}" }) { row ->
        val node = row.node
        val key = tagKey(node.path)
        TreeRow(
            row.level,
            AppIcons.Tag,
            node.name,
            itemTextColor(tagColor(tagColors, node.path)),
            node.count,
            key.takeIf { node.children.isNotEmpty() }?.let { it in expanded },
            { onToggle(key) },
            { onOpen(node.path) },
            menu(node.path),
        )
    }
}
