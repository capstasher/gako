package io.github.capstasher.gako.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.capstasher.gako.doc.Bitmaps
import io.github.capstasher.gako.doc.TextAlign
import io.github.capstasher.gako.doc.TextFont
import io.github.capstasher.gako.doc.TextInfo
import kotlin.math.ceil

/** The text tool's box being edited: new text at ([x], [y]), or an existing text layer. */
class TextSession(val layerId: Long?, x: Int, y: Int, info: TextInfo) {
    var x by mutableIntStateOf(x)
    var y by mutableIntStateOf(y)
    var info by mutableStateOf(info)

    companion object {
        fun typeface(info: TextInfo): Typeface {
            val family = when (info.font) {
                TextFont.Sans -> Typeface.SANS_SERIF
                TextFont.Serif -> Typeface.SERIF
                TextFont.Mono -> Typeface.MONOSPACE
                TextFont.Condensed -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            }
            val style = when {
                info.bold && info.italic -> Typeface.BOLD_ITALIC
                info.bold -> Typeface.BOLD
                info.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            val file = info.fontFile?.let { Fonts.load(it) }
            return Typeface.create(file ?: family, style)
        }

        fun layout(info: TextInfo, outline: Boolean = false): StaticLayout {
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = info.size.coerceAtLeast(1f)
                color = if (outline) info.outlineColor else info.color
                typeface = typeface(info)
                if (outline) {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = info.outline * 2      // the stroke straddles the edge; the fill covers the inner half
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
            }
            val text = info.text.ifEmpty { " " }
            val width = text.lines().maxOf { ceil(paint.measureText(it)).toInt() }.coerceAtLeast(1)
            val align = when (info.align) {
                TextAlign.Left -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
                TextAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(align)
                .setIncludePad(true)
                .build()
        }

        /** The text as a tightly sized transparent bitmap (with a little room for italics). */
        fun render(info: TextInfo): Bitmap {
            val l = layout(info)
            val edge = ceil(info.outline.coerceAtLeast(0f)).toInt()
            val pad = ceil(info.size * 0.15f).toInt() + edge
            val b = Bitmaps.blank(l.width + pad * 2, l.height + edge * 2)
            val c = Canvas(b)
            c.translate(pad.toFloat(), edge.toFloat())
            if (info.outline > 0f) layout(info, outline = true).draw(c)
            l.draw(c)
            return b
        }
    }
}
