package io.github.capstasher.gako.debug

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Color
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.ViewModelProvider
import io.github.capstasher.gako.editor.EditorViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Debug builds only. A tiny server on the abstract socket "gako-inspector" that answers
 * scripts/ui.py in milliseconds (instead of uiautomator's ~2 s dump): what's on screen, read from
 * Compose's semantics (what accessibility, and so uiautomator, sees), the editor's state, and where
 * an image pixel is on screen at the current zoom. One request per connection: a line in, JSON out.
 *
 *   ui          {"nodes": [{"text", "desc", "click", "box": [l, t, r, b], "win"}]}  (screen pixels)
 *   state       the document, tool, options, history and busy state
 *   img X Y     {"x", "y"}: image pixel (X, Y) on screen
 */
object Inspector {
    private const val SOCKET = "gako-inspector"
    private val roots = mutableListOf<WeakReference<ViewRootForTest>>()
    @Volatile private var activity: WeakReference<Activity>? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(app: Application) {
        // Compose calls this for every root it makes: the activity's, and each dialog's and popup's.
        ViewRootForTest.onViewCreatedCallback = { r -> synchronized(roots) { roots += WeakReference(r) } }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { activity = WeakReference(a) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        thread(isDaemon = true, name = "gako-inspector") {
            val server = try { LocalServerSocket(SOCKET) } catch (e: Exception) { return@thread }
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { continue }
                thread(isDaemon = true) { s.use { serve(it) } }
            }
        }
    }

    private fun serve(s: LocalSocket) {
        val line = s.inputStream.bufferedReader().readLine()?.trim().orEmpty()
        val parts = line.split(Regex("\\s+"))
        val reply = try {
            onMain {
                when (parts[0]) {
                    "ui" -> JSONObject().put("nodes", nodes())
                    "state" -> state()
                    "img" -> image(parts[1].toFloat(), parts[2].toFloat())
                    else -> JSONObject().put("error", "unknown request '$line'")
                }
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.toString())
        }
        s.outputStream.write((reply.toString() + "\n").toByteArray())
        s.outputStream.flush()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        val done = CountDownLatch(1)
        main.post { result = runCatching(block); done.countDown() }
        if (!done.await(3, TimeUnit.SECONDS)) error("the app's main thread is busy")
        return result!!.getOrThrow()
    }

    /** The Compose roots on screen now, bottom window first. */
    private fun liveRoots(): List<ViewRootForTest> = synchronized(roots) {
        roots.removeAll { it.get() == null }
        roots.mapNotNull { it.get() }.filter { it.view.isAttachedToWindow && it.view.isShown }
    }

    private fun nodes(): JSONArray {
        val out = JSONArray()
        liveRoots().forEachIndexed { win, root ->
            val origin = IntArray(2).also { root.view.rootView.getLocationOnScreen(it) }
            fun walk(n: SemanticsNode) {
                val c = n.config
                val text = c.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                    ?: c.getOrNull(SemanticsProperties.EditableText)?.text
                val desc = c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
                val b = n.boundsInWindow
                if ((text != null || desc != null) && b.width > 0 && b.height > 0) {
                    out.put(JSONObject()
                        .put("text", text ?: "")
                        .put("desc", desc ?: "")
                        .put("click", SemanticsActions.OnClick in c)
                        .put("win", win)
                        .put("box", JSONArray(listOf(origin[0] + b.left.toInt(), origin[1] + b.top.toInt(),
                            origin[0] + b.right.toInt(), origin[1] + b.bottom.toInt()))))
                }
                n.children.forEach(::walk)
            }
            walk(root.semanticsOwner.rootSemanticsNode)
        }
        return out
    }

    private fun vm(): EditorViewModel {
        val a = activity?.get() as? ComponentActivity ?: error("GAKO isn't in front")
        return ViewModelProvider(a)[EditorViewModel::class.java]
    }

    private fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)

    private fun state(): JSONObject {
        val vm = vm()
        val o = JSONObject()
            .put("name", vm.documentName)
            .put("dirty", vm.dirty)
            .put("tool", vm.tool.name)
            .put("busy", vm.busy?.label ?: JSONObject.NULL)
            .put("engine", vm.engine::class.simpleName)
            .put("canUndo", vm.canUndo).put("canRedo", vm.canRedo)
            .put("fg", hex(vm.fg)).put("bg", hex(vm.bg))
            .put("editingMask", vm.editingMask)
            .put("textOpen", vm.text != null)
            .put("filterOpen", vm.session?.op?.title ?: JSONObject.NULL)
            .put("options", vm.options.toString())
        val d = vm.doc ?: return o.put("image", JSONObject.NULL)
        val layers = JSONArray()
        for (l in d.layers) layers.put(JSONObject()
            .put("name", l.name).put("group", l.group).put("visible", l.visible)
            .put("opacity", l.opacity.toDouble()).put("mode", l.mode.name).put("gimpMode", l.gimpMode)
            .put("box", JSONArray(listOf(l.x, l.y, l.x + l.width, l.y + l.height)))
            .put("mask", l.mask != null).put("text", l.text?.text ?: JSONObject.NULL)
            .put("parent", l.parent?.let { p -> d.layer(p)?.name } ?: JSONObject.NULL))
        val paths = JSONArray()
        for (p in d.paths) paths.put(JSONObject().put("name", p.name)
            .put("strokes", JSONArray(p.strokes.map { "${it.anchors}${if (it.closed) " closed" else ""}" })))
        return o.put("image", JSONObject()
            .put("w", d.width).put("h", d.height)
            .put("active", d.active?.name ?: JSONObject.NULL)
            .put("layers", layers) // bottom to top
            .put("selection", d.selection?.bounds?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
            .put("paths", paths)
            .put("editing", "path ${vm.pathIndex} stroke ${vm.strokeIndex}"))
    }

    /** Image pixel → screen pixel, through the canvas's current zoom, pan and turn. */
    private fun image(x: Float, y: Float): JSONObject {
        val vm = vm()
        val main = liveRoots().firstOrNull() ?: error("no window")
        val origin = IntArray(2).also { main.view.rootView.getLocationOnScreen(it) }
        var canvas: SemanticsNode? = null
        fun walk(n: SemanticsNode) {
            if (n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Image, ") } == true) canvas = n
            n.children.forEach(::walk)
        }
        walk(main.semanticsOwner.rootSemanticsNode)
        val c = canvas ?: error("no image open")
        val p = vm.docToView(x, y)
        val b = c.boundsInWindow
        return JSONObject().put("x", (origin[0] + b.left + p.x).toInt()).put("y", (origin[1] + b.top + p.y).toInt())
    }
}

/** Starts the [Inspector] as the app starts (debug builds only, from the debug manifest). */
class InspectorProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let(Inspector::start)
        return true
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
