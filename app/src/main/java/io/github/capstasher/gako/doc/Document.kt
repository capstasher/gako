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
    Luminosity("Luminosity", BlendMode.LUMINOSITY);

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
) {
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

/** A whole image at one moment. Cheap to copy: bitmaps are shared until changed. */
data class DocState(
    val width: Int,
    val height: Int,
    val layers: List<Layer>,          // bottom to top
    val activeId: Long,
    val selection: Selection? = null,
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
    class Patch(label: String, val layerId: Long, val rect: Rect, val before: Bitmap, val after: Bitmap) : Step(label)

    /** Memory this step keeps alive that the live document doesn't. */
    fun cost(): Long = when (this) {
        is Patch -> before.allocationByteCount.toLong() + after.allocationByteCount
        is Snapshot -> {
            val live = after.layers.map { it.bitmap }.toSet()
            before.layers.filter { it.bitmap !in live }.sumOf { it.bitmap.allocationByteCount.toLong() } +
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
