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
import androidx.compose.foundation.gestures.calculateRotation
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import kotlin.math.roundToInt
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.doc.Tree
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
    /** View rotation in degrees (two-finger twist). The image itself doesn't change. */
    var angle by remember(doc.width, doc.height) { mutableFloatStateOf(0f) }
    val docState by rememberUpdatedState(doc)
    val checker = remember { checkerBrush() }
    val handlePx = with(LocalDensity.current) { 22.dp.toPx() }
    val text = vm.text
    val textBitmap: Bitmap? = text?.let { t -> remember(t.info) { TextSession.render(t.info) } }
    var lastTap by remember { mutableStateOf(0L to Offset.Zero) }

    fun fit(w: Float, h: Float) = min(w / docState.width, h / docState.height) * 0.94f
    fun effective(w: Float, h: Float) = if (scale > 0f) scale else fit(w, h)
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // The inverse of toDoc below, for tests and tools: image pixel → canvas pixel (reads the view state when called).
    androidx.compose.runtime.SideEffect {
        vm.docToView = { x, y ->
            val w = viewSize.width.toFloat()
            val h = viewSize.height.toFloat()
            val s = effective(w, h)
            rotate(Offset((x - docState.width / 2f) * s, (y - docState.height / 2f) * s), angle) + pan + Offset(w / 2, h / 2)
        }
    }
    // The unsnapped angle during a twist, so snapping to straight doesn't stick.
    var rawAngle by remember { mutableFloatStateOf(0f) }

    // Clipped: zoomed in, the image must not spill over the bars around the canvas.
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize().clipToBounds()) {
    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .semantics { contentDescription = "Image, ${doc.width} by ${doc.height} pixels; ${vm.tool.label}" }
            // Keyed on the canvas size: the view state is recreated when it changes (crop, resize),
            // and the gesture handler must write to the new one.
            // A hovering pen shows the brush's outline where it would paint.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val c = e.changes.firstOrNull() ?: continue
                        val pen = c.type == PointerType.Stylus || c.type == PointerType.Eraser
                        vm.hover = when {
                            !pen || c.pressed -> null
                            e.type == androidx.compose.ui.input.pointer.PointerEventType.Exit -> null
                            else -> c.position
                        }
                    }
                }
            }
            .pointerInput(doc.width, doc.height) {
                fun toDoc(p: Offset): Offset {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val s = effective(w, h)
                    val v = rotate(p - Offset(w / 2, h / 2) - pan, -angle)
                    return v / s + Offset(docState.width / 2f, docState.height / 2f)
                }
                fun tolerance() = handlePx / effective(size.width.toFloat(), size.height.toFloat())

                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val session = vm.session
                    val textSession = vm.text
                    val tool = vm.tool
                    val modal = session != null || vm.curves != null || textSession != null
                    // With a pen around, fingers navigate and only the pen paints (palm rejection).
                    val isPen = first.type == PointerType.Stylus || first.type == PointerType.Eraser
                    if (isPen) vm.stylusSeen = true
                    vm.input.toolOverride = if (first.type == PointerType.Eraser && vm.tool.paints) Tool.Eraser else null
                    val fingerOnly = vm.stylusSeen && !isPen
                    val toolDrags = !modal && !fingerOnly && tool != Tool.Navigate && tool !in tapTools && vm.busy == null
                    val downPos = first.position
                    val downTime = first.uptimeMillis
                    // When the finger came down by this device's clock (events can carry timestamps on another base).
                    val downClock = SystemClock.uptimeMillis()
                    val slop = viewConfiguration.touchSlop
                    var multi = false
                    var started = false
                    var moved = false
                    var textStart: Pair<Int, Int>? = null
                    rawAngle = angle
                    // Multi-finger taps: two fingers = undo, three = redo (if they barely move and lift quickly).
                    var maxFingers = 1
                    var multiTravel = 0f
                    var multiZoom = 1f
                    first.consume()

                    // Long-press with a colour-painting tool picks the colour there (as in most phone painting apps).
                    // Not with a pen (it may rest while drawing) nor the airbrush (holding still builds it up).
                    val canPick = !modal && !fingerOnly && !isPen && vm.busy == null &&
                        tool in setOf(Tool.Paintbrush, Tool.Pencil, Tool.BucketFill, Tool.Gradient, Tool.Text)
                    var picked = false
                    fun pickHere() {
                        // Held still: undo any dot just started and pick instead.
                        if (started) { vm.input.cancel(); started = false }
                        val p = toDoc(downPos)
                        vm.pickAt(p.x, p.y)
                        picked = true
                    }
                    while (true) {
                        val waiting = canPick && !moved && !multi && !picked
                        val ev = if (waiting) {
                            // A resting finger still sends tiny moves: wait out what's left of the hold either way.
                            val left = LONG_PRESS_MS - (SystemClock.uptimeMillis() - downClock)
                            if (left <= 0) { pickHere(); null } else withTimeoutOrNull(left) { awaitPointerEvent() } ?: run { pickHere(); null }
                        } else awaitPointerEvent()
                        if (ev == null) continue
                        if (picked) {
                            // Swallow the rest of this gesture.
                            ev.changes.forEach { it.consume() }
                            if (ev.changes.none { it.pressed }) break
                            continue
                        }
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            val quick = ev.changes.first().uptimeMillis - downTime < MULTI_TAP_MS
                            if (multi && quick && multiTravel < slop * 2 && multiZoom in 0.93f..1.07f && !modal) {
                                when (maxFingers) {
                                    2 -> vm.undo()
                                    3 -> vm.redo()
                                }
                                break
                            }
                            if (started) vm.input.up()
                            else if (!multi && !moved) {
                                val p = toDoc(downPos)
                                when {
                                    session != null -> if (session.point != null) vm.tapImage(p.x, p.y)
                                    textSession != null -> { textSession.x = p.x.toInt(); textSession.y = p.y.toInt() }
                                    vm.curves != null -> Unit
                                    tool == Tool.Navigate || vm.busy != null || (fingerOnly && tool !in tapTools) -> {
                                        // Double-tap toggles fit / close-up.
                                        val (t0, p0) = lastTap
                                        if (first.uptimeMillis - t0 < 320 && (downPos - p0).getDistance() < slop * 3) {
                                            val w = size.width.toFloat()
                                            val h = size.height.toFloat()
                                            if ((scale > 0f && scale > fit(w, h) * 1.05f) || angle != 0f) {
                                                scale = 0f; pan = Offset.Zero; angle = 0f
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
                            maxFingers = maxOf(maxFingers, pressed.size)
                            multiTravel += ev.calculatePan().getDistance()
                            multiZoom *= ev.calculateZoom()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val zoom = ev.calculateZoom()
                            val panChange = ev.calculatePan()
                            val centroid = ev.calculateCentroid()
                            val turn = ev.calculateRotation()
                            val s0 = effective(w, h)
                            val s1 = (s0 * zoom).coerceIn(fit(w, h) * 0.25f, 64f)
                            val c = centroid - Offset(w / 2, h / 2)
                            // Keep the point under the fingers under the fingers while zooming and turning.
                            rawAngle += turn
                            val snapped = snapAngle(rawAngle)
                            pan = c + panChange - rotate(c - pan, snapped - angle) * (s1 / s0)
                            angle = snapped
                            scale = s1
                            ev.changes.forEach { it.consume() }
                            continue
                        }
                        if (multi) continue   // after a pinch, wait for every finger to lift
                        val ch = pressed[0]
                        if (!moved && (ch.position - downPos).getDistance() > slop) moved = true
                        val pressure = if (isPen) ch.pressure.coerceIn(0.05f, 1.5f) else 1f
                        when {
                            textSession != null -> if (moved) {
                                val start = textStart ?: (textSession.x to textSession.y).also { textStart = it }
                                val d = toDoc(ch.position) - toDoc(downPos)
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
        // Doc → screen: about the view centre, shifted by pan, turned by angle, scaled.
        val view = Matrix().apply {
            setTranslate(-d.width / 2f, -d.height / 2f)
            postScale(s, s)
            postRotate(angle)
            postTranslate(size.width / 2 + pan.x, size.height / 2 + pan.y)
        }
        if (angle == 0f) drawRect(checker, topLeft, Size(dw, dh))
        else drawIntoCanvas { cc ->
            val corners = floatArrayOf(0f, 0f, d.width.toFloat(), 0f, d.width.toFloat(), d.height.toFloat(), 0f, d.height.toFloat())
            view.mapPoints(corners)
            val outline = Path().apply {
                moveTo(corners[0], corners[1]); lineTo(corners[2], corners[3]); lineTo(corners[4], corners[5]); lineTo(corners[6], corners[7]); close()
            }
            cc.nativeCanvas.save()
            cc.nativeCanvas.clipPath(outline)
            drawRect(checker, Offset.Zero, size)
            cc.nativeCanvas.restore()
        }

        val rev = vm.revision   // read so in-place pixel edits repaint
        val preview = vm.preview
        val comparing = vm.comparing
        val input = vm.input
        val tool = vm.tool
        val active = d.active
        val moveDelta = input.moveDelta
        // The Move tool moves a group with everything in it.
        val moving: Set<Long> = if (moveDelta != null && active != null) {
            if (active.group) d.layers.filter { Tree.isInside(it, active.id, Tree.byId(d.layers)) }.map { it.id }.toSet() else setOf(active.id)
        } else emptySet()
        val transform = if (tool == Tool.Transform && vm.session == null) input.transformFor(d) else null
        val gradient = input.gradient
        val px = 1f / s   // one screen pixel in canvas units

        drawIntoCanvas { cc ->
            val nc = cc.nativeCanvas
            nc.save()
            nc.concat(view)
            val bounds = RectF(0f, 0f, d.width.toFloat(), d.height.toFloat())
            nc.save()
            nc.clipRect(bounds)
            val iso = nc.saveLayer(bounds, null)
            val smooth = s < 2f
            fun drawLeaf(nc: android.graphics.Canvas, layer: Layer) {
                val editingThis = text != null && text.layerId == layer.id
                if (!editingThis) {
                    layer.bitmap.setHasMipMap(smooth)
                    val paint = Compositor.layerPaint(layer, smooth)
                    when {
                        layer === active && preview != null && !comparing -> {
                            val place = vm.previewPlacement
                            if (place != null && !vm.editingMask && d.selection == null && layer.activeMask == null) {
                                // A filter that grows the layer (drop shadow): draw the bigger preview where it lands.
                                val (px0, py0, sc) = place
                                val m = Matrix().apply {
                                    setScale((1 / sc).toFloat(), (1 / sc).toFloat())
                                    postTranslate(layer.x + (px0 / sc).toFloat(), layer.y + (py0 / sc).toFloat())
                                }
                                nc.drawBitmap(preview, m, paint.apply { isFilterBitmap = true })
                            } else drawPreview(nc, layer, preview, d, paint, vm.editingMask)
                        }
                        layer.id in moving && moveDelta != null -> Compositor.drawLayer(nc, layer, paint,
                            Matrix().apply { setTranslate(layer.x + moveDelta.x, layer.y + moveDelta.y) })
                        layer === active && transform != null && transform.changed ->
                            Compositor.drawLayer(nc, layer, paint.apply { isFilterBitmap = true }, transform.matrix())
                        layer === active && input.liveStroke?.layerId == layer.id ->
                            drawStroking(nc, layer, paint, input.liveStroke!!, input.liveStrokeOnMask)
                        else -> Compositor.drawLayer(nc, layer, paint)
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
            var textShown = false
            fun drawLayersLive(layers: List<Layer>) = Compositor.drawTree(nc, layers, bounds, tight = false) { c, l ->
                drawLeaf(c, l)
                if (text != null && (text.layerId == l.id || (text.layerId == null && l === active))) textShown = true
            }
            val projection = vm.projection
            if (!projection.needed) {
                drawLayersLive(d.layers)
            } else {
                // GIMP's exact composite when nothing's being edited; while something is, the
                // exact composite of what's below the edited layer, and the rest drawn live.
                val editing = vm.editingLive
                val frame = projection.frame?.takeIf { it.doc === d && it.revision == rev }
                val below = projection.belowFor(d)
                val pixels = Paint().apply { isFilterBitmap = smooth }
                when {
                    !editing && frame != null -> {
                        frame.bitmap.setHasMipMap(smooth)
                        nc.drawBitmap(frame.bitmap, 0f, 0f, pixels)
                    }
                    below != null -> {
                        below.bitmap.setHasMipMap(smooth)
                        nc.drawBitmap(below.bitmap, 0f, 0f, pixels)
                        drawLayersLive(d.layers.subList(below.start, d.layers.size))
                    }
                    else -> drawLayersLive(d.layers)
                }
            }
            // Text being typed always shows, even if its layer is hidden (or a group is active).
            if (text != null && textBitmap != null && !textShown) {
                nc.drawBitmap(textBitmap, text.x.toFloat(), text.y.toFloat(), null)
            }
            nc.restoreToCount(iso)
            nc.restore()

            // ---- grids: the pixel grid when zoomed right in, and the optional thirds/squares grid
            val visible = RectF(0f, 0f, size.width, size.height).also { r ->
                Matrix().also { view.invert(it) }.mapRect(r)
                if (!r.intersect(0f, 0f, d.width.toFloat(), d.height.toFloat())) r.setEmpty()
            }
            if (s >= 8f && !visible.isEmpty) {
                val pg = Paint().apply { color = android.graphics.Color.argb(70, 128, 128, 128); strokeWidth = 0f }
                for (x in kotlin.math.floor(visible.left).toInt()..kotlin.math.ceil(visible.right).toInt())
                    nc.drawLine(x.toFloat(), visible.top, x.toFloat(), visible.bottom, pg)
                for (y in kotlin.math.floor(visible.top).toInt()..kotlin.math.ceil(visible.bottom).toInt())
                    nc.drawLine(visible.left, y.toFloat(), visible.right, y.toFloat(), pg)
            }
            when (vm.grid) {
                EditorViewModel.Grid.Thirds -> {
                    val g = Paint().apply { color = android.graphics.Color.argb(150, 255, 255, 255); strokeWidth = 1.2f * px }
                    val sh = Paint().apply { color = android.graphics.Color.argb(110, 0, 0, 0); strokeWidth = 3f * px }
                    for (k in 1..2) {
                        val x = d.width * k / 3f
                        val y = d.height * k / 3f
                        nc.drawLine(x, 0f, x, d.height.toFloat(), sh); nc.drawLine(x, 0f, x, d.height.toFloat(), g)
                        nc.drawLine(0f, y, d.width.toFloat(), y, sh); nc.drawLine(0f, y, d.width.toFloat(), y, g)
                    }
                }
                EditorViewModel.Grid.Squares -> {
                    val step = niceGridStep(maxOf(d.width, d.height) / 16f)
                    val g = Paint().apply { color = android.graphics.Color.argb(120, 255, 255, 255); strokeWidth = 1f * px }
                    var x = step
                    while (x < d.width) { nc.drawLine(x, 0f, x, d.height.toFloat(), g); x += step }
                    var y = step
                    while (y < d.height) { nc.drawLine(0f, y, d.width.toFloat(), y, g); y += step }
                }
                EditorViewModel.Grid.Off -> Unit
            }

            // ---- GIMP's guides (dashed blue lines across the image) and sample points (numbered crosses)
            if (d.gimp.guides.isNotEmpty() || d.gimp.samplePoints.isNotEmpty()) {
                val under = Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 1f * px }
                val guide = Paint().apply {
                    color = android.graphics.Color.rgb(0, 160, 255); strokeWidth = 1f * px
                    pathEffect = DashPathEffect(floatArrayOf(6 * px, 6 * px), 0f)
                }
                for (g in d.gimp.guides) {
                    val p = g.position.toFloat()
                    if (g.horizontal) { nc.drawLine(0f, p, d.width.toFloat(), p, under); nc.drawLine(0f, p, d.width.toFloat(), p, guide) }
                    else { nc.drawLine(p, 0f, p, d.height.toFloat(), under); nc.drawLine(p, 0f, p, d.height.toFloat(), guide) }
                }
                val mark = Paint().apply { color = android.graphics.Color.rgb(0, 160, 255); strokeWidth = 1.5f * px; isAntiAlias = true; textSize = 11f * px }
                d.gimp.samplePoints.forEachIndexed { i, sp ->
                    val cx = sp.x + 0.5f; val cy = sp.y + 0.5f; val r = 7f * px
                    nc.drawLine(cx - r, cy, cx - 2 * px, cy, mark); nc.drawLine(cx + 2 * px, cy, cx + r, cy, mark)
                    nc.drawLine(cx, cy - r, cx, cy - 2 * px, mark); nc.drawLine(cx, cy + 2 * px, cx, cy + r, mark)
                    nc.drawText("${i + 1}", cx + 3 * px, cy + 12 * px, mark)
                }
            }

            // ---- overlays
            val ink = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * px; color = android.graphics.Color.BLACK; isAntiAlias = true }
            val dash = Paint(ink).apply { color = android.graphics.Color.WHITE; pathEffect = DashPathEffect(floatArrayOf(5 * px, 5 * px), 0f) }
            val gold = Paint(ink).apply { color = Gako.Gold.toArgb(); strokeWidth = 2f * px }

            // Active layer's boundary when it isn't the canvas (GIMP's yellow dashes).
            if (active != null && !active.group && vm.session == null && (active.x != 0 || active.y != 0 || active.width != d.width || active.height != d.height) &&
                transform == null) {
                val r = RectF(active.bounds).apply { if (moveDelta != null) offset(moveDelta.x, moveDelta.y) }
                nc.drawRect(r, Paint(dash).apply { color = Gako.Gold.toArgb() })
            }

            d.selection?.let { sel ->
                nc.drawPath(sel.outline, ink)
                nc.drawPath(sel.outline, dash)
            }
            // The Selection Brush's stroke, tinted while it's being painted.
            input.selPath?.let { p ->
                @Suppress("UNUSED_VARIABLE") val sr = input.selStrokeRevision
                nc.drawPath(p, Paint().apply {
                    color = android.graphics.Color.argb(110, 210, 168, 78)
                    style = Paint.Style.STROKE
                    strokeWidth = vm.options.size.coerceAtLeast(1f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                })
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
                c.forEach { handle(nc, it.x, it.y, 9 * px, px) }
                if (!t.perspective) {
                    val knob = t.knob(handlePx * px * 2.2f)
                    nc.drawLine((c[0].x + c[1].x) / 2, (c[0].y + c[1].y) / 2, knob.x, knob.y, gold)
                    handle(nc, knob.x, knob.y, 10 * px, px, round = true)
                }
            }
            // Symmetry axes while a mirroring paint tool is in use.
            val sym = vm.options.symmetry
            if (tool.paints && tool != Tool.Smudge && !tool.cloning && sym != io.github.capstasher.gako.tools.Symmetry.Off) {
                val axis = Paint(dash).apply { color = Gako.Gold.toArgb(); alpha = 170 }
                if (sym.vertical) nc.drawLine(d.width / 2f, 0f, d.width / 2f, d.height.toFloat(), axis)
                if (sym.horizontal) nc.drawLine(0f, d.height / 2f, d.width.toFloat(), d.height / 2f, axis)
            }
            // The other paths (faintly) and the other strokes of the one being edited.
            if (vm.session == null && d.paths.isNotEmpty()) {
                val editingPaths = tool == Tool.Paths
                d.paths.forEachIndexed { pi, vp ->
                    if (pi != vm.pathIndex && !editingPaths) return@forEachIndexed
                    val others = android.graphics.Path()
                    vp.strokes.forEachIndexed { si, st -> if (pi != vm.pathIndex || si != vm.strokeIndex) st.addTo(others) }
                    if (others.isEmpty) return@forEachIndexed
                    nc.drawPath(others, Paint(ink).apply { alpha = if (pi == vm.pathIndex) 160 else 120 })
                    nc.drawPath(others, Paint(gold).apply { alpha = if (pi == vm.pathIndex && editingPaths) 200 else 150 })
                }
            }
            // The Paths tool's path: always shown while it's the tool, faintly otherwise.
            val pts = input.path.points
            if (pts.isNotEmpty() && vm.session == null) {
                val editing = tool == Tool.Paths
                val p = input.path.toPath()
                nc.drawPath(p, ink)
                nc.drawPath(p, Paint(gold).apply { if (!editing) alpha = 120 })
                if (editing) {
                    val handlePaint = Paint(gold).apply { strokeWidth = 1.2f * px }
                    pts.forEachIndexed { i, pt ->
                        if (pt.smooth) {
                            nc.drawLine(pt.inX, pt.inY, pt.outX, pt.outY, handlePaint)
                            handle(nc, pt.inX, pt.inY, 5 * px, px, round = true)
                            handle(nc, pt.outX, pt.outY, 5 * px, px, round = true)
                        }
                        handle(nc, pt.x, pt.y, if (i == 0) 8 * px else 6 * px, px, round = i == 0 && !input.path.closed)
                    }
                }
            }
            // Foreground Select: outside the outline dimmed, the marking strokes tinted.
            if (tool == Tool.ForegroundSelect) input.fgOutline?.let { o ->
                @Suppress("UNUSED_VARIABLE") val fr = input.fgRevision
                val outside = android.graphics.Path(o).apply { fillType = android.graphics.Path.FillType.INVERSE_WINDING }
                nc.save()
                nc.clipRect(0f, 0f, d.width.toFloat(), d.height.toFloat())
                nc.drawPath(outside, Paint().apply { color = android.graphics.Color.argb(120, 0, 0, 0) })
                nc.restore()
                nc.drawPath(o, ink)
                nc.drawPath(o, dash)
                val mark = Paint().apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                for (s in input.fgStrokes) nc.drawPath(s.path, mark.apply {
                    strokeWidth = s.width
                    color = if (s.foreground) android.graphics.Color.argb(150, 210, 168, 78) else android.graphics.Color.argb(150, 60, 90, 200)
                })
            }
            // The Measure tool's line.
            if (tool == Tool.Measure) input.measure?.let { (a, b) ->
                nc.drawLine(a.x, a.y, b.x, b.y, ink)
                nc.drawLine(a.x, a.y, b.x, b.y, gold)
                handle(nc, a.x, a.y, 6 * px, px, round = true)
                handle(nc, b.x, b.y, 6 * px, px, round = true)
            }
            // The Warp brush under the finger.
            if (tool == Tool.Warp) input.warpAt?.let { p ->
                val r = vm.options.size / 2f
                nc.drawCircle(p.x, p.y, r, ink)
                nc.drawCircle(p.x, p.y, r, Paint(gold).apply { strokeWidth = 1.2f * px })
            }
            if (tool.cloning) input.cloneSource?.let { src ->
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

            // Brush outlines in screen space: the size preview at the centre, and under a hovering pen.
            if (tool.paints || tool == Tool.SelectBrush || tool == Tool.Warp) {
                val r = vm.options.size / 2f * s
                val ring = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
                fun outline(x: Float, y: Float) {
                    nc.drawCircle(x, y, r, ring.apply { color = android.graphics.Color.BLACK; strokeWidth = 3f })
                    nc.drawCircle(x, y, r, ring.apply { color = android.graphics.Color.WHITE; strokeWidth = 1.2f })
                }
                if (vm.sizePreview) outline(size.width / 2, size.height / 2)
                vm.hover?.let { outline(it.x, it.y) }
            }
        }
    }
        // Zoom level; tap to switch between fitting the screen and 100%.
        if (viewSize.width > 0) {
            val w = viewSize.width.toFloat()
            val h = viewSize.height.toFloat()
            val pct = (effective(w, h) * 100).roundToInt()
            val turned = if (angle != 0f) " · ${angle.roundToInt()}°" else ""
            androidx.compose.material3.Text(
                (if (scale == 0f) "Fit · $pct%" else "$pct%") + turned,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = Gako.Parchment,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(10.dp)
                    .background(Gako.Ink.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable {
                        when {
                            angle != 0f -> { angle = 0f; pan = Offset.Zero }
                            scale == 0f -> { scale = 1f; pan = Offset.Zero }
                            else -> { scale = 0f; pan = Offset.Zero }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        // GIMP's Quick Mask button: on while the selection is being painted; tap to turn it off.
        if (vm.quickMaskOn) {
            androidx.compose.material3.Text(
                "Quick Mask · tap to end",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = Gako.Parchment,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopStart).padding(10.dp)
                    .background(androidx.compose.ui.graphics.Color(0xCC8B1A1A), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable { vm.toggleQuickMask() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The layer being painted on: its pre-stroke copy plus the stroke's tiles, so the GPU only re-uploads
 * the tiles that changed. When the stroke is on the mask, the same goes for the mask.
 */
private fun drawStroking(nc: android.graphics.Canvas, layer: Layer, paint: Paint, s: io.github.capstasher.gako.tools.LiveView, onMask: Boolean) {
    val bounds = RectF(layer.bounds)
    val copy = Paint().apply { blendMode = BlendMode.SRC; isFilterBitmap = paint.isFilterBitmap }
    fun drawPieces() {
        nc.drawBitmap(s.viewBase, layer.x.toFloat(), layer.y.toFloat(), copy)
        for ((i, tile) in s.viewTileMap) {
            val r = s.viewTileRect(i)
            nc.drawBitmap(tile, (layer.x + r.left).toFloat(), (layer.y + r.top).toFloat(), copy)
        }
    }
    val outer = nc.saveLayer(bounds, paint)
    if (onMask) {
        nc.drawBitmap(layer.bitmap, layer.x.toFloat(), layer.y.toFloat(), copy)
        if (layer.maskEnabled) {
            val m = nc.saveLayer(bounds, Compositor.maskPaint)
            drawPieces()
            nc.restoreToCount(m)
        }
    } else {
        drawPieces()
        layer.activeMask?.let { nc.drawBitmap(it, layer.x.toFloat(), layer.y.toFloat(), Compositor.maskPaint) }
    }
    nc.restoreToCount(outer)
}

/** The active layer with its filter preview (of the layer or of its mask), through the selection if there is one. */
private fun drawPreview(nc: android.graphics.Canvas, layer: Layer, preview: Bitmap, d: DocState, paint: Paint, onMask: Boolean) {
    if (onMask) {
        Compositor.drawLayer(nc, layer, paint, mask = preview)
        return
    }
    val sel = d.selection
    if (sel == null) {
        Compositor.drawLayer(nc, layer, paint, content = preview)
        return
    }
    val dst = RectF(layer.bounds)
    val outer = nc.saveLayer(dst, paint)
    nc.drawBitmap(layer.bitmap, layer.x.toFloat(), layer.y.toFloat(), null)
    val inner = nc.saveLayer(dst, null)
    nc.drawBitmap(preview, null, dst, Paint().apply { isFilterBitmap = true; blendMode = BlendMode.SRC })
    nc.drawBitmap(sel.mask, 0f, 0f, Paint().apply { blendMode = BlendMode.DST_IN })
    nc.restoreToCount(inner)
    layer.activeMask?.let { nc.drawBitmap(it, layer.x.toFloat(), layer.y.toFloat(), Compositor.maskPaint) }
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

private const val LONG_PRESS_MS = 550L
private const val MULTI_TAP_MS = 280L

/** 1, 2, 5, 10, 20, 50… pixels: the round number nearest [raw]. */
private fun niceGridStep(raw: Float): Float {
    var m = 1f
    while (m * 10 <= raw) m *= 10
    return listOf(1f, 2f, 5f, 10f).map { it * m }.minByOrNull { kotlin.math.abs(it - raw) } ?: raw
}

/** [v] turned by [deg] degrees (clockwise on screen, like Canvas.rotate). */
private fun rotate(v: Offset, deg: Float): Offset {
    if (deg == 0f) return v
    val r = Math.toRadians(deg.toDouble())
    val c = kotlin.math.cos(r).toFloat()
    val s = kotlin.math.sin(r).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

/** Within 5° of a right angle counts as straight, so the view is easy to put back. */
private fun snapAngle(a: Float): Float {
    val norm = ((a % 360f) + 540f) % 360f - 180f
    for (k in listOf(-180f, -90f, 0f, 90f, 180f)) if (kotlin.math.abs(norm - k) < 5f) return if (k == -180f) 180f else k
    return norm
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
