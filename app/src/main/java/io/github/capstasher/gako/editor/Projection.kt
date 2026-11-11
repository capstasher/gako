package io.github.capstasher.gako.editor

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.doc.Tree
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the canvas shows when the quick GPU drawing would differ from GIMP (layer modes other than
 * sRGB Normal, GIMP 2.10+'s linear-light compositing, masks): GIMP's own result, made by the
 * native compositor in the background after each change, like GIMP's projection.
 *
 * While something is being edited (a stroke, a filter preview, a move) the canvas draws the edited
 * layer live on the GPU, over [below], the exact composite of everything under it, so only that
 * layer (and any above it) is approximate until the edit is done.
 */
class Projection(private val changes: PixelChanges) {

    /** The whole image composited, for [doc] as it was at [revision] (and pixel change [seq]). */
    class Frame(val doc: DocState, val revision: Int, val bitmap: Bitmap, val seq: Long)

    /** Everything below index [start] (a top-level item's first layer) composited, as of change [seq]. */
    class Below(val layers: List<Layer>, val start: Int, val bitmap: Bitmap, val seq: Long)

    /** Whether the open image needs this at all (else the GPU drawing is already exact). */
    var needed by mutableStateOf(false); private set
    var frame by mutableStateOf<Frame?>(null); private set
    var below by mutableStateOf<Below?>(null); private set

    /**
     * One composite at a time: a cancelled refresh's native work runs to the end, and must not
     * share a bitmap with the next one.
     */
    private val lock = Mutex()

    /**
     * Brings [frame] and [below] up to date with [doc] at [revision]. Called (and cancelled) for
     * every change, so the short wait first lets a burst of changes settle. While [editing], the
     * whole-image composite waits (the canvas wouldn't show it, and painting needs the CPU).
     *
     * A stale [frame] or [below] is never drawn, so its bitmap is reused for the new one (taken out
     * of view first): the display costs two extra copies of the image, not four.
     */
    suspend fun refresh(doc: DocState?, revision: Int, editing: Boolean) {
        needed = doc != null && !Compositor.canvasIsExact(doc)
        if (doc == null || !needed) { clear(); return }
        delay(SETTLE_MS)
        lock.withLock {
            val f = frame
            if (!editing && f?.let { it.doc === doc && it.revision == revision } != true) {
                val seq = changes.now
                // Same layers (a selection, path or guide change, or pixels painted in place): only
                // what was painted needs compositing again, if anything.
                val change = if (f != null && sameLayers(f.doc, doc)) changes.since(f.seq) else PixelChanges.Change.Everywhere
                when (change) {
                    PixelChanges.Change.Nowhere -> frame = Frame(doc, revision, f!!.bitmap, seq)
                    is PixelChanges.Change.Within -> {
                        val r = Rect(change.area).apply { if (!intersect(0, 0, doc.width, doc.height)) setEmpty() }
                        if (!r.isEmpty) {
                            frame = null
                            val part = withContext(Dispatchers.Default) { Compositor.composite(doc.layers, r.left, r.top, r.width(), r.height()) }
                                ?: return
                            Canvas(f!!.bitmap).drawBitmap(part, r.left.toFloat(), r.top.toFloat(), Paint().apply { blendMode = BlendMode.SRC })
                        }
                        frame = Frame(doc, revision, f!!.bitmap, seq)
                    }
                    PixelChanges.Change.Everywhere -> {
                        val into = reuse(f?.bitmap, doc)
                        frame = null
                        val bmp = withContext(Dispatchers.Default) { Compositor.composite(doc.layers, 0, 0, doc.width, doc.height, into) }
                            ?: return
                        frame = Frame(doc, revision, bmp, seq)
                    }
                }
            }
            val start = belowStart(doc)
            val small = doc.width.toLong() * doc.height <= BELOW_MAX_PIXELS
            if (small && start > 0 && below?.let { matches(it, doc, start) } != true) {
                val seq = changes.now
                val layers = doc.layers.subList(0, start).toList()
                val into = reuse(below?.bitmap, doc)
                below = null
                val bmp = withContext(Dispatchers.Default) { Compositor.composite(layers, 0, 0, doc.width, doc.height, into) }
                    ?: return
                below = Below(layers, start, bmp, seq)
            }
        }
    }

    /** Same canvas and the very same layers (a pixel change in place is the only possible difference). */
    private fun sameLayers(a: DocState, b: DocState): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        if (a.layers === b.layers) return true
        if (a.layers.size != b.layers.size) return false
        for (i in a.layers.indices) if (a.layers[i] !== b.layers[i]) return false
        return true
    }

    /** [below] if it's still what lies under [doc]'s active layer, else null. */
    fun belowFor(doc: DocState): Below? {
        val b = below ?: return null
        return b.takeIf { matches(it, doc, belowStart(doc)) }
    }

    fun clear() {
        frame = null; below = null
    }

    /** Drops what can be rebuilt (memory is short). */
    fun trimMemory() {
        below = null
    }

    private fun matches(b: Below, doc: DocState, start: Int): Boolean {
        if (start != b.start || b.bitmap.width != doc.width || b.bitmap.height != doc.height) return false
        for (i in 0 until start) if (doc.layers[i] !== b.layers[i]) return false
        // Pixels of a layer below can change in place too (Undo of a stroke on it).
        return !changes.touched(b.seq, b.layers)
    }

    /** [old] cleared if it's the right size, else a new bitmap (null if there's no memory for one). */
    private fun reuse(old: Bitmap?, doc: DocState): Bitmap? =
        old?.takeIf { it.width == doc.width && it.height == doc.height && it.isMutable }?.also { it.eraseColor(0) }
            ?: runCatching { Bitmaps.blank(doc.width, doc.height) }.getOrNull()

    companion object {
        const val SETTLE_MS = 30L
        /** Bigger images skip [below] (another whole copy): their edits draw live on the GPU. */
        const val BELOW_MAX_PIXELS = 16_000_000L

        /**
         * Where the top-level item holding the active layer starts: everything before it is
         * complete and can be composited once; 0 if there's nothing below (or no active layer).
         */
        fun belowStart(doc: DocState): Int {
            var top = doc.active ?: return 0
            val byId = Tree.byId(doc.layers)
            while (true) top = byId[top.parent ?: break] ?: break
            val i = doc.layers.indexOfFirst { it.id == top.id }
            if (i < 0) return 0
            return if (top.group) Tree.range(doc.layers, top.id).first else i
        }
    }
}

