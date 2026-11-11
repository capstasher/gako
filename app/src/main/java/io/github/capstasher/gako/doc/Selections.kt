package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class SelectMode(val label: String) { Replace("Replace"), Add("Add"), Subtract("Subtract"), Intersect("Intersect") }

/** Building, combining and measuring selections. Masks are ALPHA_8, canvas-sized. */
object Selections {

    fun emptyMask(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)

    fun fromPath(w: Int, h: Int, path: Path, antialias: Boolean = true): Bitmap {
        val m = emptyMask(w, h)
        Canvas(m).drawPath(path, Paint().apply { isAntiAlias = antialias; color = Color.BLACK })
        return m
    }

    fun all(w: Int, h: Int): Bitmap = emptyMask(w, h).apply { eraseColor(Color.BLACK) }

    /** Combines [shape] with the existing selection, GIMP-style. */
    fun combine(existing: Bitmap?, shape: Bitmap, mode: SelectMode): Bitmap {
        if (existing == null || mode == SelectMode.Replace) {
            return if (mode == SelectMode.Subtract || mode == SelectMode.Intersect) {
                if (existing == null && mode == SelectMode.Subtract) emptyMask(shape.width, shape.height)
                else shape
            } else shape
        }
        val out = existing.copy(Bitmap.Config.ALPHA_8, true)
        val p = Paint().apply {
            blendMode = when (mode) {
                SelectMode.Add -> BlendMode.SRC_OVER
                SelectMode.Subtract -> BlendMode.DST_OUT
                SelectMode.Intersect -> BlendMode.DST_IN
                SelectMode.Replace -> BlendMode.SRC
            }
        }
        Canvas(out).drawBitmap(shape, 0f, 0f, p)
        return out
    }

