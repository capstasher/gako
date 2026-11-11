package io.github.capstasher.gako

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.capstasher.gako.TestImages.maxDiff
import io.github.capstasher.gako.TestImages.pattern
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.DocOps
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.tools.Warp
import io.github.capstasher.gako.tools.WarpMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class TransformTest {

    @Test fun warpStrokeUndoesToTheOriginal() {
        val b = pattern(120, 90, 4)
        val orig = Bitmaps.copy(b)
        val w = Warp(1L, b)
        w.beginStroke()
        repeat(5) { w.dab(WarpMode.Move, 60f + it * 2, 45f, 2f, 0f, 30f, 1f, 0.5f) }
        val patch = w.endStroke("Warp")
        assertNotNull(patch)
        assertTrue("the warp changed something", maxDiff(orig, b) > 0)
        // Its "before" (re-rendered from the original through the mesh as it was) is the original.
        val r = patch!!.rect
        assertTrue("before ≈ original, off by ${maxDiff(patch.before, Bitmaps.crop(orig, r))}",
            maxDiff(patch.before, Bitmaps.crop(orig, r)) <= 1)
        assertEquals(0, maxDiff(patch.after, Bitmaps.crop(b, r)))
        // Outside the patch nothing changed.
        val outside = Rect(0, 0, b.width, b.height)
        if (r.left > 0) assertEquals(0, maxDiff(Bitmaps.crop(orig, Rect(0, 0, r.left, b.height)), Bitmaps.crop(b, Rect(0, 0, r.left, b.height))))
        assertTrue(outside.contains(r))
    }

    @Test fun warpEraseBringsBackTheOriginal() {
        val b = pattern(100, 80, 5)
        val orig = Bitmaps.copy(b)
        val w = Warp(1L, b)
        w.beginStroke()
        w.dab(WarpMode.SwirlCw, 50f, 40f, 0f, 0f, 30f, 1f, 0.3f)
        w.endStroke("Warp")
        assertTrue(maxDiff(orig, b) > 0)
        // A second stroke on the same mesh erases the first.
        w.beginStroke()
        repeat(150) { w.dab(WarpMode.Erase, 50f, 40f, 0f, 0f, 60f, 1f, 0.3f) }
        w.endStroke("Warp")
        assertTrue("erased back to the original, off by ${maxDiff(orig, b)}", maxDiff(orig, b) <= 2)
    }

    @Test fun warpCancelRestores() {
        val b = pattern(64, 64, 6)
        val orig = Bitmaps.copy(b)
        val w = Warp(1L, b)
        w.beginStroke()
        w.dab(WarpMode.Grow, 32f, 32f, 0f, 0f, 20f, 1f, 0.5f)
        assertTrue(maxDiff(orig, b) > 0)
        w.cancelStroke()
        assertTrue(maxDiff(orig, b) <= 1)
    }

    @Test fun straightenCropsToTheBiggestRectangleWithNoEmptyCorners() {
        val doc = DocState(400, 300, listOf(Layer(Layer.newId(), "Photo", pattern(400, 300, 7))), 0L)
        val deg = 10f
        val out = DocOps.rotateBy(doc, deg, crop = true)
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        val k = minOf(400f / (400f * c + 300f * s), 300f / (400f * s + 300f * c))
        assertEquals((400 * k).toInt(), out.width)
        assertEquals((300 * k).toInt(), out.height)
        assertTrue("aspect kept", abs(out.width.toFloat() / out.height - 4f / 3f) < 0.01f)
        // Opaque everywhere inside (a pixel's margin for the antialiased edge).
        val flat = Compositor.flatten(out)
        for (y in 1 until out.height - 1) for (x in 1 until out.width - 1) {
            val alpha = flat.getPixel(x, y) ushr 24
            assertTrue("transparent at $x,$y", alpha >= 250)
        }
    }

    @Test fun rotateWithoutCropGrowsTheCanvasAndKeepsTheCentre() {
        val src = pattern(400, 300, 8)
        val doc = DocState(400, 300, listOf(Layer(Layer.newId(), "Photo", src)), 0L)
        val out = DocOps.rotateBy(doc, 30f, crop = false)
        val a = Math.toRadians(30.0)
        assertEquals((400 * cos(a) + 300 * sin(a)).toFloat(), out.width.toFloat(), 1f)
        assertEquals((400 * sin(a) + 300 * cos(a)).toFloat(), out.height.toFloat(), 1f)
        // The corners are empty, the centre is the old centre.
        val flat = Compositor.flatten(out)
        assertEquals(0, flat.getPixel(0, 0) ushr 24)
        val centre = flat.getPixel(out.width / 2, out.height / 2)
        val old = src.getPixel(200, 150)
        for (sh in intArrayOf(0, 8, 16)) assertTrue(abs(((centre ushr sh) and 255) - ((old ushr sh) and 255)) <= 24)
    }
}
