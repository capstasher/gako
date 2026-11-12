package io.github.capstasher.gako

import android.graphics.Bitmap
import android.graphics.Color
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.doc.LayerMode
import io.github.capstasher.gako.doc.PathStroke
import io.github.capstasher.gako.doc.VectorPath
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.max

/** Small images and documents for the instrumented tests. */
object TestImages {

    /** An opaque pattern that differs at every pixel (so moves and flips show). */
    fun pattern(w: Int, h: Int, seed: Int): Bitmap = Bitmaps.blank(w, h).also { b ->
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            Color.argb(255, (x * 7 + seed * 31) and 255, (y * 5 + seed * 17) and 255, ((x + y) * 3 + seed) and 255)
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** A grey mask, black on the left to white on the right. */
    fun mask(w: Int, h: Int): Bitmap = Bitmaps.blank(w, h).also { b ->
        val px = IntArray(w * h) { i -> val v = (i % w) * 255 / max(1, w - 1); Color.rgb(v, v, v) }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The largest difference in any channel between two same-sized bitmaps. */
    fun maxDiff(a: Bitmap, b: Bitmap): Int {
        assertTrue("sizes ${a.width}x${a.height} vs ${b.width}x${b.height}", a.width == b.width && a.height == b.height)
        val pa = pixels(a); val pb = pixels(b)
        var m = 0
        for (i in pa.indices) for (s in intArrayOf(0, 8, 16, 24)) m = max(m, abs(((pa[i] ushr s) and 255) - ((pb[i] ushr s) and 255)))
        return m
    }

    /**
     * Background, a multiply group (80%) holding a masked screen layer (50%) at an offset, and a
     * hidden layer partly off the canvas; one closed path.
     */
    fun document(): DocState {
        val bg = Layer(Layer.newId(), "Background", pattern(64, 48, 1))
        val group = Layer(Layer.newId(), "Group", Bitmaps.blank(1, 1), opacity = 0.8f, mode = LayerMode.Multiply, group = true)
        val inside = Layer(Layer.newId(), "Inside", pattern(20, 10, 2), x = 5, y = 7, opacity = 0.5f, mode = LayerMode.Screen,
            mask = mask(20, 10), parent = group.id)
        val top = Layer(Layer.newId(), "Top", pattern(16, 16, 3), x = -4, y = 40, visible = false)
        val path = VectorPath("Outline", listOf(PathStroke(floatArrayOf(
            10f, 10f, 10f, 10f, 14f, 8f,
            40f, 12f, 40f, 12f, 40f, 12f,
            30f, 40f, 30f, 40f, 30f, 40f,
        ), true)))
        return DocState(64, 48, listOf(bg, inside, group, top), inside.id, paths = listOf(path))
    }
}
