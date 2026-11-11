package io.github.capstasher.gako

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.capstasher.gako.TestImages.maxDiff
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.tools.BrushShape
import io.github.capstasher.gako.tools.Tool
import io.github.capstasher.gako.tools.WarpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * End-to-end tests of the real screens: buttons are found by their labels, the canvas is touched
 * at image pixels, and the editor's state is checked directly. Run them quickly with
 * `scripts/devtest.sh UiTest` (or one: `scripts/devtest.sh UiTest#foregroundSelectFindsTheCircle`).
 */
@RunWith(AndroidJUnit4::class)
class UiTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val vm: EditorViewModel get() = ViewModelProvider(rule.activity)[EditorViewModel::class.java]

    private fun idle() {
        rule.waitUntil(30_000) { vm.busy == null }
        rule.waitForIdle()
    }

    /** Waits for start-up (engine, any restore), then opens a fresh white image and lets [draw] paint on it. */
    private fun fresh(w: Int = 400, h: Int = 300, draw: (Canvas) -> Unit = {}) {
        rule.waitUntil(30_000) { vm.busy == null && vm.engine is EditorViewModel.EngineState.Ready }
        rule.runOnUiThread {
            vm.newImageNow(w, h, Color.WHITE)
            draw(Canvas(vm.doc!!.layers[0].bitmap))
        }
        rule.waitForIdle()
    }

    private fun tool(t: Tool, size: Float? = null, f: (io.github.capstasher.gako.tools.ToolOptions) -> io.github.capstasher.gako.tools.ToolOptions = { it }) {
        rule.runOnUiThread {
            vm.selectTool(t)
            vm.updateOptions(f(if (size != null) vm.options.copy(size = size) else vm.options))
        }
        rule.waitForIdle()
    }

    private val canvas get() = rule.onNodeWithContentDescription("Image, ", substring = true)

    /** A button or row inside the open dialog (the strip or menus may have the same label). */
    private fun inDialog(text: String) = rule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    /** A chip in the tool options strip. */
    private fun chip(text: String) = rule.onNode(hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))

    /** An image pixel, where it is in the canvas node. */
    private fun at(x: Float, y: Float): Offset = vm.docToView(x, y)

    private fun TouchInjectionScope.stroke(points: List<Pair<Float, Float>>) {
        down(at(points[0].first, points[0].second))
        for (p in points.drop(1)) { advanceEventTime(16); moveTo(at(p.first, p.second)) }
        advanceEventTime(16)
        up()
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, steps: Int = 12) =
        (0..steps).map { x1 + (x2 - x1) * it / steps to y1 + (y2 - y1) * it / steps }

    // ------------------------------------------------------------------ tests

    @Test fun pathsListNewDeleteUndo() {
        fresh()
        tool(Tool.Paths)
        for ((x, y) in listOf(50f to 50f, 150f to 60f, 100f to 150f)) canvas.performTouchInput { click(at(x, y)) }
        rule.waitForIdle()
        assertEquals(3, vm.doc!!.paths.single().strokes.single().anchors)

        rule.onNodeWithText("Path 1", substring = true).performClick()
        inDialog("＋ New path").performClick()
        for ((x, y) in listOf(250f to 200f, 350f to 210f, 300f to 280f)) canvas.performTouchInput { click(at(x, y)) }
        rule.waitForIdle()
        assertEquals(listOf("Path 1", "Path 2"), vm.doc!!.paths.map { it.name })

        rule.onNodeWithText("Path 2", substring = true).performClick()
        rule.onAllNodesWithContentDescription("Path options")[0].performClick()
        rule.onNode(hasText("Delete") and hasAnyAncestor(isPopup())).performClick()
        rule.waitForIdle()
        assertEquals(listOf("Path 2"), vm.doc!!.paths.map { it.name })
        inDialog("Close").performClick()
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.waitForIdle()
        assertEquals(listOf("Path 1", "Path 2"), vm.doc!!.paths.map { it.name })
    }

    @Test fun brushPresetSetsTheBrush() {
        fresh()
        tool(Tool.Paintbrush)
        rule.onNodeWithText("★ Brushes").performClick()
        inDialog("Calligraphy").performClick()
        rule.waitForIdle()
        assertEquals(BrushShape.Calligraphy, vm.options.brushShape)
        assertEquals(36f, vm.options.size)
    }

    @Test fun paintThenTwoFingerTapUndoes() {
        fresh()
        tool(Tool.Paintbrush, size = 20f) { it.copy(opacity = 1f, brushShape = BrushShape.Round, symmetry = io.github.capstasher.gako.tools.Symmetry.Off) }
        rule.runOnUiThread { vm.setForeground(Color.BLACK) }
        canvas.performTouchInput { stroke(line(50f, 150f, 350f, 150f)) }
        idle()
        assertEquals(Color.BLACK, vm.doc!!.layers[0].bitmap.getPixel(200, 150))
        canvas.performTouchInput {
            down(0, at(150f, 100f)); down(1, at(250f, 100f))
            advanceEventTime(60)
            up(0); up(1)
        }
        idle()
        assertEquals(Color.WHITE, vm.doc!!.layers[0].bitmap.getPixel(200, 150))
    }

    @Test fun foregroundSelectFindsTheCircle() {
        fresh { c -> c.drawCircle(200f, 150f, 60f, Paint().apply { color = Color.rgb(230, 120, 30); isAntiAlias = true }) }
        tool(Tool.ForegroundSelect, size = 30f) { it.copy(feather = 0f, selectMode = io.github.capstasher.gako.doc.SelectMode.Replace) }
        // Roughly around it…
        val ring = (0..36).map { 200f + 95f * cos(it * Math.PI / 18).toFloat() to 150f + 95f * sin(it * Math.PI / 18).toFloat() }
        canvas.performTouchInput { stroke(ring) }
        rule.waitForIdle()
        assertNotNull(vm.input.fgOutline)
        // …then over it.
        canvas.performTouchInput { stroke(line(170f, 150f, 230f, 150f)) }
        canvas.performTouchInput { stroke(line(200f, 120f, 200f, 180f)) }
        chip("Select").performClick()
        idle()
        val b = vm.doc!!.selection!!.bounds
        for ((got, want) in listOf(b.left to 140, b.top to 90, b.right to 260, b.bottom to 210))
            assertTrue("selection $b, expected about [140,90][260,210]", abs(got - want) <= 4)
    }

    @Test fun warpStrokeAndUndo() {
        fresh { c -> val p = Paint().apply { color = Color.BLACK }; for (x in 0 until 400 step 20) c.drawRect(x.toFloat(), 0f, x + 10f, 300f, p) }
        val before = Bitmaps.copy(vm.doc!!.layers[0].bitmap)
        tool(Tool.Warp, size = 120f) { it.copy(warpMode = WarpMode.Move, strength = 1f, hardness = 0.5f) }
        canvas.performTouchInput { stroke(line(150f, 150f, 250f, 150f, 20)) }
        idle()
        assertTrue("the warp moved pixels", maxDiff(before, vm.doc!!.layers[0].bitmap) > 100)
        rule.onNodeWithContentDescription("Undo").performClick()
        idle()
        assertTrue("undo restored them", maxDiff(before, vm.doc!!.layers[0].bitmap) <= 1)
    }

    @Test fun measureAndStraighten() {
        fresh()
        tool(Tool.Measure)
        canvas.performTouchInput { stroke(line(40f, 200f, 360f, 170f)) }
        rule.waitForIdle()
        assertNotNull(vm.input.measure)
        chip("Straighten & crop").performClick()
        idle()
        val d = vm.doc!!
        assertTrue("cropped smaller: ${d.width}×${d.height}", d.width < 400 && d.height < 300)
        assertEquals(4f / 3f, d.width.toFloat() / d.height, 0.02f)
    }

    @Test fun textTooBigForALayerStaysOpen() {
        fresh()
        tool(Tool.Text)
        canvas.performTouchInput { click(at(20f, 20f)) }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("W".repeat(60))
        rule.runOnUiThread { vm.text!!.info = vm.text!!.info.copy(size = 900f) }
        rule.onNodeWithContentDescription("Done").performClick()
        rule.waitForIdle()
        assertNotNull("still editing", vm.text)
        assertEquals(1, vm.doc!!.layers.size)
    }
}
