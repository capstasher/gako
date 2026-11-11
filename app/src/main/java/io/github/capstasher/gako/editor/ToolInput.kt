package io.github.capstasher.gako.editor

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.doc.SelectMode
import io.github.capstasher.gako.doc.Selections
import io.github.capstasher.gako.engine.Engine
import io.github.capstasher.gako.tools.FillArea
import io.github.capstasher.gako.tools.GradientColors
import io.github.capstasher.gako.tools.GradientShape
import io.github.capstasher.gako.tools.PaintStroke
import io.github.capstasher.gako.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Turns finger gestures on the canvas into tool actions. Coordinates are canvas pixels;
 * [tol] is how far, in canvas pixels, a finger may miss a handle.
 */
class ToolInput(private val vm: EditorViewModel) {

    /** Unified Transform's state for one layer: scale and rotation about its centre, then a shift. */
    data class LayerTransform(
        val layerId: Long,
        val bitmap: Bitmap,
        val x: Int, val y: Int,
        val sx: Float = 1f, val sy: Float = 1f,
        val angle: Float = 0f,     // degrees
        val tx: Float = 0f, val ty: Float = 0f,
        /** Perspective mode: where the four corners go (canvas x,y × 4, clockwise from top-left). */
        val quad: FloatArray? = null,
    ) {
        val cx get() = x + bitmap.width / 2f
        val cy get() = y + bitmap.height / 2f
        val perspective get() = quad != null
        val changed get() = quad != null || sx != 1f || sy != 1f || angle != 0f || tx != 0f || ty != 0f

        private fun source() = floatArrayOf(0f, 0f, bitmap.width.toFloat(), 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), 0f, bitmap.height.toFloat())

        /** Layer pixel space to canvas. */
        fun matrix() = Matrix().apply {
            val q = quad
            if (q != null) {
                setPolyToPoly(source(), 0, q, 0, 4)
            } else {
                postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
                postScale(sx, sy)
                postRotate(angle)
                postTranslate(cx + tx, cy + ty)
            }
        }

        fun cornerArray(): FloatArray = source().also { matrix().mapPoints(it) }

        fun corners(): List<PointF> {
            val pts = cornerArray()
            return List(4) { PointF(pts[it * 2], pts[it * 2 + 1]) }
        }

