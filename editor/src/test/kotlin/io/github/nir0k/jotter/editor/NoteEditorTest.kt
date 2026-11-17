package io.github.nir0k.jotter.editor

import android.app.Application
import android.widget.Magnifier
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Typing into the editor's text fields changes the note's model: what the field reports becomes
 * text, blocks and shortcuts, and the body is written as the desktop would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NoteEditorTest {
    @get:Rule val compose = createComposeRule()

    private val colors = NoteColors(0xFFFFFF, 0x202020, 0x707070, 0x0060C0, 0xEEEEEE, 0x999999, 0xCCCCCC, 0xD04000, 0xFFFFFF, false, "breeze-light")

    /** Shows a body in the editor and returns its controller. */
    private fun show(body: String): NoteEditorController {
        val controller = NoteEditorController("01a1872b-97c3-75de-bada-1e2d73552a21", body, editable = true, { it })
        compose.setContent {
            NoteEditor(controller, colors, EditorStrings.from(LocalContext.current), NoteImages(null, rememberCoroutineScope()), { it })
        }
        return controller
    }

    /** Typing joins the text, Enter starts a block, and `- ` at its start makes a bulleted list. */
    @Test
    fun typingEnterAndShortcut() {
        val controller = show("<p>ab</p>")
        val field = compose.onAllNodes(hasSetTextAction()).onFirst()
        field.performClick()
        field.performTextInputSelection(TextRange(2))
        field.performTextInput("c")
        compose.waitForIdle()
        assertEquals("<p>abc</p>", controller.body())
        field.performTextInput("\n")
        field.performTextInput("-")
        field.performTextInput(" ")
        field.performTextInput("d")
        compose.waitForIdle()
        assertEquals("<p>abc</p>\n<ul>\n<li>d</li>\n</ul>", controller.body())
    }

    /** Enter at the end of the text starts an empty block that stands on a line of its own at once. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun enterShowsTheEmptyBlock() {
        val controller = show("<p>ab</p>")
        val field = compose.onAllNodes(hasSetTextAction()).onFirst()
        field.performClick()
        field.performTextInputSelection(TextRange(2))
        field.performTextInput("\n")
        compose.waitForIdle()
        assertEquals("<p>ab</p>\n<p></p>", controller.body())
        val layouts = ArrayList<TextLayoutResult>()
        field.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        assertEquals(2, layouts.single().lineCount)
    }

    /**
     * A tap past the end of a line puts the cursor at the end of that line, an empty line's tap on
     * that line, and a tap at the start of the next line stays there.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tapPastTheEndOfALine() {
        show("<p>abc</p>\n<p></p>\n<p>def</p>")
        val field = compose.onAllNodes(hasSetTextAction()).onFirst()
        field.performClick()
        compose.waitForIdle()
        val layouts = ArrayList<TextLayoutResult>()
        field.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        val layout = layouts.single()
        /** Taps a position and returns where the cursor went. */
        fun tap(position: Offset): TextRange {
            field.performTouchInput { click(position) }
            compose.waitForIdle()
            return field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        }
        fun middle(line: Int) = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
        assertEquals(TextRange(3), tap(Offset(layout.size.width - 4f, middle(0))))
        assertEquals(TextRange(4), tap(Offset(layout.size.width - 4f, middle(1))))
        assertEquals(TextRange(5), tap(Offset(1f, middle(2))))
    }

    /**
     * A long press selects the word under it and shows the selection menu, which asks the editor's
     * clipboard what can be pasted: that query must reach the platform's clipboard rather than throw.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(shadows = [InertMagnifier::class])
    fun longPressSelectsAWord() {
        show("<p>one two</p>")
        val field = compose.onAllNodes(hasSetTextAction()).onFirst()
        field.performTouchInput { longClick(Offset(4f, centerY)) }
        compose.waitForIdle()
        assertEquals(TextRange(0, 3), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    /** A toolbar command applies to the field's selection, and undo takes it back. */
    @Test
    fun commandsOnTheSelection() {
        val controller = show("<p>word</p>")
        val field = compose.onAllNodes(hasSetTextAction()).onFirst()
        field.performClick()
        field.performTextInputSelection(TextRange(0, 4))
        compose.waitForIdle()
        controller.toggleMark(io.github.nir0k.jotter.core.body.Mark.Bold)
        compose.waitForIdle()
        assertEquals("<p><strong>word</strong></p>", controller.body())
        controller.undo()
        compose.waitForIdle()
        assertEquals("<p>word</p>", controller.body())
    }

    /** A dragged image handle sets a width from 10 % to full, taking a preset within 3 of it. */
    @Test
    fun resizedImageWidth() {
        assertEquals(50, resizedWidth(400f, 120f, 1000f))
        assertEquals(37, resizedWidth(400f, -30f, 1000f))
        assertEquals(10, resizedWidth(400f, -390f, 1000f))
        assertEquals(100, resizedWidth(400f, 900f, 1000f))
    }

    /**
     * The text beside a wrapped image moves right by splitting paragraphs at the band's edges; the
     * part after a split keeps its paragraph's indent of later lines as a continuation.
     */
    @Test
    fun indentedBesideWrappedImage() {
        val item = ParagraphStyle(textIndent = TextIndent(1.5.em, 1.5.em))
        val plain = ParagraphStyle()
        val result = listOf(Styled(item, 0, 6), Styled(plain, 6, 10)).indented(from = 3, to = 8, shiftSp = 20f, emSp = 16f)
        assertEquals(listOf(0 to 3, 3 to 6, 6 to 8, 8 to 10), result.map { it.start to it.end })
        assertEquals(item, result[0].item)
        assertEquals(TextIndent(44.sp, 44.sp), result[1].item.textIndent)
        assertEquals(TextIndent(20.sp, 20.sp), result[2].item.textIndent)
        assertEquals(TextIndent(0.sp, 0.sp), result[3].item.textIndent)
    }
}

/** The magnifier of a long press, which Robolectric cannot draw: its window has no surface there. */
@Implements(Magnifier::class)
class InertMagnifier {
    /** Draws nothing for a source point. */
    @Implementation
    protected fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    /** Draws nothing for a source point and a magnifier position. */
    @Implementation
    protected fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    /** Redraws nothing. */
    @Implementation
    protected fun update() = Unit

    /** Hides nothing, as nothing was drawn. */
    @Implementation
    protected fun dismiss() = Unit
}
