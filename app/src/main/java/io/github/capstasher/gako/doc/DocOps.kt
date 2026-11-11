package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Whole-image operations. Each returns a new [DocState] and leaves the old one untouched. */
object DocOps {

    fun newImage(w: Int, h: Int, fill: Int?, name: String = "Background"): DocState {
        val layer = Layer(Layer.newId(), name, Bitmaps.blank(w, h, fill ?: 0))
        return DocState(w, h, listOf(layer), layer.id)
    }

    fun fromBitmap(b: Bitmap, name: String = "Background"): DocState {
        val layer = Layer(Layer.newId(), name, Bitmaps.mutable(b))
        return DocState(b.width, b.height, listOf(layer), layer.id)
    }

    private fun transformed(b: Bitmap, m: Matrix): Bitmap {
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapRect(r)
        val out = Bitmaps.blank(r.width().roundToInt(), r.height().roundToInt())
        val mm = Matrix(m).apply { postTranslate(-r.left, -r.top) }
        Canvas(out).drawBitmap(b, mm, Paint().apply { isFilterBitmap = true })
        return out
    }

    private fun transformedMask(b: Bitmap, m: Matrix, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(out).drawBitmap(b, m, Paint().apply { isFilterBitmap = true })
        return out
    }

    /** Rotates by a multiple of 90° clockwise, or flips, every layer and the selection. */
    fun rotate(doc: DocState, quarterTurns: Int): DocState {
        val q = ((quarterTurns % 4) + 4) % 4
        if (q == 0) return doc
        val w = doc.width
        val h = doc.height
        val nw = if (q % 2 == 1) h else w
        val nh = if (q % 2 == 1) w else h
        // Canvas-space matrix mapping old canvas onto new canvas.
        val m = Matrix().apply {
            postRotate(90f * q)
            when (q) {
                1 -> postTranslate(h.toFloat(), 0f)
                2 -> postTranslate(w.toFloat(), h.toFloat())
                3 -> postTranslate(0f, w.toFloat())
            }
        }
        return mapAll(doc, m, nw, nh)
    }

    fun flip(doc: DocState, horizontal: Boolean): DocState {
        val m = Matrix().apply {
            if (horizontal) { postScale(-1f, 1f); postTranslate(doc.width.toFloat(), 0f) }
            else { postScale(1f, -1f); postTranslate(0f, doc.height.toFloat()) }
        }
        return mapAll(doc, m, doc.width, doc.height)
    }

    private fun mapAll(doc: DocState, m: Matrix, nw: Int, nh: Int): DocState {
        val layers = doc.layers.map { l ->
            val r = RectF(l.bounds)
            m.mapRect(r)
            // Per-layer matrix: layer space -> canvas space -> new canvas.
            val lm = Matrix().apply { postTranslate(l.x.toFloat(), l.y.toFloat()); postConcat(m) }
            l.copy(bitmap = transformed(l.bitmap, lm), mask = l.mask?.let { transformed(it, lm) },
                x = r.left.roundToInt(), y = r.top.roundToInt())
        }
        val sel = doc.selection?.let { Selections.make(transformedMask(it.mask, m, nw, nh)) }
        return doc.copy(width = nw, height = nh, layers = layers, selection = sel)
    }

