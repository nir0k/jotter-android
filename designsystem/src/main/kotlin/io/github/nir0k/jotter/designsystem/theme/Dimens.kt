package io.github.nir0k.jotter.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Dimens names every measurement by the role it plays. Components use these values instead of
 * literals so one context (a profile section, a dense list) keeps one geometry everywhere.
 */
object Dimens {
    /** Minimum interaction target of any tappable control. */
    val TouchTarget = 48.dp

    /** Outer padding and gap of ordinary lists of cover cards. */
    val ListPadding = 12.dp

    /** Minimum width of a cover card column: one column on phones, more in wide windows. */
    val CoverCardMinWidth = 320.dp

    /** List and detail: the window width from which they stand side by side, and the list's share of it. */
    val ListDetailMinWidth = 840.dp
    const val ListDetailListShare = 0.4f

    /** Maximum width of a search window opened over a list. */
    val SearchWindowMaxWidth = 600.dp

    /** Outer padding of a scrolling screen made of sections (profile, settings, forms). */
    val ScreenPadding = 16.dp
    val SectionGap = 14.dp

    /** Dense list: horizontal and vertical outer padding and the gap between cards. */
    val DenseListHorizontal = 16.dp
    val DenseListVertical = 10.dp
    val DenseListGap = 6.dp

    /** Body padding and inner gap of an ordinary card. */
    val CardPadding = 14.dp
    val CardInnerGap = 4.dp

    /** Padding and gap between title and rows inside a settings section card. */
    val SectionPadding = 14.dp
    val SectionFieldGap = 10.dp

    /** Dialogs: outer screen margin, body padding and field gap. */
    val DialogMargin = 16.dp
    val DialogPadding = 20.dp
    val DialogFieldGap = 12.dp
    val ChoiceListMaxHeight = 460.dp

    /** Connection screen: outer margin, maximum form width and logo size. */
    val EntryMargin = 24.dp
    val EntryMaxWidth = 420.dp
    val EntryLogo = 96.dp

    /** Gallery grid: minimum cell, gutter and horizontal margin. */
    val GalleryCell = 110.dp

    /** Media shelf: minimum poster cell and compact row poster size. */
    val PosterCell = 150.dp

    /** Dense metric card: minimum heights without and with a label row, and preview width. */
    val DenseCardHeight = 80.dp

    /** Icon sizes by context. */
    val IconNavigation = 24.dp
    val IconToolbar = 22.dp
    val IconMenu = 20.dp
    val IconSecondary = 18.dp
    val IconInline = 16.dp
    val IconMetric = 14.dp
    val IconChevron = 14.dp
}

/** Shapes names every corner treatment by role. */
object Shapes {
    val Card = RoundedCornerShape(12.dp)
    val Dialog = RoundedCornerShape(20.dp)
    val Field = RoundedCornerShape(28.dp)
    val TextArea = RoundedCornerShape(20.dp)
    val Trigger = RoundedCornerShape(8.dp)
    val SearchSurface = RoundedCornerShape(10.dp)
    val Pill = RoundedCornerShape(50)
}
