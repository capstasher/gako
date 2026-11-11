package io.github.capstasher.gako.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.doc.LayerEffect
import io.github.capstasher.gako.doc.LayerMode

/**
 * GIMP 3's layer effects (non-destructive filters) from an XCF, applied the way GIMP draws them:
 * each GEGL operation runs on the layer as it is so far, and its result goes back over the layer
 * with the effect's mode (GIMP's Replace unless set otherwise) and opacity, through the effect's
 * mask, inside the layer's bounds unless the effect isn't clipped (a drop shadow can spill out).
 *
 * GAKO has no non-destructive filters yet, so the effects become part of the layer's pixels.
 */
object Effects {

    class Outcome(val doc: DocState, val applied: Int, val skipped: List<String>) {
        val note: String? get() = when {
            applied == 0 && skipped.isEmpty() -> null
            skipped.isEmpty() -> "Applied $applied GIMP layer effect${if (applied == 1) "" else "s"} to the layers (GAKO doesn't keep them separate yet)"
            else -> "GAKO can't apply ${skipped.joinToString()}; ${if (skipped.size == 1) "that layer shows" else "those layers show"} without its effects" +
                (if (applied > 0) " (applied $applied others)" else "")
        }
    }

    /**
     * Applies every layer's effects, first to last, and drops them from the layers. A layer with
     * an effect GAKO can't run (a GIMP-only operation, an argument it can't pass) keeps its own
     * pixels, so it's never half done. [operations]: the GEGL operations the engine has.
     */
    suspend fun apply(doc: DocState, operations: Set<String>): Outcome {
        if (doc.layers.none { it.effects.isNotEmpty() }) return Outcome(doc, 0, emptyList())
        var applied = 0
        val skipped = ArrayList<String>()
        val layers = doc.layers.map { l ->
            if (l.effects.isEmpty()) return@map l
            val active = l.effects.filter { it.active }
            val cant = active.filter { it.operation !in operations || it.unsupported.isNotEmpty() }
            if (cant.isNotEmpty()) {
                skipped += cant.map { "“${label(it)}” on “${l.name}”" }
                return@map l.copy(effects = emptyList())
            }
            var cur = l
            for (e in active) {
                cur = applyOne(cur, e, doc) ?: run {
                    skipped += "“${label(e)}” on “${l.name}”"
                    return@map l.copy(effects = emptyList())
                }
            }
            applied += active.size
            cur.copy(effects = emptyList())
        }
        return Outcome(doc.copy(layers = layers), applied, skipped)
    }

    private fun label(e: LayerEffect) = e.name.ifBlank { e.operation.substringAfter(':') }

    private suspend fun applyOne(l: Layer, e: LayerEffect, doc: DocState): Layer? {
        // An unclipped effect may draw past the layer, as far as the canvas.
        val grow = if (e.clip) null else Rect(-l.x, -l.y, doc.width - l.x, doc.height - l.y).apply { union(0, 0, l.width, l.height) }
        val result = Engine.apply(e.operation, e.args, l.bitmap, growWithin = grow) as? Engine.Result.Ok ?: return null
        val fx = l.x + result.x
        val fy = l.y + result.y
        val area = Rect(l.bounds).apply { if (!e.clip) union(fx, fy, fx + result.bitmap.width, fy + result.bitmap.height) }
        val (mode, legacy) = LayerMode.ofGimp(e.mode)
        val filtered = Layer(0, "", result.bitmap, fx, fy, opacity = e.opacity, mode = mode, legacy = legacy, xcfMode = e.mode,
            blendSpace = e.blendSpace, compositeSpace = e.compositeSpace, compositeMode = e.compositeMode,
            mask = maskFor(e.mask, l, doc, fx, fy, result.bitmap.width, result.bitmap.height))
        val base = Layer(0, "", l.bitmap, l.x, l.y)
        val out = Compositor.composite(listOf(base, filtered), area.left, area.top, area.width(), area.height()) ?: return null
        // The layer's own mask covers only where the layer was: beyond that, as in GIMP, it hides.
        val layerMask = l.mask?.let { m ->
            if (area == l.bounds) m
            else Bitmaps.blank(area.width(), area.height(), Color.BLACK).also {
                Canvas(it).drawBitmap(m, (l.x - area.left).toFloat(), (l.y - area.top).toFloat(), null)
            }
        }
        return l.copy(bitmap = out, x = area.left, y = area.top, mask = layerMask, text = null)
    }

    /**
     * The effect's mask placed over the filtered pixels at ([fx], [fy]), [w]×[h]. GIMP keeps it like
     * a selection (canvas-sized; empty means the whole layer). Null if it doesn't limit anything.
     */
    private fun maskFor(mask: Bitmap?, l: Layer, doc: DocState, fx: Int, fy: Int, w: Int, h: Int): Bitmap? {
        mask ?: return null
        val (mx, my) = when {
            mask.width == doc.width && mask.height == doc.height -> 0 to 0
            mask.width == l.width && mask.height == l.height -> l.x to l.y
            else -> return null
        }
        val px = IntArray(mask.width * mask.height).also { mask.getPixels(it, 0, mask.width, 0, 0, mask.width, mask.height) }
        if (px.all { (it shr 16) and 255 == 0 }) return null
        if (px.all { (it shr 16) and 255 == 255 } && Rect(mx, my, mx + mask.width, my + mask.height).contains(Rect(fx, fy, fx + w, fy + h))) return null
        return Bitmaps.blank(w, h, Color.BLACK).also { Canvas(it).drawBitmap(mask, (mx - fx).toFloat(), (my - fy).toFloat(), null) }
    }
}
