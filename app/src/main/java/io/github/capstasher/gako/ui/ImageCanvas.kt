package io.github.capstasher.gako.ui

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import kotlin.math.roundToInt
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.editor.TextSession
import io.github.capstasher.gako.tools.Tool
import io.github.capstasher.gako.ui.theme.Gako
import kotlin.math.max
import kotlin.math.min

/** Tools that act on a tap (released without moving) rather than on a drag. */
private val tapTools = setOf(Tool.FuzzySelect, Tool.ColorSelect, Tool.BucketFill, Tool.Text)

/**
 * The image. One finger uses the current tool (or pans with Navigate); two fingers always pan and
 * zoom. Layers are composited by the GPU each frame, so painting only re-uploads what changed.
 */
@Composable
fun ImageCanvas(vm: EditorViewModel, doc: DocState, modifier: Modifier = Modifier) {
    // 0 means "fit to view"; reset whenever the document changes shape.
    var scale by remember(doc.width, doc.height) { mutableFloatStateOf(0f) }
    var pan by remember(doc.width, doc.height) { mutableStateOf(Offset.Zero) }
    val docState by rememberUpdatedState(doc)
    val checker = remember { checkerBrush() }
    val handlePx = with(LocalDensity.current) { 22.dp.toPx() }
    val text = vm.text
    val textBitmap: Bitmap? = text?.let { t -> remember(t.info) { TextSession.render(t.info) } }
    var lastTap by remember { mutableStateOf(0L to Offset.Zero) }

    fun fit(w: Float, h: Float) = min(w / docState.width, h / docState.height) * 0.94f
    fun effective(w: Float, h: Float) = if (scale > 0f) scale else fit(w, h)
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                fun toDoc(p: Offset): Offset {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val s = effective(w, h)
                    return (p - Offset(w / 2, h / 2) - pan) / s + Offset(docState.width / 2f, docState.height / 2f)
                }
                fun tolerance() = handlePx / effective(size.width.toFloat(), size.height.toFloat())

                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val session = vm.session
                    val textSession = vm.text
                    val tool = vm.tool
                    val modal = session != null || vm.curves != null || textSession != null
                    val toolDrags = !modal && tool != Tool.Navigate && tool !in tapTools && vm.busy == null
                    val downPos = first.position
                    val downTime = first.uptimeMillis
                    val slop = viewConfiguration.touchSlop
                    var multi = false
                    var started = false
                    var moved = false
                    var textStart: Pair<Int, Int>? = null
                    first.consume()

                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (started) vm.input.up()
                            else if (!multi && !moved) {
                                val p = toDoc(downPos)
                                when {
                                    session != null -> if (session.point != null) vm.tapImage(p.x, p.y)
                                    textSession != null -> { textSession.x = p.x.toInt(); textSession.y = p.y.toInt() }
                                    vm.curves != null -> Unit
                                    tool == Tool.Navigate || vm.busy != null -> {
                                        // Double-tap toggles fit / close-up.
                                        val (t0, p0) = lastTap
                                        if (first.uptimeMillis - t0 < 320 && (downPos - p0).getDistance() < slop * 3) {
                                            val w = size.width.toFloat()
                                            val h = size.height.toFloat()
                                            if (scale > 0f && scale > fit(w, h) * 1.05f) {
                                                scale = 0f; pan = Offset.Zero
                                            } else {
                                                val s0 = effective(w, h)
                                                val s1 = maxOf(1f, fit(w, h) * 2.5f)
                                                val c = downPos - Offset(w / 2, h / 2)
                                                pan = c - (c - pan) * (s1 / s0)
                                                scale = s1
                                            }
                                            lastTap = 0L to Offset.Zero
                                        } else lastTap = first.uptimeMillis to downPos
                                    }
                                    else -> vm.input.tap(p.x, p.y, tolerance())
                                }
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            if (started) { vm.input.cancel(); started = false }
                            multi = true
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val zoom = ev.calculateZoom()
                            val panChange = ev.calculatePan()
                            val centroid = ev.calculateCentroid()
                            val s0 = effective(w, h)
                            val s1 = (s0 * zoom).coerceIn(fit(w, h) * 0.25f, 64f)
                            val c = centroid - Offset(w / 2, h / 2)
                            pan = c - (c - pan) * (s1 / s0) + panChange
                            scale = s1
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (multi) continue   // after a pinch, wait for every finger to lift
                        val ch = pressed[0]
                        if (!moved && (ch.position - downPos).getDistance() > slop) moved = true
                        val pressure = if (ch.type == PointerType.Stylus) ch.pressure.coerceIn(0.05f, 1.5f) else 1f
                        when {
                            textSession != null -> if (moved) {
                                val start = textStart ?: (textSession.x to textSession.y).also { textStart = it }
                                val d = (ch.position - downPos) / effective(size.width.toFloat(), size.height.toFloat())
                                textSession.x = start.first + d.x.toInt()
                                textSession.y = start.second + d.y.toInt()
                            }
                            toolDrags -> {
                                if (!started && (moved || ch.uptimeMillis - downTime > 120)) {
                                    val p = toDoc(downPos)
                                    vm.input.down(p.x, p.y, pressure, tolerance())
                                    started = true
                                }
                                if (started && ch.position != ch.previousPosition) {
                                    val p = toDoc(ch.position)
                                    vm.input.move(p.x, p.y, pressure)
                                }
                            }
                            moved -> pan += ch.positionChange()
                        }
                        ch.consume()
                    }
                }
            },
    ) {
        val d = docState
        val s = effective(size.width, size.height)
        val dw = d.width * s
        val dh = d.height * s
        val topLeft = Offset(size.width / 2 + pan.x - dw / 2, size.height / 2 + pan.y - dh / 2)
        drawRect(checker, topLeft, Size(dw, dh))

        @Suppress("UNUSED_VARIABLE") val rev = vm.revision   // repaint on in-place pixel edits
        val preview = vm.preview
        val comparing = vm.comparing
        val input = vm.input
        val tool = vm.tool
        val active = d.active
        val moveDelta = input.moveDelta
        val transform = if (tool == Tool.Transform && vm.session == null) input.transformFor(d) else null
        val gradient = input.gradient
        val px = 1f / s   // one screen pixel in canvas units

        drawIntoCanvas { cc ->
            val nc = cc.nativeCanvas
            nc.save()
            nc.translate(topLeft.x, topLeft.y)
            nc.scale(s, s)
            val bounds = RectF(0f, 0f, d.width.toFloat(), d.height.toFloat())
            nc.save()
            nc.clipRect(bounds)
            val iso = nc.saveLayer(bounds, null)
            val smooth = s < 2f
            for (layer in d.layers) {
                val editingThis = text != null && text.layerId == layer.id
                if (layer.visible && !editingThis) {
                    layer.bitmap.setHasMipMap(smooth)
                    val paint = Compositor.layerPaint(layer, smooth)
                    when {
                        layer === active && preview != null && !comparing -> drawPreview(nc, layer, preview, d, paint)
                        layer === active && moveDelta != null ->
                            nc.drawBitmap(layer.bitmap, layer.x + moveDelta.x, layer.y + moveDelta.y, paint)
                        layer === active && transform != null && transform.changed ->
                            nc.drawBitmap(layer.bitmap, transform.matrix(), paint.apply { isFilterBitmap = true })
                        else -> nc.drawBitmap(layer.bitmap, layer.x.toFloat(), layer.y.toFloat(), paint)
                    }
                    if (layer === active && gradient != null) {
                        val g = Paint().apply {
                            shader = input.gradientShader(gradient.first, gradient.second)
                            alpha = (vm.options.opacity * 255).toInt()
                        }
                        nc.save()
                        nc.clipRect(RectF(layer.bounds))
                        nc.drawPaint(g)
                        nc.restore()
                    }
                }
                if (text != null && textBitmap != null &&
                    (text.layerId == layer.id || (text.layerId == null && layer === active))) {
                    nc.drawBitmap(textBitmap, text.x.toFloat(), text.y.toFloat(), null)
                }
            }
            nc.restoreToCount(iso)
            nc.restore()

            // ---- overlays
            val ink = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * px; color = android.graphics.Color.BLACK; isAntiAlias = true }
            val dash = Paint(ink).apply { color = android.graphics.Color.WHITE; pathEffect = DashPathEffect(floatArrayOf(5 * px, 5 * px), 0f) }
            val gold = Paint(ink).apply { color = Gako.Gold.toArgb(); strokeWidth = 2f * px }

            // Active layer's boundary when it isn't the canvas (GIMP's yellow dashes).
            if (active != null && vm.session == null && (active.x != 0 || active.y != 0 || active.width != d.width || active.height != d.height) &&
                transform == null) {
                val r = RectF(active.bounds).apply { if (moveDelta != null) offset(moveDelta.x, moveDelta.y) }
                nc.drawRect(r, Paint(dash).apply { color = Gako.Gold.toArgb() })
            }

            d.selection?.let { sel ->
                nc.drawPath(sel.outline, ink)
                nc.drawPath(sel.outline, dash)
            }
            input.dragRect?.let { r ->
                if (tool == Tool.EllipseSelect) { nc.drawOval(r, ink); nc.drawOval(r, dash) }
                else { nc.drawRect(r, ink); nc.drawRect(r, dash) }
            }
            input.lasso?.let { path ->
                @Suppress("UNUSED_VARIABLE") val lr = input.lassoRevision
                nc.drawPath(path, ink); nc.drawPath(path, dash)
            }
            gradient?.let { (a, b) ->
                nc.drawLine(a.x, a.y, b.x, b.y, ink)
                nc.drawLine(a.x, a.y, b.x, b.y, gold)
                handle(nc, a.x, a.y, 7 * px, px)
                handle(nc, b.x, b.y, 7 * px, px)
            }
            if (tool == Tool.Crop && vm.session == null) drawCrop(nc, input.cropRect(d), d, px)
            transform?.let { t ->
                val c = t.corners()
                val outline = Path().apply { moveTo(c[0].x, c[0].y); c.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                nc.drawPath(outline, ink)
                nc.drawPath(outline, gold)
                val knob = t.knob(handlePx * px * 2.2f)
                nc.drawLine((c[0].x + c[1].x) / 2, (c[0].y + c[1].y) / 2, knob.x, knob.y, gold)
                c.forEach { handle(nc, it.x, it.y, 9 * px, px) }
                handle(nc, knob.x, knob.y, 10 * px, px, round = true)
            }
            if (tool == Tool.Clone) input.cloneSource?.let { src ->
                val r = 12 * px
                nc.drawCircle(src.x, src.y, r, ink)
                nc.drawCircle(src.x, src.y, r, gold)
                nc.drawLine(src.x - r * 1.6f, src.y, src.x + r * 1.6f, src.y, gold)
                nc.drawLine(src.x, src.y - r * 1.6f, src.x, src.y + r * 1.6f, gold)
            }
            if (text != null && textBitmap != null) {
                val r = RectF(text.x.toFloat(), text.y.toFloat(), (text.x + textBitmap.width).toFloat(), (text.y + textBitmap.height).toFloat())
                nc.drawRect(r, ink); nc.drawRect(r, Paint(dash).apply { color = Gako.Gold.toArgb() })
            }

            // A filter's centre point (lens flare, supernova…).
            vm.session?.let { session ->
                val layer = d.layer(session.layerId) ?: return@let
                val (ppx, ppy) = session.point ?: return@let
                val x = (session.values[ppx.name] as? Number)?.toFloat() ?: return@let
                val y = (session.values[ppy.name] as? Number)?.toFloat() ?: return@let
                val m = if (ppx.unit == "relative-coordinate") android.graphics.PointF(layer.x + x * layer.width, layer.y + y * layer.height)
                        else android.graphics.PointF(layer.x + x, layer.y + y)
                val r = 15 * px
                nc.drawCircle(m.x, m.y, r, Paint(ink).apply { strokeWidth = 5 * px; alpha = 140 })
                nc.drawCircle(m.x, m.y, r, gold)
                nc.drawLine(m.x - 24 * px, m.y, m.x - 8 * px, m.y, gold)
                nc.drawLine(m.x + 8 * px, m.y, m.x + 24 * px, m.y, gold)
                nc.drawLine(m.x, m.y - 24 * px, m.x, m.y - 8 * px, gold)
                nc.drawLine(m.x, m.y + 8 * px, m.x, m.y + 24 * px, gold)
            }
            nc.restore()
        }
    }
        // Zoom level; tap to switch between fitting the screen and 100%.
        if (viewSize.width > 0) {
            val w = viewSize.width.toFloat()
            val h = viewSize.height.toFloat()
            val pct = (effective(w, h) * 100).roundToInt()
            androidx.compose.material3.Text(
                if (scale == 0f) "Fit · $pct%" else "$pct%",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = Gako.Parchment,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(10.dp)
                    .background(Gako.Ink.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable {
                        if (scale == 0f) { scale = 1f; pan = Offset.Zero } else { scale = 0f; pan = Offset.Zero }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** The active layer with its filter preview, through the selection if there is one. */
private fun drawPreview(nc: android.graphics.Canvas, layer: Layer, preview: Bitmap, d: DocState, paint: Paint) {
    val dst = RectF(layer.bounds)
    val sel = d.selection
    if (sel == null) {
        nc.drawBitmap(preview, null, dst, paint)
        return
    }
    val outer = nc.saveLayer(dst, paint)
    nc.drawBitmap(layer.bitmap, layer.x.toFloat(), layer.y.toFloat(), null)
    val inner = nc.saveLayer(dst, null)
    nc.drawBitmap(preview, null, dst, Paint().apply { isFilterBitmap = true; blendMode = BlendMode.SRC })
    nc.drawBitmap(sel.mask, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_IN })
    nc.restoreToCount(inner)
    nc.restoreToCount(outer)
}

private fun handle(nc: android.graphics.Canvas, x: Float, y: Float, r: Float, px: Float, round: Boolean = false) {
    val fill = Paint().apply { color = Gako.Gold.toArgb(); isAntiAlias = true }
    val edge = Paint().apply { color = android.graphics.Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * px; isAntiAlias = true }
    if (round) {
        nc.drawCircle(x, y, r, fill); nc.drawCircle(x, y, r, edge)
    } else {
        val rr = RectF(x - r, y - r, x + r, y + r)
        nc.drawRect(rr, fill); nc.drawRect(rr, edge)
    }
}

private fun drawCrop(nc: android.graphics.Canvas, r: RectF, d: DocState, px: Float) {
    val shade = Paint().apply { color = android.graphics.Color.argb(150, 0, 0, 0) }
    val w = d.width.toFloat()
    val h = d.height.toFloat()
    nc.drawRect(0f, 0f, w, r.top, shade)
    nc.drawRect(0f, r.bottom, w, h, shade)
    nc.drawRect(0f, r.top, r.left, r.bottom, shade)
    nc.drawRect(r.right, r.top, w, r.bottom, shade)
    val line = Paint().apply { style = Paint.Style.STROKE; color = Gako.Gold.toArgb(); strokeWidth = 2 * px; isAntiAlias = true }
    val thirds = Paint(line).apply { strokeWidth = 1 * px; alpha = 140 }
    for (i in 1..2) {
        val x = r.left + r.width() * i / 3
        val y = r.top + r.height() * i / 3
        nc.drawLine(x, r.top, x, r.bottom, thirds)
        nc.drawLine(r.left, y, r.right, y, thirds)
    }
    nc.drawRect(r, line)
    val len = min(24 * px, min(r.width(), r.height()) / 3)
    val thick = Paint(line).apply { strokeWidth = 5 * px }
    for ((cx, cy) in listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)) {
        val sx = if (cx == r.left) 1 else -1
        val sy = if (cy == r.top) 1 else -1
        nc.drawLine(cx, cy, cx + sx * len, cy, thick)
        nc.drawLine(cx, cy, cx, cy + sy * len, thick)
    }
    @Suppress("unused") val unused = max(0f, 0f)
    @Suppress("unused") val m = Matrix()
}

private fun checkerBrush(): ShaderBrush {
    val cell = 12
    val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val light = android.graphics.Color.rgb(0x99, 0x99, 0x99)
    val dark = android.graphics.Color.rgb(0x66, 0x66, 0x66)
    for (y in 0 until cell * 2) for (x in 0 until cell * 2) {
        bmp.setPixel(x, y, if ((x / cell + y / cell) % 2 == 0) light else dark)
    }
    return ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
