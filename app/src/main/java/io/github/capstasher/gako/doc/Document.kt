package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicLong

/** GIMP's layer modes that Android's compositor can reproduce. */
enum class LayerMode(val label: String, val blend: BlendMode) {
    Normal("Normal", BlendMode.SRC_OVER),
    Multiply("Multiply", BlendMode.MULTIPLY),
    Screen("Screen", BlendMode.SCREEN),
    Overlay("Overlay", BlendMode.OVERLAY),
    SoftLight("Soft light", BlendMode.SOFT_LIGHT),
    HardLight("Hard light", BlendMode.HARD_LIGHT),
    Dodge("Dodge", BlendMode.COLOR_DODGE),
    Burn("Burn", BlendMode.COLOR_BURN),
    Darken("Darken only", BlendMode.DARKEN),
    Lighten("Lighten only", BlendMode.LIGHTEN),
    Addition("Addition", BlendMode.PLUS),
    Difference("Difference", BlendMode.DIFFERENCE),
    Exclusion("Exclusion", BlendMode.EXCLUSION),
    Hue("HSL Hue", BlendMode.HUE),
    Saturation("HSL Saturation", BlendMode.SATURATION),
    Color("HSL Color", BlendMode.COLOR),
    Luminosity("Luminosity", BlendMode.LUMINOSITY),
    /** Groups only: the children blend straight onto what's below, as if there were no group. */
    PassThrough("Pass through", BlendMode.SRC_OVER);

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name.equals(name, true) } ?: Normal
    }
}

enum class TextFont(val label: String) { Sans("Sans"), Serif("Serif"), Mono("Mono"), Condensed("Condensed") }
enum class TextAlign { Left, Center, Right }

/** What a text layer was made from, so the text tool can re-edit it. */
data class TextInfo(
    val text: String,
    val size: Float,
    val color: Int,
    val font: TextFont = TextFont.Sans,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: TextAlign = TextAlign.Left,
    /** An outline around the letters (0 = none), in pixels, and its colour. */
    val outline: Float = 0f,
    val outlineColor: Int = -0x1000000,
    /** A font file (system or added by the user) instead of [font]; [font] is the fallback. */
    val fontFile: String? = null,
)

/**
 * One layer. [bitmap] is premultiplied ARGB_8888, mutable, and placed at ([x], [y]) on the canvas.
 * Layers are immutable values; a pixel edit either swaps the bitmap (filters) or patches it in
 * place and records a [Step.Patch] (painting).
 */
data class Layer(
    val id: Long,
    val name: String,
    val bitmap: Bitmap,
    val x: Int = 0,
    val y: Int = 0,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val mode: LayerMode = LayerMode.Normal,
    val lockAlpha: Boolean = false,
    val text: TextInfo? = null,
    /** GIMP's layer mask: an opaque grey ARGB bitmap the size of [bitmap]; white shows, black hides. */
    val mask: Bitmap? = null,
    val maskEnabled: Boolean = true,
    /** A layer group: no pixels of its own; its children are the layers whose [parent] is its id. */
    val group: Boolean = false,
    /** The group this layer is in, or null at the top level. */
    val parent: Long? = null,
    /** Groups: children shown in the Layers panel. */
    val expanded: Boolean = true,
) {
    /** The mask that affects how the layer looks right now. */
    val activeMask: Bitmap? get() = mask.takeIf { maskEnabled }

    val width get() = bitmap.width
    val height get() = bitmap.height
    val bounds get() = Rect(x, y, x + width, y + height)

    companion object {
        private val ids = AtomicLong(1)
        fun newId() = ids.getAndIncrement()
    }
}

/**
 * A selection: an ALPHA_8 mask the size of the canvas (255 = selected), its bounding box,
 * and an outline for marching ants.
 */
class Selection(val mask: Bitmap, val bounds: Rect, val outline: Path)

/**
 * One Bézier stroke in canvas coordinates: per anchor six floats (in-handle x/y, anchor x/y,
 * out-handle x/y), as GIMP stores them.
 */
class PathStroke(val points: FloatArray, val closed: Boolean) {
    val anchors get() = points.size / 6

