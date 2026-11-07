package io.github.capstasher.gako.editor

import android.app.ActivityManager
import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
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

    /** An operation being tuned: parameters are previewed live on a downscaled proxy. */
    class FilterSession(
        val op: OpDescription,
        values: Map<String, Any?>,
        val proxy: Bitmap,
        val proxyScale: Double,
    ) {
        var values by mutableStateOf(values)
        var previewing by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)

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
        if (busy != null) return
        cancelSession()
        busy = Busy("Opening…")
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
                note = "Opened at $tw×$th (from $w×$h). Full-size editing comes with tiled documents in stage 2."
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

    enum class ExportFormat(val label: String, val ext: String, val mime: String, val format: Bitmap.CompressFormat, val quality: Int) {
        Png("PNG", "png", "image/png", Bitmap.CompressFormat.PNG, 100),
        Jpeg("JPEG", "jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 92),
        Webp("WebP", "webp", "image/webp", Bitmap.CompressFormat.WEBP_LOSSLESS, 100),
    }

    private fun exportBaseName() = documentName.substringBeforeLast('.').ifBlank { "untitled" } + "-gako"

    fun export(format: ExportFormat) {
        val bmp = image ?: return
        if (busy != null) return
        busy = Busy("Exporting ${format.label}…")
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    val fileName = "${exportBaseName()}.${format.ext}"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GAKO")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("MediaStore refused the file")
                    resolver.openOutputStream(uri)?.use { bmp.compress(format.format, format.quality, it) }
                        ?: error("Couldn't write the file")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    fileName
                }
                say("Saved Pictures/GAKO/$name")
            } catch (t: Throwable) {
                say("Export failed: ${t.message}")
            } finally {
                busy = null
            }
        }
    }

    /** Writes a PNG for the share sheet and returns its content Uri. */
    suspend fun shareableUri(): Uri? {
        val bmp = image ?: return null
        busy = Busy("Preparing…")
        return try {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val dir = File(app.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, "${exportBaseName()}.png")
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

    fun undo() {
        if (busy != null || session != null) return
        val previous = undoStack.removeLastOrNull() ?: return
        image?.let { pushBounded(redoStack, it) }
        image = previous
        updateHistoryFlags()
    }

    fun redo() {
        if (busy != null || session != null) return
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
        if (busy != null || session != null) return
        viewModelScope.launch {
            busy = Busy("Transforming…")
            val out = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            }
            commit(out)
            busy = null
        }
    }

    // ---------------------------------------------------------------- filters

    /** Opens the parameter panel for [operation], or applies it straight away if it has no settings. */
    fun startFilter(operation: String) {
        val src = image ?: run { say("Open an image first"); return }
        if (busy != null) return
        cancelSession()
        viewModelScope.launch {
            val desc = Engine.describe(operation) ?: run { say("$operation isn't available"); return@launch }
            if (desc.params.isEmpty()) {
                runCommit(desc.title, operation, emptyMap(), src)
                return@launch
            }
            val scale = minOf(1.0, PROXY_SIDE.toDouble() / max(src.width, src.height))
            val proxy = if (scale < 1.0) withContext(Dispatchers.Default) {
                Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt().coerceAtLeast(1),
                    (src.height * scale).roundToInt().coerceAtLeast(1), true)
            } else src
            session = FilterSession(desc, desc.defaults(), proxy, proxy.width.toDouble() / src.width)
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
        if (s.previewing) Engine.cancel()
        previewJob = viewModelScope.launch {
            if (!immediate) delay(PREVIEW_DEBOUNCE_MS)
            s.previewing = true
            val result = Engine.apply(s.op.name, scaled(s.values, s.op, s.proxyScale), s.proxy)
            s.previewing = false
            when (result) {
                is Engine.Result.Ok -> if (session === s) { preview = result.bitmap; s.error = null }
                is Engine.Result.Failed -> s.error = result.message
                Engine.Result.Cancelled -> Unit
            }
        }
    }

    fun cancelSession() {
        val s = session ?: return
        previewJob?.cancel()
        if (s.previewing) Engine.cancel()
        session = null
        preview = null
    }

    fun applySession() {
        val s = session ?: return
        val src = image ?: return
        previewJob?.cancel()
        if (s.previewing) Engine.cancel()
        viewModelScope.launch {
            runCommit(s.op.title, s.op.name, s.values, src)
            if (session === s) session = null
        }
    }

    private suspend fun runCommit(title: String, operation: String, values: Map<String, Any?>, src: Bitmap) {
        busy = Busy(title, 0f, cancellable = true)
        val result = Engine.apply(operation, values, src) { p -> busy = busy?.copy(progress = p) }
        busy = null
        when (result) {
            is Engine.Result.Ok -> commit(result.bitmap)
            is Engine.Result.Failed -> say("$title failed: ${result.message}")
            Engine.Result.Cancelled -> say("$title cancelled")
        }
    }

    fun cancelBusy() {
        if (busy?.cancellable == true) Engine.cancel()
    }

    companion object {
        /** Stage-1 cap on opened images (Android won't draw a bitmap over 100 MB);
         *  stage 2 moves documents into tiled GEGL buffers. */
        const val MAX_PIXELS = 24_000_000L
        const val PROXY_SIDE = 1600
        const val PREVIEW_DEBOUNCE_MS = 60L
    }
}
