package io.github.capstasher.gako.engine

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * GIMP's image engine (babl + GEGL), reached through libgako.so.
 * All calls are serialised; GEGL itself runs each one across several threads.
 */
object Engine {
    private const val TAG = "GAKO"
    private val lock = Mutex()
    @Volatile private var ready = false
    var threads = 1; private set

    init {
        System.loadLibrary("gako")
    }

    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    sealed interface Result {
        data class Ok(val bitmap: Bitmap) : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    @JvmStatic private external fun nativeInit(
        opsDir: String, bablDir: String, swapDir: String, threads: Int, cacheMb: Int,
    ): String?
    @JvmStatic private external fun nativeListOperations(): String
    @JvmStatic private external fun nativeDescribe(operation: String): String?
    @JvmStatic private external fun nativeApply(
        operation: String, params: String, src: Bitmap, dst: Bitmap, listener: ProgressListener?,
    ): Int
    @JvmStatic private external fun nativeCancel()
    @JvmStatic private external fun nativeLastError(): String?

    /** Starts GEGL. Returns null on success or an error message. */
    suspend fun start(context: Context): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (ready) return@withLock null
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            val ops = linkModules(libDir, "libgeglop_", File(context.filesDir, "gegl-ops"))
            val babl = linkModules(libDir, "libbablext_", File(context.filesDir, "babl-ext"))
            val swap = File(context.cacheDir, "gegl-swap").apply { deleteRecursively(); mkdirs() }

            // glib and babl expect a Unix home and cache dir; babl keeps its conversion cache there.
            Os.setenv("HOME", context.filesDir.path, true)
            Os.setenv("XDG_CACHE_HOME", context.cacheDir.path, true)
            Os.setenv("XDG_CONFIG_HOME", context.filesDir.path, true)
            Os.setenv("TMPDIR", context.cacheDir.path, true)

            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            val cacheMb = (memory.totalMem / (1024 * 1024) / 8).toInt().coerceIn(128, 1024)
            threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

            val error = nativeInit(ops.path, babl.path, swap.path, threads, cacheMb)
            ready = error == null
            Log.i(TAG, "GEGL started: threads=$threads cache=${cacheMb}MB error=$error")
            error
        }
    }

    /** GEGL and babl look for "<name>.so" in a directory; Android can only ship "lib*.so". */
    private fun linkModules(libDir: File, prefix: String, dir: File): File {
        dir.deleteRecursively()
        dir.mkdirs()
        libDir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".so") }
            ?.forEach { Os.symlink(it.absolutePath, File(dir, it.name.removePrefix(prefix)).absolutePath) }
        return dir
    }

    suspend fun operations(): List<OpInfo> = withContext(Dispatchers.Default) {
        lock.withLock {
            val arr = JSONArray(nativeListOperations())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                OpInfo(
                    name = o.getString("name"),
                    title = o.optStringOrNull("title") ?: o.getString("name").substringAfter(':'),
                    description = o.optStringOrNull("description") ?: "",
                    categories = o.getJSONArray("categories").let { c -> List(c.length()) { c.getString(it) } },
                    kind = OpKind.from(o.getString("kind")),
                )
            }
        }
    }

    suspend fun describe(operation: String): OpDescription? = withContext(Dispatchers.Default) {
        lock.withLock { nativeDescribe(operation)?.let { OpDescription.parse(it) } }
    }

    /** What the engine is busy with right now, so a cancel only stops the job it was meant for. */
    @Volatile private var runningTag: Any? = null

    /**
     * Runs [operation] over [src] and returns a new bitmap of the same size.
     * [tag] identifies the job for [cancel].
     */
    suspend fun apply(
        operation: String,
        params: Map<String, Any?>,
        src: Bitmap,
        tag: Any? = null,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.Default) {
        lock.withLock {
            ensureActive()
            val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val json = JSONObject().apply { params.forEach { (k, v) -> if (v != null) put(k, v) } }
            runningTag = tag
            val rc = try {
                nativeApply(operation, json.toString(), src, dst, ProgressListener { onProgress(it) })
            } finally {
                runningTag = null
            }
            when (rc) {
                0 -> Result.Ok(dst)
                1 -> { dst.recycle(); Result.Cancelled }
                else -> { dst.recycle(); Result.Failed(nativeLastError() ?: "Unknown engine error") }
            }
        }
    }

    /** Stops the running job at its next chunk, if it's the one tagged [tag] (or any job if null). */
    fun cancel(tag: Any? = null) {
        val running = runningTag ?: return
        if (tag == null || tag === running) nativeCancel()
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