    fun mapped(m: android.graphics.Matrix) = PathStroke(points.copyOf().also { m.mapPoints(it) }, closed)

    fun sameAs(o: PathStroke?) = o != null && closed == o.closed && points.contentEquals(o.points)

    /** Adds this stroke to [into] as cubic segments (closed if the stroke is). */
    fun addTo(into: android.graphics.Path, close: Boolean = closed) {
        val n = anchors
        if (n == 0) return
        val f = points
        into.moveTo(f[2], f[3])
        for (i in 1 until n) {
            val a = (i - 1) * 6; val b = i * 6
            into.cubicTo(f[a + 4], f[a + 5], f[b], f[b + 1], f[b + 2], f[b + 3])
        }
        if (close && n > 1) {
            val a = (n - 1) * 6
            into.cubicTo(f[a + 4], f[a + 5], f[0], f[1], f[2], f[3])
            into.close()
        }
    }
}

/** A named path of one or more strokes (GIMP's paths can have several). */
class VectorPath(val name: String, val strokes: List<PathStroke>) {
    fun mapped(m: android.graphics.Matrix) = VectorPath(name, strokes.map { it.mapped(m) })
}

/** Paths moved by [m] (crop, scale, rotate…), or the same list when there are none. */
fun List<VectorPath>.mapped(m: android.graphics.Matrix) = if (isEmpty()) this else map { it.mapped(m) }

/**
 * Layer groups in the flat, bottom-to-top layer list: a group sits directly above its children
 * (like in a PSD), so each group and everything in it is one contiguous run ending at the group.
 */
object Tree {
    fun byId(layers: List<Layer>) = layers.associateBy { it.id }

    fun isInside(l: Layer, groupId: Long, byId: Map<Long, Layer>): Boolean {
        var p = l.parent
        while (p != null) {
            if (p == groupId) return true
            p = byId[p]?.parent
        }
        return false
    }

    /** Indices of [id] and everything inside it: start..(index of id). */
    fun range(layers: List<Layer>, id: Long): IntRange {
        val end = layers.indexOfFirst { it.id == id }
        if (end < 0) return IntRange.EMPTY
        val byId = byId(layers)
        var start = end
        while (start > 0 && isInside(layers[start - 1], id, byId)) start--
        return start..end
    }

    fun depth(l: Layer, byId: Map<Long, Layer>): Int {
        var d = 0
        var p = l.parent
        while (p != null) { d++; p = byId[p]?.parent }
        return d
    }

    /** Shown on the canvas: visible itself and inside visible groups. */
    fun shows(l: Layer, byId: Map<Long, Layer>): Boolean {
        if (!l.visible) return false
        var p = l.parent
        while (p != null) {
            val g = byId[p] ?: return true
            if (!g.visible) return false
            p = g.parent
        }
        return true
    }

    /** Hidden in the Layers panel because a group above it is collapsed. */
    fun collapsedAway(l: Layer, byId: Map<Long, Layer>): Boolean {
        var p = l.parent
        while (p != null) {
            val g = byId[p] ?: return false
            if (!g.expanded) return true
            p = g.parent
        }
        return false
    }