    /** Crops the canvas to [r] (canvas coordinates), dropping pixels outside it. */
    fun crop(doc: DocState, r: Rect): DocState {
        val crop = Rect(r).apply { intersect(0, 0, doc.width, doc.height) }
        if (crop.isEmpty) return doc
        val layers = doc.layers.map { l ->
            val keep = Rect(l.bounds)
            if (!keep.intersect(crop)) {
                l.copy(bitmap = Bitmaps.blank(1, 1), mask = l.mask?.let { Bitmaps.blank(1, 1, android.graphics.Color.WHITE) }, x = 0, y = 0)
            } else {
                val local = Rect(keep).apply { offset(-l.x, -l.y) }
                l.copy(bitmap = Bitmaps.mutable(Bitmaps.crop(l.bitmap, local)),
                    mask = l.mask?.let { Bitmaps.mutable(Bitmaps.crop(it, local)) },
                    x = keep.left - crop.left, y = keep.top - crop.top)
            }
        }
        val sel = doc.selection?.let { s ->
            val m = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ALPHA_8)
            Canvas(m).drawBitmap(s.mask, -crop.left.toFloat(), -crop.top.toFloat(), null)
            Selections.make(m)
        }
        return doc.copy(width = crop.width(), height = crop.height(), layers = layers, selection = sel)
    }

    /** Image > Canvas Size: a bigger or smaller canvas, layers moved by ([dx], [dy]). */
    fun canvasSize(doc: DocState, w: Int, h: Int, dx: Int, dy: Int, resizeLayers: Boolean): DocState {
        val layers = doc.layers.map { l ->
            if (resizeLayers && l.x == 0 && l.y == 0 && l.width == doc.width && l.height == doc.height) {
                val b = Bitmaps.blank(w, h)
                Canvas(b).drawBitmap(l.bitmap, dx.toFloat(), dy.toFloat(), null)
                val m = l.mask?.let { old ->
                    Bitmaps.blank(w, h, android.graphics.Color.WHITE).also { Canvas(it).drawBitmap(old, dx.toFloat(), dy.toFloat(), null) }
                }
                l.copy(bitmap = b, mask = m, x = 0, y = 0)
            } else l.copy(x = l.x + dx, y = l.y + dy)
        }
        val sel = doc.selection?.let { s ->
            val m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            Canvas(m).drawBitmap(s.mask, dx.toFloat(), dy.toFloat(), null)
            Selections.make(m)
        }
        return doc.copy(width = w, height = h, layers = layers, selection = sel)
    }

    /** Image > Scale Image: every layer, its position and the selection scale together. */
    fun scale(doc: DocState, w: Int, h: Int): DocState {
        val sx = w.toFloat() / doc.width
        val sy = h.toFloat() / doc.height
        val layers = doc.layers.map { l ->
            val nx = floor(l.x * sx).toInt()
            val ny = floor(l.y * sy).toInt()
            val nw = (ceil((l.x + l.width) * sx).toInt() - nx).coerceAtLeast(1)
            val nh = (ceil((l.y + l.height) * sy).toInt() - ny).coerceAtLeast(1)
            l.copy(bitmap = scaled(l.bitmap, nw, nh), mask = l.mask?.let { scaled(it, nw, nh) }, x = nx, y = ny)
        }
        val sel = doc.selection?.let { Selections.make(Bitmap.createScaledBitmap(it.mask, w, h, true)) }
        return doc.copy(width = w, height = h, layers = layers, selection = sel)
    }

    /** Good-quality downscale (repeated halving, like a mipmap) or bilinear upscale. */
    fun scaled(src: Bitmap, w: Int, h: Int): Bitmap {
        var b = src
        while (b.width / 2 >= w && b.height / 2 >= h && b.width > 1 && b.height > 1) {
            b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        }
        return Bitmaps.mutable(if (b.width == w && b.height == h) Bitmaps.copy(b) else Bitmap.createScaledBitmap(b, w, h, true))
    }

    fun scaleLayer(doc: DocState, id: Long, w: Int, h: Int): DocState {
        val l = doc.layer(id) ?: return doc
        val cx = l.x + l.width / 2f
        val cy = l.y + l.height / 2f
        return doc.replace(l.copy(bitmap = scaled(l.bitmap, w, h), mask = l.mask?.let { scaled(it, w, h) },
            x = (cx - w / 2f).roundToInt(), y = (cy - h / 2f).roundToInt()))
    }

    /** Layer > Layer to Image Size. */
    fun layerToImageSize(doc: DocState, id: Long): DocState {
        val l = doc.layer(id) ?: return doc
        val b = Bitmaps.blank(doc.width, doc.height)
        Canvas(b).drawBitmap(l.bitmap, l.x.toFloat(), l.y.toFloat(), null)
        val m = l.mask?.let { old ->
            Bitmaps.blank(doc.width, doc.height, android.graphics.Color.WHITE).also { Canvas(it).drawBitmap(old, l.x.toFloat(), l.y.toFloat(), null) }
        }
        return doc.replace(l.copy(bitmap = b, mask = m, x = 0, y = 0))
    }

    /** Merges the active layer into the one below, GIMP's Merge Down. */
    fun mergeDown(doc: DocState): DocState {
        val i = doc.activeIndex
        if (i <= 0) return doc
        val top = doc.layers[i]
        val below = doc.layers[i - 1]
        val r = Rect(below.bounds).apply { if (top.visible) union(top.bounds) }
        val b = Bitmaps.blank(r.width(), r.height())
        val c = Canvas(b)
        c.translate(-r.left.toFloat(), -r.top.toFloat())
        Compositor.drawLayer(c, below, Paint())
        if (top.visible) Compositor.drawLayer(c, top, Compositor.layerPaint(top))
        val merged = below.copy(bitmap = b, x = r.left, y = r.top, text = null, mask = null)
        val layers = doc.layers.toMutableList().apply { removeAt(i); set(i - 1, merged) }
        return doc.copy(layers = layers, activeId = merged.id)
    }

    fun flatten(doc: DocState, background: Int): DocState {
        val b = Compositor.flatten(doc, background)
        val l = Layer(Layer.newId(), "Background", b)
        return doc.copy(layers = listOf(l), activeId = l.id)
    }

    fun mergeVisible(doc: DocState): DocState {
        val visible = doc.layers.filter { it.visible }
        if (visible.size < 2) return doc
        val b = Compositor.flatten(doc, null, visible)
        val merged = Layer(Layer.newId(), visible.last().name, b)
        val firstIndex = doc.layers.indexOf(visible.first())
        // Hidden layers keep their places; the merged layer sits where the lowest visible one was.
        val layers = doc.layers.subList(0, firstIndex).filter { !it.visible } + merged +
            doc.layers.subList(firstIndex, doc.layers.size).filter { !it.visible }
        return doc.copy(layers = layers, activeId = merged.id)
    }

    /** Draws [paint]'s colour (or [source]) into the selection, or the whole layer if none. */
    fun fillLayer(layer: Layer, selection: Bitmap?, color: Int, blend: BlendMode = BlendMode.SRC_OVER) {
        val c = Canvas(layer.bitmap)
        val p = Paint().apply { this.color = color; blendMode = blend }
        if (selection == null) {
            c.drawPaint(p)
        } else {
            val bounds = RectF(0f, 0f, layer.width.toFloat(), layer.height.toFloat())
            val save = c.saveLayer(bounds, Paint().apply { blendMode = blend })
            c.drawBitmap(selection, 0f, 0f, Paint().apply { this.color = color })
            c.restoreToCount(save)
        }
    }

    /** Canvas pixel bounds of [r] intersected with the layer, in layer coordinates. */
    fun layerRect(layer: Layer, r: Rect?): Rect {
        val out = Rect(0, 0, layer.width, layer.height)
        if (r != null) {
            val local = Rect(r).apply { offset(-layer.x, -layer.y) }
            if (!out.intersect(local)) out.setEmpty()
        }
        return out
    }
}
