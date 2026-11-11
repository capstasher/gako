package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicLong

/**
 * GIMP's layer modes, in the order of GIMP's menu ([section] separates its groups). [gimp] is the
 * mode's number in GIMP 2.10+ (blending in linear light or its own space), [legacyGimp] the number
 * of its "legacy" (sRGB) form, if it has one. [blend] is the nearest Android blend mode, used for
 * quick on-screen drawing while something is being edited.
 */
enum class LayerMode(val label: String, val gimp: Int, val legacyGimp: Int?, val blend: BlendMode, val section: Int) {
    Normal("Normal", 28, 0, BlendMode.SRC_OVER, 0),
    Lighten("Lighten only", 36, 10, BlendMode.LIGHTEN, 1),
    LumaLighten("Luma lighten only", 55, null, BlendMode.LIGHTEN, 1),
    Screen("Screen", 31, 4, BlendMode.SCREEN, 1),
    Dodge("Dodge", 42, 16, BlendMode.COLOR_DODGE, 1),
    Addition("Addition", 33, 7, BlendMode.PLUS, 1),
    Darken("Darken only", 35, 9, BlendMode.DARKEN, 2),
    LumaDarken("Luma darken only", 54, null, BlendMode.DARKEN, 2),
    Multiply("Multiply", 30, 3, BlendMode.MULTIPLY, 2),
    Burn("Burn", 43, 17, BlendMode.COLOR_BURN, 2),
    LinearBurn("Linear burn", 53, null, BlendMode.COLOR_BURN, 2),
    Overlay("Overlay", 23, 5, BlendMode.OVERLAY, 3),
    SoftLight("Soft light", 45, 19, BlendMode.SOFT_LIGHT, 3),
    HardLight("Hard light", 44, 18, BlendMode.HARD_LIGHT, 3),
    VividLight("Vivid light", 48, null, BlendMode.HARD_LIGHT, 3),
    PinLight("Pin light", 49, null, BlendMode.HARD_LIGHT, 3),
    LinearLight("Linear light", 50, null, BlendMode.HARD_LIGHT, 3),
    HardMix("Hard mix", 51, null, BlendMode.HARD_LIGHT, 3),
    Difference("Difference", 32, 6, BlendMode.DIFFERENCE, 4),
    Exclusion("Exclusion", 52, null, BlendMode.EXCLUSION, 4),
    Subtract("Subtract", 34, 8, BlendMode.DIFFERENCE, 4),
    GrainExtract("Grain extract", 46, 20, BlendMode.DIFFERENCE, 4),
    GrainMerge("Grain merge", 47, 21, BlendMode.OVERLAY, 4),
    Divide("Divide", 41, 15, BlendMode.COLOR_DODGE, 4),
    Hue("HSV Hue", 37, 11, BlendMode.HUE, 5),
    Saturation("HSV Saturation", 38, 12, BlendMode.SATURATION, 5),
    Color("HSL Color", 39, 13, BlendMode.COLOR, 5),
    Value("HSV Value", 40, 14, BlendMode.LUMINOSITY, 5),
    LchHue("LCh Hue", 24, null, BlendMode.HUE, 6),
    LchChroma("LCh Chroma", 25, null, BlendMode.SATURATION, 6),
    LchColor("LCh Color", 26, null, BlendMode.COLOR, 6),
    LchLightness("LCh Lightness", 27, null, BlendMode.LUMINOSITY, 6),
    Luminance("Luminance", 56, null, BlendMode.LUMINOSITY, 6),
    /** Groups only: the children blend straight onto what's below, as if there were no group. */
    PassThrough("Pass through", 61, null, BlendMode.SRC_OVER, 7);