    /**
     * Raises (+1) or lowers (−1) item [id] (with everything inside it) among its siblings in a
     * bottom-to-top list where each group sits right above its contents. Next to a sibling group it
     * goes into that group; at the edge of its own group it steps out of it. Returns the new list, or
     * null if it can't move. Generic so it can be tested without Android.
     */
    fun <T> move(items: List<T>, id: Long, delta: Int, idOf: (T) -> Long, parentOf: (T) -> Long?,
                 isGroup: (T) -> Boolean, withParent: (T, Long?) -> T): List<T>? {
        val list = items.toMutableList()
        val byId = list.associateBy(idOf)
        fun inside(t: T, g: Long): Boolean {
            var p = parentOf(t)
            while (p != null) { if (p == g) return true; p = byId[p]?.let(parentOf) }
            return false
        }
        fun rangeOf(l: List<T>, x: Long): IntRange {
            val end = l.indexOfFirst { idOf(it) == x }
            if (end < 0) return IntRange.EMPTY
            var start = end
            while (start > 0 && inside(l[start - 1], x)) start--
            return start..end
        }
        val me = byId[id] ?: return null
        val parent = parentOf(me)
        val range = rangeOf(list, id)
        val block = list.slice(range)
        fun reparented(p: Long?) = block.map { if (idOf(it) == id) withParent(it, p) else it }
        fun removeBlock() { for (k in range.reversed()) list.removeAt(k) }
        if (delta > 0) {
            val next = list.getOrNull(range.last + 1) ?: return null
            val sibling = list.drop(range.last + 1).firstOrNull { parentOf(it) == parent }
            when {
                parent != null && idOf(next) == parent -> {
                    removeBlock()
                    val gi = list.indexOfFirst { idOf(it) == parent }
                    list.addAll(gi + 1, reparented(byId[parent]?.let(parentOf)))
                }
                sibling == null -> return null
                isGroup(sibling) -> {
                    removeBlock()
                    list.addAll(rangeOf(list, idOf(sibling)).first, reparented(idOf(sibling)))
                }
                else -> {
                    val sib = rangeOf(list, idOf(sibling))
                    val sibBlock = list.slice(sib)
                    for (k in sib.reversed()) list.removeAt(k)
                    list.addAll(range.first, sibBlock)
                }
            }
        } else {
            val prev = list.getOrNull(range.first - 1)
            when {
                prev == null || (parent != null && !inside(prev, parent)) -> {
                    if (parent == null) return null
                    // Step out, just below our group.
                    removeBlock()
                    list.addAll(rangeOf(list, parent).first, reparented(byId[parent]?.let(parentOf)))
                }
                parentOf(prev) == parent && isGroup(prev) -> {
                    removeBlock()
                    list.addAll(list.indexOfFirst { idOf(it) == idOf(prev) }, reparented(idOf(prev)))
                }
                else -> {
                    val sibTop = list.subList(0, range.first).lastOrNull { parentOf(it) == parent } ?: return null
                    val sib = rangeOf(list, idOf(sibTop))
                    removeBlock()
                    list.addAll(sib.first, block)
                }
            }
        }
        return list
    }

    /**
     * Repairs a layer list so the group invariant holds: parents that don't exist (or aren't
     * groups) become the top level, and every group is moved to sit right above its contents.
     * Run after reading files.
     */
    fun normalize(layers: List<Layer>): List<Layer> {
        val ids = layers.filter { it.group }.map { it.id }.toSet()
        val fixed = layers.map { l -> if (l.parent != null && l.parent !in ids || l.parent == l.id) l.copy(parent = null) else l }
        // Break cycles: walk up; if we come back to ourselves, cut the link.
        val byId = fixed.associateBy { it.id }.toMutableMap()
        for (l in fixed) {
            val seen = HashSet<Long>()
            var p = l.parent
            while (p != null && seen.add(p)) p = byId[p]?.parent
            if (p != null) byId[l.id] = l.copy(parent = null)
        }
        val all = fixed.map { byId[it.id]!! }
        // Rebuild in order: each level's items bottom-to-top, a group's contents right below it.
        val out = ArrayList<Layer>(all.size)
        fun emit(parent: Long?) {
            for (l in all) if (l.parent == parent) {
                if (l.group) emit(l.id)
                out += l
            }
        }
        emit(null)
        return out
    }

    /** Groups containing [l], innermost first. */
    fun ancestors(l: Layer, byId: Map<Long, Layer>): List<Layer> {
        val out = ArrayList<Layer>()
        var p = l.parent
        while (p != null) { val g = byId[p] ?: break; out += g; p = g.parent }
        return out
    }

    /** The list with every group containing [id] opened (so the layer shows in the panel). */
    fun reveal(layers: List<Layer>, id: Long): List<Layer> {
        val byId = byId(layers)
        val l = byId[id] ?: return layers
        val closed = ancestors(l, byId).filter { !it.expanded }.map { it.id }.toSet()
        if (closed.isEmpty()) return layers
        return layers.map { if (it.id in closed) it.copy(expanded = true) else it }
    }