/**
 * Where layers' pixels changed in place (strokes, fills, Undo and Redo of them), so the canvas's
 * composite can be refreshed just there. Changes during a stroke aren't logged: the stroke's patch,
 * recorded when it ends, covers them, and a cancelled stroke puts the pixels back.
 */
class PixelChanges {
    sealed interface Change {
        data object Nowhere : Change
        data class Within(val area: Rect) : Change
        data object Everywhere : Change
    }

    private class Entry(val seq: Long, val area: Rect, val layerId: Long)

    private var seq = 0L
    private val log = ArrayDeque<Entry>()

    val now: Long @Synchronized get() = seq

    /** Pixels of layer [layerId] changed within [area] (canvas coordinates). */
    @Synchronized fun add(layerId: Long, area: Rect) {
        seq++
        log.addLast(Entry(seq, Rect(area), layerId))
        while (log.size > MAX) log.removeFirst()
    }

    /** Everything changed after [since]: nowhere, within one area, or (if forgotten) anywhere. */
    @Synchronized fun since(since: Long): Change {
        if (since == seq) return Change.Nowhere
        if (log.isEmpty() || log.first().seq > since + 1) return Change.Everywhere
        val r = Rect()
        for (e in log) if (e.seq > since) r.union(e.area)
        return if (r.isEmpty) Change.Nowhere else Change.Within(r)
    }

    /** Whether any of [layers] changed after [since] (or that can't be known any more). */
    @Synchronized fun touched(since: Long, layers: List<Layer>): Boolean {
        if (since == seq) return false
        if (log.isEmpty() || log.first().seq > since + 1) return true
        val ids = layers.mapTo(HashSet()) { it.id }
        return log.any { it.seq > since && it.layerId in ids }
    }

    private companion object { const val MAX = 512 }
}