    fun invert(mask: Bitmap): Bitmap {
        val out = all(mask.width, mask.height)
        Canvas(out).drawBitmap(mask, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_OUT })
        return out
    }

    /** Wraps a mask as a [Selection], or null if nothing is selected. */
    fun make(mask: Bitmap): Selection? {
        val bytes = bytesOf(mask)
        val w = mask.width
        val h = mask.height
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            var any = false
            for (x in 0 until w) {
                if (bytes[row + x].toInt() != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    any = true
                }
            }
            if (any) {
                if (y < minY) minY = y
                maxY = y
            }
        }
        if (maxX < 0) return null
        return Selection(mask, Rect(minX, minY, maxX + 1, maxY + 1), outline(mask))
    }

    /** The mask's pixels as one byte per pixel, rows packed. */
    fun bytesOf(mask: Bitmap): ByteArray {
        val w = mask.width
        val h = mask.height
        val stride = mask.rowBytes
        val buf = ByteBuffer.allocate(stride * h)
        mask.copyPixelsToBuffer(buf)
        val raw = buf.array()
        if (stride == w) return raw
        val out = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(raw, y * stride, out, y * w, w)
        return out
    }

    fun maskFromBytes(bytes: ByteArray, w: Int, h: Int): Bitmap {
        val m = emptyMask(w, h)
        val stride = m.rowBytes
        val data = if (stride == w) bytes else ByteArray(stride * h).also {
            for (y in 0 until h) System.arraycopy(bytes, y * w, it, y * stride, w)
        }
        m.copyPixelsFromBuffer(ByteBuffer.wrap(data))
        return m
    }

    /**
     * Marching-ants outline: the boundary between selected (>= 50%) and unselected pixels,
     * traced on a copy no bigger than [maxSide] and scaled back up.
     */
    fun outline(mask: Bitmap, maxSide: Int = 2048): Path {
        val s = minOf(1f, maxSide.toFloat() / max(mask.width, mask.height))
        val small = if (s < 1f) Bitmap.createScaledBitmap(mask, max(1, (mask.width * s).roundToInt()),
            max(1, (mask.height * s).roundToInt()), true) else mask
        val w = small.width
        val h = small.height
        val b = bytesOf(small)
        fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h && (b[y * w + x].toInt() and 0xFF) >= 128
        val path = Path()
        // Horizontal edges: between row y-1 and y, merged into runs.
        for (y in 0..h) {
            var start = -1
            for (x in 0..w) {
                val edge = x < w && inside(x, y - 1) != inside(x, y)
                if (edge && start < 0) start = x
                if (!edge && start >= 0) {
                    path.moveTo(start.toFloat(), y.toFloat()); path.lineTo(x.toFloat(), y.toFloat())
                    start = -1
                }
            }
        }
        // Vertical edges: between column x-1 and x.
        for (x in 0..w) {
            var start = -1
            for (y in 0..h) {
                val edge = y < h && inside(x - 1, y) != inside(x, y)
                if (edge && start < 0) start = y
                if (!edge && start >= 0) {
                    path.moveTo(x.toFloat(), start.toFloat()); path.lineTo(x.toFloat(), y.toFloat())
                    start = -1
                }
            }
        }
        if (s < 1f) path.transform(Matrix().apply { setScale(mask.width.toFloat() / w, mask.height.toFloat() / h) })
        return path
    }

    // ------------------------------------------------------------ colour-based

    private fun close(a: Int, b: Int, threshold: Int): Boolean {
        // GIMP compares channels and takes the largest difference.
        val da = abs((a ushr 24) - (b ushr 24))
        if (da > threshold) return false
        // Fully transparent pixels match each other whatever their colour.
        if ((a ushr 24) == 0 && (b ushr 24) == 0) return true
        return abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= threshold &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= threshold &&
            abs((a and 0xFF) - (b and 0xFF)) <= threshold
    }

    /**
     * Fuzzy select: the contiguous region around ([sx], [sy]) of colours within [threshold]
     * of the seed, in [pixels] (w×h, unpremultiplied ARGB). Returns a 0/255 byte mask.
     */
    fun floodRegion(pixels: IntArray, w: Int, h: Int, sx: Int, sy: Int, threshold: Int, diagonal: Boolean = false): ByteArray {
        val mask = ByteArray(w * h)
        if (sx !in 0 until w || sy !in 0 until h) return mask
        val seed = pixels[sy * w + sx]
        val stack = IntArrayStack()
        stack.push(sy * w + sx)
        while (stack.isNotEmpty()) {
            val i = stack.pop()
            val y = i / w
            var x = i % w
            if (mask[i].toInt() != 0) continue
            // Walk left to the start of this span.
            while (x > 0 && mask[y * w + x - 1].toInt() == 0 && close(pixels[y * w + x - 1], seed, threshold)) x--
            var aboveOpen = false
            var belowOpen = false
            var left = x
            while (x < w && mask[y * w + x].toInt() == 0 && close(pixels[y * w + x], seed, threshold)) {
                mask[y * w + x] = -1
                if (y > 0) {
                    val j = (y - 1) * w + x
                    val ok = mask[j].toInt() == 0 && close(pixels[j], seed, threshold)
                    if (ok && !aboveOpen) stack.push(j)
                    aboveOpen = ok
                }
                if (y < h - 1) {
                    val j = (y + 1) * w + x
                    val ok = mask[j].toInt() == 0 && close(pixels[j], seed, threshold)
                    if (ok && !belowOpen) stack.push(j)
                    belowOpen = ok
                }
                x++
            }
            if (diagonal) {
                left -= 1
                for (dy in intArrayOf(-1, 1)) {
                    val yy = y + dy
                    if (yy !in 0 until h) continue
                    for (xx in intArrayOf(left, x)) {
                        if (xx !in 0 until w) continue
                        val j = yy * w + xx
                        if (mask[j].toInt() == 0 && close(pixels[j], seed, threshold)) stack.push(j)
                    }
                }
            }
        }
        return mask
    }

    /** Select by colour: every pixel within [threshold] of [color], anywhere. */
    fun byColor(pixels: IntArray, color: Int, threshold: Int): ByteArray {
        val out = ByteArray(pixels.size)
        for (i in pixels.indices) if (close(pixels[i], color, threshold)) out[i] = -1
        return out
    }

    /** Pixels of a canvas-sized bitmap as unpremultiplied ARGB. */
    fun pixelsOf(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    /** A layer's opacity as a mask in canvas coordinates. */
    fun alphaOf(layer: Layer, w: Int, h: Int): Bitmap {
        val m = emptyMask(w, h)
        Canvas(m).drawBitmap(layer.bitmap.extractAlpha(), layer.x.toFloat(), layer.y.toFloat(),
            Paint().apply { color = Color.BLACK })
        return m
    }

    /** A grey, opaque ARGB copy of a mask, for running GEGL filters over it. */
    fun maskToGrey(mask: Bitmap): Bitmap {
        val b = Bitmaps.blank(mask.width, mask.height, Color.BLACK)
        Canvas(b).drawBitmap(mask, 0f, 0f, Paint().apply { color = Color.WHITE })
        return b
    }

    /** Back from [maskToGrey]: the red channel becomes the mask. */
    fun greyToMask(grey: Bitmap): Bitmap {
        val w = grey.width
        val h = grey.height
        val px = pixelsOf(grey)
        val out = ByteArray(w * h)
        for (i in px.indices) out[i] = ((px[i] shr 16) and 0xFF).toByte()
        return maskFromBytes(out, w, h)
    }
}

/** A growable int stack, so flood fills don't allocate per pixel. */
private class IntArrayStack {
    private var data = IntArray(4096)
    private var size = 0
    fun push(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun pop(): Int = data[--size]
    fun isNotEmpty() = size > 0
}