    /** The image's real (pixel) layers, in order. */
    fun leaves(layers: List<Layer>) = layers.filter { !it.group }
}

/** A whole image at one moment. Cheap to copy: bitmaps are shared until changed. */
data class DocState(
    val width: Int,
    val height: Int,
    val layers: List<Layer>,          // bottom to top
    val activeId: Long,
    val selection: Selection? = null,
    /** Paths that came with the file (and go back into it on save). */
    val paths: List<VectorPath> = emptyList(),
) {
    val active: Layer? get() = layers.firstOrNull { it.id == activeId } ?: layers.lastOrNull()
    val activeIndex: Int get() = layers.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: layers.lastIndex

    fun layer(id: Long) = layers.firstOrNull { it.id == id }

    fun replace(layer: Layer) = copy(layers = layers.map { if (it.id == layer.id) layer else it })

    fun bytes(): Long = layers.sumOf { it.bitmap.allocationByteCount.toLong() } +
        (selection?.mask?.allocationByteCount?.toLong() ?: 0L)
}

/** One undoable change. */
sealed class Step(val label: String) {
    /** Whole-document change: layers added, removed, reordered, swapped, resized, reselected. */
    class Snapshot(label: String, val before: DocState, val after: DocState) : Step(label)

    /** Pixels changed in place inside [rect] (layer coordinates) of layer [layerId]. */
    class Patch(label: String, val layerId: Long, val rect: Rect, val before: Bitmap, val after: Bitmap,
                /** The pixels belong to the layer's mask, not the layer. */
                val onMask: Boolean = false) : Step(label) {
        fun target(l: Layer): Bitmap? = if (onMask) l.mask else l.bitmap
        fun forMask() = Patch(label, layerId, rect, before, after, onMask = true)
    }

    /** Memory this step keeps alive that the live document doesn't. */
    fun cost(): Long = when (this) {
        is Patch -> before.allocationByteCount.toLong() + after.allocationByteCount
        is Snapshot -> {
            val live = after.layers.flatMap { listOfNotNull(it.bitmap, it.mask) }.toSet()
            before.layers.flatMap { listOfNotNull(it.bitmap, it.mask) }.filter { it !in live }
                .sumOf { it.allocationByteCount.toLong() } +
                (if (before.selection !== after.selection) before.selection?.mask?.allocationByteCount?.toLong() ?: 0L else 0L)
        }
    }
}

object Bitmaps {
    fun blank(w: Int, h: Int, color: Int = 0): Bitmap =
        Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply {
            if (color != 0) eraseColor(color)
        }

    /** A mutable ARGB_8888 copy (or the same bitmap if it already is one). */
    fun mutable(b: Bitmap): Bitmap =
        if (b.isMutable && b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, true)

    fun copy(b: Bitmap): Bitmap = b.copy(Bitmap.Config.ARGB_8888, true)

    /** Writes [src] into [dst] at (x, y), replacing what was there. */
    fun put(dst: Bitmap, src: Bitmap, x: Int, y: Int) {
        Canvas(dst).drawBitmap(src, x.toFloat(), y.toFloat(), Paint().apply { blendMode = BlendMode.SRC })
    }

    fun crop(b: Bitmap, r: Rect): Bitmap = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())

    /**
     * Multiplies what's on [c] by [mask]'s alpha (DST_IN) — the mask placed at ([x], [y]), nothing
     * kept outside it. A software canvas treats a drawn ALPHA_8 bitmap as coverage, which turns
     * DST_IN into a no-op wherever the mask is clear; a shader gives true alpha instead.
     */
    fun maskIn(c: Canvas, mask: Bitmap, x: Float = 0f, y: Float = 0f) {
        val shader = android.graphics.BitmapShader(mask, android.graphics.Shader.TileMode.DECAL, android.graphics.Shader.TileMode.DECAL)
        if (x != 0f || y != 0f) shader.setLocalMatrix(android.graphics.Matrix().apply { setTranslate(x, y) })
        c.drawPaint(Paint().apply { this.shader = shader; blendMode = BlendMode.DST_IN })
    }
}
