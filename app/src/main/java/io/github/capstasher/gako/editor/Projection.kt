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
class Projection {

    /** The whole image composited, for [doc] as it was at [revision]. */
    class Frame(val doc: DocState, val revision: Int, val bitmap: Bitmap)

    /** Everything below index [start] (a top-level item's first layer) composited. */
    class Below(val layers: List<Layer>, val start: Int, val bitmap: Bitmap)

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
            if (!editing && frame?.let { it.doc === doc && it.revision == revision } != true) {
                val into = reuse(frame?.bitmap, doc)
                frame = null
                val bmp = withContext(Dispatchers.Default) { Compositor.composite(doc.layers, 0, 0, doc.width, doc.height, into) }
                    ?: return
                frame = Frame(doc, revision, bmp)
            }
            val start = belowStart(doc)
            val small = doc.width.toLong() * doc.height <= BELOW_MAX_PIXELS
            if (small && start > 0 && below?.let { matches(it, doc, start) } != true) {
                val layers = doc.layers.subList(0, start).toList()
                val into = reuse(below?.bitmap, doc)
                below = null
                val bmp = withContext(Dispatchers.Default) { Compositor.composite(layers, 0, 0, doc.width, doc.height, into) }
                    ?: return
                below = Below(layers, start, bmp)
            }
        }
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
        return true
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
