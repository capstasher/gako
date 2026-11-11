package io.github.capstasher.gako.editor

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.capstasher.gako.doc.Bitmaps
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Channel(val label: String) { Value("Value"), Red("Red"), Green("Green"), Blue("Blue"), Alpha("Alpha") }

/** GIMP's Levels for one channel. */
data class LevelsChannel(
    val inLow: Float = 0f, val inHigh: Float = 1f, val gamma: Float = 1f,
    val outLow: Float = 0f, val outHigh: Float = 1f,
) {
    fun map(v: Float): Float {
        val n = ((v - inLow) / (inHigh - inLow).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
        val g = n.pow(1f / gamma.coerceIn(0.1f, 10f))
        return outLow + (outHigh - outLow) * g
    }
}

/** Colours > Curves or Levels being tuned on one layer, previewed on [proxy]. */
class CurvesSession(val layerId: Long, val proxy: Bitmap, val levels: Boolean, val histogram: Histogram) {
    var channel by mutableStateOf(Channel.Value)
    var points by mutableStateOf(Channel.entries.associateWith { listOf(0f to 0f, 1f to 1f) })
    var levelValues by mutableStateOf(Channel.entries.associateWith { LevelsChannel() })

    fun lut(ch: Channel): IntArray =
        if (levels) {
            val l = levelValues[ch]!!
            IntArray(256) { (l.map(it / 255f) * 255f).roundToInt().coerceIn(0, 255) }
        } else Curves.lut(points[ch]!!)

    /** Red, green, blue and alpha tables with the Value curve applied after each colour's own. */
    fun luts(): Array<IntArray> {
        val v = lut(Channel.Value)
        val r = lut(Channel.Red)
        val g = lut(Channel.Green)
        val b = lut(Channel.Blue)
        return arrayOf(IntArray(256) { v[r[it]] }, IntArray(256) { v[g[it]] }, IntArray(256) { v[b[it]] }, lut(Channel.Alpha))
    }

    fun reset() {
        if (levels) levelValues = levelValues + (channel to LevelsChannel())
        else points = points + (channel to listOf(0f to 0f, 1f to 1f))
    }

    /** Levels > Auto: stretch each colour channel to its 0.5% / 99.5% points. */
    fun autoLevels() {
        val out = levelValues.toMutableMap()
        for ((ch, hist) in listOf(Channel.Red to histogram.red, Channel.Green to histogram.green, Channel.Blue to histogram.blue)) {
            val total = hist.sum().coerceAtLeast(1)
            var acc = 0; var lo = 0
            while (lo < 255 && acc + hist[lo] < total * 0.005) { acc += hist[lo]; lo++ }
            acc = 0; var hi = 255
            while (hi > lo && acc + hist[hi] < total * 0.005) { acc += hist[hi]; hi-- }
            out[ch] = LevelsChannel(inLow = lo / 255f, inHigh = maxOf(hi, lo + 1) / 255f)
        }
        out[Channel.Value] = LevelsChannel()
        levelValues = out
    }
}

class Histogram(val value: IntArray, val red: IntArray, val green: IntArray, val blue: IntArray, val alpha: IntArray) {
    fun of(ch: Channel) = when (ch) {
        Channel.Value -> value; Channel.Red -> red; Channel.Green -> green; Channel.Blue -> blue; Channel.Alpha -> alpha
    }

    companion object {
        fun of(b: Bitmap): Histogram {
            val v = IntArray(256); val r = IntArray(256); val g = IntArray(256); val bl = IntArray(256); val a = IntArray(256)
            val row = IntArray(b.width)
            for (y in 0 until b.height) {
                b.getPixels(row, 0, b.width, 0, y, b.width, 1)
                for (p in row) {
                    val al = p ushr 24
                    a[al]++
                    if (al == 0) continue
                    val rr = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val bb = p and 0xFF
                    r[rr]++; g[gg]++; bl[bb]++
                    v[maxOf(rr, gg, bb)]++
                }
            }
            return Histogram(v, r, g, bl, a)
        }
    }
}

object Curves {
    /** A smooth curve through the points that never overshoots (monotone cubic, Fritsch–Carlson). */
    fun lut(points: List<Pair<Float, Float>>): IntArray {
        val p = points.sortedBy { it.first }
        val n = p.size
        if (n < 2) return IntArray(256) { it }
        val xs = FloatArray(n) { p[it].first }
        val ys = FloatArray(n) { p[it].second }
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]).coerceAtLeast(1e-5f) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (abs(d[i]) < 1e-6f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
            }
        }
        return IntArray(256) { i ->
            val x = i / 255f
            val y = when {
                x <= xs[0] -> ys[0]
                x >= xs[n - 1] -> ys[n - 1]
                else -> {
                    var k = 0
                    while (k < n - 2 && x > xs[k + 1]) k++
                    val h = xs[k + 1] - xs[k]
                    val t = (x - xs[k]) / h
                    val t2 = t * t; val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] +
                        (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
                }
            }
            (y * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    /** A copy of [src] with the tables applied (to unpremultiplied colour). */
    fun apply(src: Bitmap, luts: Array<IntArray>): Bitmap {
        val (lr, lg, lb, la) = luts
        val out = Bitmaps.blank(src.width, src.height)
        val w = src.width
        val rows = maxOf(1, 262_144 / w)
        val buf = IntArray(w * rows)
        var y = 0
        while (y < src.height) {
            val h = minOf(rows, src.height - y)
            src.getPixels(buf, 0, w, 0, y, w, h)
            for (i in 0 until w * h) {
                val p = buf[i]
                buf[i] = (la[p ushr 24] shl 24) or (lr[(p shr 16) and 0xFF] shl 16) or (lg[(p shr 8) and 0xFF] shl 8) or lb[p and 0xFF]
            }
            out.setPixels(buf, 0, w, 0, y, w, h)
            y += h
        }
        return out
    }
}
