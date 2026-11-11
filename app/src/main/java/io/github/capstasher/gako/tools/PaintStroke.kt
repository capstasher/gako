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
    private val dab: Bitmap? = if (hard) null else dabBitmap(baseSize, if (tool == Tool.Airbrush) 0.2f else options.hardness)
    private val dabPaint = Paint().apply {
        isFilterBitmap = true
        if (tool == Tool.Airbrush) alpha = (options.airRate * 160).roundToInt().coerceIn(4, 255)
    }
    private val pencilPaint = Paint().apply { isAntiAlias = false; color = Color.BLACK }
    private val spacing = if (tool == Tool.Airbrush) max(1f, baseSize * 0.08f) else max(0.5f, baseSize * if (hard) 0.2f else 0.1f)

    private val restorePaint = Paint().apply { blendMode = BlendMode.SRC }
    private val maskTint = Paint().apply { this.color = this@PaintStroke.color or (0xFF shl 24) }
    private val layerPaint = Paint().apply {
        alpha = (options.opacity * 255).roundToInt().coerceIn(0, 255)
        blendMode = when {
            tool == Tool.Eraser -> if (layer.lockAlpha) BlendMode.SRC_ATOP else BlendMode.DST_OUT
            tool == Tool.DodgeBurn || layer.lockAlpha -> BlendMode.SRC_ATOP
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

    private fun stamp(x: Float, y: Float, pressure: Float, dirty: Rect) {
        val size = baseSize * pressure.coerceIn(0.1f, 2f)
        val r = size / 2f
        val box = Rect(floor(x - r - 1).toInt(), floor(y - r - 1).toInt(), ceil(x + r + 1).toInt(), ceil(y + r + 1).toInt())
        if (!box.intersect(0, 0, bmp.width, bmp.height)) return
        if (hard) {
            if (size <= 1.5f) maskCanvas.drawRect(floor(x), floor(y), floor(x) + 1, floor(y) + 1, pencilPaint)
            else maskCanvas.drawCircle(x, y, r, pencilPaint)
        } else {
            maskCanvas.drawBitmap(dab!!, null, RectF(x - r, y - r, x + r, y + r), dabPaint)
        }
        dirty.union(box)
    }

    private fun render(dirty: Rect) {
        if (dirty.isEmpty) return
        total.union(dirty)
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
            else -> c.drawBitmap(mask, 0f, 0f, maskTint)
        }
        selection?.let { Bitmaps.maskIn(c, it) }
        c.restoreToCount(save)
        c.restore()
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
        private val dabCache = HashMap<Pair<Int, Int>, Bitmap>()

        /** A round soft dab: solid out to [hardness] of the radius, then a smooth falloff. */
        fun dabBitmap(size: Float, hardness: Float): Bitmap {
            val d = ceil(size).toInt().coerceIn(2, 256)
            val h = (hardness.coerceIn(0f, 1f) * 20).roundToInt()
            return dabCache.getOrPut(d to h) {
                val b = Bitmap.createBitmap(d, d, Bitmap.Config.ALPHA_8)
                val bytes = ByteArray(b.rowBytes * d)
                val r = d / 2f
                val inner = h / 20f
                for (y in 0 until d) for (x in 0 until d) {
                    val dist = sqrt((x + 0.5f - r) * (x + 0.5f - r) + (y + 0.5f - r) * (y + 0.5f - r)) / r
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