    /** GIMP's number for this mode, in its legacy form if [legacy] and it has one. */
    fun number(legacy: Boolean) = if (legacy && legacyGimp != null) legacyGimp else gimp

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name.equals(name, true) } ?: Normal

        /** GIMP's mode [n] as GAKO's mode and whether it's the legacy form (unknown modes read as Normal). */
        fun ofGimp(n: Int): Pair<LayerMode, Boolean> {
            entries.firstOrNull { it.gimp == n }?.let { return it to false }
            entries.firstOrNull { it.legacyGimp == n }?.let { return it to true }
            return when (n) {
                1, 2, 22 -> Normal to true          // dissolve, behind, colour erase (legacy)
                else -> Normal to false             // behind, colour erase, erase, merge, split, replace, anti-erase
            }
        }
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
    /**
     * The GIMP layer-mode number this layer had in the file it came from. Kept while it still reads
     * as [mode] and [legacy], so modes GAKO draws as Normal (Dissolve, Behind…) survive a round trip.
     */
    val xcfMode: Int? = null,
    /** GIMP's layer data GAKO doesn't use (colour tag, locks, tattoo, parasites…), kept for XCF. */
    val xcf: XcfLayerData? = null,
    /**
     * Use the mode's legacy form, as GIMP 2.8 and older did: blended and composited in sRGB, which
     * is what most phone apps (and Photoshop) do. Otherwise GIMP 2.10+'s, which composites in linear
     * light. Modes without a legacy form ignore it.
     */
    val legacy: Boolean = true,
    /** GIMP's per-layer blend space, composite space and composite mode (0 = the mode's own). */
    val blendSpace: Int = 0,
    val compositeSpace: Int = 0,
    val compositeMode: Int = 0,
    /** GIMP 3 layer effects from an XCF, first applied first; GAKO applies them when it opens the file. */
    val effects: List<LayerEffect> = emptyList(),
    /**
     * GIMP's Quick Mask: the selection as an opaque grey layer (white selected) that any tool can
     * paint or filter, shown as a red tint; never composited or saved (see [DocState.forFiles]).
     */
    val quickMask: Boolean = false,
) {
    /** GIMP's number for how this layer blends. */
    val gimpMode: Int get() = when {
        mode == LayerMode.PassThrough -> if (group) LayerMode.PassThrough.gimp else LayerMode.Normal.number(legacy)
        xcfMode != null && LayerMode.ofGimp(xcfMode) == (mode to legacyFor(mode)) -> xcfMode
        else -> mode.number(legacy)
    }

    /** [legacy] as it applies to [m] (always false for modes without a legacy form). */
    fun legacyFor(m: LayerMode) = legacy && m.legacyGimp != null

    /**
     * Set to blend the way Photoshop and OpenRaster files expect: in sRGB, over what's below
     * (as GIMP's own PSD import does).
     */
    fun gammaBlended(): Layer = if (mode == LayerMode.Normal || mode == LayerMode.PassThrough) copy(legacy = true)
        else copy(legacy = false, blendSpace = 4, compositeSpace = 4, compositeMode = 1)

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

/**
 * Paths moved by [m] (crop, scale, rotate…). Always a new list, even when empty, so undo can tell
 * the step changed where paths go (and doesn't carry later paths into the old geometry).
 */
fun List<VectorPath>.mapped(m: android.graphics.Matrix): List<VectorPath> = if (isEmpty()) ArrayList() else map { it.mapped(m) }

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
    /** GIMP's guides, sample points, channels and other image data (see [GimpExtras]). */
    val gimp: GimpExtras = GimpExtras(),
) {
    val active: Layer? get() = layers.firstOrNull { it.id == activeId } ?: layers.lastOrNull()
    val activeIndex: Int get() = layers.indexOfFirst { it.id == activeId }.takeIf { it >= 0 } ?: layers.lastIndex

    fun layer(id: Long) = layers.firstOrNull { it.id == id }

    /**
     * Whether a new layer should use the legacy (sRGB) modes: like the active layer, so a file from
     * GIMP 2.10+ gets layers that blend like its own, and GAKO's images keep sRGB blending.
     */
    val newLayerLegacy: Boolean get() = (active?.takeIf { !it.group } ?: layers.lastOrNull { !it.group })?.legacy ?: true

    fun replace(layer: Layer) = copy(layers = layers.map { if (it.id == layer.id) layer else it })

    /** The Quick Mask layer, while Quick Mask is on. */
    val quickMask: Layer? get() = layers.lastOrNull { it.quickMask }

    /** What goes into files and the composite: everything but the Quick Mask. */
    fun forFiles(): DocState {
        if (layers.none { it.quickMask }) return this
        val kept = layers.filterNot { it.quickMask }
        return copy(layers = kept, activeId = kept.firstOrNull { it.id == activeId }?.id ?: kept.lastOrNull()?.id ?: activeId)
    }

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

/** An XCF property kept as it was read: GIMP data GAKO doesn't use but mustn't lose. */
class XcfProp(val type: Int, val data: ByteArray)

/** An XCF parasite (named data attached to an image or item: comments, ICC profiles, GIMP text…). */
class XcfParasite(val name: String, val flags: Int, val data: ByteArray)

/**
 * A layer's XCF data GAKO doesn't use, and the pixels it came with: if those change, GIMP is
 * told (a GIMP text layer is then marked modified, so GIMP keeps GAKO's pixels).
 */
class XcfLayerData(val props: List<XcfProp>, val parasites: List<XcfParasite>, val bitmap: Bitmap, val generation: Int) {
    fun pixelsChanged(now: Bitmap) = now !== bitmap || now.generationId != generation
}

/** A GIMP channel: a canvas-sized grey bitmap (red = value) and its XCF properties (colour, opacity…). */
class Channel(val name: String, val bitmap: Bitmap, val props: List<XcfProp>)

/** A guide line: horizontal at y = [position], or vertical at x = [position]. */
data class Guide(val horizontal: Boolean, val position: Int)

/** A GIMP sample point at ([x], [y]); [pickMode] is how GIMP reads it (pixel, average…). */
data class SamplePoint(val x: Int, val y: Int, val pickMode: Int = 0)

/**
 * The parts of a GIMP image GAKO keeps for the round trip back to GIMP: guides and sample points,
 * saved channels, and image properties it doesn't use (resolution, units, parasites such as the
 * comment, ICC profile and metadata, the grid, item sets). Whole-image transforms move guides,
 * sample points and channels along with the layers ([mapped]).
 */
data class GimpExtras(
    val guides: List<Guide> = emptyList(),
    val samplePoints: List<SamplePoint> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val props: List<XcfProp> = emptyList(),
) {
    val isEmpty get() = guides.isEmpty() && samplePoints.isEmpty() && channels.isEmpty() && props.isEmpty()

    /** Moved onto a [w]×[h] canvas through [m] (old canvas → new). Guides that end up slanted go. */
    fun mapped(m: android.graphics.Matrix, w: Int, h: Int): GimpExtras {
        if (guides.isEmpty() && samplePoints.isEmpty() && channels.isEmpty()) return this
        val g = guides.mapNotNull { guide ->
            val p = guide.position.toFloat()
            val pts = if (guide.horizontal) floatArrayOf(0f, p, 1000f, p) else floatArrayOf(p, 0f, p, 1000f)
            m.mapPoints(pts)
            when {
                kotlin.math.abs(pts[1] - pts[3]) < 0.01f -> Guide(true, kotlin.math.round(pts[1]).toInt()).takeIf { it.position in 0..h }
                kotlin.math.abs(pts[0] - pts[2]) < 0.01f -> Guide(false, kotlin.math.round(pts[0]).toInt()).takeIf { it.position in 0..w }
                else -> null
            }
        }
        val s = samplePoints.mapNotNull { sp ->
            val pt = floatArrayOf(sp.x + 0.5f, sp.y + 0.5f)
            m.mapPoints(pt)
            val x = kotlin.math.floor(pt[0]).toInt(); val y = kotlin.math.floor(pt[1]).toInt()
            if (x in 0 until w && y in 0 until h) sp.copy(x = x, y = y) else null
        }
        val c = channels.map { ch ->
            val out = Bitmaps.blank(w, h, android.graphics.Color.BLACK)
            Canvas(out).drawBitmap(ch.bitmap, m, Paint().apply { isFilterBitmap = true })
            Channel(ch.name, out, ch.props)
        }
        return copy(guides = g, samplePoints = s, channels = c)
    }
}

/**
 * A GIMP 3 layer effect (a non-destructive filter): a GEGL [operation] with its [args], blended back
 * over the layer with GIMP's [mode] and [opacity] through [mask] (grey, canvas-sized like a selection;
 * empty means everywhere), and kept within the layer's bounds if [clip]. [unsupported] names arguments
 * GAKO can't pass on (GIMP gradients and configs).
 */
class LayerEffect(
    val name: String,
    val operation: String,
    val args: Map<String, Any>,
    val unsupported: List<String>,
    val active: Boolean,
    val opacity: Float,
    val mode: Int,
    val blendSpace: Int,
    val compositeSpace: Int,
    val compositeMode: Int,
    val clip: Boolean,
    val mask: Bitmap?,
)
