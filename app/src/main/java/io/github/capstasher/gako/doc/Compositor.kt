package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.roundToInt

/** Software compositing of a [DocState], matching what the on-screen canvas draws. */
object Compositor {

    fun layerPaint(layer: Layer, filter: Boolean = true) = Paint().apply {
        alpha = (layer.opacity * 255).roundToInt().coerceIn(0, 255)
        blendMode = layer.mode.blend
        isFilterBitmap = filter
    }

    /** Draws every visible layer onto [canvas] in canvas coordinates, isolated from what's below. */
    fun drawLayers(canvas: Canvas, doc: DocState, layers: List<Layer> = doc.layers) {
        val save = canvas.saveLayer(RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat()), null)
        for (l in layers) {
            if (!l.visible || l.opacity <= 0f) continue
            canvas.drawBitmap(l.bitmap, l.x.toFloat(), l.y.toFloat(), layerPaint(l))
        }
        canvas.restoreToCount(save)
    }

    /** The image as it looks, as one canvas-sized bitmap. */
    fun flatten(doc: DocState, background: Int? = null, layers: List<Layer> = doc.layers): Bitmap {
        val out = Bitmaps.blank(doc.width, doc.height)
        val c = Canvas(out)
        if (background != null) c.drawColor(background)
        drawLayers(c, doc, layers)
        return out
    }

    /** The composite colour (unpremultiplied ARGB) at one canvas pixel. */
    fun sample(doc: DocState, x: Int, y: Int): Int {
        val px = Bitmaps.blank(1, 1)
        val c = Canvas(px)
        c.translate(-x.toFloat(), -y.toFloat())
        drawLayers(c, doc)
        return px.getPixel(0, 0)
    }

    /** One layer's colour at a canvas pixel, or transparent outside it. */
    fun sampleLayer(layer: Layer, x: Int, y: Int): Int {
        val lx = x - layer.x
        val ly = y - layer.y
        if (lx !in 0 until layer.width || ly !in 0 until layer.height) return Color.TRANSPARENT
        return layer.bitmap.getPixel(lx, ly)
    }

    /** The selection mask cropped and shifted into [layer]'s own coordinates (ALPHA_8). */
    fun selectionFor(layer: Layer, selection: Selection?): Bitmap? {
        selection ?: return null
        val m = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ALPHA_8)
        Canvas(m).drawBitmap(selection.mask, -layer.x.toFloat(), -layer.y.toFloat(), Paint().apply { blendMode = BlendMode.SRC })
        return m
    }

    /**
     * Blends a filtered copy of a layer back over the original through the selection:
     * selected pixels come from [result], the rest stay as in [original].
     */
    fun mergeThroughMask(original: Bitmap, result: Bitmap, mask: Bitmap): Bitmap {
        val out = Bitmaps.copy(original)
        val c = Canvas(out)
        val bounds = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        // Remove the selected part of the original, then add the selected part of the result.
        c.drawBitmap(mask, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT })
        val save = c.saveLayer(bounds, Paint().apply { blendMode = BlendMode.PLUS })
        c.drawBitmap(result, 0f, 0f, Paint().apply { blendMode = BlendMode.SRC })
        c.drawBitmap(mask, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_IN })
        c.restoreToCount(save)
        return out
    }

    fun thumbnail(src: Bitmap, side: Int): Bitmap {
        val s = side.toFloat() / max(src.width, src.height)
        if (s >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt().coerceAtLeast(1),
            (src.height * s).roundToInt().coerceAtLeast(1), true)
    }

    fun docRect(doc: DocState) = Rect(0, 0, doc.width, doc.height)
}
