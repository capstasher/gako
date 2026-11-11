package io.github.capstasher.gako.tools

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.doc.Step
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One brush stroke on one layer, GIMP-style: dabs build a coverage mask for the whole stroke,
 * and the layer is re-composited from its untouched copy wherever the mask grew, so the stroke's
 * opacity never stacks up with itself.
 *
 * Coordinates passed in are canvas coordinates; everything inside is in layer coordinates.
 */
class PaintStroke(
    private val tool: Tool,
    private val layer: Layer,
    private val selection: Bitmap?,          // ALPHA_8, layer-sized, or null
    private val color: Int,
    private val options: ToolOptions,
    /** Clone: where the source is relative to the brush, in pixels. */
    private val cloneOffset: Pair<Float, Float>? = null,
    /** Symmetry axes in canvas coordinates (x of the vertical axis, y of the horizontal one). */
    private val mirrorX: Float? = null,
    private val mirrorY: Float? = null,
    /** Filter brush: the filtered layer the stroke reveals. */
    private val revealSource: Bitmap? = null,
) {
    private val bmp: Bitmap = layer.bitmap
    private val before: Bitmap = bmp.copy(Bitmap.Config.ARGB_8888, false)
    private val mask: Bitmap = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ALPHA_8)
    private val maskCanvas = Canvas(mask)
    private val layerCanvas = Canvas(bmp)
    private val total = Rect()
    private var last: Pair<Float, Float>? = null
    private var carry = 0f   // distance travelled since the last dab
    private val bounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())

    private val baseSize = options.size.coerceAtLeast(1f)
    private val hard = tool == Tool.Pencil
    private val round = options.brushShape == BrushShape.Round
    private val dab: Bitmap? = if (hard && round) null
        else dabBitmap(baseSize, if (hard) 1f else if (tool == Tool.Airbrush) 0.2f else options.hardness, options.brushShape, options.brushAngle)
    private val dabPaint = Paint().apply {
        isFilterBitmap = true
        if (tool == Tool.Airbrush) alpha = (options.airRate * 160).roundToInt().coerceIn(4, 255)
    }
    private val pencilPaint = Paint().apply { isAntiAlias = false; color = Color.BLACK }
    // Flat nibs need closer dabs, or their thin edge shows each one.
    private val spacing = when {
        tool == Tool.Airbrush -> max(1f, baseSize * 0.08f)
        options.brushShape != BrushShape.Round -> max(0.5f, baseSize * 0.03f)
        else -> max(0.5f, baseSize * if (hard) 0.2f else 0.1f)
    }

    private val restorePaint = Paint().apply { blendMode = BlendMode.SRC }
    private val maskTint = Paint().apply { this.color = this@PaintStroke.color or (0xFF shl 24) }
    private val layerPaint = Paint().apply {
        alpha = (options.opacity * 255).roundToInt().coerceIn(0, 255)
        blendMode = when {
            tool == Tool.Eraser -> if (layer.lockAlpha) BlendMode.SRC_ATOP else BlendMode.DST_OUT
            tool == Tool.DodgeBurn || tool == Tool.Blur || layer.lockAlpha -> BlendMode.SRC_ATOP
            tool == Tool.FilterBrush -> BlendMode.SRC_OVER
            else -> BlendMode.SRC_OVER
        }
    }
    private val sourcePaint = Paint().apply {
        isFilterBitmap = true
        if (tool == Tool.DodgeBurn) {
            // Exposure up (dodge) or down (burn), like GIMP's at "midtones".
            val e = options.exposure.coerceIn(0.05f, 1f)
            val k = if (options.burn) 1f - 0.5f * e else 1f + e
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k, 1f) })
        }
        if (tool == Tool.Eraser && layer.lockAlpha) {
            // Locked alpha: "erase" to the background colour instead.
            colorFilter = null
        }
    }

    // ---- Blur and Heal reveal a source worked out tile by tile as the brush reaches it, kept only
    // for the tiles the stroke touches (a full-size copy of a big layer would cost ~100 MB).
    private val tileSize = 128
    private val tilesX = (bmp.width + tileSize - 1) / tileSize
    private val sourceTiles = HashMap<Int, Bitmap>()
    private val blurLevels = 1 + (options.strength * 3.99f).toInt()          // halvings: 2×…16×

    private fun tileRect(i: Int): Rect {
        val tx = i % tilesX
        val ty = i / tilesX
        return Rect(tx * tileSize, ty * tileSize, min(bmp.width, (tx + 1) * tileSize), min(bmp.height, (ty + 1) * tileSize))
    }

    private fun tilesIn(r: Rect): List<Int> {
        val out = ArrayList<Int>()
        if (r.isEmpty) return out
        for (ty in max(0, r.top) / tileSize..(min(bmp.height, r.bottom) - 1) / tileSize)
            for (tx in max(0, r.left) / tileSize..(min(bmp.width, r.right) - 1) / tileSize) out += ty * tilesX + tx
        return out
    }

    /** [before] over [r], with the layer's edge pixels repeated outside it (so blurs don't darken edges). */
    private fun region(r: Rect): Bitmap = Bitmaps.blank(r.width(), r.height()).also {
        val shader = android.graphics.BitmapShader(before, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(-r.left.toFloat(), -r.top.toFloat()) })
        Canvas(it).drawPaint(Paint().apply { this.shader = shader })
    }

    /** A box blur by repeated 2× bilinear halving and doubling; exact when sizes divide by 2^levels. */
    private fun boxBlur(b: Bitmap, levels: Int): Bitmap {
        var p = b
        repeat(levels) { p = Bitmap.createScaledBitmap(p, max(1, p.width / 2), max(1, p.height / 2), true) }
        repeat(levels) { p = Bitmap.createScaledBitmap(p, p.width * 2, p.height * 2, true) }
        return if (p.width == b.width && p.height == b.height) p else Bitmap.createScaledBitmap(p, b.width, b.height, true)
    }

    /** [t] grown by [pad] and rounded up to a multiple of [unit], so every tile blurs on the same grid. */
    private fun paddedArea(t: Rect, pad: Int, unit: Int): Rect {
        val a = Rect(t).apply { inset(-pad, -pad) }
        a.right = a.left + (a.width() + unit - 1) / unit * unit
        a.bottom = a.top + (a.height() + unit - 1) / unit * unit
        return a
    }

    private fun ensureSource(r: Rect) {
        for (i in tilesIn(r)) {
            if (sourceTiles.containsKey(i)) continue
            val t = tileRect(i)
            sourceTiles[i] = when (tool) {
                Tool.Blur -> blurTile(t)
                Tool.Heal -> healTile(t) ?: continue
                else -> continue
            }
        }
    }

    private fun blurTile(t: Rect): Bitmap {
        val k = 1 shl blurLevels
        val area = paddedArea(t, k * 4, k)
        val blurred = boxBlur(region(area), blurLevels)
        return Bitmap.createBitmap(blurred, t.left - area.left, t.top - area.top, t.width(), t.height())
    }

    // Heal: the source's detail with the destination's own tone (GIMP's heal, approximately):
    // result = source + blur(destination) − blur(source).
    private fun healTile(t: Rect): Bitmap? {
        val (ox, oy) = cloneOffset ?: return null
        val dx = ox.roundToInt()
        val dy = oy.roundToInt()
        val levels = 4                         // ~16 px: the scale of "tone" carried over
        val area = paddedArea(t, 64, 1 shl levels)
        val w = t.width()
        val h = t.height()
        val dstBlur = boxBlur(region(area), levels)
        val srcBlur = boxBlur(region(Rect(area).apply { offset(dx, dy) }), levels)
        val src = region(Rect(t).apply { offset(dx, dy) })
        val a = IntArray(w * h); val bd = IntArray(w * h); val bs = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        dstBlur.getPixels(bd, 0, w, t.left - area.left, t.top - area.top, w, h)
        srcBlur.getPixels(bs, 0, w, t.left - area.left, t.top - area.top, w, h)
        for (k in a.indices) {
            val s = a[k]; val d = bd[k]; val q = bs[k]
            val rr = (((s shr 16) and 0xFF) + ((d shr 16) and 0xFF) - ((q shr 16) and 0xFF)).coerceIn(0, 255)
            val gg = (((s shr 8) and 0xFF) + ((d shr 8) and 0xFF) - ((q shr 8) and 0xFF)).coerceIn(0, 255)
            val bb = ((s and 0xFF) + (d and 0xFF) - (q and 0xFF)).coerceIn(0, 255)
            a[k] = (s and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
        return Bitmaps.blank(w, h).also { it.setPixels(a, 0, w, 0, 0, w, h) }
    }

    private fun drawSource(c: Canvas, r: Rect) {
        for (i in tilesIn(r)) {
            val tile = sourceTiles[i] ?: continue
            val t = tileRect(i)
            c.drawBitmap(tile, t.left.toFloat(), t.top.toFloat(), null)
        }
    }

    // ---- Smudge: carry the colour under the brush along the stroke.
    private var accum: Bitmap? = null
    private val smudgeRate = 0.3f + options.strength * 0.65f

    private fun smudgeDab(x: Float, y: Float, dirty: Rect) {
        val d = ceil(baseSize).toInt().coerceAtLeast(2)
        val left = (x - d / 2f).roundToInt()
        val top = (y - d / 2f).roundToInt()
        val box = Rect(left, top, left + d, top + d)
        val acc = accum
        if (acc == null) {
            accum = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(bmp, -left.toFloat(), -top.toFloat(), null)
            }
            return
        }
        val c = layerCanvas
        val boxF = RectF(box)
        val save = c.saveLayer(boxF, layerPaint)
        c.drawBitmap(acc, left.toFloat(), top.toFloat(), null)
        val shape = android.graphics.BitmapShader(dab!!, android.graphics.Shader.TileMode.DECAL, android.graphics.Shader.TileMode.DECAL).apply {
            setLocalMatrix(android.graphics.Matrix().apply {
                setScale(d.toFloat() / dab.width, d.toFloat() / dab.height)
                postTranslate(left.toFloat(), top.toFloat())
            })
        }
        c.drawRect(boxF, Paint().apply { shader = shape; blendMode = BlendMode.DST_IN })
        selection?.let { Bitmaps.maskIn(c, it) }
        c.restoreToCount(save)
        // Pick up some of what's there now for the next dab.
        Canvas(acc).drawBitmap(bmp, -left.toFloat(), -top.toFloat(), Paint().apply { alpha = ((1 - smudgeRate) * 255).roundToInt() })
        if (box.intersect(0, 0, bmp.width, bmp.height)) {
            dirty.union(box)
            total.union(box)
        }
    }

    val changed: Boolean get() = !total.isEmpty

    fun begin(x: Float, y: Float, pressure: Float = 1f) {
        val lx = x - layer.x
        val ly = y - layer.y
        last = lx to ly
        carry = 0f
        val dirty = Rect()
        stamp(lx, ly, pressure, dirty)
        render(dirty)
    }

    fun moveTo(x: Float, y: Float, pressure: Float = 1f) {
        val lx = x - layer.x
        val ly = y - layer.y
        val (px, py) = last ?: return begin(x, y, pressure)
        val dist = hypot(lx - px, ly - py)
        if (dist <= 0f) return
        val dirty = Rect()
        var t = spacing - carry
        while (t <= dist) {
            val f = t / dist
            stamp(px + (lx - px) * f, py + (ly - py) * f, pressure, dirty)
            t += spacing
        }
        carry = (carry + dist) % spacing
        last = lx to ly
        if (!dirty.isEmpty) render(dirty)
    }

    /** Airbrush: keep adding paint while the finger rests. */
    fun dwell(pressure: Float = 1f) {
        val (px, py) = last ?: return
        val dirty = Rect()
        stamp(px, py, pressure, dirty)
        render(dirty)
    }

    // Symmetry, in layer coordinates; tools that copy pixels around don't mirror.
    private val mirrorable = tool != Tool.Smudge && tool != Tool.Clone && tool != Tool.Heal
    private val axisX = mirrorX?.takeIf { mirrorable }?.minus(layer.x)
    private val axisY = mirrorY?.takeIf { mirrorable }?.minus(layer.y)

    private fun stamp(x: Float, y: Float, pressure: Float, dirty: Rect) {
        stampOne(x, y, pressure, dirty)
        val ax = axisX
        val ay = axisY
        if (ax != null) stampOne(2 * ax - x, y, pressure, dirty)
        if (ay != null) stampOne(x, 2 * ay - y, pressure, dirty)
        if (ax != null && ay != null) stampOne(2 * ax - x, 2 * ay - y, pressure, dirty)
    }

    private fun stampOne(x: Float, y: Float, pressure: Float, dirty: Rect) {
        if (tool == Tool.Smudge) { smudgeDab(x, y, dirty); return }
        val size = baseSize * pressure.coerceIn(0.1f, 2f)
        val r = size / 2f
        val box = Rect(floor(x - r - 1).toInt(), floor(y - r - 1).toInt(), ceil(x + r + 1).toInt(), ceil(y + r + 1).toInt())
        if (!box.intersect(0, 0, bmp.width, bmp.height)) return
        if (hard && dab == null) {
            if (size <= 1.5f) maskCanvas.drawRect(floor(x), floor(y), floor(x) + 1, floor(y) + 1, pencilPaint)
            else maskCanvas.drawCircle(x, y, r, pencilPaint)
        } else if (hard) {
            maskCanvas.drawBitmap(dab!!, null, RectF(x - r, y - r, x + r, y + r), pencilPaint)
        } else {
            maskCanvas.drawBitmap(dab!!, null, RectF(x - r, y - r, x + r, y + r), dabPaint)
        }
        dirty.union(box)
    }

    // ---- What the screen shows during the stroke: the layer as it was (uploaded to the GPU once)
    // with copies of just the 256 px tiles the stroke has touched on top, so a brush move re-uploads
    // a tile or two instead of the whole layer.
    private val viewTile = 256
    private val viewTilesX = (bmp.width + viewTile - 1) / viewTile
    private val viewTiles = HashMap<Int, Bitmap>()
    private val copyPaint = Paint().apply { blendMode = BlendMode.SRC }

    val layerId: Long get() = layer.id
    val viewBase: Bitmap get() = before
    val viewTileMap: Map<Int, Bitmap> get() = viewTiles

    fun viewTileRect(i: Int): Rect {
        val tx = i % viewTilesX
        val ty = i / viewTilesX
        return Rect(tx * viewTile, ty * viewTile, min(bmp.width, (tx + 1) * viewTile), min(bmp.height, (ty + 1) * viewTile))
    }

    private fun refreshView(dirty: Rect) {
        val d = Rect(dirty)
        if (!d.intersect(0, 0, bmp.width, bmp.height)) return
        for (ty in d.top / viewTile..(d.bottom - 1) / viewTile) for (tx in d.left / viewTile..(d.right - 1) / viewTile) {
            val i = ty * viewTilesX + tx
            val t = viewTileRect(i)
            val fresh = !viewTiles.containsKey(i)
            val tile = viewTiles.getOrPut(i) { Bitmap.createBitmap(t.width(), t.height(), Bitmap.Config.ARGB_8888) }
            // A new tile copies all of its area; an existing one only what changed.
            val src = if (fresh) Rect(t) else Rect(t).apply { intersect(d) }
            val dst = Rect(src).apply { offset(-t.left, -t.top) }
            Canvas(tile).drawBitmap(bmp, src, dst, copyPaint)
        }
    }

    private fun render(dirty: Rect) {
        if (dirty.isEmpty) return
        if (tool == Tool.Smudge) { refreshView(dirty); return }
        total.union(dirty)
        if (tool == Tool.Blur || tool == Tool.Heal) ensureSource(dirty)
        val c = layerCanvas
        c.save()
        c.clipRect(dirty)
        c.drawBitmap(before, 0f, 0f, restorePaint)
        val save = c.saveLayer(RectF(dirty), layerPaint)
        when (tool) {
            Tool.Clone -> {
                val (dx, dy) = cloneOffset ?: (0f to 0f)
                c.drawBitmap(before, -dx, -dy, sourcePaint)
                Bitmaps.maskIn(c, mask)
            }
            Tool.DodgeBurn -> {
                c.drawBitmap(before, 0f, 0f, sourcePaint)
                Bitmaps.maskIn(c, mask)
            }
            Tool.Blur, Tool.Heal -> {
                drawSource(c, dirty)
                Bitmaps.maskIn(c, mask)
            }
            Tool.FilterBrush -> {
                // The filtered pixels replace the original ones where painted (alpha too).
                revealSource?.let { c.drawBitmap(it, 0f, 0f, null) }
                Bitmaps.maskIn(c, mask)
            }
            else -> c.drawBitmap(mask, 0f, 0f, maskTint)
        }
        selection?.let { Bitmaps.maskIn(c, it) }
        c.restoreToCount(save)
        c.restore()
        refreshView(dirty)
    }

    /** Puts the layer back as it was. */
    fun cancel() {
        if (!total.isEmpty) {
            layerCanvas.save()
            layerCanvas.clipRect(total)
            layerCanvas.drawBitmap(before, 0f, 0f, restorePaint)
            layerCanvas.restore()
        }
        total.setEmpty()
    }

    /** Finishes the stroke and returns the undo step for it (null if nothing changed). */
    fun finish(label: String): Step.Patch? {
        if (total.isEmpty || !total.intersect(0, 0, bmp.width, bmp.height)) return null
        return Step.Patch(label, layer.id, Rect(total), Bitmaps.crop(before, total), Bitmaps.crop(bmp, total))
    }

    companion object {
        private val dabCache = HashMap<String, Bitmap>()

        /**
         * A brush dab: solid out to [hardness] of the radius, then a smooth falloff. Round, square
         * or a calligraphy nib (a flat ellipse), the last two turned by [angle] degrees.
         */
        fun dabBitmap(size: Float, hardness: Float, shape: BrushShape = BrushShape.Round, angle: Float = 0f): Bitmap {
            val d = ceil(size).toInt().coerceIn(2, 256)
            val h = (hardness.coerceIn(0f, 1f) * 20).roundToInt()
            val a = if (shape == BrushShape.Round) 0 else angle.roundToInt()
            return dabCache.getOrPut("$d/$h/$shape/$a") {
                val b = Bitmap.createBitmap(d, d, Bitmap.Config.ALPHA_8)
                val bytes = ByteArray(b.rowBytes * d)
                val r = d / 2f
                val inner = h / 20f
                val cos = kotlin.math.cos(Math.toRadians(a.toDouble())).toFloat()
                val sin = kotlin.math.sin(Math.toRadians(a.toDouble())).toFloat()
                for (y in 0 until d) for (x in 0 until d) {
                    val px = x + 0.5f - r
                    val py = y + 0.5f - r
                    // Into the brush's own axes.
                    val u = px * cos + py * sin
                    val v = -px * sin + py * cos
                    val dist = when (shape) {
                        BrushShape.Round -> sqrt(px * px + py * py) / r
                        BrushShape.Square -> max(kotlin.math.abs(u), kotlin.math.abs(v)) / (r * 0.75f)
                        BrushShape.Calligraphy -> sqrt(u * u + (v / 0.22f) * (v / 0.22f)) / r
                    }
                    val a = when {
                        dist >= 1f -> 0f
                        dist <= inner -> 1f
                        else -> {
                            val t = (dist - inner) / max(1e-3f, 1f - inner)
                            val s = 1f - t
                            s * s * (3 - 2 * s)   // smoothstep
                        }
                    }
                    bytes[y * b.rowBytes + x] = (a * 255).roundToInt().toByte()
                }
                b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                if (dabCache.size > 32) dabCache.clear()
                b
            }
        }

        fun clampRect(r: Rect, w: Int, h: Int) = Rect(max(0, r.left), max(0, r.top), min(w, r.right), min(h, r.bottom))
    }
}