        /** The rotate knob: above the middle of the (transformed) top edge. */
        fun knob(offset: Float): PointF {
            val c = corners()
            val mx = (c[0].x + c[1].x) / 2
            val my = (c[0].y + c[1].y) / 2
            val a = Math.toRadians(angle.toDouble())
            val up = if (sy < 0) -1f else 1f
            return PointF(mx + (sin(a) * offset * up).toFloat(), my - (cos(a) * offset * up).toFloat())
        }
    }

    /** The Paths tool's path; it stays while other tools are used, until cleared or a new image. */
    val path = io.github.capstasher.gako.tools.PathEditor()

    /** The Selection Brush's stroke so far (canvas-sized ALPHA_8). */
    var selStroke by mutableStateOf<Bitmap?>(null); private set
    var selStrokeRevision by mutableIntStateOf(0); private set
    /** The finger's path while the Selection Brush paints, drawn as a thick line over the image (cheaper than the mask). */
    var selPath by mutableStateOf<Path?>(null); private set
    private var selCanvas: Canvas? = null
    private var selLast: PointF? = null

    var dragRect by mutableStateOf<RectF?>(null); private set
    /** Foreground Select: the rough outline around the object, then strokes marking foreground (or background). */
    var fgOutline by mutableStateOf<Path?>(null); private set
    val fgStrokes = androidx.compose.runtime.mutableStateListOf<FgStroke>()
    var fgRevision by mutableIntStateOf(0); private set
    var fgMarkBackground by mutableStateOf(false)
    private var fgCurrent: FgStroke? = null

    class FgStroke(val path: Path, val width: Float, val foreground: Boolean)

    /** The Measure tool's line (canvas pixels); it stays until another tool or a new line. */
    var measure by mutableStateOf<Pair<PointF, PointF>?>(null); private set
    private var measureEnd = 1
    var lasso by mutableStateOf<Path?>(null); private set
    var lassoRevision by mutableIntStateOf(0); private set
    var gradient by mutableStateOf<Pair<PointF, PointF>?>(null); private set
    var moveDelta by mutableStateOf<PointF?>(null); private set
    var cloneSource by mutableStateOf<PointF?>(null); private set
    var settingCloneSource by mutableStateOf(false)

    private var cropState by mutableStateOf<Pair<Pair<Int, Int>, RectF>?>(null)
    private var transformState by mutableStateOf<LayerTransform?>(null)

    private var stroke: PaintStroke? = null
    /** Warp Transform's mesh; it lasts across strokes until something else changes the layer. */
    private var warp: io.github.capstasher.gako.tools.Warp? = null
    private var warpRevision = -1
    private var warpOnMask = false
    private var warpLast: PointF? = null
    /** Android is short of memory: let go of an idle warp mesh (and its copy of the layer). */
    fun trimMemory() {
        if (warp?.strokeActive != true) warp = null
    }

    /** Where the Warp brush is, while a warp stroke is under way (canvas pixels). */
    var warpAt by mutableStateOf<PointF?>(null); private set
    private var strokeLabel = ""
    private var smoothed: PointF? = null
    private var lastRaw: PointF? = null
    private var strokeOnMask = false
    private var airJob: Job? = null
    private var lastPressure = 1f
    private var down = PointF()
    private var cloneOffset: PointF? = null
    private var mode = Drag.None
    private var cropAtDown = RectF()
    private var transformAtDown: LayerTransform? = null
    private var lassoPoints = ArrayList<PointF>()

    private enum class Drag { None, Move, Corner0, Corner1, Corner2, Corner3, Left, Top, Right, Bottom, New, Scale, Rotate }

    private val doc get() = vm.doc
    /** A tool used just for this gesture: the pen's eraser end erases. */
    var toolOverride: Tool? = null
    private val tool get() = toolOverride ?: vm.tool
    private val options get() = vm.options

    // ------------------------------------------------------------ paths

    /**
     * The path being edited, all its strokes, as one Android path ([closed]: every stroke closed,
     * holes where strokes overlap an odd number of times — how GIMP fills a path). Null if empty.
     */
    private fun activeGeometry(closed: Boolean, minAnchors: Int): Path? {
        val strokes = vm.activeStrokes()
        if (strokes.sumOf { it.anchors } < minAnchors) return null
        return Path().apply {
            fillType = Path.FillType.EVEN_ODD
            strokes.forEach { it.addTo(this, close = closed || it.closed) }
        }
    }

    /** Select > From Path, combined with the selection in the current mode. */
    fun pathToSelection() {
        val d = doc ?: return
        val p = activeGeometry(true, 2) ?: run { vm.say("Draw a path first"); return }
        select(d, "Selection from Path") { Selections.fromPath(d.width, d.height, p) }
    }

    /** Edit > Stroke Path: the brush size and FG colour along the path, on the active layer. */
    fun strokePath() {
        val p = activeGeometry(false, 2) ?: run { vm.say("Draw a path first"); return }
        val width = options.size
        val color = vm.paintColor(vm.fg) or (0xFF shl 24)
        val opacity = options.opacity
        vm.editActiveLayer("Stroke Path") { l, sel -> paintPath(l, sel, p, color, opacity, width) }
    }

    /** Fills the inside of the path with the FG colour. */
    fun fillPath() {
        val p = activeGeometry(true, 3) ?: run { vm.say("A fill needs at least three points"); return }
        val color = vm.paintColor(vm.fg) or (0xFF shl 24)
        vm.editActiveLayer("Fill Path") { l, sel -> paintPath(l, sel, p, color, options.opacity, null) }
    }

    private fun paintPath(l: Layer, sel: Bitmap?, p: Path, color: Int, opacity: Float, strokeWidth: Float?) {
        val c = Canvas(l.bitmap)
        val save = c.saveLayer(RectF(0f, 0f, l.width.toFloat(), l.height.toFloat()), Paint().apply {
            alpha = (opacity * 255).roundToInt()
            blendMode = if (l.lockAlpha) BlendMode.SRC_ATOP else BlendMode.SRC_OVER
        })
        c.translate(-l.x.toFloat(), -l.y.toFloat())
        c.drawPath(p, Paint().apply {
            this.color = color
            isAntiAlias = true
            if (strokeWidth != null) {
                style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        })
        c.translate(l.x.toFloat(), l.y.toFloat())
        sel?.let { Bitmaps.maskIn(c, it) }
        c.restoreToCount(save)
    }

    // ------------------------------------------------------------ crop & transform state

    /** The crop rectangle for [d]; the whole canvas until the user moves it. */
    fun cropRect(d: DocState): RectF {
        val s = cropState
        return if (s != null && s.first == (d.width to d.height)) s.second else RectF(0f, 0f, d.width.toFloat(), d.height.toFloat())
    }

    fun startCrop() {
        cropState = null
    }

    /** The transform in progress on the active layer (identity until dragged). */
    fun transformFor(d: DocState): LayerTransform? {
        val a = d.active?.takeIf { !it.group } ?: return null
        val t = transformState
        return if (t != null && t.layerId == a.id && t.bitmap === a.bitmap && t.x == a.x && t.y == a.y) t
               else LayerTransform(a.id, a.bitmap, a.x, a.y)
    }

    fun startTransform() {
        transformState = null
    }

    /** A brush stroke is under way. */
    val stroking: Boolean get() = stroke != null || warp?.strokeActive == true

    /** The stroke in progress, for the canvas to draw from its tiles (null otherwise). */
    val liveStroke: io.github.capstasher.gako.tools.LiveView?
        get() = stroke ?: warp?.takeIf { it.strokeActive }
    val liveStrokeOnMask: Boolean get() = if (stroke != null) strokeOnMask else warpOnMask

    fun hasPendingEdit() = (tool == Tool.Transform && transformState?.changed == true) ||
        (tool == Tool.Crop && cropState != null)

    /** Drops anything half-done. */
    fun reset() {
        stroke?.let { it.cancel(); vm.pixelsChanged() }
        stroke = null
        warp?.let { if (it.strokeActive) { it.cancelStroke(); vm.pixelsChanged() } }
        warp = null
        warpAt = null
        measure = null
        fgOutline = null
        fgStrokes.clear()
        fgCurrent = null
        airJob?.cancel()
        dragRect = null
        lasso = null
        gradient = null
        moveDelta = null
        selStroke = null
        selPath = null
        selCanvas = null
        cropState = null
        transformState = null
        mode = Drag.None
    }

    fun syncAfterHistory() {
        cropState = null
        transformState = null
    }

    // ------------------------------------------------------------ gestures

    fun down(x: Float, y: Float, pressure: Float, tol: Float) {
        val d = doc ?: return
        if (vm.busy != null) return
        down = PointF(x, y)
        lastPressure = pressure
        when (tool) {
            Tool.Paintbrush, Tool.Pencil, Tool.Airbrush, Tool.Eraser, Tool.DodgeBurn, Tool.Clone, Tool.Heal, Tool.Smudge, Tool.Blur, Tool.FilterBrush -> beginStroke(d, x, y, pressure)
            Tool.RectSelect, Tool.EllipseSelect -> dragRect = RectF(x, y, x, y)
            Tool.FreeSelect -> {
                lassoPoints = arrayListOf(PointF(x, y))
                lasso = Path().apply { moveTo(x, y) }
                lassoRevision++
            }
            Tool.Gradient -> gradient = PointF(x, y) to PointF(x, y)
            Tool.Move -> moveDelta = PointF(0f, 0f)
            Tool.ColorPicker -> pick(d, x, y)
            Tool.Crop -> {
                cropAtDown = RectF(cropRect(d))
                mode = hitCrop(cropAtDown, x, y, tol)
            }
            Tool.Transform -> {
                val t = transformFor(d) ?: return
                transformAtDown = t
                mode = hitTransform(t, x, y, tol)
            }
            Tool.Paths -> {
                // A point of another path or stroke: switch to it (then the drag reshapes it).
                if (!path.hits(x, y, tol)) vm.otherPathAt(x, y, tol)?.let { (pi, si) -> vm.editPath(pi, si) }
                path.down(x, y, tol)
            }
            Tool.Warp -> beginWarp(d, x, y)
            Tool.ForegroundSelect -> if (fgOutline == null) {
                lassoPoints = arrayListOf(PointF(x, y))
                lasso = Path().apply { moveTo(x, y) }
                lassoRevision++
            } else {
                fgCurrent = FgStroke(Path().apply { moveTo(x, y); lineTo(x + 0.01f, y) }, options.size.coerceAtLeast(1f), !fgMarkBackground)
                    .also { fgStrokes.add(it) }
                fgRevision++
            }
            Tool.Measure -> {
                val m = measure
                measureEnd = when {
                    m != null && hypot(x - m.first.x, y - m.first.y) < tol -> 0
                    m != null && hypot(x - m.second.x, y - m.second.y) < tol -> 1
                    else -> { measure = PointF(x, y) to PointF(x, y); 1 }
                }
            }
            Tool.SelectBrush -> {
                val m = try { Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ALPHA_8) } catch (e: OutOfMemoryError) { return }
                selStroke = m
                selCanvas = Canvas(m)
                selLast = null
                selPath = Path().apply { moveTo(x, y); lineTo(x + 0.01f, y) }
                selDab(x, y)
            }
            else -> Unit
        }
    }

    /**
     * Selection Brush dabs, spaced along the finger's path. [selLast] is the last dab drawn, so slow
     * moves (less than a step per event) still add up to dabs.
     */
    private fun selDab(x: Float, y: Float) {
        val c = selCanvas ?: return
        val r = options.size.coerceAtLeast(1f) / 2f
        val dab = PaintStroke.dabBitmap(r * 2, options.hardness)
        val paint = Paint().apply { isFilterBitmap = true }
        fun stamp(px: Float, py: Float) = c.drawBitmap(dab, null, RectF(px - r, py - r, px + r, py + r), paint)
        val from = selLast
        if (from == null) {
            stamp(x, y)
            selLast = PointF(x, y)
        } else {
            val dist = hypot(x - from.x, y - from.y)
            val step = max(1f, r * 0.25f)
            if (dist < step) return
            val n = (dist / step).toInt()
            for (k in 1..n) stamp(from.x + (x - from.x) * k * step / dist, from.y + (y - from.y) * k * step / dist)
            selLast = PointF(from.x + (x - from.x) * n * step / dist, from.y + (y - from.y) * n * step / dist)
        }
        selStrokeRevision++
    }

    fun move(x: Float, y: Float, pressure: Float) {
        val d = doc ?: return
        lastPressure = pressure
        when (tool) {
            Tool.Paintbrush, Tool.Pencil, Tool.Airbrush, Tool.Eraser, Tool.DodgeBurn, Tool.Clone, Tool.Heal, Tool.Smudge, Tool.Blur, Tool.FilterBrush -> {
                val s = stroke ?: return
                lastRaw = PointF(x, y)
                // Smoothing trails the finger a little, which irons out wobble.
                val k = 1f - options.smoothing.coerceIn(0f, 1f) * 0.9f
                val p = smoothed?.let { PointF(it.x + (x - it.x) * k, it.y + (y - it.y) * k) } ?: PointF(x, y)
                smoothed = p
                try {
                    s.moveTo(p.x, p.y, pressure)
                } catch (e: OutOfMemoryError) {
                    outOfMemory()
                    return
                }
                vm.pixelsChanged()
            }
            Tool.RectSelect, Tool.EllipseSelect -> dragRect = RectF(min(down.x, x), min(down.y, y), max(down.x, x), max(down.y, y))
            Tool.FreeSelect -> {
                lassoPoints.add(PointF(x, y))
                lasso?.lineTo(x, y)
                lassoRevision++
            }
            Tool.Gradient -> gradient = down to PointF(x, y)
            Tool.Move -> moveDelta = PointF(x - down.x, y - down.y)
            Tool.ColorPicker -> pick(d, x, y)
            Tool.Crop -> dragCrop(d, x, y)
            Tool.Transform -> dragTransform(x, y)
            Tool.Paths -> path.move(x, y)
            Tool.Warp -> warpTo(x, y)
            Tool.ForegroundSelect -> if (fgOutline == null) {
                lassoPoints.add(PointF(x, y))
                lasso?.lineTo(x, y)
                lassoRevision++
            } else fgCurrent?.let { it.path.lineTo(x, y); fgRevision++ }
            Tool.Measure -> measure?.let { m -> measure = if (measureEnd == 0) PointF(x, y) to m.second else m.first to PointF(x, y) }
            Tool.SelectBrush -> { selPath?.lineTo(x, y); selDab(x, y); selStrokeRevision++ }
            else -> Unit
        }
    }

    fun up() {
        val d = doc ?: return
        airJob?.cancel()
        when (tool) {
            Tool.Paintbrush, Tool.Pencil, Tool.Airbrush, Tool.Eraser, Tool.DodgeBurn, Tool.Clone, Tool.Heal, Tool.Smudge, Tool.Blur, Tool.FilterBrush -> {
                val s = stroke ?: return
                try {
                    // Catch up with the finger so the stroke ends where it lifted.
                    if (options.smoothing > 0f) lastRaw?.let { s.moveTo(it.x, it.y, lastPressure) }
                    stroke = null
                    s.finish(strokeLabel)?.let { vm.record(if (strokeOnMask) it.forMask() else it) }
                } catch (e: OutOfMemoryError) {
                    stroke = s
                    outOfMemory()
                    return
                }
                vm.pixelsChanged()
            }
            Tool.RectSelect, Tool.EllipseSelect -> {
                val r = dragRect ?: return
                dragRect = null
                if (r.width() < 2 || r.height() < 2) { tapSelect(); return }
                val path = Path().apply {
                    val radius = options.cornerRadius
                    when {
                        tool == Tool.RectSelect && radius > 0f -> addRoundRect(snap(r), radius, radius, Path.Direction.CW)
                        tool == Tool.RectSelect -> addRect(snap(r), Path.Direction.CW)
                        else -> addOval(r, Path.Direction.CW)
                    }
                }
                select(d, if (tool == Tool.RectSelect) "Rectangle Select" else "Ellipse Select") {
                    Selections.fromPath(d.width, d.height, path, antialias = tool == Tool.EllipseSelect || options.cornerRadius > 0f)
                }
            }
            Tool.FreeSelect -> {
                val pts = lassoPoints
                lasso = null
                if (pts.size < 3) { tapSelect(); return }
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                    close()
                }
                select(d, "Free Select") { Selections.fromPath(d.width, d.height, path) }
            }
            Tool.Gradient -> {
                val (a, b) = gradient ?: return
                gradient = null
                if (hypot(b.x - a.x, b.y - a.y) < 1f) return
                applyGradient(a, b)
            }
            Tool.Paths -> path.up()
            Tool.Warp -> endWarp()
            Tool.ForegroundSelect -> if (fgOutline == null) {
                val pts = lassoPoints
                lasso = null
                if (pts.size >= 3) {
                    fgOutline = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        close()
                    }
                    vm.say("Now paint over the object, then tap Select")
                }
            } else fgCurrent = null
            Tool.Measure -> measure?.let { m -> if (hypot(m.second.x - m.first.x, m.second.y - m.first.y) < 1f) measure = null }
            Tool.SelectBrush -> {
                val m = selStroke ?: return
                selStroke = null
                selPath = null
                selCanvas = null
                // Like GIMP's Paint Select, strokes add up (Replace acts as Add; Select › None starts over).
                val mode = if (options.selectMode == SelectMode.Replace) SelectMode.Add else options.selectMode
                select(d, "Selection Brush", mode) { m }
            }
            Tool.Move -> {
                val delta = moveDelta ?: return
                moveDelta = null
                val l = d.active ?: return
                val dx = delta.x.roundToInt()
                val dy = delta.y.roundToInt()
                if (dx == 0 && dy == 0) return
                if (l.group) {
                    // Everything in the group moves together.
                    val byId = io.github.capstasher.gako.doc.Tree.byId(d.layers)
                    vm.change("Move Group", d.copy(layers = d.layers.map {
                        if (!it.group && io.github.capstasher.gako.doc.Tree.isInside(it, l.id, byId)) it.copy(x = it.x + dx, y = it.y + dy) else it
                    }))
                } else vm.change("Move Layer", d.replace(l.copy(x = l.x + dx, y = l.y + dy)))
            }
            else -> Unit
        }
        mode = Drag.None
    }

    /** A second finger arrived or the gesture was taken over: undo whatever this gesture did. */
    fun cancel() {
        airJob?.cancel()
        stroke?.let { it.cancel(); vm.pixelsChanged() }
        stroke = null
        warp?.let { if (it.strokeActive) { it.cancelStroke(); vm.pixelsChanged() } }
        warpAt = null
        dragRect = null
        lasso = null
        gradient = null
        moveDelta = null
        if (tool == Tool.Paths) path.cancel()
        if (tool == Tool.ForegroundSelect) {
            if (fgOutline == null) lasso = null
            fgCurrent?.let { fgStrokes.remove(it) }
            fgCurrent = null
        }
        selStroke = null
        selPath = null
        selCanvas = null
        if (tool == Tool.Crop && mode != Drag.None) doc?.let { cropState = (it.width to it.height) to cropAtDown }
        if (tool == Tool.Transform && mode != Drag.None) transformState = transformAtDown
        mode = Drag.None
    }

    fun tap(x: Float, y: Float, tol: Float) {
        val d = doc ?: return
        if (vm.busy != null) return
        when (tool) {
            Tool.Paintbrush, Tool.Pencil, Tool.Airbrush, Tool.Eraser, Tool.DodgeBurn, Tool.Smudge, Tool.Blur, Tool.FilterBrush -> {
                beginStroke(d, x, y, 1f)
                up()
            }
            Tool.Clone, Tool.Heal -> {
                if (settingCloneSource || cloneSource == null) setCloneSource(x, y)
                else { beginStroke(d, x, y, 1f); up() }
            }
            Tool.RectSelect, Tool.EllipseSelect, Tool.FreeSelect -> tapSelect()
            Tool.SelectBrush -> { down(x, y, 1f, tol); up() }
            Tool.ForegroundSelect -> if (fgOutline != null) { down(x, y, 1f, tol); up() }
            Tool.FuzzySelect, Tool.ColorSelect -> colourSelect(d, x, y)
            Tool.BucketFill -> bucketFill(d, x, y)
            Tool.ColorPicker -> pick(d, x, y)
            Tool.Text -> vm.startText(x.roundToInt(), y.roundToInt())
            Tool.Paths -> {
                val other = if (!path.hits(x, y, tol)) vm.otherPathAt(x, y, tol) else null
                if (other != null) vm.editPath(other.first, other.second)
                else if (!path.tap(x, y, tol)) vm.say("The path is closed: tap its name ▾ for a new path or stroke")
            }
            Tool.Transform -> Unit
            else -> Unit
        }
    }

    // ------------------------------------------------------------ painting

    private fun beginStroke(d: DocState, x: Float, y: Float, pressure: Float) {
        if (vm.refuseGroup(d)) return
        val layer = vm.targetOf(d) ?: return
        if (!layer.visible) { vm.say("“${layer.name}” is hidden"); return }
        if (tool.cloning) {
            if (settingCloneSource || cloneSource == null) { setCloneSource(x, y); return }
            val src = cloneSource!!
            if (cloneOffset == null || !options.cloneAligned) cloneOffset = PointF(src.x - x, src.y - y)
        }
        val reveal = if (tool == Tool.FilterBrush) {
            val fb = vm.filterBrush
            when {
                fb == null -> { vm.say("Open a filter and tap its brush button to load the Filter Brush"); return }
                vm.editingMask -> { vm.say("The filter brush paints on layers, not masks"); return }
                fb.layerId != layer.id || fb.bitmap.width != layer.width || fb.bitmap.height != layer.height -> {
                    vm.say("The filter brush holds ${fb.title} for another layer; open a filter again for this one"); return
                }
                else -> fb.bitmap
            }
        } else null
        // Painting turns a text layer into an ordinary one.
        val target = if (layer.text != null) layer.copy(text = null).also { vm.silently(d.replace(it)) } else layer
        val sel = Compositor.selectionFor(target, d.selection)
        val color = vm.paintColor(if (tool == Tool.Eraser && target.lockAlpha) vm.bg else vm.fg)
        strokeOnMask = vm.editingMask
        stroke = try {
            val sym = options.symmetry
            PaintStroke(tool, target, sel, color, options, cloneOffset?.let { it.x to it.y },
                mirrorX = if (sym.vertical) d.width / 2f else null,
                mirrorY = if (sym.horizontal) d.height / 2f else null,
                revealSource = reveal,
            ).also { it.begin(x, y, pressure) }
        } catch (e: OutOfMemoryError) {
            vm.say("Not enough memory to paint on a layer this big")
            return
        }
        strokeLabel = if (tool == Tool.FilterBrush) vm.filterBrush?.title ?: tool.label else tool.label
        smoothed = PointF(x, y)
        lastRaw = PointF(x, y)
        vm.pixelsChanged()
        if (tool == Tool.Airbrush) {
            airJob = vm.viewModelScope.launch {
                while (isActive) {
                    delay(40)
                    stroke?.dwell(lastPressure) ?: break
                    vm.pixelsChanged()
                }
            }
        }
    }

    // ------------------------------------------------------------ warp

    private fun beginWarp(d: DocState, x: Float, y: Float) {
        if (vm.refuseGroup(d)) return
        val layer = vm.targetOf(d) ?: return
        if (!layer.visible) { vm.say("“${layer.name}” is hidden"); return }
        // Warping turns a text layer into an ordinary one.
        val target = if (layer.text != null) layer.copy(text = null).also { vm.silently(d.replace(it)) } else layer
        val onMask = vm.editingMask
        // targetOf already gives the mask as the layer's bitmap when the mask is being edited.
        val bmp = target.bitmap
        val w = warp
        // A new mesh unless this one still matches the layer exactly as the last warp stroke left it.
        if (w == null || w.layerId != target.id || w.bmp !== bmp || warpRevision != vm.revision || warpOnMask != onMask) {
            warp = null
            warp = try { io.github.capstasher.gako.tools.Warp(target.id, bmp) } catch (e: OutOfMemoryError) {
                vm.say("Not enough memory to warp a layer this big"); return
            }
            warpOnMask = onMask
        }
        warp!!.beginStroke()
        warpLast = PointF(x, y)
        warpAt = PointF(x, y)
        if (options.warpMode != io.github.capstasher.gako.tools.WarpMode.Move) warpDab(x, y, 0f, 0f)
        vm.pixelsChanged()
        // Grow, shrink, swirl, erase and smooth keep working while the finger rests.
        if (options.warpMode != io.github.capstasher.gako.tools.WarpMode.Move) {
            airJob = vm.viewModelScope.launch {
                while (isActive) {
                    delay(40)
                    val p = warpLast ?: break
                    if (warp?.strokeActive != true) break
                    warpDab(p.x, p.y, 0f, 0f)
                    vm.pixelsChanged()
                }
            }
        }
    }

    /** One dab at canvas point ([x], [y]); the mesh works in layer pixels. */
    private fun warpDab(x: Float, y: Float, dx: Float, dy: Float) {
        val w = warp ?: return
        val l = doc?.layer(w.layerId) ?: return
        try {
            w.dab(options.warpMode, x - l.x, y - l.y, dx, dy, options.size / 2f, options.strength, options.hardness)
        } catch (e: OutOfMemoryError) {
            vm.say("Not enough memory for that warp; try a smaller brush")
        }
    }

    private fun warpTo(x: Float, y: Float) {
        val w = warp ?: return
        if (!w.strokeActive) return
        val last = warpLast ?: return
        val dist = hypot(x - last.x, y - last.y)
        // Small steps, so the push follows the finger's path.
        val step = max(1f, options.size / 8f)
        val n = max(1, ceil(dist / step).toInt())
        for (k in 1..n) {
            val px = last.x + (x - last.x) * k / n
            val py = last.y + (y - last.y) * k / n
            warpDab(px, py, (x - last.x) / n, (y - last.y) / n)
        }
        warpLast = PointF(x, y)
        warpAt = PointF(x, y)
        vm.pixelsChanged()
    }

    private fun endWarp() {
        airJob?.cancel()
        warpAt = null
        val w = warp ?: return
        if (!w.strokeActive) return
        val patch = try { w.endStroke("Warp Transform") } catch (e: OutOfMemoryError) {
            w.cancelStroke(); warp = null; vm.pixelsChanged()
            vm.say("Not enough memory to finish that warp"); return
        }
        patch?.let { vm.record(if (warpOnMask) it.forMask() else it) }
        vm.pixelsChanged()
        warpRevision = vm.revision
    }

    /**
     * The Measure line's angle from the nearest axis, in degrees (anticlockwise positive, as GIMP
     * shows it), or null without a line.
     */
    fun measureTilt(): Float? {
        val (a, b) = measure ?: return null
        val deg = Math.toDegrees(atan2(-(b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
        var t = deg % 90f
        if (t > 45f) t -= 90f
        if (t < -45f) t += 90f
        return t
    }

    /** Rotates the image so the Measure line is level (or upright, whichever is nearer). */
    fun straighten(crop: Boolean) {
        val t = measureTilt() ?: run { vm.say("Drag a line along something that should be level"); return }
        if (kotlin.math.abs(t) < 0.05f) { vm.say("Already straight"); return }
        // Anticlockwise tilt: turn the image clockwise by as much (Android's positive angles are clockwise).
        vm.rotateBy(t, crop)
    }

    /** A stroke ran out of memory: undo it and say so, rather than crash. */
    private fun outOfMemory() {
        airJob?.cancel()
        stroke?.let { runCatching { it.cancel() } }
        stroke = null
        vm.pixelsChanged()
        vm.say("Not enough memory for that stroke; try a smaller brush or image")
    }

    private fun setCloneSource(x: Float, y: Float) {
        cloneSource = PointF(x, y)
        cloneOffset = null
        settingCloneSource = false
        vm.say("Source set. Now paint where the ${if (tool == Tool.Heal) "fix" else "copy"} should go.")
    }

    private fun pick(d: DocState, x: Float, y: Float) {
        val xi = x.toInt().coerceIn(0, d.width - 1)
        val yi = y.toInt().coerceIn(0, d.height - 1)
        val c = if (options.sampleMerged || d.active?.group == true) Compositor.sample(d, xi, yi)
                else d.active?.let { Compositor.sampleLayer(it, xi, yi) } ?: return
        if (Color.alpha(c) == 0) return
        vm.setForeground(c or (0xFF shl 24))
    }

    // ------------------------------------------------------------ selection

    /** A tap without a drag deselects, as in GIMP. */
    private fun tapSelect() {
        if (options.selectMode == SelectMode.Replace && doc?.selection != null) vm.selectNone()
    }

    private fun snap(r: RectF) = RectF(r.left.roundToInt().toFloat(), r.top.roundToInt().toFloat(),
        r.right.roundToInt().toFloat(), r.bottom.roundToInt().toFloat())

    /** Builds a shape mask off the main thread, feathers it, then combines it with the selection. */
    private fun select(d: DocState, label: String, modeOverride: SelectMode? = null, shape: suspend () -> Bitmap?) {
        val mode = modeOverride ?: options.selectMode
        val feather = options.feather
        vm.viewModelScope.launch {
            vm.busy = EditorViewModel.Busy(label)
            try {
                val combined = withContext(Dispatchers.Default) {
                    var m = shape() ?: return@withContext null
                    if (feather > 0f) {
                        val r = Engine.apply("gegl:gaussian-blur",
                            mapOf("std-dev-x" to feather / 2.0, "std-dev-y" to feather / 2.0), Selections.maskToGrey(m))
                        if (r is Engine.Result.Ok) m = Selections.greyToMask(r.bitmap)
                    }
                    Selections.combine(d.selection?.mask, m, mode)
                }
                if (combined != null && vm.doc === d) vm.setSelection(label, combined)
            } catch (e: OutOfMemoryError) {
                vm.say("$label: not enough memory")
            } finally {
                vm.busy = null
            }
        }
    }

    fun fgUndoStroke() {
        if (fgStrokes.isNotEmpty()) fgStrokes.removeAt(fgStrokes.lastIndex) else fgOutline = null
        fgRevision++
    }

    fun fgStartOver() {
        fgOutline = null
        fgStrokes.clear()
        fgRevision++
    }

    /**
     * Foreground Select: GEGL's matting (what GIMP's tool uses) works out the object's edge inside
     * the outline from the strokes, on what's visible. It runs on the outline's box only, at up to
     * ~2.5 MP (scaled back up after), so it's quick and fits in a phone's memory.
     */
    fun foregroundSelect() {
        val d = doc ?: return
        val outline = fgOutline ?: run { vm.say("First draw roughly around the object"); return }
        if (fgStrokes.none { it.foreground }) { vm.say("Paint over the object first (Foreground)"); return }
        val strokes = fgStrokes.toList()
        val box = RectF().also { outline.computeBounds(it, true) }
        val crop = Rect(kotlin.math.floor(box.left).toInt() - 6, kotlin.math.floor(box.top).toInt() - 6,
            ceil(box.right).toInt() + 6, ceil(box.bottom).toInt() + 6)
        if (!crop.intersect(0, 0, d.width, d.height)) return
        val scale = kotlin.math.sqrt(minOf(1.0, FG_MAX_PIXELS / (crop.width().toDouble() * crop.height()))).toFloat()
        val sw = max(1, (crop.width() * scale).roundToInt())
        val sh = max(1, (crop.height() * scale).roundToInt())
        select(d, "Foreground Select") {
            val view = Matrix().apply {
                setTranslate(-crop.left.toFloat(), -crop.top.toFloat())
                postScale(sw / crop.width().toFloat(), sh / crop.height().toFloat())
            }
            // What's visible, over white.
            val image = Bitmaps.blank(sw, sh, Color.WHITE)
            Canvas(image).apply { concat(view); Compositor.drawLayers(this, d) }
            // The trimap: outside the outline background (0), inside unknown (128), strokes 255 / 0.
            val tri = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
            Canvas(tri).apply {
                concat(view)
                drawPath(outline, Paint().apply { color = Color.argb(128, 0, 0, 0); blendMode = BlendMode.SRC })
                for (s in strokes) drawPath(s.path, Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = s.width
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    color = if (s.foreground) Color.BLACK else Color.TRANSPARENT
                    blendMode = BlendMode.SRC
                })
            }
            val trimap = alphaBytes(tri)
            when (val r = Engine.matting(image, trimap)) {
                is Engine.Result2.Ok -> {
                    if (r.alpha.none { it != 0.toByte() }) {
                        vm.say("Nothing came out: paint more of the object, and keep the outline around it")
                        return@select null
                    }
                    val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8)
                    fromAlphaBytes(small, r.alpha)
                    val mask = Selections.emptyMask(d.width, d.height)
                    Canvas(mask).drawBitmap(small, null, crop, Paint().apply { isFilterBitmap = true })
                    fgStartOver()
                    mask
                }
                is Engine.Result2.Failed -> { vm.say("Foreground Select failed: ${r.message}"); null }
                Engine.Result2.Cancelled -> null
            }
        }
    }

    /** An ALPHA_8 bitmap's bytes, one per pixel, rows packed. */
    private fun alphaBytes(b: Bitmap): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(b.rowBytes * b.height)
        b.copyPixelsToBuffer(buf)
        val src = buf.array()
        if (b.rowBytes == b.width) return src
        return ByteArray(b.width * b.height).also { out ->
            for (y in 0 until b.height) System.arraycopy(src, y * b.rowBytes, out, y * b.width, b.width)
        }
    }

    private fun fromAlphaBytes(b: Bitmap, bytes: ByteArray) {
        val padded = if (b.rowBytes == b.width) bytes else ByteArray(b.rowBytes * b.height).also { out ->
            for (y in 0 until b.height) System.arraycopy(bytes, y * b.width, out, y * b.rowBytes, b.width)
        }
        b.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(padded))
    }

    /** Pixels to sample for fuzzy select / fill: the active layer, or everything visible. */
    private fun sampleCanvas(d: DocState): IntArray {
        val src = if (options.sampleMerged || d.active?.group == true) Compositor.flatten(d)
        else Bitmaps.blank(d.width, d.height).also { b ->
            d.active?.let { Canvas(b).drawBitmap(it.bitmap, it.x.toFloat(), it.y.toFloat(), null) }
        }
        return Selections.pixelsOf(src)
    }

    private fun colourSelect(d: DocState, x: Float, y: Float) {
        val xi = x.toInt()
        val yi = y.toInt()
        if (xi !in 0 until d.width || yi !in 0 until d.height) return
        val fuzzy = tool == Tool.FuzzySelect
        val threshold = options.threshold
        val diagonal = options.diagonal
        select(d, if (fuzzy) "Fuzzy Select" else "Select by Colour") {
            val px = sampleCanvas(d)
            val bytes = if (fuzzy) Selections.floodRegion(px, d.width, d.height, xi, yi, threshold, diagonal)
                        else Selections.byColor(px, px[yi * d.width + xi], threshold)
            Selections.maskFromBytes(bytes, d.width, d.height)
        }
    }

    private fun bucketFill(d: DocState, x: Float, y: Float) {
        val layer = d.active ?: return
        if (options.fillArea == FillArea.Selection) {
            vm.fillWith(vm.fg, "Bucket Fill")
            return
        }
        val xi = x.toInt()
        val yi = y.toInt()
        if (xi !in 0 until d.width || yi !in 0 until d.height) return
        val threshold = options.threshold
        val diagonal = options.diagonal
        val color = vm.paintColor(vm.fg)
        val opacity = options.opacity
        vm.viewModelScope.launch {
            vm.busy = EditorViewModel.Busy("Bucket Fill")
            try {
                val region = withContext(Dispatchers.Default) {
                    val px = sampleCanvas(d)
                    val bytes = Selections.floodRegion(px, d.width, d.height, xi, yi, threshold, diagonal)
                    val canvasMask = Selections.maskFromBytes(bytes, d.width, d.height)
                    Selections.make(canvasMask)?.let { it.mask to it.bounds }
                } ?: return@launch
                if (vm.doc !== d) return@launch
                val (mask, bounds) = region
                // Filling turns a text layer into an ordinary one.
                if (!vm.editingMask && layer.text != null) vm.silently(d.replace(layer.copy(text = null)))
                vm.editActiveLayer("Bucket Fill", bounds) { l, sel ->
                    val c = Canvas(l.bitmap)
                    val save = c.saveLayer(RectF(0f, 0f, l.width.toFloat(), l.height.toFloat()), Paint().apply {
                        alpha = (opacity * 255).roundToInt()
                        blendMode = if (l.lockAlpha) BlendMode.SRC_ATOP else BlendMode.SRC_OVER
                    })
                    c.drawBitmap(mask, -l.x.toFloat(), -l.y.toFloat(), Paint().apply { this.color = color or (0xFF shl 24) })
                    sel?.let { Bitmaps.maskIn(c, it) }
                    c.restoreToCount(save)
                }
            } finally {
                vm.busy = null
            }
        }
    }

    // ------------------------------------------------------------ gradient

    /** The gradient's shader, in canvas coordinates. */
    fun gradientShader(a: PointF, b: PointF): Shader {
        val c1 = vm.paintColor(vm.fg) or (0xFF shl 24)
        val c2 = if (options.gradientColors == GradientColors.FgTransparent) vm.paintColor(vm.fg) and 0x00FFFFFF
                 else vm.paintColor(vm.bg) or (0xFF shl 24)
        val len = max(1f, hypot(b.x - a.x, b.y - a.y))
        return when (options.gradientShape) {
            GradientShape.Linear -> LinearGradient(a.x, a.y, b.x, b.y, c1, c2, Shader.TileMode.CLAMP)
            GradientShape.Radial -> RadialGradient(a.x, a.y, len, c1, c2, Shader.TileMode.CLAMP)
            GradientShape.Mirrored -> LinearGradient(2 * a.x - b.x, 2 * a.y - b.y, b.x, b.y,
                intArrayOf(c2, c1, c2), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        }
    }

    private fun applyGradient(a: PointF, b: PointF) {
        val shader = gradientShader(a, b)
        val opacity = options.opacity
        vm.editActiveLayer("Gradient") { l, sel ->
            val c = Canvas(l.bitmap)
            val save = c.saveLayer(RectF(0f, 0f, l.width.toFloat(), l.height.toFloat()), Paint().apply {
                alpha = (opacity * 255).roundToInt()
                blendMode = if (l.lockAlpha) BlendMode.SRC_ATOP else BlendMode.SRC_OVER
            })
            c.translate(-l.x.toFloat(), -l.y.toFloat())
            c.drawPaint(Paint().apply { this.shader = shader })
            c.translate(l.x.toFloat(), l.y.toFloat())
            sel?.let { Bitmaps.maskIn(c, it) }
            c.restoreToCount(save)
        }
    }

    // ------------------------------------------------------------ crop

    private fun hitCrop(r: RectF, x: Float, y: Float, tol: Float): Drag {
        val nearL = abs(x - r.left) < tol
        val nearR = abs(x - r.right) < tol
        val nearT = abs(y - r.top) < tol
        val nearB = abs(y - r.bottom) < tol
        val inX = x > r.left - tol && x < r.right + tol
        val inY = y > r.top - tol && y < r.bottom + tol
        return when {
            nearL && nearT -> Drag.Corner0
            nearR && nearT -> Drag.Corner1
            nearR && nearB -> Drag.Corner2
            nearL && nearB -> Drag.Corner3
            options.cropAspect.ratio == null && nearL && inY -> Drag.Left
            options.cropAspect.ratio == null && nearR && inY -> Drag.Right
            options.cropAspect.ratio == null && nearT && inX -> Drag.Top
            options.cropAspect.ratio == null && nearB && inX -> Drag.Bottom
            r.contains(x, y) -> Drag.Move
            else -> Drag.New
        }
    }

    private fun dragCrop(d: DocState, x: Float, y: Float) {
        val w = d.width.toFloat()
        val h = d.height.toFloat()
        val r0 = cropAtDown
        val dx = x - down.x
        val dy = y - down.y
        val ratio = options.cropAspect.ratio
        var r = RectF(r0)
        fun cornerRect(ax: Float, ay: Float, px: Float, py: Float): RectF {
            var cw = abs(px - ax)
            var ch = abs(py - ay)
            if (ratio != null) {
                if (cw / max(ch, 1f) > ratio) cw = ch * ratio else ch = cw / ratio
            }
            val sx = if (px >= ax) 1f else -1f
            val sy = if (py >= ay) 1f else -1f
            return RectF(min(ax, ax + sx * cw), min(ay, ay + sy * ch), max(ax, ax + sx * cw), max(ay, ay + sy * ch))
        }
        when (mode) {
            Drag.Move -> {
                val ox = dx.coerceIn(-r0.left, w - r0.right)
                val oy = dy.coerceIn(-r0.top, h - r0.bottom)
                r.offset(ox, oy)
            }
            Drag.Corner0 -> r = cornerRect(r0.right, r0.bottom, x, y)
            Drag.Corner1 -> r = cornerRect(r0.left, r0.bottom, x, y)
            Drag.Corner2 -> r = cornerRect(r0.left, r0.top, x, y)
            Drag.Corner3 -> r = cornerRect(r0.right, r0.top, x, y)
            Drag.Left -> r.left = min(x, r0.right - 1)
            Drag.Right -> r.right = max(x, r0.left + 1)
            Drag.Top -> r.top = min(y, r0.bottom - 1)
            Drag.Bottom -> r.bottom = max(y, r0.top + 1)
            Drag.New -> r = cornerRect(down.x, down.y, x, y)
            else -> return
        }
        r.intersect(0f, 0f, w, h)
        if (r.width() >= 1 && r.height() >= 1) cropState = (d.width to d.height) to r
    }

    fun applyCrop() {
        val d = doc ?: return
        val r = cropRect(d)
        val rect = Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
        if (rect == Rect(0, 0, d.width, d.height)) { vm.say("Drag the edges in first"); return }
        cropState = null
        vm.cropTo(rect)
    }

    // ------------------------------------------------------------ transform

    private fun hitTransform(t: LayerTransform, x: Float, y: Float, tol: Float): Drag {
        if (t.perspective) {
            t.corners().forEachIndexed { i, c ->
                if (hypot(x - c.x, y - c.y) < tol * 1.4f) return listOf(Drag.Corner0, Drag.Corner1, Drag.Corner2, Drag.Corner3)[i]
            }
            val inv = Matrix().also { t.matrix().invert(it) }
            val p = floatArrayOf(x, y).also { inv.mapPoints(it) }
            return if (p[0] in 0f..t.bitmap.width.toFloat() && p[1] in 0f..t.bitmap.height.toFloat()) Drag.Move else Drag.None
        }
        val knob = t.knob(tol * 2.2f)
        if (hypot(x - knob.x, y - knob.y) < tol * 1.2f) return Drag.Rotate
        t.corners().forEach { c -> if (hypot(x - c.x, y - c.y) < tol * 1.2f) return Drag.Scale }
        val inv = Matrix().also { t.matrix().invert(it) }
        val p = floatArrayOf(x, y).also { inv.mapPoints(it) }
        return if (p[0] in 0f..t.bitmap.width.toFloat() && p[1] in 0f..t.bitmap.height.toFloat()) Drag.Move else Drag.Rotate
    }

    private fun dragTransform(x: Float, y: Float) {
        val t0 = transformAtDown ?: return
        val q0 = t0.quad
        if (q0 != null) {
            val dx = x - down.x
            val dy = y - down.y
            val q = q0.copyOf()
            when (mode) {
                Drag.Move -> for (i in 0 until 4) { q[i * 2] += dx; q[i * 2 + 1] += dy }
                Drag.Corner0, Drag.Corner1, Drag.Corner2, Drag.Corner3 -> {
                    val i = mode.ordinal - Drag.Corner0.ordinal
                    q[i * 2] += dx; q[i * 2 + 1] += dy
                }
                else -> return
            }
            transformState = t0.copy(quad = q)
            return
        }
        val cx = t0.cx + t0.tx
        val cy = t0.cy + t0.ty
        transformState = when (mode) {
            Drag.Move -> t0.copy(tx = t0.tx + x - down.x, ty = t0.ty + y - down.y)
            Drag.Scale -> {
                val k = hypot(x - cx, y - cy) / max(1f, hypot(down.x - cx, down.y - cy))
                t0.copy(sx = (t0.sx * k).let { if (abs(it) < 0.01f) 0.01f * sign(t0.sx) else it },
                        sy = (t0.sy * k).let { if (abs(it) < 0.01f) 0.01f * sign(t0.sy) else it })
            }
            Drag.Rotate -> {
                val a0 = atan2(down.y - cy, down.x - cx)
                val a1 = atan2(y - cy, x - cx)
                t0.copy(angle = t0.angle + Math.toDegrees((a1 - a0).toDouble()).toFloat())
            }
            else -> return
        }
    }

    fun setTransform(t: LayerTransform) {
        transformState = t
    }

    fun applyTransform() {
        val d = doc ?: return
        if (vm.refuseGroup(d)) return
        val t = transformFor(d) ?: return
        if (!t.changed) { vm.say("Drag the layer, its corners or the knob first"); return }
        transformState = null
        vm.docJob("Transform Layer") { state ->
            val l = state.layer(t.layerId) ?: return@docJob null
            val m = t.matrix()
            val r = RectF(0f, 0f, l.width.toFloat(), l.height.toFloat())
            m.mapRect(r)
            val left = kotlin.math.floor(r.left).toInt()
            val top = kotlin.math.floor(r.top).toInt()
            val w = (kotlin.math.ceil(r.right).toInt() - left).coerceIn(1, 16384)
            val h = (kotlin.math.ceil(r.bottom).toInt() - top).coerceIn(1, 16384)
            val out = Bitmaps.blank(w, h)
            m.postTranslate(-left.toFloat(), -top.toFloat())
            val smooth = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
            Canvas(out).drawBitmap(l.bitmap, m, smooth)
            val mask = l.mask?.let { old -> Bitmaps.blank(w, h, Color.BLACK).also { Canvas(it).drawBitmap(old, m, smooth) } }
            state.replace(l.copy(bitmap = out, mask = mask, x = left, y = top, text = null))
        }
    }

    @Suppress("unused")
    private fun layerOf(d: DocState): Layer? = d.active

    private companion object {
        /** Foreground Select works on at most this many pixels (it needs ~30 bytes each). */
        const val FG_MAX_PIXELS = 2_500_000.0
    }
}
