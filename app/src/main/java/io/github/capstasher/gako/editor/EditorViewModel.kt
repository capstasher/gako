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
    /** The filter/curves preview of the active layer (a downscaled copy), or null. */
    var preview by mutableStateOf<Bitmap?>(null); internal set
    var documentName by mutableStateOf("Untitled"); private set
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
            val error = try {
                Engine.start(app)
            } catch (t: Throwable) {
                t.message ?: t.toString()
            }
            engine = if (error != null) EngineState.Failed(error)
                     else EngineState.Ready(Engine.operations())
        }
    }

    internal fun say(message: String) {
        messageChannel.trySend(message)
    }

    /** Anything modal (filter, text, curves, a tool mid-edit) that should end before another action. */
    private fun endInteractions() {
        cancelSession()
        cancelText()
        cancelCurves()
        input.reset()
        exportSession = null
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

    fun updateOptions(o: ToolOptions) {
        options = o
        toolPrefs.edit()
            .putFloat("size", o.size).putFloat("opacity", o.opacity).putFloat("hardness", o.hardness)
            .putInt("threshold", o.threshold).putFloat("feather", o.feather).putBoolean("sampleMerged", o.sampleMerged)
            .apply()
    }

    private fun loadOptions() = ToolOptions(
        size = toolPrefs.getFloat("size", 24f),
        opacity = toolPrefs.getFloat("opacity", 1f),
        hardness = toolPrefs.getFloat("hardness", 0.75f),
        threshold = toolPrefs.getInt("threshold", 15),
        feather = toolPrefs.getFloat("feather", 0f),
        sampleMerged = toolPrefs.getBoolean("sampleMerged", false),
    )

    fun setForeground(c: Int) {
        fg = c
        toolPrefs.edit().putInt("fg", c).apply()
    }

    fun setBackground(c: Int) {
        bg = c
        toolPrefs.edit().putInt("bg", c).apply()
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

    internal fun record(step: Step) {
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
    }

    /** Changes the document without an undo step (e.g. picking the active layer). */
    internal fun silently(after: DocState) {
        doc = after
    }

    internal fun pixelsChanged() {
        revision++
    }

    private fun updateHistoryFlags() {
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
        if (input.hasPendingEdit()) { input.reset(); return }
        if (session != null || text != null || curves != null) return
        val step = undoStack.removeLastOrNull() ?: return
        when (step) {
            is Step.Snapshot -> doc = step.before
            is Step.Patch -> doc?.layer(step.layerId)?.let { Bitmaps.put(it.bitmap, step.before, step.rect.left, step.rect.top) }
        }
        redoStack.addLast(step)
        revision++
        dirty = true
        updateHistoryFlags()
        input.syncAfterHistory()
    }

    fun redo() {
        if (blockedByBusy()) return
        if (session != null || text != null || curves != null) return
        input.reset()
        val step = redoStack.removeLastOrNull() ?: return
        when (step) {
            is Step.Snapshot -> doc = step.after
            is Step.Patch -> doc?.layer(step.layerId)?.let { Bitmaps.put(it.bitmap, step.after, step.rect.left, step.rect.top) }
        }
        undoStack.addLast(step)
        revision++
        dirty = true
        updateHistoryFlags()
        input.syncAfterHistory()
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
                if (out != null && doc === d) change(label, out)
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
        val layer = d.active ?: return false
        val sel = Compositor.selectionFor(layer, d.selection)
        val r = DocOps.layerRect(layer, canvasRect ?: d.selection?.bounds)
        if (r.isEmpty) { say("Nothing to change there on “${layer.name}”"); return false }
        val before = Bitmaps.crop(layer.bitmap, r)
        edit(layer, sel)
        record(Step.Patch(label, layer.id, r, before, Bitmaps.crop(layer.bitmap, r)))
        revision++
        return true
    }

    // ---------------------------------------------------------------- files

    private fun newDocument(state: DocState, name: String) {
        endInteractions()
        resetHistory()
        doc = state
        preview = null
        documentName = name
        dirty = false
        revision++
        if (tool == Tool.Crop) input.startCrop()
        if (tool == Tool.Transform) input.startTransform()
    }

    fun newImage(w: Int, h: Int, fill: Int?) {
        if (blockedByBusy()) return
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) { say("That size isn't possible (max ${MAX_PIXELS / 1_000_000} MP)"); return }
        newDocument(DocOps.newImage(w, h, fill), "Untitled")
    }

    fun open(uri: Uri, asLayers: Boolean = false) {
        if (blockedByBusy()) return
        endInteractions()
        busy = Busy("Opening")
        viewModelScope.launch {
            try {
                val name = displayName(uri) ?: "Untitled"
                val (state, note) = withContext(Dispatchers.IO) { load(uri, name) }
                val d = doc
                if (asLayers && d != null) {
                    // Each opened layer goes in centred above the active one.
                    val added = state.layers.map {
                        it.copy(id = Layer.newId(), name = if (state.layers.size == 1) name.substringBeforeLast('.') else it.name,
                            x = it.x + (d.width - state.width) / 2, y = it.y + (d.height - state.height) / 2)
                    }
                    val i = d.activeIndex + 1
                    val layers = d.layers.toMutableList().apply { addAll(i, added) }
                    change("Open as Layers", d.copy(layers = layers, activeId = added.last().id))
                } else {
                    newDocument(state, name)
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

    private fun load(uri: Uri, name: String): Pair<DocState, String?> {
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val lower = name.lowercase()
        if (lower.endsWith(".ora") || mime == "image/openraster") {
            return resolver.openInputStream(uri)!!.use { OraFile.read(it) } to null
        }
        if (lower.endsWith(".xcf") || mime == "image/x-xcf") {
            return resolver.openInputStream(uri)!!.use { io.github.capstasher.gako.doc.XcfReader.read(it) } to null
        }
        val (bitmap, note) = decode(uri)
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
            .putBoolean("toFolder", exportToFolder)
        if (!format.layered) e.putString("format", format.name)
        e.apply()

        busy = Busy(if (format.layered) "Saving" else "Exporting")
        viewModelScope.launch {
            try {
                val where = withContext(Dispatchers.IO) {
                    val scratch = File(getApplication<Application>().cacheDir, "export")
                    val write: (java.io.OutputStream) -> Unit = { Exporter.encode(d, format, s.options, it, scratch) }
                    if (folder != null) writeToFolder(folder, fileName, format, write)
                    else writeToPictures(fileName, format, write)
                }
                exportSession = null
                if (format.layered) {
                    documentName = fileName
                    dirty = false
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

    private fun writeToPictures(fileName: String, format: ExportFormat, write: (java.io.OutputStream) -> Unit): String {
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
        return where
    }

    private fun writeToFolder(folder: ExportFolder, fileName: String, format: ExportFormat,
                              write: (java.io.OutputStream) -> Unit): String {
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
        return folder.name
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

    private fun insertAboveActive(d: DocState, layer: Layer): DocState {
        val i = (d.activeIndex + 1).coerceIn(0, d.layers.size)
        return d.copy(layers = d.layers.toMutableList().apply { add(i, layer) }, activeId = layer.id)
    }

    enum class NewFill(val label: String) { Transparent("Transparency"), Foreground("Foreground"), Background("Background"), White("White") }

    fun newLayer(fill: NewFill = NewFill.Transparent) {
        val d = doc ?: return
        if (blockedByBusy()) return
        endInteractions()
        val color = when (fill) {
            NewFill.Transparent -> 0
            NewFill.Foreground -> fg
            NewFill.Background -> bg
            NewFill.White -> Color.WHITE
        }
        val l = Layer(Layer.newId(), layerName(d, "Layer"), Bitmaps.blank(d.width, d.height, color))
        change("New Layer", insertAboveActive(d, l))
    }

    fun duplicateLayer() {
        val d = doc ?: return
        val a = d.active ?: return
        if (blockedByBusy()) return
        endInteractions()
        val copy = a.copy(id = Layer.newId(), name = layerName(d, "${a.name} copy"), bitmap = Bitmaps.copy(a.bitmap))
        change("Duplicate Layer", insertAboveActive(d, copy))
    }

    fun deleteLayer() {
        val d = doc ?: return
        val a = d.active ?: return
        if (blockedByBusy()) return
        if (d.layers.size <= 1) { say("An image needs at least one layer"); return }
        endInteractions()
        val i = d.activeIndex
        val layers = d.layers.filter { it.id != a.id }
        change("Delete Layer", d.copy(layers = layers, activeId = layers[(i - 1).coerceAtLeast(0)].id))
    }

    fun moveLayer(delta: Int) {
        val d = doc ?: return
        if (blockedByBusy()) return
        val i = d.activeIndex
        val j = (i + delta).coerceIn(0, d.layers.lastIndex)
        if (i == j) return
        val layers = d.layers.toMutableList()
        val l = layers.removeAt(i)
        layers.add(j, l)
        change(if (delta > 0) "Raise Layer" else "Lower Layer", d.copy(layers = layers))
    }

    fun mergeDown() {
        val d = doc ?: return
        if (d.activeIndex <= 0) { say("There's no layer below to merge into"); return }
        docJob("Merge Down") { DocOps.mergeDown(it) }
    }

    fun setActiveLayer(id: Long) {
        val d = doc ?: return
        if (d.activeId == id || session != null || curves != null) return
        if (text != null) commitText()
        input.reset()
        silently(d.copy(activeId = id))
        if (tool == Tool.Transform) input.startTransform()
    }

    fun setLayerVisible(id: Long, visible: Boolean) {
        val d = doc ?: return
        val l = d.layer(id) ?: return
        change(if (visible) "Show Layer" else "Hide Layer", d.replace(l.copy(visible = visible)))
    }

    private var propertyBefore: DocState? = null

    /** Live opacity change while a slider moves; [commitLayerProperty] records it. */
    fun setLayerOpacityLive(opacity: Float) {
        val d = doc ?: return
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
        val l = d.active ?: return
        if (l.mode != mode) change("Layer Mode", d.replace(l.copy(mode = mode)))
    }

    fun renameLayer(id: Long, name: String) {
        val d = doc ?: return
        val l = d.layer(id) ?: return
        val n = name.trim().ifEmpty { return }
        if (n != l.name) change("Rename Layer", d.replace(l.copy(name = n)))
    }

    fun setLockAlpha(lock: Boolean) {
        val d = doc ?: return
        val l = d.active ?: return
        change("Lock Alpha Channel", d.replace(l.copy(lockAlpha = lock)))
    }

    fun layerToImageSize() = doc?.active?.let { a -> docJob("Layer to Image Size") { DocOps.layerToImageSize(it, a.id) } }
    fun scaleLayer(w: Int, h: Int) = doc?.active?.let { a -> docJob("Scale Layer") { DocOps.scaleLayer(it, a.id, w, h) } }

    fun alphaToSelection() {
        val d = doc ?: return
        val a = d.active ?: return
        docJob("Alpha to Selection") { it.copy(selection = Selections.make(Selections.alphaOf(a, it.width, it.height))) }
    }

    fun newFromVisible() = docJob("New from Visible") {
        val l = Layer(Layer.newId(), layerName(it, "Visible"), Compositor.flatten(it))
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
        input.reset()
        setSelection("Select All", Selections.all(d.width, d.height))
    }

    fun selectNone() {
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

    private suspend fun run(src: Bitmap, op: String, params: Map<String, Any?>): Bitmap? =
        when (val r = Engine.apply(op, params, src)) {
            is Engine.Result.Ok -> r.bitmap
            is Engine.Result.Failed -> { say("$op failed: ${r.message}"); null }
            Engine.Result.Cancelled -> null
        }

    // ---------------------------------------------------------------- edit

    private var clipboard: Pair<Bitmap, Rect>? = null
    val hasClipboard get() = clipboard != null

    private fun copySelected(merged: Boolean): Pair<Bitmap, Rect>? {
        val d = doc ?: return null
        val source: Layer = if (merged) Layer(0, "", Compositor.flatten(d)) else d.active ?: return null
        val sel = d.selection
        val area = Rect(sel?.bounds ?: source.bounds)
        if (!area.intersect(source.bounds)) return null
        val out = Bitmaps.blank(area.width(), area.height())
        val c = Canvas(out)
        c.drawBitmap(source.bitmap, (source.x - area.left).toFloat(), (source.y - area.top).toFloat(), null)
        if (sel != null) c.drawBitmap(sel.mask, -area.left.toFloat(), -area.top.toFloat(), Paint().apply { blendMode = BlendMode.DST_IN })
        return out to area
    }

    fun copy(merged: Boolean = false) {
        if (blockedByBusy()) return
        clipboard = copySelected(merged) ?: run { say("Nothing to copy"); return }
        say(if (merged) "Copied the visible image" else "Copied")
    }

    fun cut() {
        if (blockedByBusy()) return
        clipboard = copySelected(false) ?: return
        clear("Cut")
    }

    fun paste(asNewImage: Boolean = false) {
        if (blockedByBusy()) return
        val (b, r) = clipboard ?: run { say("The clipboard is empty"); return }
        if (asNewImage) {
            newDocument(DocOps.fromBitmap(Bitmaps.copy(b), "Pasted"), "Pasted")
            return
        }
        val d = doc ?: run { newDocument(DocOps.fromBitmap(Bitmaps.copy(b), "Pasted"), "Pasted"); return }
        endInteractions()
        val x = if (r.right <= d.width && r.bottom <= d.height) r.left else (d.width - b.width) / 2
        val y = if (r.right <= d.width && r.bottom <= d.height) r.top else (d.height - b.height) / 2
        val l = Layer(Layer.newId(), layerName(d, "Pasted Layer"), Bitmaps.copy(b), x, y)
        change("Paste", insertAboveActive(d, l))
        if (tool != Tool.Move && tool != Tool.Transform) selectTool(Tool.Move)
        say("Pasted as a new layer. Drag to place it.")
    }

    fun clear(label: String = "Clear") {
        if (blockedByBusy()) return
        endInteractions()
        editActiveLayer(label) { l, sel ->
            val erase = if (l.lockAlpha) bg else 0
            DocOps.fillLayer(l, sel, if (l.lockAlpha) erase else Color.BLACK,
                if (l.lockAlpha) BlendMode.SRC_ATOP else BlendMode.DST_OUT)
        }
    }

    fun fillWith(color: Int, label: String) {
        if (blockedByBusy()) return
        endInteractions()
        editActiveLayer(label) { l, sel ->
            DocOps.fillLayer(l, sel, color, if (l.lockAlpha) BlendMode.SRC_ATOP else BlendMode.SRC_OVER)
        }
    }

    // ---------------------------------------------------------------- text

    fun startText(x: Int, y: Int) {
        val d = doc ?: return
        if (busy != null) return
        // Tapping an existing text layer edits it.
        val hit = d.layers.asReversed().firstOrNull { it.visible && it.text != null && it.bounds.contains(x, y) }
        text = if (hit != null) {
            silently(d.copy(activeId = hit.id))
            TextSession(hit.id, hit.x, hit.y, hit.text!!)
        } else {
            val size = (minOf(d.width, d.height) / 12f).coerceIn(12f, 400f)
            TextSession(null, x, y, io.github.capstasher.gako.doc.TextInfo("", size, fg))
        }
    }

    fun commitText() {
        val t = text ?: return
        text = null
        val d = doc ?: return
        if (t.info.text.isBlank()) return
        val bmp = TextSession.render(t.info)
        val existing = t.layerId?.let { d.layer(it) }
        if (existing != null) {
            change("Edit Text", d.replace(existing.copy(bitmap = bmp, text = t.info, x = t.x, y = t.y)))
        } else {
            val name = t.info.text.lineSequence().first().take(24)
            change("Add Text", insertAboveActive(d, Layer(Layer.newId(), name, bmp, t.x, t.y, text = t.info)))
        }
    }

    fun cancelText() {
        text = null
    }

    // ---------------------------------------------------------------- curves

    fun startCurves(levels: Boolean = false) {
        val d = doc ?: run { say("Open an image first"); return }
        val layer = d.active ?: return
        if (blockedByBusy()) return
        endInteractions()
        viewModelScope.launch {
            val proxy = proxyOf(layer.bitmap, FINE_SIDE)
            curves = CurvesSession(layer.id, proxy.bitmap, levels, Histogram.of(proxy.bitmap))
            updateCurvesPreview()
        }
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
        val layer = d.layer(c.layerId) ?: return
        val luts = c.luts()
        curves = null
        preview = null
        docJob(if (c.levels) "Levels" else "Curves") { state ->
            val result = Curves.apply(layer.bitmap, luts)
            val merged = Compositor.selectionFor(layer, state.selection)?.let { Compositor.mergeThroughMask(layer.bitmap, result, it) } ?: result
            state.replace(layer.copy(bitmap = Bitmaps.mutable(merged), text = null))
        }
    }

    fun cancelCurves() {
        if (curves == null) return
        curvesJob?.cancel()
        curves = null
        preview = null
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

    /** Opens the parameter panel for [operation], or applies it straight away if it has no settings. */
    fun startFilter(operation: String) {
        val d = doc ?: run { say("Open an image first"); return }
        val layer = d.active ?: return
        if (blockedByBusy()) return
        endInteractions()
        viewModelScope.launch {
            val desc = describe(operation) ?: run { say("$operation isn't available"); return@launch }
            if (desc.params.isEmpty()) {
                runCommit(desc.title, operation, emptyMap(), layer)
                return@launch
            }
            val src = layer.bitmap
            val fine = proxyOf(src, FINE_SIDE)
            val draft = if (fine.bitmap.width <= DRAFT_SIDE && fine.bitmap.height <= DRAFT_SIDE) fine
                        else proxyOf(fine.bitmap, DRAFT_SIDE).let { Proxy(it.bitmap, it.bitmap.width.toDouble() / src.width) }
            session = FilterSession(desc, desc.defaults(), layer.id, draft, fine)
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
                    if (!showPreview(s, gen, preview(s, s.draft))) return@launch
                    val predicted = (SystemClock.elapsedRealtime() - t0) * (s.fine.pixels / s.draft.pixels)
                    val expected = if (s.fineMs >= 0) s.fineMs.toDouble() else predicted
                    if (expected > FINE_BUDGET_MS) return@launch   // heavy filter: the draft is the preview
                }
                val t1 = SystemClock.elapsedRealtime()
                if (showPreview(s, gen, preview(s, s.fine))) s.fineMs = SystemClock.elapsedRealtime() - t1
            } finally {
                indicator.cancel()
                if (s.generation == gen) s.working = false
            }
        }
    }

    private suspend fun preview(s: FilterSession, proxy: Proxy) =
        Engine.apply(s.op.name, scaled(s.values, s.op, proxy.scale), proxy.bitmap, tag = s)

    private fun showPreview(s: FilterSession, gen: Int, result: Engine.Result): Boolean {
        if (s.generation != gen || session !== s) return false
        return when (result) {
            is Engine.Result.Ok -> { preview = result.bitmap; s.error = null; true }
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
    }

    fun applySession() {
        val s = session ?: return
        val layer = doc?.layer(s.layerId) ?: return
        if (blockedByBusy()) return
        previewJob?.cancel()
        Engine.cancel(tag = s)
        viewModelScope.launch {
            runCommit(s.op.title, s.op.name, s.values, layer)
            if (session === s) {
                session = null
                preview = null
            }
        }
    }

    private suspend fun runCommit(title: String, operation: String, values: Map<String, Any?>, layer: Layer) {
        val tag = Any()
        commitTag = tag
        busy = Busy(title, null, cancellable = true)
        val src = layer.bitmap
        val result = Engine.apply(operation, values, src, tag) { p -> busy = busy?.copy(progress = p) }
        commitTag = null
        try {
            when (result) {
                is Engine.Result.Ok -> {
                    val d = doc ?: return
                    val current = d.layer(layer.id) ?: return
                    val mask = Compositor.selectionFor(current, d.selection)
                    val merged = if (mask != null) withContext(Dispatchers.Default) {
                        Compositor.mergeThroughMask(src, result.bitmap, mask)
                    } else result.bitmap
                    preview = null
                    change(title, d.replace(current.copy(bitmap = Bitmaps.mutable(merged), text = null)))
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
    }
}

/** Dialogs the menus open. */
sealed interface Dialog {
    data object NewImage : Dialog
    data object CanvasSize : Dialog
    data object ScaleImage : Dialog
    data object ScaleLayer : Dialog
    data class RenameLayer(val id: Long) : Dialog
    data class ModifySelection(val kind: EditorViewModel.SelectionChange) : Dialog
    data object NewLayer : Dialog
    data class PickColor(val foreground: Boolean) : Dialog
}
