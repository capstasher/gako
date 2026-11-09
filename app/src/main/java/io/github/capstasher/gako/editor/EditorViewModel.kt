package io.github.capstasher.gako.editor

import android.app.ActivityManager
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.capstasher.gako.engine.Engine
import io.github.capstasher.gako.engine.OpDescription
import io.github.capstasher.gako.engine.OpInfo
import io.github.capstasher.gako.engine.Param
import io.github.capstasher.gako.export.ExportFormat
import io.github.capstasher.gako.export.ExportOptions
import io.github.capstasher.gako.export.Exporter
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

    /** A downscaled copy of the image used for previews, and its scale relative to the image. */
    class Proxy(val bitmap: Bitmap, val scale: Double) {
        val pixels: Double get() = bitmap.width.toDouble() * bitmap.height
    }

    /**
     * An operation being tuned. Previews run on a quick [draft] first and, if the filter is fast
     * enough, a sharper [fine] copy; heavy filters stay on the draft so the sliders stay live.
     */
    class FilterSession(
        val op: OpDescription,
        values: Map<String, Any?>,
        val draft: Proxy,
        val fine: Proxy,
    ) {
        var values by mutableStateOf(values)
        /** Only true once a preview has been running long enough to be worth showing. */
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
    var image by mutableStateOf<Bitmap?>(null); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var documentName by mutableStateOf("Untitled"); private set
    var busy by mutableStateOf<Busy?>(null); private set
    var session by mutableStateOf<FilterSession?>(null); private set
    var comparing by mutableStateOf(false)
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()
    private var previewJob: Job? = null
    private var commitJob: Job? = null
    private val resolver get() = getApplication<Application>().contentResolver

    /** How much image memory undo may hold, from the device's RAM. */
    private val undoBudget: Long = run {
        val mi = ActivityManager.MemoryInfo()
        app.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        (mi.totalMem / 6).coerceIn(256L shl 20, 1536L shl 20)
    }

    val operations: List<OpInfo> get() = (engine as? EngineState.Ready)?.operations.orEmpty()

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

    private fun say(message: String) {
        messageChannel.trySend(message)
    }

    // ---------------------------------------------------------------- files

    fun open(uri: Uri) {
        if (blockedByBusy()) return
        cancelSession()
        busy = Busy("Opening")
        viewModelScope.launch {
            try {
                val (bitmap, note) = withContext(Dispatchers.IO) { decode(uri) }
                undoStack.clear(); redoStack.clear(); updateHistoryFlags()
                image = bitmap
                preview = null
                documentName = displayName(uri) ?: "Untitled"
                note?.let(::say)
            } catch (t: Throwable) {
                say("Couldn't open that image: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    private fun decode(uri: Uri): Pair<Bitmap, String?> {
        var note: String? = null
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded
                     else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
        return bitmap to note
    }

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun baseName() = documentName.substringBeforeLast('.').ifBlank { "untitled" }

    // ---------------------------------------------------------------- export

    /** GIMP's Export Image dialog: a name (its extension picks the type), a folder and options. */
    class ExportSession(name: String, format: ExportFormat, options: ExportOptions) {
        var name by mutableStateOf(name)
        var format by mutableStateOf(format)
        var options by mutableStateOf(options)

        /** Typing an extension switches the file type, like GIMP's "By extension". */
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

    /** null means the default, Pictures/GAKO. */
    data class ExportFolder(val uri: Uri, val name: String)

    private val prefs = app.getSharedPreferences("export", 0)

    var exportSession by mutableStateOf<ExportSession?>(null); private set
    var exportFolder by mutableStateOf(
        prefs.getString("folderUri", null)?.let { ExportFolder(Uri.parse(it), prefs.getString("folderName", "Folder") ?: "Folder") }
    ); private set
    var exportToFolder by mutableStateOf(prefs.getBoolean("toFolder", false) && exportFolder != null)

    fun startExport() {
        if (image == null || blockedByBusy()) return
        cancelSession()
        val format = runCatching { ExportFormat.valueOf(prefs.getString("format", "Png")!!) }.getOrDefault(ExportFormat.Png)
        val options = ExportOptions(
            quality = prefs.getInt("quality", 90),
            webpLossless = prefs.getBoolean("webpLossless", false),
            gifDither = prefs.getBoolean("gifDither", true),
            tiffDeflate = prefs.getBoolean("tiffDeflate", true),
            tgaRle = prefs.getBoolean("tgaRle", true),
        )
        exportSession = ExportSession("${baseName()}.${format.ext}", format, options)
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
        val bmp = image ?: return
        if (busy != null) return
        val name = s.name.trim()
        if (name.isEmpty()) { say("Give the file a name"); return }
        val format = s.format
        val fileName = if (ExportFormat.forFileName(name) == format) name else "$name.${format.ext}"
        val folder = exportFolder.takeIf { exportToFolder }
        prefs.edit()
            .putString("format", format.name)
            .putInt("quality", s.options.quality)
            .putBoolean("webpLossless", s.options.webpLossless)
            .putBoolean("gifDither", s.options.gifDither)
            .putBoolean("tiffDeflate", s.options.tiffDeflate)
            .putBoolean("tgaRle", s.options.tgaRle)
            .putBoolean("toFolder", exportToFolder)
            .apply()

        busy = Busy("Exporting")
        viewModelScope.launch {
            try {
                val where = withContext(Dispatchers.IO) {
                    val scratch = File(getApplication<Application>().cacheDir, "export")
                    if (folder != null) writeToFolder(folder, fileName, format) { Exporter.encode(bmp, format, s.options, it, scratch) }
                    else writeToPictures(fileName, format) { Exporter.encode(bmp, format, s.options, it, scratch) }
                }
                exportSession = null
                say("Exported $fileName to $where")
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

    /** Writes a PNG for the share sheet and returns its content Uri. */
    suspend fun shareableUri(): Uri? {
        val bmp = image ?: return null
        busy = Busy("Preparing")
        return try {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val dir = File(app.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, "${baseName()}.png")
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            }
        } finally {
            busy = null
        }
    }

    // ---------------------------------------------------------------- history

    private fun commit(result: Bitmap) {
        image?.let { pushBounded(undoStack, it) }
        redoStack.clear()
        image = result
        preview = null
        updateHistoryFlags()
    }

    private fun pushBounded(stack: ArrayDeque<Bitmap>, bitmap: Bitmap) {
        stack.addLast(bitmap)
        while (stack.size > 1 && stack.sumOf { it.allocationByteCount.toLong() } > undoBudget) {
            stack.removeFirst()
        }
    }

    private fun updateHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** True (and says why) if a long job is running, so taps never vanish silently. */
    private fun blockedByBusy(): Boolean {
        val b = busy ?: return false
        say(if (b.cancellable) "Still working on ${b.label}. Tap ✕ to stop it." else "Still ${b.label.lowercase()}…")
        return true
    }

    fun undo() {
        if (blockedByBusy() || session != null) return
        val previous = undoStack.removeLastOrNull() ?: return
        image?.let { pushBounded(redoStack, it) }
        image = previous
        updateHistoryFlags()
    }

    fun redo() {
        if (blockedByBusy() || session != null) return
        val next = redoStack.removeLastOrNull() ?: return
        image?.let { pushBounded(undoStack, it) }
        image = next
        updateHistoryFlags()
    }

    // ---------------------------------------------------------------- image ops

    fun rotate(degrees: Float) = transform(Matrix().apply { postRotate(degrees) })
    fun flip(horizontal: Boolean) = transform(Matrix().apply { if (horizontal) postScale(-1f, 1f) else postScale(1f, -1f) })

    private fun transform(matrix: Matrix) {
        val src = image ?: return
        if (blockedByBusy() || session != null) return
        viewModelScope.launch {
            busy = Busy("Transforming")
            val out = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            }
            commit(out)
            busy = null
        }
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
        val src = image ?: run { say("Open an image first"); return }
        if (blockedByBusy()) return
        cancelSession()
        exportSession = null
        viewModelScope.launch {
            val desc = describe(operation) ?: run { say("$operation isn't available"); return@launch }
            if (desc.params.isEmpty()) {
                runCommit(desc.title, operation, emptyMap(), src)
                return@launch
            }
            val fine = proxyOf(src, FINE_SIDE)
            val draft = if (fine.bitmap.width <= DRAFT_SIDE && fine.bitmap.height <= DRAFT_SIDE) fine
                        else proxyOf(fine.bitmap, DRAFT_SIDE).let { Proxy(it.bitmap, it.bitmap.width.toDouble() / src.width) }
            session = FilterSession(desc, desc.defaults(), draft, fine)
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

    /** A tap on the canvas, in image pixels. Sets the op's x/y point if it has one. */
    fun tapImage(x: Float, y: Float) {
        val s = session ?: return
        val (px, py) = s.point ?: return
        val src = image ?: return
        val relative = px.unit == "relative-coordinate"
        val vx = if (relative) x / src.width.toDouble() else x.toDouble()
        val vy = if (relative) y / src.height.toDouble() else y.toDouble()
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
        val src = image ?: return
        if (blockedByBusy()) return
        previewJob?.cancel()
        Engine.cancel(tag = s)
        viewModelScope.launch {
            runCommit(s.op.title, s.op.name, s.values, src)
            if (session === s) session = null
        }
    }

    private suspend fun runCommit(title: String, operation: String, values: Map<String, Any?>, src: Bitmap) {
        val tag = Any()
        commitTag = tag
        busy = Busy(title, null, cancellable = true)
        val result = Engine.apply(operation, values, src, tag) { p -> busy = busy?.copy(progress = p) }
        busy = null
        commitTag = null
        when (result) {
            is Engine.Result.Ok -> commit(result.bitmap)
            is Engine.Result.Failed -> say("$title failed: ${result.message}")
            Engine.Result.Cancelled -> say("$title cancelled")
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
                    for (x in 0 until w) row[x] = android.graphics.Color.rgb(x * 255 / w, y * 255 / h, (x + y) and 0xFF)
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
        /** Stage-1 cap on opened images (Android won't draw a bitmap over 100 MB);
         *  stage 2 moves documents into tiled GEGL buffers. */
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
