package io.github.capstasher.gako.tools

import android.graphics.Path
import android.graphics.PointF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.hypot

/** One anchor of a Bézier path, with its two handles (equal to the anchor for a corner). */
data class PathPoint(
    val x: Float, val y: Float,
    val inX: Float = x, val inY: Float = y,
    val outX: Float = x, val outY: Float = y,
) {
    val smooth get() = inX != x || inY != y || outX != x || outY != y

    fun moved(dx: Float, dy: Float) = PathPoint(x + dx, y + dy, inX + dx, inY + dy, outX + dx, outY + dy)

    /** Handles mirrored around the anchor, the out handle at ([hx], [hy]). */
    fun withHandles(hx: Float, hy: Float) = copy(outX = hx, outY = hy, inX = 2 * x - hx, inY = 2 * y - hy)
}

/**
 * GIMP's Paths tool, for fingers: tap to add a corner, drag from empty space to add a smooth point,
 * drag points or handles to reshape, tap the first point to close. Coordinates are canvas pixels.
 */
class PathEditor {
    var points by mutableStateOf<List<PathPoint>>(emptyList()); private set
    var closed by mutableStateOf(false); private set

    private var history = ArrayList<Pair<List<PathPoint>, Boolean>>()
    private var drag: Drag? = null
    private var down = PointF()
    private var start: List<PathPoint> = emptyList()

    private sealed interface Drag {
        data class Anchor(val i: Int) : Drag
        data class HandleOut(val i: Int) : Drag
        data class HandleIn(val i: Int) : Drag
        data class New(val i: Int) : Drag
    }

    val isEmpty get() = points.isEmpty()

    fun toPath(): Path {
        val p = Path()
        val pts = points
        if (pts.isEmpty()) return p
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            p.cubicTo(a.outX, a.outY, b.inX, b.inY, b.x, b.y)
        }
        if (closed && pts.size > 1) {
            val a = pts.last(); val b = pts.first()
            p.cubicTo(a.outX, a.outY, b.inX, b.inY, b.x, b.y)
            p.close()
        }
        return p
    }

    private fun remember() {
        history.add(points to closed)
        if (history.size > 200) history.removeAt(0)
    }

    private fun hit(x: Float, y: Float, tol: Float): Drag? {
        val pts = points
        // Handles first (they sit on top), then anchors.
        for (i in pts.indices.reversed()) {
            val p = pts[i]
            if (p.smooth) {
                if (hypot(x - p.outX, y - p.outY) < tol) return Drag.HandleOut(i)
                if (hypot(x - p.inX, y - p.inY) < tol) return Drag.HandleIn(i)
            }
        }
        for (i in pts.indices.reversed()) if (hypot(x - pts[i].x, y - pts[i].y) < tol) return Drag.Anchor(i)
        return null
    }

    fun down(x: Float, y: Float, tol: Float) {
        down = PointF(x, y)
        start = points
        val h = hit(x, y, tol)
        if (h == null && closed) { drag = null; return }   // a closed path only reshapes
        remember()
        drag = h ?: run {
            points = points + PathPoint(x, y)
            start = points
            Drag.New(points.lastIndex)
        }
    }

    fun move(x: Float, y: Float) {
        val dx = x - down.x
        val dy = y - down.y
        val list = start.toMutableList()
        when (val d = drag ?: return) {
            is Drag.Anchor -> list[d.i] = start[d.i].moved(dx, dy)
            is Drag.HandleOut -> list[d.i] = start[d.i].withHandles(start[d.i].outX + dx, start[d.i].outY + dy)
            is Drag.HandleIn -> {
                val p = start[d.i]
                val nx = p.inX + dx; val ny = p.inY + dy
                list[d.i] = p.copy(inX = nx, inY = ny, outX = 2 * p.x - nx, outY = 2 * p.y - ny)
            }
            is Drag.New -> {
                if (list.size <= d.i) list += PathPoint(down.x, down.y)
                list[d.i] = PathPoint(down.x, down.y).withHandles(x, y)
            }
        }
        points = list
    }

    fun up() {
        drag = null
    }

    fun cancel() {
        drag = null
        history.removeLastOrNull()?.let { (p, c) -> points = p; closed = c }
    }

    /** A tap: close the path on its first point, otherwise add a corner. */
    fun tap(x: Float, y: Float, tol: Float) {
        val h = hit(x, y, tol)
        if (h is Drag.Anchor && h.i == 0 && !closed && points.size >= 3) {
            remember()
            closed = true
            return
        }
        if (h != null || closed) return
        remember()
        points = points + PathPoint(x, y)
    }

    fun undoPoint() {
        history.removeLastOrNull()?.let { (p, c) -> points = p; closed = c }
    }

    fun toggleClosed() {
        if (points.size < 3) return
        remember()
        closed = !closed
    }

    fun toVector(name: String = "Path"): io.github.capstasher.gako.doc.VectorPath? {
        if (points.size < 2) return null
        val f = FloatArray(points.size * 6)
        points.forEachIndexed { i, p ->
            f[i * 6] = p.inX; f[i * 6 + 1] = p.inY; f[i * 6 + 2] = p.x; f[i * 6 + 3] = p.y; f[i * 6 + 4] = p.outX; f[i * 6 + 5] = p.outY
        }
        return io.github.capstasher.gako.doc.VectorPath(name, f, closed)
    }

    /** Replaces the path with one from a file (or none). */
    fun load(v: io.github.capstasher.gako.doc.VectorPath?) {
        history.clear()
        drag = null
        if (v == null) { points = emptyList(); closed = false; return }
        points = List(v.anchors) { i ->
            val o = i * 6
            PathPoint(v.points[o + 2], v.points[o + 3], v.points[o], v.points[o + 1], v.points[o + 4], v.points[o + 5])
        }
        closed = v.closed
    }

    fun clear() {
        if (points.isEmpty()) return
        remember()
        points = emptyList()
        closed = false
    }
}
