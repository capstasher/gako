package io.github.capstasher.gako.editor

import android.app.ActivityManager
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.DocOps
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.doc.LayerMode
import io.github.capstasher.gako.doc.OraFile
import io.github.capstasher.gako.doc.Selections
import io.github.capstasher.gako.doc.Step
import io.github.capstasher.gako.doc.Tree
import io.github.capstasher.gako.doc.VectorPath
import io.github.capstasher.gako.engine.Engine
import io.github.capstasher.gako.engine.OpDescription
import io.github.capstasher.gako.engine.OpInfo
import io.github.capstasher.gako.engine.Param
import io.github.capstasher.gako.export.ExportFormat
import io.github.capstasher.gako.export.ExportOptions
import io.github.capstasher.gako.export.Exporter

import io.github.capstasher.gako.tools.Tool
import io.github.capstasher.gako.tools.ToolOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface EngineState {
        data object Starting : EngineState
        data class Ready(val operations: List<OpInfo>) : EngineState
        data class Failed(val message: String) : EngineState
    }

    data class Busy(val label: String, val progress: Float? = null, val cancellable: Boolean = false)

    /** A downscaled copy of a layer used for previews, and its scale relative to the layer. */
    class Proxy(val bitmap: Bitmap, val scale: Double) {
        val pixels: Double get() = bitmap.width.toDouble() * bitmap.height
    }

    /**
     * An operation being tuned on the active layer. Previews run on a quick [draft] first and, if
     * the filter is fast enough, a sharper [fine] copy; heavy filters stay on the draft.
     */
    class FilterSession(
        val op: OpDescription,
        values: Map<String, Any?>,
        val layerId: Long,
        val draft: Proxy,
        val fine: Proxy,
        /** Decided when the filter opened: it works on the layer's mask, not its pixels. */
        val onMask: Boolean = false,
    ) {
        var values by mutableStateOf(values)
        var working by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        internal var generation = 0
        internal var fineMs = -1L

        /** The x/y pair a tap on the canvas should set, if the op has one (lens flare centre, etc.). */
        val point: Pair<Param.Number, Param.Number>? = run {
            val nums = op.params.filterIsInstance<Param.Number>()
                .filter { it.unit == "relative-coordinate" || it.unit == "pixel-coordinate" }
            val x = nums.firstOrNull { it.name.endsWith("x") || it.name.contains("-x") }
            val y = nums.firstOrNull { it.name.endsWith("y") || it.name.contains("-y") }
            if (x != null && y != null && x.unit == y.unit) x to y else null
        }
    }

    var engine by mutableStateOf<EngineState>(EngineState.Starting); private set

    /** The open image, or null. Replaced (never mutated) except for in-place painting. */
    var doc by mutableStateOf<DocState?>(null); internal set
    /** Bumped whenever pixels change in place, so the canvas redraws. */
    var revision by mutableIntStateOf(0); private set
    /** Where pixels changed in place, so the canvas's exact composite is refreshed just there. */
    internal val pixelChanges = PixelChanges()
    /** GIMP's exact composite for the canvas, kept up to date in the background (see [Projection]). */
    val projection = Projection(pixelChanges)

    /** Logs a patch's area (it's in its layer's coordinates) for [projection]. */
    private fun logPatch(step: Step.Patch) {
        val l = doc?.layer(step.layerId) ?: return
        pixelChanges.add(l.id, Rect(step.rect).apply { offset(l.x, l.y) })
    }

    /** Whether a layer is changing live right now (a stroke, a move, a filter preview, text…). */
    val editingLive: Boolean get() {
        val d = doc ?: return false
        return (preview != null && !comparing) || input.moveDelta != null || input.liveStroke != null ||
            input.gradient != null || text != null ||
            (tool == io.github.capstasher.gako.tools.Tool.Transform && session == null && input.transformFor(d)?.changed == true)
    }
    /** The filter/curves preview of the active layer (a downscaled copy), or null. */
    var preview by mutableStateOf<Bitmap?>(null); internal set
    /**
     * Where a grown filter preview sits: its offset from the layer's corner in proxy pixels, and the
     * proxy's scale. Null when the preview covers exactly the layer.
     */
    var previewPlacement by mutableStateOf<Triple<Int, Int, Double>?>(null); private set
    var documentName by mutableStateOf("Untitled"); private set
    /** EXIF of the photo this image was opened from, for exports. */
    var metadata by mutableStateOf<Map<String, String>?>(null); private set
    var dirty by mutableStateOf(false); private set
    var busy by mutableStateOf<Busy?>(null); internal set
    var session by mutableStateOf<FilterSession?>(null); private set
    var comparing by mutableStateOf(false)
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var undoLabel by mutableStateOf<String?>(null); private set
    var redoLabel by mutableStateOf<String?>(null); private set

    // ---- tools
    private val toolPrefs = app.getSharedPreferences("tools", 0)
    var tool by mutableStateOf(runCatching { Tool.valueOf(toolPrefs.getString("tool", "Navigate")!!) }.getOrDefault(Tool.Navigate))
        private set
    var options by mutableStateOf(loadOptions())
    var fg by mutableIntStateOf(toolPrefs.getInt("fg", Color.BLACK))
    var bg by mutableIntStateOf(toolPrefs.getInt("bg", Color.WHITE))
    val input = ToolInput(this)
    var text by mutableStateOf<TextSession?>(null); internal set
    var curves by mutableStateOf<CurvesSession?>(null); internal set
    var dialog by mutableStateOf<Dialog?>(null)

    enum class Grid(val label: String) { Off("Grid: Off"), Thirds("Grid: Thirds"), Squares("Grid: Squares") }
    /** An overlay grid for lining things up (not part of the image). */
    var grid by mutableStateOf(runCatching { Grid.valueOf(app.getSharedPreferences("view", 0).getString("grid", "Off")!!) }.getOrDefault(Grid.Off))
        private set

    fun cycleGrid() {
        grid = Grid.entries[(grid.ordinal + 1) % Grid.entries.size]
        getApplication<Application>().getSharedPreferences("view", 0).edit().putString("grid", grid.name).apply()
    }

    /** Tools, filters and curves work on the active layer's mask instead of its pixels. */
    var editMask by mutableStateOf(false)
        private set
    val editingMask: Boolean get() = editMask && doc?.active?.mask != null

    /** Switches tools between the layer's pixels and its mask (not while a job uses one of them). */
    fun chooseMaskEditing(onMask: Boolean) {
        if (editMask == onMask) return
        if (blockedByBusy() || session != null || curves != null) return
        input.reset()
        editMask = onMask
    }

    /** What edits go to: the active layer, or its mask dressed up as a layer (alpha locked, opaque grey). */
    internal fun targetOf(d: DocState): Layer? {
        val a = d.active ?: return null
        if (a.group) return null            // groups have no pixels of their own
        val m = a.mask
        return if (editMask && m != null) a.copy(bitmap = m, lockAlpha = true, text = null, mode = LayerMode.Normal,
            opacity = 1f, mask = null) else a
    }

    /** The active layer with [b] put back where [targetOf] took it from. */
    internal fun withTarget(l: Layer, b: Bitmap): Layer =
        if (editMask && l.mask != null) l.copy(mask = b) else l.copy(bitmap = b, text = null)

    /** Masks are grey: paint with a colour's brightness. */
    internal fun paintColor(c: Int): Int {
        if (!editingMask && doc?.active?.quickMask != true) return c
        val l = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)).roundToInt().coerceIn(0, 255)
        return Color.rgb(l, l, l)
    }

    private val recentsStore = Recents(app)
    /** Recently opened or saved images, newest first (for the start screen). */
    var recents by mutableStateOf(recentsStore.list()); private set

    private fun remember(uri: Uri, name: String, d: DocState) {
        viewModelScope.launch(Dispatchers.IO) {
            recentsStore.add(uri, name, d)
            val list = recentsStore.list()
            withContext(Dispatchers.Main) { recents = list }
        }
    }

    fun forgetRecent(uri: Uri) {
        recentsStore.remove(uri)
        recents = recentsStore.list()
    }

    /** Opens a recent item; if it's gone or no longer readable, drops it from the list. */
    fun openRecent(item: Recents.Item) {
        val readable = runCatching { resolver.openInputStream(item.uri)?.close(); true }.getOrDefault(false)
        if (!readable) {
            say("“${item.name}” can't be opened any more")
            forgetRecent(item.uri)
            return
        }
        open(item.uri)
    }

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    private var previewJob: Job? = null
    private val resolver get() = getApplication<Application>().contentResolver

    /** How much image memory undo may hold, from the device's RAM. */
    private val undoBudget: Long = run {
        val mi = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        (mi.totalMem / 6).coerceIn(256L shl 20, 1536L shl 20)
    }

    val operations: List<OpInfo> get() = (engine as? EngineState.Ready)?.operations.orEmpty()
    val hasImage get() = doc != null

    init {
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { input.path.points to input.path.closed }.collect { syncPath() }
        }
        viewModelScope.launch {
            // A new change cancels the refresh under way and starts again.
            androidx.compose.runtime.snapshotFlow { Triple(doc, revision, editingLive) }
                .collectLatest { (d, rev, editing) -> projection.refresh(d, rev, editing) }
        }
        viewModelScope.launch {
            val error = try {
                Engine.start(app)
            } catch (t: Throwable) {
                t.message ?: t.toString()
            }
            engine = if (error != null) EngineState.Failed(error)
                     else EngineState.Ready(Engine.operations())
        }
        restoreRecovery()
    }

    /**
     * Where an image pixel is on the canvas right now (canvas pixels, from its top-left), as the
     * canvas last laid it out. For tests and tools that need to touch a spot on the image.
     */
    @Volatile var docToView: (Float, Float) -> androidx.compose.ui.geometry.Offset = { x, y -> androidx.compose.ui.geometry.Offset(x, y) }
        internal set

    internal fun say(message: String) {
        messageChannel.trySend(message)
    }

    /** Anything modal (filter, text, curves, a tool mid-edit) that should end before another action. */
    private fun endInteractions() {
        cancelSession()
        commitText()
        cancelCurves()
        input.reset()
        exportSession = null
    }

    // ---------------------------------------------------------------- keyboard

    /** The Size slider is open: the canvas shows the brush's size at its centre. */
    var sizePreview by mutableStateOf(false)
    /** Where a hovering pen is (screen pixels), for a brush outline. */
    var hover by mutableStateOf<androidx.compose.ui.geometry.Offset?>(null)

    /** Once a pen has touched the canvas, fingers navigate and only the pen uses tools. */
    var stylusSeen by mutableStateOf(false)

    /** GIMP's keyboard shortcuts, for tablets, Chromebooks and DeX. Returns true if handled. */
    fun onKey(code: Int, ctrl: Boolean, shift: Boolean): Boolean {
        val modal = text != null || dialog != null || confirm != null || exportSession != null
        if (modal) return false
        if (ctrl) {
            when (code) {
                android.view.KeyEvent.KEYCODE_Z -> if (shift) redo() else undo()
                android.view.KeyEvent.KEYCODE_Y -> redo()
                android.view.KeyEvent.KEYCODE_S -> if (shift) startExport(project = true) else save()
                android.view.KeyEvent.KEYCODE_E -> startExport()
                android.view.KeyEvent.KEYCODE_C -> copy(merged = shift)
                android.view.KeyEvent.KEYCODE_X -> cut()
                android.view.KeyEvent.KEYCODE_V -> paste(asNewImage = shift)
                android.view.KeyEvent.KEYCODE_A -> if (shift) selectNone() else selectAll()
                android.view.KeyEvent.KEYCODE_I -> invertSelection()
                android.view.KeyEvent.KEYCODE_F -> repeatLastFilter()
                android.view.KeyEvent.KEYCODE_N -> if (shift) newLayer() else dialog = Dialog.NewImage
                android.view.KeyEvent.KEYCODE_D -> duplicateLayer()
                android.view.KeyEvent.KEYCODE_J -> layerViaSelection(cut = shift)
                android.view.KeyEvent.KEYCODE_M -> startCurves()
                android.view.KeyEvent.KEYCODE_L -> startCurves(levels = true)
                else -> return false
            }
            return true
        }
        if (session != null || curves != null) {
            return when (code) {
                android.view.KeyEvent.KEYCODE_ENTER -> { if (session != null) applySession() else applyCurves(); true }
                android.view.KeyEvent.KEYCODE_ESCAPE -> { cancelSession(); cancelCurves(); true }
                else -> false
            }
        }
        if (code == android.view.KeyEvent.KEYCODE_Q && shift) { toggleQuickMask(); return true }   // GIMP's Shift+Q
        val t: Tool? = when (code) {
            android.view.KeyEvent.KEYCODE_P -> Tool.Paintbrush
            android.view.KeyEvent.KEYCODE_N -> Tool.Pencil
            android.view.KeyEvent.KEYCODE_A -> Tool.Airbrush
            android.view.KeyEvent.KEYCODE_E -> if (shift) Tool.Eraser else Tool.EllipseSelect
            android.view.KeyEvent.KEYCODE_C -> if (shift) Tool.Crop else Tool.Clone
            android.view.KeyEvent.KEYCODE_S -> Tool.Smudge
            android.view.KeyEvent.KEYCODE_D -> if (shift) Tool.DodgeBurn else null
            android.view.KeyEvent.KEYCODE_B -> if (shift) Tool.BucketFill else Tool.Paths
            android.view.KeyEvent.KEYCODE_G -> Tool.Gradient
            android.view.KeyEvent.KEYCODE_T -> if (shift) Tool.Transform else Tool.Text
            android.view.KeyEvent.KEYCODE_O -> if (shift) Tool.ColorSelect else Tool.ColorPicker
            android.view.KeyEvent.KEYCODE_M -> if (shift) Tool.Measure else Tool.Move
            android.view.KeyEvent.KEYCODE_W -> Tool.Warp
            android.view.KeyEvent.KEYCODE_R -> Tool.RectSelect
            android.view.KeyEvent.KEYCODE_F -> Tool.FreeSelect
            android.view.KeyEvent.KEYCODE_U -> Tool.FuzzySelect
            android.view.KeyEvent.KEYCODE_H -> if (shift) Tool.Heal else Tool.Navigate
            else -> null
        }
        if (t != null) { selectTool(t); return true }
        return when (code) {
            android.view.KeyEvent.KEYCODE_X -> { swapColors(); true }
            android.view.KeyEvent.KEYCODE_FORWARD_DEL, android.view.KeyEvent.KEYCODE_DEL -> { clear(); true }
            android.view.KeyEvent.KEYCODE_LEFT_BRACKET -> { updateOptions(options.copy(size = (options.size / 1.2f).coerceAtLeast(1f))); true }
            android.view.KeyEvent.KEYCODE_RIGHT_BRACKET -> { updateOptions(options.copy(size = (options.size * 1.2f).coerceAtMost(1000f))); true }
            android.view.KeyEvent.KEYCODE_ENTER -> when (tool) {
                Tool.Crop -> { input.applyCrop(); true }
                Tool.Transform -> { input.applyTransform(); true }
                else -> false
            }
            android.view.KeyEvent.KEYCODE_ESCAPE -> { input.reset(); true }
            else -> false
        }
    }

    // ---------------------------------------------------------------- tools

    fun selectTool(t: Tool) {
        if (t == tool) return
        input.reset()
        if (text != null) commitText()
        tool = t
        toolPrefs.edit().putString("tool", t.name).apply()
        if (t == Tool.Crop) input.startCrop()
        if (t == Tool.Transform) input.startTransform()
    }

    val presets = io.github.capstasher.gako.tools.Presets(app)
    var presetList by mutableStateOf(presets.all()); private set

    fun usePreset(p: io.github.capstasher.gako.tools.BrushPreset) {
        selectTool(p.tool)
        updateOptions(p.applyTo(options))
        say("Brush: ${p.name}")
    }

    fun savePreset(name: String) {
        val o = options
        val t = if (tool.paints) tool else Tool.Paintbrush
        presets.save(io.github.capstasher.gako.tools.BrushPreset(name.trim().ifEmpty { "My brush" }, t, o.size, o.opacity,
            o.hardness, o.brushShape, o.brushAngle, o.smoothing))
        presetList = presets.all()
        say("Saved brush “${name.trim()}”")
    }

    fun deletePreset(name: String) {
        presets.delete(name)
        presetList = presets.all()
    }

    fun updateOptions(o: ToolOptions) {
        options = o
        toolPrefs.edit()
            .putFloat("size", o.size).putFloat("opacity", o.opacity).putFloat("hardness", o.hardness)
            .putInt("threshold", o.threshold).putFloat("feather", o.feather).putBoolean("sampleMerged", o.sampleMerged)
            .putString("brushShape", o.brushShape.name).putFloat("brushAngle", o.brushAngle).putFloat("smoothing", o.smoothing)
            .putString("symmetry", o.symmetry.name).putString("warpMode", o.warpMode.name).putFloat("strength", o.strength)
            .apply()
    }

    private fun loadOptions() = ToolOptions(
        size = toolPrefs.getFloat("size", 24f),
        opacity = toolPrefs.getFloat("opacity", 1f),
        hardness = toolPrefs.getFloat("hardness", 0.75f),
        threshold = toolPrefs.getInt("threshold", 15),
        feather = toolPrefs.getFloat("feather", 0f),
        sampleMerged = toolPrefs.getBoolean("sampleMerged", false),
        brushShape = runCatching { io.github.capstasher.gako.tools.BrushShape.valueOf(toolPrefs.getString("brushShape", "Round")!!) }
            .getOrDefault(io.github.capstasher.gako.tools.BrushShape.Round),
        brushAngle = toolPrefs.getFloat("brushAngle", 30f),
        smoothing = toolPrefs.getFloat("smoothing", 0f),
        symmetry = runCatching { io.github.capstasher.gako.tools.Symmetry.valueOf(toolPrefs.getString("symmetry", "Off")!!) }
            .getOrDefault(io.github.capstasher.gako.tools.Symmetry.Off),
        warpMode = runCatching { io.github.capstasher.gako.tools.WarpMode.valueOf(toolPrefs.getString("warpMode", "Move")!!) }
            .getOrDefault(io.github.capstasher.gako.tools.WarpMode.Move),
        strength = toolPrefs.getFloat("strength", 0.5f),
    )

    fun setForeground(c: Int) {
        fg = c
        toolPrefs.edit().putInt("fg", c).apply()
    }

    fun setBackground(c: Int) {
        bg = c
        toolPrefs.edit().putInt("bg", c).apply()
    }

    /** Long-press eyedropper: the visible colour at a canvas point becomes the FG colour. */
    fun pickAt(x: Float, y: Float) {
        val d = doc ?: return
        val xi = x.toInt()
        val yi = y.toInt()
        if (xi !in 0 until d.width || yi !in 0 until d.height) return
        val c = Compositor.sample(d, xi, yi)
        if (Color.alpha(c) == 0) return
        setForeground(c or (0xFF shl 24))
        say(String.format(java.util.Locale.US, "Picked #%06X", c and 0xFFFFFF))
    }

    fun swapColors() {
        val f = fg
        setForeground(bg)
        setBackground(f)
    }

    fun resetColors() {
        setForeground(Color.BLACK)
        setBackground(Color.WHITE)
    }

    // ---------------------------------------------------------------- history

    private var autosaveJob: Job? = null

    internal fun record(step: Step) {
        if (step is Step.Patch) logPatch(step)
        // Also keep a recovery copy a little while after the last edit, in case the app crashes.
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_IDLE_MS)
            if (busy == null) autosave()
        }
        undoStack.addLast(step)
        redoStack.clear()
        var total = undoStack.sumOf { it.cost() }
        while (undoStack.size > 1 && total > undoBudget) total -= undoStack.removeFirst().cost()
        dirty = true
        updateHistoryFlags()
    }

    /** Replaces the document and records the change as one undo step. */
    internal fun change(label: String, after: DocState) {
        val before = doc ?: return
        if (after == before) return
        record(Step.Snapshot(label, before, after))
        doc = after
        reloadPath(before, after)
    }

    /**
     * Undo/redo of a snapshot: back to [target], except that a step which didn't touch the paths
     * leaves them as they are now (the Paths tool edits them without undo steps of their own).
     */
    private fun restore(target: DocState, other: DocState) {
        val cur = doc
        val next = if (cur != null && target.paths === other.paths && cur.paths !== target.paths) target.copy(paths = cur.paths) else target
        doc = next
        reloadPath(cur, next)
    }

    /** Changes the document without an undo step (e.g. picking the active layer). */
    internal fun silently(after: DocState) {
        doc = after
    }

    internal fun pixelsChanged() {
        revision++
    }

    private fun updateHistoryFlags() {
        historyVersion++
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        undoLabel = undoStack.lastOrNull()?.label
        redoLabel = redoStack.lastOrNull()?.label
    }

    /** True (and says why) if a long job is running, so taps never vanish silently. */
    internal fun blockedByBusy(): Boolean {
        val b = busy ?: return false
        say(if (b.cancellable) "Still working on ${b.label}. Tap ✕ to stop it." else "Still ${b.label.lowercase()}…")
        return true
    }

    fun undo() {
        if (blockedByBusy()) return
        if (input.hasPendingEdit() || input.stroking) { input.reset(); return }
        if (session != null || text != null || curves != null) return
        var step = undoStack.removeLastOrNull() ?: return
        when (step) {
            is Step.Snapshot -> {
                // Paths drawn since this step (which moved or changed paths) go, but come back with Redo.
                val cur = doc
                if (cur != null && step.before.paths !== step.after.paths && cur.paths !== step.after.paths)
                    step = Step.Snapshot(step.label, step.before, step.after.copy(paths = cur.paths))
                restore(step.before, step.after)
            }
            is Step.Patch -> {
                doc?.layer(step.layerId)?.let { l -> step.target(l)?.let { Bitmaps.put(it, step.before, step.rect.left, step.rect.top) } }
                logPatch(step)
            }
        }
        redoStack.addLast(step)
        revision++
        dirty = true
        updateHistoryFlags()
        input.syncAfterHistory()
    }

    fun redo() {
        if (blockedByBusy()) return
        if (input.stroking) return
        if (session != null || text != null || curves != null) return
        input.reset()
        var step = redoStack.removeLastOrNull() ?: return
        when (step) {
            is Step.Snapshot -> {
                val cur = doc
                if (cur != null && step.before.paths !== step.after.paths && cur.paths !== step.before.paths)
                    step = Step.Snapshot(step.label, step.before.copy(paths = cur.paths), step.after)
                restore(step.after, step.before)
            }
            is Step.Patch -> {
                doc?.layer(step.layerId)?.let { l -> step.target(l)?.let { Bitmaps.put(it, step.after, step.rect.left, step.rect.top) } }
                logPatch(step)
            }
        }
        undoStack.addLast(step)
        revision++
        dirty = true
        updateHistoryFlags()
        input.syncAfterHistory()
    }

    /** Undo History: what each step was, oldest first, and how many of them are currently done. */
    private var historyVersion by mutableIntStateOf(0)
    val historyLabels: List<String> get() = historyVersion.let { undoStack.map { it.label } + redoStack.reversed().map { it.label } }
    val historyPosition: Int get() = historyVersion.let { undoStack.size }

    /** Undo or redo until exactly [position] steps are done. */
    fun jumpToHistory(position: Int) {
        if (busy != null) return
        endInteractions()
        while (undoStack.size > position) {
            val before = undoStack.size
            undo()
            if (undoStack.size == before) break
        }
        while (undoStack.size < position) {
            val before = undoStack.size
            redo()
            if (undoStack.size == before) break
        }
    }

    private fun resetHistory() {
        undoStack.clear()
        redoStack.clear()
        updateHistoryFlags()
    }

    /** Runs [block] off the main thread with a busy pill, then records its result. */
    internal fun docJob(label: String, block: suspend (DocState) -> DocState?) {
        val d = doc ?: run { say("Open an image first"); return }
        if (blockedByBusy()) return
        endInteractions()
        viewModelScope.launch {
            busy = Busy(label)
            try {
                val out = withContext(Dispatchers.Default) { block(d) }
                when {
                    out == null -> Unit
                    // Bigger than GAKO handles (and than Android will draw: ~100 MB per bitmap).
                    out.width.toLong() * out.height > MAX_PIXELS || out.layers.any { !it.group && it.width.toLong() * it.height > MAX_PIXELS } ->
                        say("$label would make the image or a layer bigger than ${MAX_PIXELS / 1_000_000} megapixels")
                    doc === d -> change(label, out)
                    else -> say("$label wasn't applied: the image changed meanwhile")
                }
            } catch (t: OutOfMemoryError) {
                say("$label: not enough memory")
            } catch (t: Throwable) {
                say("$label failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    /**
     * Edits the active layer's pixels in place inside [canvasRect] (default: the selection, or the
     * whole layer) and records just that patch. [edit] gets the layer and the selection in layer space.
     */
    internal fun editActiveLayer(label: String, canvasRect: Rect? = null, edit: (Layer, Bitmap?) -> Unit): Boolean {
        val d = doc ?: return false
        if (refuseGroup(d)) return false
        val layer = targetOf(d) ?: return false
        val sel = Compositor.selectionFor(layer, d.selection)
        val r = DocOps.layerRect(layer, canvasRect ?: d.selection?.bounds)
        if (r.isEmpty) { say("Nothing to change there on “${layer.name}”"); return false }
        val before = Bitmaps.crop(layer.bitmap, r)
        edit(layer, sel)
        val patch = Step.Patch(label, layer.id, r, before, Bitmaps.crop(layer.bitmap, r))
        record(if (editingMask) patch.forMask() else patch)
        revision++
        return true
    }

    // ---------------------------------------------------------------- files

    private fun newDocument(state: DocState, name: String, keepRecovery: Boolean = false) {
        if (!keepRecovery) clearRecovery()
        filterBrush = null
        pathIndex = 0
        strokeIndex = 0
        pendingPathName = null
        input.path.load(state.paths.firstOrNull()?.strokes?.firstOrNull())
        metadata = null
        projectUri = null
        projectFormat = null
        endInteractions()
        resetHistory()
        doc = state.copy(layers = Tree.reveal(state.layers, state.activeId))
        preview = null
        previewPlacement = null
        documentName = name
        dirty = false
        revision++
        if (tool == Tool.Crop) input.startCrop()
        if (tool == Tool.Transform) input.startTransform()
    }

    /** A question before throwing away unsaved work. */
    class Confirm(val title: String, val message: String, val action: String, val run: () -> Unit)
    var confirm by mutableStateOf<Confirm?>(null)

    /** Runs [run] now, or after asking if it would replace an image with unsaved changes. */
    private fun guardDiscard(what: String, run: () -> Unit) {
        if (doc != null && dirty) {
            confirm = Confirm("Discard changes?", "“$documentName” has unsaved changes. $what replaces it.", "Discard") {
                confirm = null
                run()
            }
        } else run()
    }

    fun newImage(w: Int, h: Int, fill: Int?) = guardDiscard("A new image") { newImageNow(w, h, fill) }

    /** Without asking about unsaved changes (the New dialog asks first; tests call this). */
    internal fun newImageNow(w: Int, h: Int, fill: Int?) {
        if (blockedByBusy()) return
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) { say("That size isn't possible (max ${MAX_PIXELS / 1_000_000} MP)"); return }
        newDocument(DocOps.newImage(w, h, fill), "Untitled")
    }

    fun open(uri: Uri, asLayers: Boolean = false) {
        if (asLayers && doc != null) openNow(uri, true) else guardDiscard("Opening another image") { openNow(uri, false) }
    }

    private fun openNow(uri: Uri, asLayers: Boolean) {
        if (blockedByBusy()) return
        endInteractions()
        busy = Busy("Opening")
        viewModelScope.launch {
            try {
                val name = displayName(uri) ?: "Untitled"
                val (loaded, loadNote) = withContext(Dispatchers.IO) { load(uri, name) }
                // GIMP 3's layer effects: drawn into their layers, as GIMP shows them.
                val (state, note) = if (loaded.layers.any { it.effects.isNotEmpty() }) {
                    busy = Busy("Applying layer effects")
                    Engine.start(getApplication())
                    val ops = Engine.operations().map { it.name }.toSet()
                    val outcome = io.github.capstasher.gako.engine.Effects.apply(loaded, ops)
                    outcome.doc to listOfNotNull(loadNote, outcome.note).joinToString("\n").ifEmpty { null }
                } else loaded to loadNote
                val d = doc
                if (asLayers && d != null) {
                    // Each opened layer goes in centred above the active one.
                    val ids = state.layers.associate { it.id to Layer.newId() }
                    val added = state.layers.map {
                        it.copy(id = ids[it.id]!!, parent = it.parent?.let { p -> ids[p] },
                            name = if (state.layers.size == 1) name.substringBeforeLast('.') else it.name,
                            x = if (it.group) it.x else it.x + (d.width - state.width) / 2,
                            y = if (it.group) it.y else it.y + (d.height - state.height) / 2)
                    }
                    change("Open as Layers", insertAllAboveActive(d, added, added.last().id))
                } else {
                    newDocument(state, name)
                    metadata = pendingMetadata
                    remember(uri, name, state)
                    val lower = name.lowercase()
                    projectFormat = when {
                        lower.endsWith(".xcf") -> ExportFormat.Xcf
                        lower.endsWith(".ora") -> ExportFormat.Ora
                        else -> null
                    }
                    projectUri = uri.takeIf { projectFormat != null }
                }
                note?.let(::say)
            } catch (t: OutOfMemoryError) {
                say("That image is too big to open")
            } catch (t: Throwable) {
                say("Couldn't open that file: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    @Volatile private var pendingMetadata: Map<String, String>? = null

    private fun load(uri: Uri, name: String): Pair<DocState, String?> {
        pendingMetadata = null
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val lower = name.lowercase()
        if (lower.endsWith(".ora") || mime == "image/openraster") {
            return resolver.openInputStream(uri)!!.use { OraFile.read(it) } to null
        }
        if (lower.endsWith(".psd") || lower.endsWith(".psb") || mime == "image/vnd.adobe.photoshop" || mime == "image/x-photoshop") {
            return resolver.openInputStream(uri)!!.use { io.github.capstasher.gako.doc.PsdReader.read(it) } to null
        }
        if (lower.endsWith(".xcf") || mime == "image/x-xcf") {
            return resolver.openInputStream(uri)!!.use { io.github.capstasher.gako.doc.XcfReader.read(it) } to null
        }
        val (bitmap, note) = decode(uri)
        pendingMetadata = runCatching { resolver.openInputStream(uri)?.use { io.github.capstasher.gako.export.Metadata.read(it) } }.getOrNull()
        return DocOps.fromBitmap(bitmap) to note
    }

    private fun decode(uri: Uri): Pair<Bitmap, String?> {
        var note: String? = null
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val w = info.size.width
            val h = info.size.height
            if (w.toLong() * h > MAX_PIXELS) {
                val s = sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h))
                val tw = (w * s).toInt()
                val th = (h * s).toInt()
                decoder.setTargetSize(tw, th)
                note = "Scaled to $tw×$th"
            }
        }
        return Bitmaps.mutable(decoded) to note
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun baseName() = documentName.substringBeforeLast('.').ifBlank { "untitled" }

    // ---------------------------------------------------------------- paths

    /**
     * Which path, and which stroke of it, the Paths tool is editing. Either may be one past the
     * end: the tool then holds a new path (or a new stroke of this path) that joins the document
     * once it has two points.
     */
    var pathIndex by mutableIntStateOf(0); private set
    var strokeIndex by mutableIntStateOf(0); private set
    /** The name a cleared path gets back if its stroke is restored with Undo point. */
    private var pendingPathName: String? = null

    private fun strokeAt(paths: List<VectorPath>, pi: Int, si: Int) = paths.getOrNull(pi)?.strokes?.getOrNull(si)

    /** The path being edited, if it is in the document yet. */
    val activePath: VectorPath? get() = doc?.paths?.getOrNull(pathIndex)

    /** Every stroke of the path being edited, with the tool's stroke as it is right now. */
    fun activeStrokes(): List<io.github.capstasher.gako.doc.PathStroke> {
        val stored = activePath?.strokes.orEmpty().toMutableList()
        val e = input.path.toStroke()
        if (e != null) { if (strokeIndex < stored.size) stored[strokeIndex] = e else stored += e }
        return stored
    }

    private fun nextPathName(paths: List<VectorPath>): String {
        var n = paths.size + 1
        while (paths.any { it.name == "Path $n" }) n++
        return "Path $n"
    }

    /** Starts editing stroke [si] of path [pi] (either may be one past the end: a new one). */
    fun editPath(pi: Int, si: Int = 0) {
        val d = doc ?: return
        if (blockedByBusy()) return
        pathIndex = pi.coerceIn(0, d.paths.size)
        strokeIndex = si.coerceIn(0, d.paths.getOrNull(pathIndex)?.strokes?.size ?: 0)
        pendingPathName = null
        input.path.load(strokeAt(d.paths, pathIndex, strokeIndex))
        selectTool(Tool.Paths)
    }

    fun newPath() = editPath(doc?.paths?.size ?: 0)

    /** The path and stroke with an anchor at ([x], [y]), other than the one being edited. */
    fun otherPathAt(x: Float, y: Float, tol: Float): Pair<Int, Int>? {
        val paths = doc?.paths ?: return null
        var best: Pair<Int, Int>? = null
        var bestD = tol
        paths.forEachIndexed { pi, vp ->
            vp.strokes.forEachIndexed { si, st ->
                if (pi == pathIndex && si == strokeIndex) return@forEachIndexed
                for (a in 0 until st.anchors) {
                    val d = kotlin.math.hypot(st.points[a * 6 + 2] - x, st.points[a * 6 + 3] - y)
                    if (d < bestD) { bestD = d; best = pi to si }
                }
            }
        }
        return best
    }

    /** Another stroke in the current path (for holes, or shapes in several pieces). */
    fun newStroke() {
        val p = activePath ?: run { say("Draw the path's first stroke first"); return }
        editPath(pathIndex, p.strokes.size)
    }

    fun renamePath(i: Int, name: String) {
        if (blockedByBusy()) return
        val d = doc ?: return
        val p = d.paths.getOrNull(i) ?: return
        val n = name.trim().ifEmpty { return }
        if (n == p.name) return
        change("Rename Path", d.copy(paths = d.paths.toMutableList().also { it[i] = VectorPath(n, p.strokes) }))
    }

    fun duplicatePath(i: Int) {
        if (blockedByBusy()) return
        val d = doc ?: return
        val p = d.paths.getOrNull(i) ?: return
        val copy = VectorPath("${p.name} copy", p.strokes)
        change("Duplicate Path", d.copy(paths = d.paths.toMutableList().also { it.add(i + 1, copy) }))
        editPath(i + 1)
    }

    fun deletePath(i: Int) {
        if (blockedByBusy()) return
        val d = doc ?: return
        if (i !in d.paths.indices) return
        // reloadPath (via change) keeps the tool on the same path, or the next one if this was it.
        change("Delete Path", d.copy(paths = d.paths.toMutableList().also { it.removeAt(i) }))
    }

    /** Moves path [i] up or down the list. */
    fun movePath(i: Int, delta: Int) {
        if (blockedByBusy()) return
        val d = doc ?: return
        val j = i + delta
        if (i !in d.paths.indices || j !in d.paths.indices) return
        change("Reorder Paths", d.copy(paths = d.paths.toMutableList().also { java.util.Collections.swap(it, i, j) }))
    }

    /** Called whenever the tool's stroke changes: writes it into its slot in the document. */
    private fun syncPath() {
        val d = doc ?: return
        val edited = input.path.toStroke()
        val current = strokeAt(d.paths, pathIndex, strokeIndex)
        if (edited == null && current == null) return
        if (edited != null && edited.sameAs(current)) return
        val paths = d.paths.toMutableList()
        if (edited != null) {
            val p = paths.getOrNull(pathIndex)
            if (p == null) {
                paths += VectorPath(pendingPathName ?: nextPathName(paths), listOf(edited))
                pathIndex = paths.lastIndex
                strokeIndex = 0
            } else {
                val strokes = p.strokes.toMutableList()
                if (strokeIndex < strokes.size) strokes[strokeIndex] = edited else { strokes += edited; strokeIndex = strokes.lastIndex }
                paths[pathIndex] = VectorPath(p.name, strokes)
            }
            pendingPathName = null
        } else {
            // Cleared (a stroke down to one point keeps its stored copy until it has two again).
            if (input.path.points.isNotEmpty()) return
            val p = paths[pathIndex]
            val rest = p.strokes.toMutableList().also { it.removeAt(strokeIndex) }
            if (rest.isEmpty()) {
                paths.removeAt(pathIndex)
                pendingPathName = p.name
                pathIndex = paths.size           // the tool now holds a new, empty path
                strokeIndex = 0
            } else {
                paths[pathIndex] = VectorPath(p.name, rest)
                strokeIndex = rest.size          // a new, empty stroke of this path
            }
        }
        doc = d.copy(paths = paths)
        dirty = true
    }

    /**
     * After undo/redo or an operation that changed the paths (delete, reorder, crop, rotate…): keep
     * the tool on the same path where it went (by identity, else by name), and show it.
     */
    private fun reloadPath(before: DocState?, after: DocState?) {
        if (after == null || before?.paths === after.paths) return
        val old = before?.paths.orEmpty()
        val was = old.getOrNull(pathIndex)
        when {
            // Paths came back into an image that had none (redo): edit the first, unless one is being drawn.
            was == null && old.isEmpty() && after.paths.isNotEmpty() && input.path.isEmpty -> { pathIndex = 0; strokeIndex = 0 }
            // A new path that isn't in the document yet stays new.
            was == null -> { pathIndex = after.paths.size; strokeIndex = 0 }
            else -> {
                val found = after.paths.indexOfFirst { it === was }.takeIf { it >= 0 }
                    ?: (if (after.paths.size == old.size) pathIndex else null)
                    ?: after.paths.indexOfFirst { it.name == was.name }.takeIf { it >= 0 }
                if (found != null) pathIndex = found
                else { pathIndex = pathIndex.coerceIn(0, after.paths.size); strokeIndex = 0 }
            }
        }
        strokeIndex = strokeIndex.coerceIn(0, after.paths.getOrNull(pathIndex)?.strokes?.size ?: 0)
        input.path.load(strokeAt(after.paths, pathIndex, strokeIndex))
    }

    // ---------------------------------------------------------------- recovery

    private val recoveryFile get() = File(getApplication<Application>().filesDir, "recovery.xcf")
    private val recoveryName get() = File(getApplication<Application>().filesDir, "recovery.name")
    private var savedForRecovery: DocState? = null
    private var autosaveWrite: Job? = null
    private var savedRevision = -1

    /** Called when the app leaves the screen: keeps unsaved work safe if Android kills the process. */
    fun autosave() {
        val d = doc ?: return
        if (!dirty) { clearRecovery(); return }
        if (d === savedForRecovery && revision == savedRevision) return
        // Copy pixels (and masks) a stroke might change while the write runs.
        val snapshot = runCatching {
            d.copy(layers = d.layers.map { l ->
                l.copy(bitmap = l.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return,
                    mask = l.mask?.let { it.copy(Bitmap.Config.ARGB_8888, false) ?: return })
            })
        }.getOrNull() ?: return
        savedForRecovery = d
        savedRevision = revision
        val name = documentName
        val previous = autosaveWrite
        autosaveWrite = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()   // one writer at a time
            runCatching {
                val tmp = File(recoveryFile.path + ".tmp")
                tmp.outputStream().buffered(1 shl 16).use { io.github.capstasher.gako.doc.XcfWriter.write(snapshot, it, level = 1) }
                tmp.renameTo(recoveryFile)
                recoveryName.writeText(name)
            }
        }
    }

    private fun clearRecovery() {
        recoveryFile.delete()
        recoveryName.delete()
    }

    private fun restoreRecovery() {
        if (!recoveryFile.exists()) return
        viewModelScope.launch {
            busy = Busy("Restoring")
            try {
                val state = withContext(Dispatchers.IO) { recoveryFile.inputStream().use { io.github.capstasher.gako.doc.XcfReader.read(it) } }
                if (doc == null) {
                    newDocument(state, runCatching { recoveryName.readText().trim() }.getOrNull()?.ifBlank { null } ?: "Recovered", keepRecovery = true)
                    dirty = true
                    say("Restored your unsaved image")
                }
            } catch (t: Throwable) {
                clearRecovery()
            } finally {
                busy = null
            }
        }
    }

    // ---------------------------------------------------------------- export

    /** GIMP's Export Image dialog: a name (its extension picks the type), a folder and options. */
    class ExportSession(name: String, format: ExportFormat, options: ExportOptions, val title: String = "Export Image") {
        var name by mutableStateOf(name)
        var format by mutableStateOf(format)
        var options by mutableStateOf(options)

        fun rename(newName: String) {
            name = newName
            ExportFormat.forFileName(newName)?.let { format = it }
        }

        fun choose(f: ExportFormat) {
            format = f
            val base = name.substringBeforeLast('.').ifBlank { name }
            name = "$base.${f.ext}"
        }
    }

    data class ExportFolder(val uri: Uri, val name: String)

    private val prefs = app.getSharedPreferences("export", 0)

    var exportSession by mutableStateOf<ExportSession?>(null); private set
    var exportFolder by mutableStateOf(
        prefs.getString("folderUri", null)?.let { ExportFolder(Uri.parse(it), prefs.getString("folderName", "Folder") ?: "Folder") }
    ); private set
    var exportToFolder by mutableStateOf(prefs.getBoolean("toFolder", false) && exportFolder != null)

    /** Where File > Save writes: the project file this image was opened from or last saved as. */
    private var projectUri: Uri? = null
    private var projectFormat: ExportFormat? = null

    /** File > Save: overwrite the project file, or ask where to save it the first time. */
    fun save() {
        val d = doc ?: return
        val uri = projectUri
        val format = projectFormat
        if (blockedByBusy()) return
        if (uri == null || format == null) { startExport(project = true); return }
        cancelSession(); cancelCurves(); if (text != null) commitText(); input.reset()
        busy = Busy("Saving")
        val file = d
        viewModelScope.launch {
            try {
                // Encode fully first, so a failure never leaves the project file half-written.
                val scratch = File(getApplication<Application>().cacheDir, "export").apply { mkdirs() }
                val tmp = File(scratch, "save.tmp")
                withContext(Dispatchers.IO) {
                    tmp.outputStream().buffered(1 shl 16).use { Exporter.encode(file, format, ExportOptions(), it, scratch) }
                }
                val stream = withContext(Dispatchers.IO) { runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() }
                if (stream == null) {
                    // Read-only (e.g. from the photo picker): save somewhere new instead.
                    tmp.delete()
                    projectUri = null
                    busy = null
                    startExport(project = true)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    stream.use { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 16) } }
                    tmp.delete()
                }
                if (doc === d) dirty = false
                clearRecovery()
                remember(uri, documentName, d)
                say("Saved $documentName")
            } catch (t: OutOfMemoryError) {
                say("Not enough memory to save. Your file wasn't touched.")
            } catch (t: Throwable) {
                say("Save failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    fun startExport(project: Boolean = false) {
        if (doc == null || blockedByBusy()) return
        cancelSession(); cancelCurves(); if (text != null) commitText(); input.reset()
        val format = if (project) ExportFormat.Xcf
            else runCatching { ExportFormat.valueOf(prefs.getString("format", "Png")!!) }.getOrDefault(ExportFormat.Png)
        val options = ExportOptions(
            quality = prefs.getInt("quality", 90),
            webpLossless = prefs.getBoolean("webpLossless", false),
            gifDither = prefs.getBoolean("gifDither", true),
            tiffDeflate = prefs.getBoolean("tiffDeflate", true),
            tgaRle = prefs.getBoolean("tgaRle", true),
            keepMetadata = prefs.getBoolean("keepMetadata", true),
            keepLocation = prefs.getBoolean("keepLocation", false),
        )
        exportSession = ExportSession("${baseName()}.${format.ext}", format, options,
            if (project) "Save Project" else "Export Image")
    }

    fun cancelExport() {
        exportSession = null
    }

    fun setExportFolder(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(tree, flags) }
        val id = DocumentsContract.getTreeDocumentId(tree)
        val label = id.substringAfter(':', id).ifBlank { "Storage" }
        exportFolder = ExportFolder(tree, label)
        exportToFolder = true
        prefs.edit().putString("folderUri", tree.toString()).putString("folderName", label).apply()
    }

    fun runExport() {
        val s = exportSession ?: return
        val d = doc ?: return
        if (busy != null) return
        val name = s.name.trim()
        if (name.isEmpty()) { say("Give the file a name"); return }
        val format = s.format
        val fileName = if (ExportFormat.forFileName(name) == format) name else "$name.${format.ext}"
        val folder = exportFolder.takeIf { exportToFolder }
        val e = prefs.edit()
            .putInt("quality", s.options.quality)
            .putBoolean("webpLossless", s.options.webpLossless)
            .putBoolean("gifDither", s.options.gifDither)
            .putBoolean("tiffDeflate", s.options.tiffDeflate)
            .putBoolean("tgaRle", s.options.tgaRle)
            .putBoolean("keepMetadata", s.options.keepMetadata)
            .putBoolean("keepLocation", s.options.keepLocation)
            .putBoolean("toFolder", exportToFolder)
        if (!format.layered) e.putString("format", format.name)
        e.apply()

        busy = Busy(if (format.layered) "Saving" else "Exporting")
        viewModelScope.launch {
            try {
                val (where, uri) = withContext(Dispatchers.IO) {
                    val scratch = File(getApplication<Application>().cacheDir, "export")
                    val meta = metadata.takeIf { s.options.keepMetadata && io.github.capstasher.gako.export.Metadata.supports(format) }
                    val write: (java.io.OutputStream) -> Unit = { out ->
                        if (meta == null) Exporter.encode(d, format, s.options, out, scratch)
                        else {
                            // EXIF is written into a finished file, so encode to a temp file first.
                            scratch.mkdirs()
                            val tmp = File(scratch, "meta.${format.ext}").apply { delete() }
                            tmp.outputStream().buffered(1 shl 16).use { Exporter.encode(d, format, s.options, it, scratch) }
                            val k = if (s.options.scale in 1..99) s.options.scale else 100
                            runCatching {
                                io.github.capstasher.gako.export.Metadata.write(tmp, meta, s.options.keepLocation,
                                    (d.width * k / 100).coerceAtLeast(1), (d.height * k / 100).coerceAtLeast(1))
                            }
                            tmp.inputStream().use { it.copyTo(out, 1 shl 16) }
                            tmp.delete()
                        }
                    }
                    if (folder != null) writeToFolder(folder, fileName, format, write)
                    else writeToPictures(fileName, format, write)
                }
                exportSession = null
                if (format.layered) {
                    projectUri = uri
                    projectFormat = format
                    documentName = fileName
                    dirty = false
                    remember(uri, fileName, d)
                }
                say("${if (format.layered) "Saved" else "Exported"} $fileName to $where")
            } catch (t: OutOfMemoryError) {
                say("Not enough memory to export")
            } catch (t: Throwable) {
                say("Export failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    private fun writeToPictures(fileName: String, format: ExportFormat, write: (java.io.OutputStream) -> Unit): Pair<String, Uri> {
        fun insert(collection: Uri, relative: String, mime: String): Uri? = runCatching {
            resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })
        }.getOrNull()

        // Images go to Pictures/GAKO; types the gallery doesn't understand land in Download/GAKO.
        var where = "Pictures/GAKO"
        val uri = (if (format.opaqueToSystem) null
                   else insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES + "/GAKO", format.mime))
            ?: insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DOWNLOADS + "/GAKO",
                      if (format.opaqueToSystem) "application/octet-stream" else format.mime)
                .also { where = "Download/GAKO" }
            ?: error("Android refused to create the file")
        try {
            resolver.openOutputStream(uri, "w")?.use(write) ?: error("Couldn't open the file for writing")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return where to uri
    }

    private fun writeToFolder(folder: ExportFolder, fileName: String, format: ExportFormat,
                              write: (java.io.OutputStream) -> Unit): Pair<String, Uri> {
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder.uri, DocumentsContract.getTreeDocumentId(folder.uri))
        val mime = if (format.opaqueToSystem) "application/octet-stream" else format.mime
        val doc = DocumentsContract.createDocument(resolver, parent, mime, fileName)
            ?: error("Couldn't create the file in ${folder.name}")
        try {
            resolver.openOutputStream(doc, "w")?.use(write) ?: error("Couldn't open the file for writing")
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw t
        }
        return folder.name to doc
    }

    /** Writes a PNG of the image for the share sheet and returns its content Uri. */
    suspend fun shareableUri(): Uri? {
        val d = doc ?: return null
        busy = Busy("Preparing")
        return try {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val dir = File(app.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, "${baseName()}.png")
                file.outputStream().use { Compositor.flatten(d).compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            }
        } finally {
            busy = null
        }
    }

    // ---------------------------------------------------------------- image

    fun rotate(quarterTurns: Int) = docJob(if (quarterTurns == 2) "Rotate 180°" else "Rotate 90°") { DocOps.rotate(it, quarterTurns) }
    fun rotateBy(degrees: Float, crop: Boolean) =
        docJob(if (crop) "Straighten" else "Rotate") { DocOps.rotateBy(it, degrees, crop) }
    fun flip(horizontal: Boolean) = docJob(if (horizontal) "Flip Horizontally" else "Flip Vertically") { DocOps.flip(it, horizontal) }
    fun flattenImage() = docJob("Flatten Image") { DocOps.flatten(it, bg) }
    fun mergeVisible() = docJob("Merge Visible Layers") { DocOps.mergeVisible(it) }
    fun scaleImage(w: Int, h: Int) = docJob("Scale Image") { DocOps.scale(it, w, h) }
    fun canvasSize(w: Int, h: Int, dx: Int, dy: Int, resizeLayers: Boolean) =
        docJob("Canvas Size") { DocOps.canvasSize(it, w, h, dx, dy, resizeLayers) }
    fun cropTo(r: Rect) = docJob("Crop Image") { DocOps.crop(it, r) }

    fun cropToSelection() {
        val s = doc?.selection ?: run { say("Select something first"); return }
        cropTo(s.bounds)
    }

    // ---- GIMP's Quick Mask

    /** The layer that was active before Quick Mask, to go back to. */
    private var beforeQuickMask: Long? = null

    val quickMaskOn: Boolean get() = doc?.quickMask != null

    /**
     * Select > Toggle Quick Mask (Shift+Q): the selection becomes a grey layer that any tool or filter
     * edits (white selected; shown as a red tint where not), and back. Both are undo steps.
     */
    fun toggleQuickMask() {
        val d = doc ?: return
        if (blockedByBusy()) return
        endInteractions()
        val qm = d.quickMask
        if (qm == null) {
            val grey = Bitmaps.blank(d.width, d.height, Color.BLACK)
            d.selection?.let { sel ->
                val bytes = Selections.bytesOf(sel.mask)
                val px = IntArray(bytes.size) { val v = bytes[it].toInt() and 255; (0xFF shl 24) or (v * 0x010101) }
                grey.setPixels(px, 0, d.width, 0, 0, d.width, d.height)
            }
            val layer = Layer(Layer.newId(), "Quick Mask", grey, lockAlpha = true, quickMask = true)
            beforeQuickMask = d.activeId
            change("Quick Mask", d.copy(layers = d.layers + layer, activeId = layer.id, selection = null))
        } else {
            val mask = Bitmaps.blank(d.width, d.height, Color.BLACK).also {
                // The grey where it lies on the canvas (it can have been moved).
                android.graphics.Canvas(it).drawBitmap(qm.bitmap, qm.x.toFloat(), qm.y.toFloat(), null)
            }
            val all = IntArray(d.width * d.height).also { mask.getPixels(it, 0, d.width, 0, 0, d.width, d.height) }
            val sel = Selections.make(Selections.maskFromBytes(ByteArray(all.size) { (all[it] shr 16).toByte() }, d.width, d.height))
            val rest = d.layers.filterNot { it.quickMask }
            val back = beforeQuickMask?.takeIf { id -> rest.any { it.id == id } } ?: rest.lastOrNull { !it.group }?.id ?: d.activeId
            change("Quick Mask Off", d.copy(layers = rest, activeId = back, selection = sel))
        }
    }

    // ---- channels (GIMP's Channels dialog: saved selections)

    /** Select > Save to Channel: the selection kept as a named channel (saved in XCF as GIMP's). */
    fun saveSelectionToChannel() {
        val d = doc ?: return
        if (blockedByBusy()) return
        val sel = d.selection ?: run { say("Select something first"); return }
        val bytes = Selections.bytesOf(sel.mask)
        val grey = Bitmaps.blank(d.width, d.height)
        grey.setPixels(IntArray(bytes.size) { val v = bytes[it].toInt() and 255; (0xFF shl 24) or (v * 0x010101) },
            0, d.width, 0, 0, d.width, d.height)
        val names = d.gimp.channels.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) "Selection Mask copy" else "Selection Mask copy #$it" }.first { it !in names }
        change("Save to Channel", d.copy(gimp = d.gimp.copy(channels = d.gimp.channels + io.github.capstasher.gako.doc.Channel(name, grey, emptyList()))))
        say("Saved the selection as “$name” (Select > Channels)")
    }

    /** A channel as (or into) the selection, GIMP's Channel to Selection with its modes. */
    fun channelToSelection(index: Int, mode: io.github.capstasher.gako.doc.SelectMode) {
        val d = doc ?: return
        if (blockedByBusy()) return
        val ch = d.gimp.channels.getOrNull(index) ?: return
        val px = IntArray(d.width * d.height).also { ch.bitmap.getPixels(it, 0, d.width, 0, 0, d.width, d.height) }
        val shape = Selections.maskFromBytes(ByteArray(px.size) { (px[it] shr 16).toByte() }, d.width, d.height)
        val sel = Selections.make(Selections.combine(d.selection?.mask, shape, mode))
        change("Channel to Selection", d.copy(selection = sel))
    }

    fun deleteChannel(index: Int) {
        val d = doc ?: return
        if (blockedByBusy()) return
        if (index !in d.gimp.channels.indices) return
        change("Delete Channel", d.copy(gimp = d.gimp.copy(channels = d.gimp.channels.filterIndexed { i, _ -> i != index })))
    }

    // ---- guides (GIMP's Image > Guides)

    /** A guide across the image: horizontal at y = [position], or vertical at x = [position]. */
    fun addGuide(horizontal: Boolean, position: Int) {
        val d = doc ?: return
        if (blockedByBusy()) return
        val g = io.github.capstasher.gako.doc.Guide(horizontal, position.coerceIn(0, if (horizontal) d.height else d.width))
        if (g in d.gimp.guides) return
        change("Add Guide", d.copy(gimp = d.gimp.copy(guides = d.gimp.guides + g)))
    }

    /** Guides along the selection's four edges. */
    fun guidesFromSelection() {
        val d = doc ?: return
        if (blockedByBusy()) return
        val b = d.selection?.bounds ?: run { say("Select something first"); return }
        val new = listOf(io.github.capstasher.gako.doc.Guide(true, b.top), io.github.capstasher.gako.doc.Guide(true, b.bottom),
            io.github.capstasher.gako.doc.Guide(false, b.left), io.github.capstasher.gako.doc.Guide(false, b.right))
            .filter { it !in d.gimp.guides }
        if (new.isNotEmpty()) change("New Guides from Selection", d.copy(gimp = d.gimp.copy(guides = d.gimp.guides + new)))
    }

    fun removeGuides() {
        val d = doc ?: return
        if (blockedByBusy()) return
        if (d.gimp.guides.isNotEmpty()) change("Remove all Guides", d.copy(gimp = d.gimp.copy(guides = emptyList())))
    }

    fun cropToContent() {
        docJob("Crop to Content") {
            val flat = Compositor.flatten(it)
            val m = flat.extractAlpha()
            val sel = Selections.make(m) ?: return@docJob null
            if (sel.bounds == Compositor.docRect(it)) return@docJob null
            DocOps.crop(it, sel.bounds)
        }
    }

    // ---------------------------------------------------------------- layers

    private fun layerName(d: DocState, base: String): String {
        val names = d.layers.map { it.name }.toSet()
        if (base !in names) return base
        var i = 1
        while ("$base #$i" in names) i++
        return "$base #$i"
    }

    /** New layers go right above the active one, in the same group (above a whole group if one is active). */
    private fun insertAboveActive(d: DocState, layer: Layer): DocState {
        val a = d.active
        val i = (d.activeIndex + 1).coerceIn(0, d.layers.size)
        return d.copy(layers = d.layers.toMutableList().apply { add(i, layer.copy(parent = a?.parent)) }, activeId = layer.id)
    }

    private fun insertAllAboveActive(d: DocState, added: List<Layer>, topId: Long): DocState {
        val a = d.active
        val i = (d.activeIndex + 1).coerceIn(0, d.layers.size)
        val placed = added.map { if (it.parent == null) it.copy(parent = a?.parent) else it }
        return d.copy(layers = d.layers.toMutableList().apply { addAll(i, placed) }, activeId = topId)
    }

    /** Says why a group can't be painted or filtered, and returns true if [d]'s active item is a group. */
    internal fun refuseGroup(d: DocState): Boolean {
        if (d.active?.group != true) return false
        say("“${d.active?.name}” is a group: pick a layer inside it")
        return true
    }

    enum class NewFill(val label: String) { Transparent("Transparency"), Foreground("Foreground"), Background("Background"), White("White") }

    fun newLayer(fill: NewFill = NewFill.Transparent) {
        if (doc == null) return
        if (blockedByBusy()) return
        endInteractions()
        val d = doc ?: return  // committing typed text may have changed it
        val color = when (fill) {
            NewFill.Transparent -> 0
            NewFill.Foreground -> fg
            NewFill.Background -> bg
            NewFill.White -> Color.WHITE
        }
        val l = Layer(Layer.newId(), layerName(d, "Layer"), Bitmaps.blank(d.width, d.height, color), legacy = d.newLayerLegacy)
        change("New Layer", insertAboveActive(d, l))
    }

    fun duplicateLayer() {
        if (doc?.active == null) return
        if (blockedByBusy()) return
        endInteractions()
        val d = doc ?: return  // committing typed text may have changed it
        val a = d.active ?: return
        // A group is duplicated with everything in it.
        val block = d.layers.slice(Tree.range(d.layers, a.id))
        val ids = block.associate { it.id to Layer.newId() }
        val copies = block.map { l ->
            l.copy(id = ids[l.id]!!, parent = l.parent?.let { ids[it] ?: it },
                name = if (l.id == a.id) layerName(d, "${a.name} copy") else l.name,
                bitmap = if (l.group) l.bitmap else Bitmaps.copy(l.bitmap), mask = l.mask?.let(Bitmaps::copy))
        }
        val i = d.activeIndex + 1
        change(if (a.group) "Duplicate Group" else "Duplicate Layer",
            d.copy(layers = d.layers.toMutableList().apply { addAll(i, copies) }, activeId = ids[a.id]!!))
    }

    fun deleteLayer() {
        if (doc == null || blockedByBusy()) return
        endInteractions()
        val d = doc ?: return
        val a = d.active ?: return
        val range = Tree.range(d.layers, a.id)
        val layers = d.layers.filterIndexed { k, _ -> k !in range }
        if (layers.none { !it.group }) { say("An image needs at least one layer"); return }
        // The next one down takes over, or the one now at the bottom.
        val next = layers.getOrNull((range.first - 1).coerceAtLeast(0)) ?: layers.first()
        change(if (a.group) "Delete Group" else "Delete Layer", d.copy(layers = layers, activeId = next.id))
    }

    /**
     * Raise (+1) or lower (−1) the active layer or group among its siblings. Next to a group it goes
     * into that group; at the edge of its own group it steps out of it — so the arrows reach anywhere.
     */
    fun moveLayer(delta: Int) {
        val d = doc ?: return
        val a = d.active ?: return
        if (blockedByBusy()) return
        val label = if (delta > 0) "Raise Layer" else "Lower Layer"
        val moved = Tree.move(d.layers, a.id, delta, { it.id }, { it.parent }, { it.group }) { l, p -> l.copy(parent = p) } ?: return
        val layers = Tree.reveal(moved, a.id)
        change(label, d.copy(layers = layers))
    }

    /** Layer > Stack > Raise to Top / Lower to Bottom (within the layer's group). */
    fun restack(top: Boolean) {
        val d = doc ?: return
        val a = d.active ?: return
        if (blockedByBusy()) return
        val out = DocOps.restack(d, a.id, top) ?: run { say(if (top) "Already at the top" else "Already at the bottom"); return }
        change(if (top) "Raise to Top" else "Lower to Bottom", out.copy(layers = Tree.reveal(out.layers, a.id)))
    }

    /** Layer > Transform: flip or turn the active layer (or everything in the active group). */
    fun transformLayer(turn: DocOps.LayerTurn) {
        val a = doc?.active ?: return
        docJob(turn.label) { DocOps.transformLayer(it, a.id, turn) ?: run { say("That group is empty"); null } }
    }

    fun cropLayerToSelection() {
        val d = doc ?: return
        if (refuseGroup(d)) return
        if (d.selection == null) { say("Select something first"); return }
        val a = d.active ?: return
        docJob("Crop Layer to Selection") { DocOps.cropLayerToSelection(it, a.id) ?: run { say("Nothing to crop"); null } }
    }

    fun fitCanvasToLayers() = docJob("Fit Canvas to Layers") { DocOps.fitCanvasToLayers(it) ?: run { say("The canvas already fits the layers"); null } }

    fun mergeDown() {
        val d = doc ?: return
        if (d.active?.group == true) { mergeGroup(); return }
        if (DocOps.mergeTarget(d) == null) { say("There's no layer right below in the same group to merge into"); return }
        docJob("Merge Down") { DocOps.mergeDown(it) }
    }

    /** Layer > New Layer Group: an empty group above the active layer. */
    fun newGroup() {
        if (doc == null) return
        if (blockedByBusy()) return
        endInteractions()
        val d = doc ?: return  // committing typed text may have changed it
        val g = Layer(Layer.newId(), layerName(d, "Group"), Bitmaps.blank(1, 1), group = true, mode = LayerMode.PassThrough)
        change("New Layer Group", insertAboveActive(d, g))
    }

    /** Puts the active layer (or group) into a new group of its own. */
    fun groupActive() {
        if (doc?.active == null) return
        if (blockedByBusy()) return
        endInteractions()
        val d = doc ?: return  // committing typed text may have changed it
        val a = d.active ?: return
        val g = Layer(Layer.newId(), layerName(d, "Group"), Bitmaps.blank(1, 1), group = true, mode = LayerMode.PassThrough, parent = a.parent)
        val range = Tree.range(d.layers, a.id)
        val layers = d.layers.toMutableList()
        layers[range.last] = a.copy(parent = g.id)
        layers.add(range.last + 1, g)
        change("Put in New Group", d.copy(layers = layers, activeId = g.id))
    }

    fun mergeGroup() {
        val a = doc?.active?.takeIf { it.group } ?: return
        docJob("Merge Layer Group") { DocOps.mergeGroup(it, a.id) }
    }

    fun setExpanded(id: Long, expanded: Boolean) {
        if (busy != null) return
        val d = doc ?: return
        val l = d.layer(id)?.takeIf { it.group } ?: return
        silently(d.replace(l.copy(expanded = expanded)))
    }

    fun setActiveLayer(id: Long) {
        val d = doc ?: return
        if (d.activeId == id || session != null || curves != null) return
        if (blockedByBusy()) return
        if (text != null) commitText()
        input.reset()
        editMask = false
        silently(d.copy(activeId = id))
        if (tool == Tool.Transform) input.startTransform()
    }

    fun setLayerVisible(id: Long, visible: Boolean) {
        val d = doc ?: return
        if (blockedByBusy()) return
        val l = d.layer(id) ?: return
        change(if (visible) "Show Layer" else "Hide Layer", d.replace(l.copy(visible = visible)))
    }

    private var propertyBefore: DocState? = null

    /** Live opacity change while a slider moves; [commitLayerProperty] records it. */
    fun setLayerOpacityLive(opacity: Float) {
        val d = doc ?: return
        if (busy != null) return
        val l = d.active ?: return
        if (propertyBefore == null) propertyBefore = d
        silently(d.replace(l.copy(opacity = opacity.coerceIn(0f, 1f))))
    }

    fun commitLayerProperty(label: String = "Layer Opacity") {
        val before = propertyBefore ?: return
        propertyBefore = null
        val after = doc ?: return
        doc = before
        change(label, after)
    }

    fun setLayerMode(mode: LayerMode) {
        val d = doc ?: return
        if (blockedByBusy()) return
        val l = d.active ?: return
        // A deliberate change: the file's own GIMP mode number and blend settings no longer apply.
        if (l.mode != mode || l.xcfMode != null) change("Layer Mode", d.replace(l.copy(mode = mode, xcfMode = null,
            blendSpace = 0, compositeSpace = 0, compositeMode = 0)))
    }

    /** Legacy (sRGB) or GIMP 2.10+ (linear light) blending for the active layer. */
    fun setLayerLegacy(legacy: Boolean) {
        val d = doc ?: return
        if (blockedByBusy()) return
        val l = d.active ?: return
        if (l.legacy != legacy) change("Layer Mode", d.replace(l.copy(legacy = legacy, xcfMode = null,
            blendSpace = 0, compositeSpace = 0, compositeMode = 0)))
    }

    fun renameLayer(id: Long, name: String) {
        val d = doc ?: return
        if (blockedByBusy()) return
        val l = d.layer(id) ?: return
        val n = name.trim().ifEmpty { return }
        if (n != l.name) change("Rename Layer", d.replace(l.copy(name = n)))
    }

    fun setLockAlpha(lock: Boolean) {
        val d = doc ?: return
        if (blockedByBusy()) return
        val l = d.active ?: return
        change("Lock Alpha Channel", d.replace(l.copy(lockAlpha = lock)))
    }

    enum class MaskInit(val label: String) {
        White("White (full opacity)"), Black("Black (full transparency)"), Alpha("Layer's alpha channel"),
        Selection("Selection"), Grey("Greyscale copy of layer")
    }

    /** Layer > Mask > Add Layer Mask. */
    fun addMask(init: MaskInit) {
        val d = doc ?: return
        val a = d.active ?: return
        if (refuseGroup(d)) return
        if (a.mask != null) { say("“${a.name}” already has a mask"); return }
        docJob("Add Layer Mask") { state ->
            val l = state.layer(a.id) ?: return@docJob null
            val m = when (init) {
                MaskInit.White -> Compositor.whiteMask(l.width, l.height)
                MaskInit.Black -> Bitmaps.blank(l.width, l.height, Color.BLACK)
                MaskInit.Alpha -> Compositor.maskFromAlpha(l.bitmap.extractAlpha(), l.width, l.height)
                MaskInit.Selection -> state.selection?.let { Compositor.maskFromAlpha(it.mask, l.width, l.height, -l.x.toFloat(), -l.y.toFloat()) }
                    ?: Compositor.whiteMask(l.width, l.height)
                MaskInit.Grey -> Bitmaps.blank(l.width, l.height, Color.BLACK).also { g ->
                    val p = Paint().apply {
                        colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
                    }
                    Canvas(g).drawBitmap(l.bitmap, 0f, 0f, p)
                }
            }
            state.replace(l.copy(mask = m, maskEnabled = true))
        }
        editMask = true
    }

    /** Layer > Mask > Apply Layer Mask: bake it into the layer's alpha. */
    fun applyMask() {
        val a = doc?.active ?: return
        if (a.mask == null) return
        editMask = false
        docJob("Apply Layer Mask") { state ->
            val l = state.layer(a.id) ?: return@docJob null
            state.replace(l.copy(bitmap = Compositor.applyMask(l), mask = null, text = null))
        }
    }

    fun deleteMask() {
        val d = doc ?: return
        if (blockedByBusy()) return
        val a = d.active ?: return
        if (a.mask == null) return
        editMask = false
        change("Delete Layer Mask", d.replace(a.copy(mask = null)))
    }

    fun setMaskEnabled(enabled: Boolean) {
        val d = doc ?: return
        if (blockedByBusy()) return
        val a = d.active ?: return
        if (a.mask == null) return
        change(if (enabled) "Enable Layer Mask" else "Disable Layer Mask", d.replace(a.copy(maskEnabled = enabled)))
    }

    fun cropLayerToContent() = doc?.takeIf { !refuseGroup(it) }?.active?.let { a ->
        docJob("Crop Layer to Content") { DocOps.cropLayerToContent(it, a.id) ?: run { say("Nothing to crop"); null } }
    }

    fun layerToImageSize() = doc?.takeIf { !refuseGroup(it) }?.active?.let { a -> docJob("Layer to Image Size") { DocOps.layerToImageSize(it, a.id) } }
    fun scaleLayer(w: Int, h: Int) = doc?.takeIf { !refuseGroup(it) }?.active?.let { a -> docJob("Scale Layer") { DocOps.scaleLayer(it, a.id, w, h) } }

    fun alphaToSelection() {
        val d = doc ?: return
        val a = d.active ?: return
        if (refuseGroup(d)) return
        docJob("Alpha to Selection") { it.copy(selection = Selections.make(Selections.alphaOf(a, it.width, it.height))) }
    }

    fun newFromVisible() = docJob("New from Visible") {
        val l = Layer(Layer.newId(), layerName(it, "Visible"), Compositor.flatten(it), legacy = it.newLayerLegacy)
        insertAboveActive(it, l)
    }

    // ---------------------------------------------------------------- selection

    internal fun setSelection(label: String, mask: Bitmap?) {
        val d = doc ?: return
        val sel = mask?.let { Selections.make(it) }
        if (sel == null && d.selection == null) return
        change(label, d.copy(selection = sel))
    }

    fun selectAll() {
        val d = doc ?: return
        if (blockedByBusy()) return
        input.reset()
        setSelection("Select All", Selections.all(d.width, d.height))
    }

    fun selectNone() {
        if (blockedByBusy()) return
        input.reset()
        setSelection("Select None", null)
    }

    fun invertSelection() {
        val d = doc ?: return
        input.reset()
        val mask = d.selection?.mask?.let { Selections.invert(it) } ?: Selections.all(d.width, d.height)
        docJob("Invert Selection") { it.copy(selection = Selections.make(mask)) }
    }

    /** Select > Feather / Grow / Shrink / Border, run through GEGL on the mask. */
    fun modifySelection(kind: SelectionChange, amount: Float) {
        val d = doc ?: return
        val sel = d.selection ?: run { say("Select something first"); return }
        if (amount <= 0f) return
        docJob(kind.label) { state ->
            val grey = Selections.maskToGrey(sel.mask)
            val result: Bitmap? = when (kind) {
                SelectionChange.Feather -> run(grey, "gegl:gaussian-blur", mapOf("std-dev-x" to amount / 2.0, "std-dev-y" to amount / 2.0))
                SelectionChange.Grow -> run(grey, "gegl:median-blur", mapOf("radius" to amount.roundToInt(), "percentile" to 100.0, "neighborhood" to "circle"))
                SelectionChange.Shrink -> run(grey, "gegl:median-blur", mapOf("radius" to amount.roundToInt(), "percentile" to 0.0, "neighborhood" to "circle"))
                SelectionChange.Border -> {
                    val grown = run(grey, "gegl:median-blur", mapOf("radius" to amount.roundToInt(), "percentile" to 100.0, "neighborhood" to "circle"))
                    val shrunk = run(grey, "gegl:median-blur", mapOf("radius" to amount.roundToInt(), "percentile" to 0.0, "neighborhood" to "circle"))
                    if (grown != null && shrunk != null) {
                        Canvas(grown).drawBitmap(shrunk, 0f, 0f, Paint().apply { blendMode = BlendMode.DIFFERENCE })
                        grown
                    } else null
                }
            }
            result ?: return@docJob null
            state.copy(selection = Selections.make(Selections.greyToMask(result)))
        }
    }

    enum class SelectionChange(val label: String) { Feather("Feather Selection"), Grow("Grow Selection"), Shrink("Shrink Selection"), Border("Border Selection") }

    /**
     * Edit > Stroke Selection: a line of the brush's width (and opacity) in the FG colour along the
     * selection's edge, centred on it, painted on the active layer.
     */
    fun strokeSelection() {
        if (blockedByBusy()) return
        // Finish any text first: committing it replaces the document.
        endInteractions()
        val d = doc ?: return
        val sel = d.selection ?: run { say("Select something first"); return }
        if (refuseGroup(d)) return
        val half = (options.size / 2f).roundToInt().coerceAtLeast(1)
        val color = paintColor(fg) or (0xFF shl 24)
        val opacity = options.opacity
        viewModelScope.launch {
            busy = Busy("Stroke Selection")
            try {
                val edge = withContext(Dispatchers.Default) {
                    // The marching-ants outline stroked with a round pen, into a mask (so overlaps don't add up).
                    val m = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ALPHA_8)
                    Canvas(m).drawPath(sel.outline, Paint().apply {
                        style = Paint.Style.STROKE
                        strokeWidth = half * 2f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                    })
                    m
                }
                val area = Rect(sel.bounds).apply { inset(-half - 2, -half - 2); intersect(0, 0, d.width, d.height) }
                if (doc !== d) { say("The image changed meanwhile; stroke the selection again"); return@launch }
                editActiveLayer("Stroke Selection", area) { l, _ ->
                    val c = Canvas(l.bitmap)
                    val save = c.saveLayer(RectF(0f, 0f, l.width.toFloat(), l.height.toFloat()), Paint().apply {
                        alpha = (opacity * 255).roundToInt()
                        blendMode = if (l.lockAlpha) BlendMode.SRC_ATOP else BlendMode.SRC_OVER
                    })
                    c.drawBitmap(edge, -l.x.toFloat(), -l.y.toFloat(), Paint().apply { this.color = color })
                    c.restoreToCount(save)
                }
            } catch (e: OutOfMemoryError) {
                say("Not enough memory to stroke the selection on an image this big")
            } finally {
                busy = null
            }
        }
    }

    private suspend fun run(src: Bitmap, op: String, params: Map<String, Any?>): Bitmap? =
        when (val r = Engine.apply(op, params, src)) {
            is Engine.Result.Ok -> r.bitmap
            is Engine.Result.Failed -> { say("$op failed: ${r.message}"); null }
            Engine.Result.Cancelled -> null
        }

    // ---------------------------------------------------------------- edit

    private var clipboard: Pair<Bitmap, Rect>? = null
    val hasClipboard get() = true

    private fun copySelected(merged: Boolean): Pair<Bitmap, Rect>? {
        val d = doc ?: return null
        val source: Layer = if (merged) Layer(0, "", Compositor.flatten(d)) else d.active ?: return null
        val sel = d.selection
        val area = Rect(sel?.bounds ?: source.bounds)
        if (!area.intersect(source.bounds)) return null
        val out = Bitmaps.blank(area.width(), area.height())
        val c = Canvas(out)
        c.drawBitmap(source.bitmap, (source.x - area.left).toFloat(), (source.y - area.top).toFloat(), null)
        if (sel != null) Bitmaps.maskIn(c, sel.mask, -area.left.toFloat(), -area.top.toFloat())
        return out to area
    }

    fun copy(merged: Boolean = false) {
        if (blockedByBusy()) return
        if (!merged && doc?.let(::refuseGroup) == true) return
        clipboard = copySelected(merged) ?: run { say("Nothing to copy"); return }
        publishClipboard()
        say(if (merged) "Copied the visible image" else "Copied")
    }

    fun cut() {
        if (blockedByBusy()) return
        if (doc?.let(::refuseGroup) == true) return
        clipboard = copySelected(false) ?: return
        publishClipboard()
        clear("Cut")
    }

    private val clipboardManager get() = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
    private var publishedClip: Uri? = null

    /** Puts the copy on Android's clipboard too, as a PNG other apps can paste. */
    private fun publishClipboard() {
        val (b, _) = clipboard ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val dir = File(app.cacheDir, "clipboard").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "gako-${System.currentTimeMillis()}.png")
                file.outputStream().buffered().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                publishedClip = uri
                withContext(Dispatchers.Main) {
                    clipboardManager.setPrimaryClip(android.content.ClipData.newUri(resolver, "GAKO", uri))
                }
            }
        }
    }

    /** An image another app put on the clipboard (not GAKO's own copy), if there is one. */
    private fun systemClipboardImage(): Uri? = runCatching {
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null
        if (uri == publishedClip) return null
        val type = resolver.getType(uri) ?: return null
        if (type.startsWith("image/")) uri else null
    }.getOrNull()

    fun paste(asNewImage: Boolean = false) {
        if (blockedByBusy()) return
        systemClipboardImage()?.let { uri ->
            if (asNewImage || doc == null) open(uri) else open(uri, asLayers = true)
            return
        }
        val (b, r) = clipboard ?: run { say("The clipboard is empty"); return }
        if (asNewImage) {
            guardDiscard("Pasting as a new image") { newDocument(DocOps.fromBitmap(Bitmaps.copy(b), "Pasted"), "Pasted") }
            return
        }
        if (doc == null) { newDocument(DocOps.fromBitmap(Bitmaps.copy(b), "Pasted"), "Pasted"); return }
        endInteractions()
        val d = doc ?: return
        val x = if (r.right <= d.width && r.bottom <= d.height) r.left else (d.width - b.width) / 2
        val y = if (r.right <= d.width && r.bottom <= d.height) r.top else (d.height - b.height) / 2
        val l = Layer(Layer.newId(), layerName(d, "Pasted Layer"), Bitmaps.copy(b), x, y, legacy = d.newLayerLegacy)
        change("Paste", insertAboveActive(d, l))
        if (tool != Tool.Move && tool != Tool.Transform) selectTool(Tool.Move)
        say("Pasted as a new layer. Drag to place it.")
    }

    /**
     * Layer via Copy / Cut: the selected part of the active layer becomes a new layer right above it,
     * in place, and the Move tool is picked so it can be dragged away. One undo step.
     */
    fun layerViaSelection(cut: Boolean) {
        val d = doc ?: return
        val a = d.active ?: return
        if (refuseGroup(d)) return
        if (d.selection == null) { say("Select something first"); return }
        if (blockedByBusy()) return
        endInteractions()
        val label = if (cut) "Layer via Cut" else "Layer via Copy"
        docJob(label) { state ->
            val l = state.layer(a.id) ?: return@docJob null
            val sel = state.selection ?: return@docJob null
            val area = Rect(sel.bounds)
            if (!area.intersect(l.bounds)) return@docJob null
            val piece = Bitmaps.blank(area.width(), area.height())
            val c = Canvas(piece)
            c.drawBitmap(l.bitmap, (l.x - area.left).toFloat(), (l.y - area.top).toFloat(), null)
            Bitmaps.maskIn(c, sel.mask, -area.left.toFloat(), -area.top.toFloat())
            val newLayer = Layer(Layer.newId(), layerName(state, "${l.name} ${if (cut) "cut" else "copy"}"), piece, area.left, area.top,
                parent = l.parent)
            var base = state
            if (cut) {
                val rest = Bitmaps.copy(l.bitmap)
                val m = Compositor.selectionFor(l, sel)!!
                DocOps.fillLayer(l.copy(bitmap = rest), m, Color.BLACK, BlendMode.DST_OUT)
                base = state.replace(l.copy(bitmap = rest, text = null))
            }
            val i = base.layers.indexOfFirst { it.id == l.id } + 1
            base.copy(layers = base.layers.toMutableList().apply { add(i, newLayer) }, activeId = newLayer.id, selection = null)
        }
        selectTool(Tool.Move)
    }

    fun clear(label: String = "Clear") {
        if (blockedByBusy()) return
        endInteractions()
        editActiveLayer(label) { l, sel ->
            DocOps.fillLayer(l, sel, if (l.lockAlpha) paintColor(bg) else Color.BLACK,
                if (l.lockAlpha) BlendMode.SRC_ATOP else BlendMode.DST_OUT)
        }
    }

    fun fillWith(color: Int, label: String) {
        if (blockedByBusy()) return
        endInteractions()
        editActiveLayer(label) { l, sel ->
            DocOps.fillLayer(l, sel, paintColor(color), if (l.lockAlpha) BlendMode.SRC_ATOP else BlendMode.SRC_OVER)
        }
    }

    // ---------------------------------------------------------------- text

    fun startText(x: Int, y: Int) {
        val d = doc ?: return
        if (busy != null) return
        // Tapping an existing text layer edits it.
        val byId = Tree.byId(d.layers)
        val hit = d.layers.asReversed().firstOrNull { Tree.shows(it, byId) && it.text != null && it.bounds.contains(x, y) }
        text = if (hit != null) {
            silently(d.copy(activeId = hit.id))
            TextSession(hit.id, hit.x, hit.y, hit.text!!)
        } else {
            val size = (minOf(d.width, d.height) / 12f).coerceIn(12f, 400f)
            TextSession(null, x, y, io.github.capstasher.gako.doc.TextInfo("", size, fg))
        }
    }

    /** Copies a font file the user picked into GAKO's fonts and uses it for the text being edited. */
    fun importFont(uri: Uri) {
        viewModelScope.launch {
            val name = displayName(uri) ?: "Font.ttf"
            val ext = name.substringAfterLast('.', "ttf").lowercase().takeIf { it in setOf("ttf", "otf") } ?: "ttf"
            val dir = Fonts.userDir(getApplication<Application>().filesDir)
            val file = File(dir, name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "_") + "." + ext)
            val ok = withContext(Dispatchers.IO) {
                Fonts.forget(file.path)
                runCatching { resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } } }.isSuccess &&
                    Fonts.isFont(file.path) && Fonts.load(file.path) != null
            }
            if (!ok) {
                file.delete()
                say("That file isn't a font GAKO can use")
                return@launch
            }
            text?.let { it.info = it.info.copy(fontFile = file.path) }
            say("Added ${Fonts.pretty(file.nameWithoutExtension)}")
        }
    }

    /**
     * Turns the text being edited into a layer. [fromDone]: the user tapped Done, so text too big
     * for a layer stays open to fix; otherwise (something else needs the text finished) it's dropped.
     */
    fun commitText(fromDone: Boolean = false) {
        val t = text ?: return
        text = null
        val d = doc ?: return
        if (t.info.text.isBlank()) return
        val bmp = TextSession.render(t.info) ?: run {
            if (fromDone) {
                text = t
                say("That text is too big for one layer; make it smaller or break the lines")
            } else say("The text was too big for one layer and wasn't added")
            return
        }
        val existing = t.layerId?.let { d.layer(it) }
        if (existing != null) {
            // The text's box changes size: keep the mask where it was on the canvas.
            val mask = existing.mask?.let { old ->
                Bitmaps.blank(bmp.width, bmp.height, Color.WHITE).also {
                    Canvas(it).drawBitmap(old, (existing.x - t.x).toFloat(), (existing.y - t.y).toFloat(), null)
                }
            }
            change("Edit Text", d.replace(existing.copy(bitmap = bmp, text = t.info, x = t.x, y = t.y, mask = mask)))
        } else {
            val name = t.info.text.lineSequence().first().take(24)
            change("Add Text", insertAboveActive(d, Layer(Layer.newId(), name, bmp, t.x, t.y, text = t.info, legacy = d.newLayerLegacy)))
        }
    }

    fun cancelText() {
        text = null
    }

    // ---------------------------------------------------------------- curves

    fun startCurves(levels: Boolean = false) {
        val d = doc ?: run { say("Open an image first"); return }
        if (refuseGroup(d)) return
        val layer = targetOf(d) ?: return
        if (blockedByBusy()) return
        endInteractions()
        viewModelScope.launch {
            val proxy = proxyOf(layer.bitmap, FINE_SIDE)
            curves = CurvesSession(layer.id, proxy.bitmap, levels, Histogram.of(proxy.bitmap))
            updateCurvesPreview()
        }
    }

    /** Colours > Auto > White Balance: each colour channel stretched to its 0.5%/99.5% points. */
    fun whiteBalance() {
        val d = doc ?: run { say("Open an image first"); return }
        if (refuseGroup(d)) return
        val layer = targetOf(d) ?: return
        val onMask = editingMask
        docJob("White Balance") { state ->
            val c = CurvesSession(layer.id, layer.bitmap, true, Histogram.of(proxyFor(layer.bitmap)))
            c.autoLevels()
            val result = Curves.apply(layer.bitmap, c.luts())
            val merged = Compositor.selectionFor(layer, state.selection)?.let { Compositor.mergeThroughMask(layer.bitmap, result, it) } ?: result
            val current = state.layer(layer.id) ?: return@docJob null
            val out = Bitmaps.mutable(merged)
            state.replace(if (onMask && current.mask != null) current.copy(mask = out) else current.copy(bitmap = out, text = null))
        }
    }

    /** A small copy for statistics (histograms) without touching every pixel. */
    private fun proxyFor(b: Bitmap): Bitmap {
        val s = minOf(1.0, 1024.0 / max(b.width, b.height))
        return if (s >= 1.0) b else Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
    }

    private var curvesJob: Job? = null

    fun updateCurvesPreview() {
        val c = curves ?: return
        curvesJob?.cancel()
        curvesJob = viewModelScope.launch {
            val luts = c.luts()
            val out = withContext(Dispatchers.Default) { Curves.apply(c.proxy, luts) }
            if (curves === c) preview = out
        }
    }

    fun applyCurves() {
        val c = curves ?: return
        val d = doc ?: return
        val layer = targetOf(d)?.takeIf { it.id == c.layerId } ?: return
        val luts = c.luts()
        val onMask = editingMask
        curves = null
        preview = null
        previewPlacement = null
        docJob(if (c.levels) "Levels" else "Curves") { state ->
            val result = Curves.apply(layer.bitmap, luts)
            val merged = Compositor.selectionFor(layer, state.selection)?.let { Compositor.mergeThroughMask(layer.bitmap, result, it) } ?: result
            val current = state.layer(layer.id) ?: return@docJob null
            val out = Bitmaps.mutable(merged)
            state.replace(if (onMask && current.mask != null) current.copy(mask = out) else current.copy(bitmap = out, text = null))
        }
    }

    fun cancelCurves() {
        if (curves == null) return
        curvesJob?.cancel()
        curves = null
        preview = null
        previewPlacement = null
    }

    // ---------------------------------------------------------------- filters

    private val descriptions = mutableMapOf<String, OpDescription>()
    private var commitTag: Any? = null

    private suspend fun describe(operation: String): OpDescription? =
        descriptions[operation] ?: Engine.describe(operation)?.also { descriptions[operation] = it }

    private suspend fun proxyOf(src: Bitmap, side: Int): Proxy {
        val scale = minOf(1.0, side.toDouble() / max(src.width, src.height))
        if (scale >= 1.0) return Proxy(src, 1.0)
        val bmp = withContext(Dispatchers.Default) {
            Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt().coerceAtLeast(1),
                (src.height * scale).roundToInt().coerceAtLeast(1), true)
        }
        return Proxy(bmp, bmp.width.toDouble() / src.width)
    }

    private val filterPrefs = app.getSharedPreferences("filters", 0)

    /** GIMP's Filters > Recently Used, newest first. */
    var recentFilters by mutableStateOf(filterPrefs.getString("recent", "")!!.split(',').filter { it.isNotBlank() })
        private set
    private var lastFilter: Pair<String, Map<String, Any?>>? = null

    private fun rememberFilter(operation: String, values: Map<String, Any?>) {
        lastFilter = operation to values
        recentFilters = (listOf(operation) + recentFilters.filter { it != operation }).take(8)
        filterPrefs.edit().putString("recent", recentFilters.joinToString(",")).apply()
    }

    val lastFilterName: String? get() = lastFilter?.first

    /** Filters > Repeat Last: the last filter again, with the same settings, no dialog. */
    fun repeatLastFilter() {
        val (op, values) = lastFilter ?: run { say("No filter used yet"); return }
        val d = doc ?: return
        if (refuseGroup(d)) return
        val layer = targetOf(d) ?: return
        val onMask = editingMask
        if (blockedByBusy()) return
        endInteractions()
        viewModelScope.launch {
            val title = describe(op)?.title ?: op
            runCommit(title, op, values, layer, onMask)
        }
    }

    /** Opens the parameter panel for [operation], or applies it straight away if it has no settings. */
    fun startFilter(operation: String) {
        val d = doc ?: run { say("Open an image first"); return }
        if (refuseGroup(d)) return
        val layer = targetOf(d) ?: return
        val onMask = editingMask
        if (blockedByBusy()) return
        endInteractions()
        viewModelScope.launch {
            val desc = describe(operation) ?: run { say("$operation isn't available"); return@launch }
            if (desc.params.isEmpty()) {
                rememberFilter(operation, emptyMap())
                runCommit(desc.title, operation, emptyMap(), layer, onMask)
                return@launch
            }
            val src = layer.bitmap
            val fine = proxyOf(src, FINE_SIDE)
            val draft = if (fine.bitmap.width <= DRAFT_SIDE && fine.bitmap.height <= DRAFT_SIDE) fine
                        else proxyOf(fine.bitmap, DRAFT_SIDE).let { Proxy(it.bitmap, it.bitmap.width.toDouble() / src.width) }
            session = FilterSession(desc, desc.defaults(), layer.id, draft, fine, onMask)
            schedulePreview(immediate = true)
        }
    }

    fun setParam(name: String, value: Any?) {
        val s = session ?: return
        s.values = s.values + (name to value)
        schedulePreview()
    }

    fun resetParams() {
        val s = session ?: return
        s.values = s.op.defaults()
        schedulePreview()
    }

    /** A tap on the canvas, in canvas pixels. Sets the op's x/y point if it has one. */
    fun tapImage(x: Float, y: Float) {
        val s = session ?: return
        val (px, py) = s.point ?: return
        val layer = doc?.layer(s.layerId) ?: return
        val lx = x - layer.x
        val ly = y - layer.y
        val relative = px.unit == "relative-coordinate"
        val vx = if (relative) lx / layer.width.toDouble() else lx.toDouble()
        val vy = if (relative) ly / layer.height.toDouble() else ly.toDouble()
        s.values = s.values + (px.name to px.clamp(vx)) + (py.name to py.clamp(vy))
        schedulePreview(immediate = true)
    }

    private fun scaled(values: Map<String, Any?>, op: OpDescription, scale: Double): Map<String, Any?> {
        if (scale == 1.0) return values
        val byName = op.params.associateBy { it.name }
        return values.mapValues { (k, v) ->
            val p = byName[k]
            if (p is Param.Number && p.scalesWithImage && v is Number) {
                val s = v.toDouble() * scale
                if (p.isInteger) s.roundToInt() else s
            } else v
        }
    }

    private fun schedulePreview(immediate: Boolean = false) {
        val s = session ?: return
        previewJob?.cancel()
        Engine.cancel(tag = s)   // only ever stops this session's preview, never an Apply
        val gen = ++s.generation
        previewJob = viewModelScope.launch {
            if (!immediate) delay(PREVIEW_DEBOUNCE_MS)
            val indicator = launch {
                delay(PREVIEW_INDICATOR_MS)
                if (s.generation == gen) s.working = true
            }
            try {
                val quick = s.fineMs in 0..FAST_PREVIEW_MS
                if (s.draft !== s.fine && !quick) {
                    val t0 = SystemClock.elapsedRealtime()
                    if (!showPreview(s, gen, s.draft, preview(s, s.draft))) return@launch
                    val predicted = (SystemClock.elapsedRealtime() - t0) * (s.fine.pixels / s.draft.pixels)
                    val expected = if (s.fineMs >= 0) s.fineMs.toDouble() else predicted
                    if (expected > FINE_BUDGET_MS) return@launch   // heavy filter: the draft is the preview
                }
                val t1 = SystemClock.elapsedRealtime()
                if (showPreview(s, gen, s.fine, preview(s, s.fine))) s.fineMs = SystemClock.elapsedRealtime() - t1
            } finally {
                indicator.cancel()
                if (s.generation == gen) s.working = false
            }
        }
    }

    private suspend fun preview(s: FilterSession, proxy: Proxy): Engine.Result {
        // Grow the preview the same way Apply will, in proxy pixels.
        val limit = doc?.layer(s.layerId)?.let { growLimit(it, s.onMask) }?.let { r ->
            val k = proxy.scale
            Rect((r.left * k).toInt(), (r.top * k).toInt(), kotlin.math.ceil(r.right * k).toInt(), kotlin.math.ceil(r.bottom * k).toInt())
        }
        return Engine.apply(s.op.name, scaled(s.values, s.op, proxy.scale), proxy.bitmap, tag = s, growWithin = limit)
    }

    private fun showPreview(s: FilterSession, gen: Int, proxy: Proxy, result: Engine.Result): Boolean {
        if (s.generation != gen || session !== s) return false
        return when (result) {
            is Engine.Result.Ok -> {
                previewPlacement = if (result.x == 0 && result.y == 0 && result.bitmap.width == proxy.bitmap.width &&
                    result.bitmap.height == proxy.bitmap.height) null else Triple(result.x, result.y, proxy.scale)
                preview = result.bitmap
                s.error = null
                true
            }
            is Engine.Result.Failed -> { s.error = result.message; false }
            Engine.Result.Cancelled -> false
        }
    }

    fun cancelSession() {
        val s = session ?: return
        previewJob?.cancel()
        Engine.cancel(tag = s)
        session = null
        preview = null
        previewPlacement = null
    }

    /** The filter brush's source: a filtered copy of one layer, and what made it. */
    class FilterBrushSource(val layerId: Long, val bitmap: Bitmap, val title: String)
    var filterBrush by mutableStateOf<FilterBrushSource?>(null); private set

    /** Instead of applying the open filter everywhere, render it and paint it on with the Filter Brush. */
    fun paintSession() {
        val s = session ?: return
        val layer = doc?.let { targetOf(it) }?.takeIf { it.id == s.layerId } ?: return
        if (s.onMask) { say("The filter brush paints on layers, not masks"); return }
        if (blockedByBusy()) return
        previewJob?.cancel()
        Engine.cancel(tag = s)
        rememberFilter(s.op.name, s.values)
        val tag = Any()
        commitTag = tag
        viewModelScope.launch {
            busy = Busy(s.op.title, null, cancellable = true)
            val result = Engine.apply(s.op.name, s.values, layer.bitmap, tag) { p -> busy = busy?.copy(progress = p) }
            commitTag = null
            busy = null
            when (result) {
                is Engine.Result.Ok -> {
                    filterBrush = FilterBrushSource(layer.id, result.bitmap, s.op.title)
                    if (session === s) { session = null; preview = null; previewPlacement = null }
                    selectTool(Tool.FilterBrush)
                    say("Paint where you want ${s.op.title}")
                }
                is Engine.Result.Failed -> say("${s.op.title} failed: ${result.message}")
                Engine.Result.Cancelled -> say("${s.op.title} cancelled")
            }
        }
    }

    fun applySession() {
        val s = session ?: return
        val layer = doc?.let { targetOf(it) }?.takeIf { it.id == s.layerId } ?: return
        if (blockedByBusy()) return
        previewJob?.cancel()
        Engine.cancel(tag = s)
        rememberFilter(s.op.name, s.values)
        viewModelScope.launch {
            runCommit(s.op.title, s.op.name, s.values, layer, s.onMask)
            if (session === s) {
                session = null
                preview = null
                previewPlacement = null
            }
        }
    }

    /**
     * Where a filter on [layer] may grow it to, in layer coordinates: the layer and the canvas, and
     * only when no selection or mask pins its shape. Null means "don't grow".
     */
    private fun growLimit(layer: Layer, onMask: Boolean): Rect? {
        val d = doc ?: return null
        val real = d.layer(layer.id) ?: return null
        if (onMask || real.mask != null || d.selection != null) return null
        return Rect(0, 0, layer.width, layer.height).apply { union(-layer.x, -layer.y, d.width - layer.x, d.height - layer.y) }
    }

    private suspend fun runCommit(title: String, operation: String, values: Map<String, Any?>, layer: Layer, onMask: Boolean) {
        val tag = Any()
        commitTag = tag
        busy = Busy(title, null, cancellable = true)
        val src = layer.bitmap
        // The selection is fixed when the filter starts; it's what the result is merged through.
        val selMask = doc?.let { d -> d.layer(layer.id)?.let { Compositor.selectionFor(it, d.selection) } }
        // Like GIMP, a filter that draws past the layer (drop shadow, glow) grows it, within the canvas.
        val limit = if (selMask == null) growLimit(layer, onMask) else null
        val result = Engine.apply(operation, values, src, tag, growWithin = limit) { p -> busy = busy?.copy(progress = p) }
        commitTag = null
        try {
            when (result) {
                is Engine.Result.Ok -> {
                    val grown = result.x != 0 || result.y != 0 || result.bitmap.width != src.width || result.bitmap.height != src.height
                    val merged = if (selMask != null && !grown) withContext(Dispatchers.Default) {
                        Compositor.mergeThroughMask(src, result.bitmap, selMask)
                    } else result.bitmap
                    preview = null
                    previewPlacement = null
                    val d = doc ?: return
                    val current = d.layer(layer.id) ?: return
                    val out = Bitmaps.mutable(merged)
                    change(title, d.replace(
                        if (onMask && current.mask != null) current.copy(mask = out)
                        else current.copy(bitmap = out, text = null, x = current.x + result.x, y = current.y + result.y)))
                }
                is Engine.Result.Failed -> say("$title failed: ${result.message}")
                Engine.Result.Cancelled -> say("$title cancelled")
            }
        } finally {
            busy = null
        }
    }

    fun cancelBusy() {
        if (busy?.cancellable == true) commitTag?.let { Engine.cancel(it) }
    }

    /** Times a plain Desaturate on a 3 MP test image, for the About screen. */
    suspend fun engineCheck(): String {
        if (busy != null || engine !is EngineState.Ready) return "Engine busy"
        val w = 2048
        val h = 1536
        val test = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                val row = IntArray(w)
                for (y in 0 until h) {
                    for (x in 0 until w) row[x] = Color.rgb(x * 255 / w, y * 255 / h, (x + y) and 0xFF)
                    setPixels(row, 0, w, 0, y, w, 1)
                }
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        val r = Engine.apply("gegl:gray", emptyMap(), test)
        val ms = SystemClock.elapsedRealtime() - t0
        return when (r) {
            is Engine.Result.Ok -> "Desaturate, 3 MP: ${"%.2f".format(ms / 1000.0)} s on ${Engine.threads} threads"
            is Engine.Result.Failed -> "Engine check failed: ${r.message}"
            Engine.Result.Cancelled -> "Engine check cancelled"
        }
    }

    companion object {
        /** Cap on image size (Android won't draw a bitmap over ~100 MB). */
        const val MAX_PIXELS = 24_000_000L
        /** Preview sizes: the sharp copy, and the quick draft heavy filters fall back to. */
        const val FINE_SIDE = 1600
        const val DRAFT_SIDE = 512
        const val PREVIEW_DEBOUNCE_MS = 60L
        /** Below this, a filter skips the draft and goes straight to the sharp preview. */
        const val FAST_PREVIEW_MS = 150L
        /** Above this, a filter's preview stays on the draft. */
        const val FINE_BUDGET_MS = 1200.0
        /** A preview gets a small spinner only if it runs longer than this. */
        const val PREVIEW_INDICATOR_MS = 600L
        /** Quiet time after an edit before the recovery copy is refreshed. */
        const val AUTOSAVE_IDLE_MS = 20_000L
    }
}

/** Dialogs the menus open. */
sealed interface Dialog {
    data object NewImage : Dialog
    data object CanvasSize : Dialog
    data object ScaleImage : Dialog
    data object RotateImage : Dialog
    data object ScaleLayer : Dialog
    data class RenameLayer(val id: Long) : Dialog
    data class ModifySelection(val kind: EditorViewModel.SelectionChange) : Dialog
    data object NewLayer : Dialog
    data class PickColor(val foreground: Boolean) : Dialog
    data object AddMask : Dialog
    data object History : Dialog
    data object NewGuide : Dialog
    data object Channels : Dialog
}
