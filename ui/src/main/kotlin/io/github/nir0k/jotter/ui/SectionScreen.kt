package io.github.nir0k.jotter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.nir0k.jotter.designsystem.components.AppTopBar
import io.github.nir0k.jotter.designsystem.components.MenuAction
import io.github.nir0k.jotter.designsystem.theme.Dimens

/**
 * SectionColumn is the scrolling column of sections of a screen or of a tab of Settings.
 *
 * @param content The sections.
 */
@Composable
fun SectionColumn(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SectionGap),
        content = content,
    )
}

/**
 * SectionScreen is the frame of a subordinate screen made of sections: a top bar with Back, the
 * title and an optional ⋮ menu, then a scrolling column of sections, which ends above the keyboard
 * so a focused field scrolls into view.
 *
 * @param title The screen title.
 * @param onBack Closes the screen.
 * @param menu The ⋮ menu of the screen's own actions; empty for none.
 * @param snackbar Transient messages of the screen's actions, or null when it has none.
 * @param add The round Add button, or null when the screen has none.
 * @param content The sections.
 */
@Composable
fun SectionScreen(
    title: String,
    onBack: () -> Unit,
    menu: List<MenuAction> = emptyList(),
    snackbar: SnackbarHostState? = null,
    add: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = { AppTopBar(title, onBack = onBack, menu = menu) },
        floatingActionButton = { add?.invoke() },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { padding ->
        SectionColumn(Modifier.padding(padding).consumeWindowInsets(padding).imePadding(), content)
    }
}
