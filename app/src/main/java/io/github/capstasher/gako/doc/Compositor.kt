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
        blendMode = if (layer.mode == LayerMode.PassThrough && !layer.group) BlendMode.SRC_OVER else layer.mode.blend
        isFilterBitmap = filter
    }

    /** Draws every visible layer onto [canvas] in canvas coordinates, isolated from what's below. */
    fun drawLayers(canvas: Canvas, doc: DocState, layers: List<Layer> = doc.layers) {
        val bounds = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        val save = canvas.saveLayer(bounds, null)
        drawTree(canvas, layers, bounds) { c, l -> drawLayer(c, l, layerPaint(l)) }
        canvas.restoreToCount(save)
    }

    /**
     * Walks the layer tree bottom to top, calling [leaf] for each visible pixel layer. A group draws
     * its children into its own layer, then blends that with its mode and opacity; a pass-through
     * group at full opacity lets them blend straight onto what's below.
     */
    fun drawTree(canvas: Canvas, layers: List<Layer>, bounds: RectF, parent: Long? = null,
                 /** Size isolated groups to their contents (saves memory); off on screen, where previews can overhang. */
                 tight: Boolean = true, leaf: (Canvas, Layer) -> Unit) {
        for (l in layers) {
            if (l.parent != parent || !l.visible || l.opacity <= 0f) continue
            if (!l.group) { leaf(canvas, l); continue }
            if (l.mode == LayerMode.PassThrough && l.opacity >= 1f) {
                drawTree(canvas, layers, bounds, l.id, tight, leaf)
            } else {
                // Only as big as the group's contents: nested full-canvas layers add up fast.
                val area = if (tight) groupBounds(layers, l.id) else RectF(bounds)
                if (area.isEmpty || !area.intersect(bounds)) continue
                val save = canvas.saveLayer(area, layerPaint(l))
                drawTree(canvas, layers, bounds, l.id, tight, leaf)
                canvas.restoreToCount(save)
            }
        }
    }

    /** The canvas area a group's pixel layers cover. */
    fun groupBounds(layers: List<Layer>, id: Long): RectF {
        val byId = Tree.byId(layers)
        val r = RectF()
        for (l in layers) if (!l.group && Tree.isInside(l, id, byId)) r.union(RectF(l.bounds))
        return r
    }

    /** Turns a grey mask into alpha (its red channel) for DST_IN. */
    val maskPaint: Paint get() = Paint().apply {
        isFilterBitmap = true
        blendMode = BlendMode.DST_IN
        colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f,
            1f, 0f, 0f, 0f, 0f,
        ))
    }

    /**
     * Draws one layer (or [content] in its place) with its mask. [matrix] maps layer pixels to the
     * canvas (default: the layer's offset); [contentScale] is how much smaller [content]/[mask] are
     * than the layer (for downscaled previews).
     */
    fun drawLayer(
        c: Canvas, l: Layer, paint: Paint,
        matrix: android.graphics.Matrix = android.graphics.Matrix().apply { setTranslate(l.x.toFloat(), l.y.toFloat()) },
        content: Bitmap = l.bitmap,
        mask: Bitmap? = l.activeMask,
    ) {
        fun place(b: Bitmap) = android.graphics.Matrix(matrix).apply {
            preScale(l.width.toFloat() / b.width, l.height.toFloat() / b.height)
        }
        if (mask == null) {
            c.drawBitmap(content, place(content), paint)
            return
        }
        val bounds = RectF(0f, 0f, l.width.toFloat(), l.height.toFloat()).also { matrix.mapRect(it) }
        val save = c.saveLayer(bounds, paint)
        c.drawBitmap(content, place(content), Paint().apply { isFilterBitmap = paint.isFilterBitmap })
        c.drawBitmap(mask, place(mask), maskPaint)
        c.restoreToCount(save)
    }

    /** The layer's pixels with its mask applied (for formats without masks, and Apply Layer Mask). */
    fun applyMask(l: Layer): Bitmap {
        val m = l.mask ?: return l.bitmap
        val out = Bitmaps.copy(l.bitmap)
        Canvas(out).drawBitmap(m, 0f, 0f, maskPaint)
        return out
    }

    /** A white (fully showing) mask for [l]. */
    fun whiteMask(w: Int, h: Int): Bitmap = Bitmaps.blank(w, h, android.graphics.Color.WHITE)

    /** A grey mask from an alpha (ALPHA_8) bitmap: white where opaque. */
    fun maskFromAlpha(alpha: Bitmap, w: Int, h: Int, dx: Float = 0f, dy: Float = 0f): Bitmap {
        val m = Bitmaps.blank(w, h, android.graphics.Color.BLACK)
        Canvas(m).drawBitmap(alpha, dx, dy, Paint().apply { color = android.graphics.Color.WHITE })
        return m
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
        Bitmaps.maskIn(c, mask)
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
