package io.github.capstasher.gako.tools

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.doc.Step
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What a Warp stroke does to the pixels under the brush (GIMP's warp behaviours). */
enum class WarpMode(val label: String) {
    Move("Move"), Grow("Grow"), Shrink("Shrink"), SwirlCw("Swirl ↻"), SwirlCcw("Swirl ↺"), Erase("Erase"), Smooth("Smooth"),
}

/** What the canvas needs to show a layer that's changing under the finger without re-uploading all of it. */
interface LiveView {
    val layerId: Long
    /** The layer as it was when the edit began (uploaded to the GPU once). */
    val viewBase: Bitmap
    /** Up-to-date copies of the tiles that have changed since, on top of [viewBase]. */
    val viewTileMap: Map<Int, Bitmap>
    fun viewTileRect(i: Int): Rect
}

/**
 * GIMP's Warp Transform: a mesh over the layer whose points the brush pushes around, the layer
 * redrawn from its original through the mesh. The mesh lasts across strokes (so Erase can bring the
 * original back) until something else changes the layer; each stroke is one undo step. Points on
 * the layer's edges only slide along them, so the edges never tear open.
 */
class Warp(override val layerId: Long, val bmp: Bitmap) : LiveView {
    private val w = bmp.width
    private val h = bmp.height
    /** The pixels the mesh maps from. */
    val original: Bitmap = bmp.copy(Bitmap.Config.ARGB_8888, false)
    /** Grid spacing: about 200 000 points at most, never finer than 4 px. */
    private val g = max(4f, ceil(sqrt(w.toFloat() * h / 200_000f)))
    private val nx = ceil(w / g).toInt() + 1
    private val ny = ceil(h / g).toInt() + 1
    private val pos = FloatArray(nx * ny * 2).also { p ->
        for (j in 0 until ny) for (i in 0 until nx) { val k = (j * nx + i) * 2; p[k] = gridX(i); p[k + 1] = gridY(j) }
    }
    /** How far any point has moved from its place, so searches know how far to look. */
    private var maxDisp = 0f

    private var strokeStart: FloatArray? = null
    private var strokeMaxDisp = 0f
    private val strokeDirty = Rect()
    /** Everything the session has changed, so the screen's tiles can be rebuilt for the next stroke. */
    private val sessionDirty = Rect()

    private fun gridX(i: Int) = min(i * g, w.toFloat())
    private fun gridY(j: Int) = min(j * g, h.toFloat())

    private val meshPaint = Paint().apply {
        shader = BitmapShader(original, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        isFilterBitmap = true
        isAntiAlias = false
    }
    private val clearPaint = Paint().apply { blendMode = BlendMode.CLEAR }
    private val canvas = Canvas(bmp)
    private var verts = FloatArray(12 * 1024)
    private var texs = FloatArray(12 * 1024)

    val strokeActive get() = strokeStart != null

    fun beginStroke() {
        strokeStart = pos.copyOf()
        strokeMaxDisp = maxDisp
        strokeDirty.setEmpty()
        // The screen shows the original plus tiles of what differs from it.
        if (!sessionDirty.isEmpty) refreshView(sessionDirty)
    }

    /**
     * The tiles only matter while a stroke is shown: drop them in between (the layer has the pixels).
     * Not recycled: a frame on its way to the screen may still use them.
     */
    private fun dropTiles() = viewTiles.clear()

    /**
     * One dab of the brush at ([cx], [cy]) (layer pixels), [dx]/[dy] being how far the brush moved
     * (for [WarpMode.Move]). Redraws what changed.
     */
    fun dab(mode: WarpMode, cx: Float, cy: Float, dx: Float, dy: Float, radius: Float, strength: Float, hardness: Float) {
        if (radius < 0.5f) return
        val reach = radius + maxDisp + g
        val i0 = max(0, floor((cx - reach) / g).toInt()); val i1 = min(nx - 1, ceil((cx + reach) / g).toInt())
        val j0 = max(0, floor((cy - reach) / g).toInt()); val j1 = min(ny - 1, ceil((cy + reach) / g).toInt())
        if (i0 > i1 || j0 > j1) return
        // Softer than a paint brush at the same hardness: a hard-edged warp tears.
        val hard = (hardness * 0.6f).coerceIn(0f, 0.6f)
        // Work out every new position first (Smooth reads its neighbours' old ones).
        val moved = ArrayList<Int>()
        val newPos = ArrayList<Float>()
        for (j in j0..j1) for (i in i0..i1) {
            val k = (j * nx + i) * 2
            val px = pos[k]; val py = pos[k + 1]
            val dist = hypot(px - cx, py - cy)
            if (dist >= radius) continue
            val t = dist / radius
            val f = strength * if (t <= hard) 1f else { val s = (t - hard) / (1f - hard); 1f - s * s * (3f - 2f * s) }
            if (f <= 0.0005f) continue
            var qx: Float; var qy: Float
            when (mode) {
                WarpMode.Move -> { qx = px + dx * f; qy = py + dy * f }
                WarpMode.Grow -> { qx = px + (px - cx) * 0.04f * f; qy = py + (py - cy) * 0.04f * f }
                WarpMode.Shrink -> { qx = px - (px - cx) * 0.04f * f; qy = py - (py - cy) * 0.04f * f }
                WarpMode.SwirlCw, WarpMode.SwirlCcw -> {
                    val a = (if (mode == WarpMode.SwirlCw) 0.05f else -0.05f) * f
                    val c = cos(a); val s = sin(a)
                    val ox = px - cx; val oy = py - cy
                    qx = cx + ox * c - oy * s; qy = cy + ox * s + oy * c
                }
                WarpMode.Erase -> { qx = px + (gridX(i) - px) * 0.2f * f; qy = py + (gridY(j) - py) * 0.2f * f }
                WarpMode.Smooth -> {
                    var sx = 0f; var sy = 0f; var n = 0
                    if (i > 0) { sx += pos[k - 2]; sy += pos[k - 1]; n++ }
                    if (i < nx - 1) { sx += pos[k + 2]; sy += pos[k + 3]; n++ }
                    if (j > 0) { sx += pos[k - nx * 2]; sy += pos[k - nx * 2 + 1]; n++ }
                    if (j < ny - 1) { sx += pos[k + nx * 2]; sy += pos[k + nx * 2 + 1]; n++ }
                    qx = px + (sx / n - px) * 0.5f * f; qy = py + (sy / n - py) * 0.5f * f
                }
            }
            // Points on the layer's edges stay on them.
            if (i == 0 || i == nx - 1) qx = gridX(i) else qx = qx.coerceIn(0f, w.toFloat())
            if (j == 0 || j == ny - 1) qy = gridY(j) else qy = qy.coerceIn(0f, h.toFloat())
            if (qx == px && qy == py) continue
            moved += j * nx + i
            newPos += qx; newPos += qy
        }
        if (moved.isEmpty()) return
        val dirty = BoxF()
        for ((n, v) in moved.withIndex()) {
            val k = v * 2
            dirty.add(pos[k], pos[k + 1])
            pos[k] = newPos[n * 2]; pos[k + 1] = newPos[n * 2 + 1]
            maxDisp = max(maxDisp, hypot(pos[k] - gridX(v % nx), pos[k + 1] - gridY(v / nx)))
        }
        // The cells around each moved point change: take in the corners of all of them.
        for (v in moved) {
            val i = v % nx; val j = v / nx
            for (b in max(0, j - 1)..min(ny - 1, j + 1)) for (a in max(0, i - 1)..min(nx - 1, i + 1)) {
                val k = (b * nx + a) * 2
                dirty.add(pos[k], pos[k + 1])
            }
        }
        val r = Rect(floor(dirty.l).toInt() - 1, floor(dirty.t).toInt() - 1, ceil(dirty.r).toInt() + 1, ceil(dirty.b).toInt() + 1)
        if (!r.intersect(0, 0, w, h)) return
        strokeDirty.union(r)
        sessionDirty.union(r)
        draw(canvas, r, pos, maxDisp, 0, 0)
        refreshView(r)
    }

    /** Ends the stroke: its undo step (the area it changed, before and after), or null if it changed nothing. */
    fun endStroke(label: String): Step.Patch? {
        val start = strokeStart ?: return null
        strokeStart = null
        dropTiles()
        val r = Rect(strokeDirty)
        if (r.isEmpty) return null
        val before = Bitmaps.blank(r.width(), r.height())
        draw(Canvas(before), Rect(r), start, max(strokeMaxDisp, maxDisp), r.left, r.top)
        return Step.Patch(label, layerId, r, before, Bitmaps.crop(bmp, r))
    }

    /** Takes the stroke back (a second finger arrived). */
    fun cancelStroke() {
        val start = strokeStart ?: return
        strokeStart = null
        start.copyInto(pos)
        dropTiles()
        val r = Rect(strokeDirty)
        if (r.isEmpty) return
        draw(canvas, r, pos, max(strokeMaxDisp, maxDisp), 0, 0)
    }

    /**
     * Redraws [clip] (layer pixels) from the original through [mesh], into [c] whose origin is at
     * ([ox], [oy]) in the layer. [disp]: how far mesh points may be from their places.
     */
    private fun draw(c: Canvas, clip: Rect, mesh: FloatArray, disp: Float, ox: Int, oy: Int) {
        val reach = disp + g
        val ci0 = max(0, floor((clip.left - reach) / g).toInt()); val ci1 = min(nx - 2, floor((clip.right + reach) / g).toInt())
        val cj0 = max(0, floor((clip.top - reach) / g).toInt()); val cj1 = min(ny - 2, floor((clip.bottom + reach) / g).toInt())
        var n = 0
        fun put(i: Int, j: Int) {
            val k = (j * nx + i) * 2
            verts[n] = mesh[k] - ox; verts[n + 1] = mesh[k + 1] - oy
            texs[n] = gridX(i); texs[n + 1] = gridY(j)
            n += 2
        }
        val cl = clip.left.toFloat(); val cr = clip.right.toFloat(); val ct = clip.top.toFloat(); val cb = clip.bottom.toFloat()
        for (j in cj0..cj1) for (i in ci0..ci1) {
            val a = (j * nx + i) * 2; val b = a + 2; val c2 = a + nx * 2; val d = c2 + 2
            val minX = min(min(mesh[a], mesh[b]), min(mesh[c2], mesh[d])); val maxX = max(max(mesh[a], mesh[b]), max(mesh[c2], mesh[d]))
            val minY = min(min(mesh[a + 1], mesh[b + 1]), min(mesh[c2 + 1], mesh[d + 1])); val maxY = max(max(mesh[a + 1], mesh[b + 1]), max(mesh[c2 + 1], mesh[d + 1]))
            if (maxX < cl || minX > cr || maxY < ct || minY > cb) continue
            if (n + 12 > verts.size) { verts = verts.copyOf(verts.size * 2); texs = texs.copyOf(texs.size * 2) }
            put(i, j); put(i + 1, j); put(i + 1, j + 1)
            put(i, j); put(i + 1, j + 1); put(i, j + 1)
        }
        c.save()
        val local = Rect(clip).apply { offset(-ox, -oy) }
        c.clipRect(local)
        c.drawRect(local, clearPaint)
        if (n > 0) c.drawVertices(Canvas.VertexMode.TRIANGLES, n, verts, 0, texs, 0, null, 0, null, 0, 0, meshPaint)
        c.restore()
    }

    private class BoxF {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        fun add(x: Float, y: Float) { l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y) }
    }

    // ---- The screen: the original (uploaded once) plus copies of the 256 px tiles that changed.
    private val viewTile = 256
    private val viewTilesX = (w + viewTile - 1) / viewTile
    private val viewTiles = HashMap<Int, Bitmap>()
    private val copyPaint = Paint().apply { blendMode = BlendMode.SRC }

    override val viewBase: Bitmap get() = original
    override val viewTileMap: Map<Int, Bitmap> get() = viewTiles

    override fun viewTileRect(i: Int): Rect {
        val tx = i % viewTilesX
        val ty = i / viewTilesX
        return Rect(tx * viewTile, ty * viewTile, min(w, (tx + 1) * viewTile), min(h, (ty + 1) * viewTile))
    }

    private fun refreshView(dirty: Rect) {
        val d = Rect(dirty)
        if (!d.intersect(0, 0, w, h)) return
        for (ty in d.top / viewTile..(d.bottom - 1) / viewTile) for (tx in d.left / viewTile..(d.right - 1) / viewTile) {
            val i = ty * viewTilesX + tx
            val t = viewTileRect(i)
            val fresh = !viewTiles.containsKey(i)
            val tile = viewTiles.getOrPut(i) { Bitmap.createBitmap(t.width(), t.height(), Bitmap.Config.ARGB_8888) }
            val src = if (fresh) Rect(t) else Rect(t).apply { intersect(d) }
            val dst = Rect(src).apply { offset(-t.left, -t.top) }
            Canvas(tile).drawBitmap(bmp, src, dst, copyPaint)
        }
    }
}
