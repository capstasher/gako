package io.github.capstasher.gako.doc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GIMP's text layers: the "gimp-text-layer" parasite (GimpText's settings, serialized) as GAKO's
 * [TextInfo], so GAKO's text tool can edit a GIMP text layer. GAKO keeps GIMP's own rendering until
 * the text is changed (GAKO's text engine isn't Pango, so it would look a little different).
 */
object GimpText {

    const val PARASITE = "gimp-text-layer"

    fun parse(data: ByteArray): TextInfo? = runCatching {
        // Bytes as chars one to one, so binary escapes survive; text values are UTF-8 inside.
        val cfg = GimpOps.Config.parse(String(data, Charsets.ISO_8859_1))
        fun utf8(s: String) = String(ByteArray(s.length) { s[it].code.toByte() }, Charsets.UTF_8)
        val text = (cfg.forms.firstOrNull { it.firstOrNull() == "text" }?.getOrNull(1) as? String)?.let(::utf8)
            ?: (cfg.forms.firstOrNull { it.firstOrNull() == "markup" }?.getOrNull(1) as? String)?.let { utf8(it).replace(Regex("<[^>]*>"), "") }
            ?: return@runCatching null
        // GAKO has no print resolution: a size in points is taken as pixels.
        val size = (cfg.num("font-size") ?: 24.0).toFloat()
        // Font: GIMP 3 nests (family …) (style …); GIMP 2.10 has one string, "Sans-serif Bold".
        val fontForm = cfg.forms.firstOrNull { it.firstOrNull() == "font" }
        val nested = fontForm?.drop(1)?.filterIsInstance<List<Any>>()?.let { GimpOps.Config(it) }
        val family = (nested?.word("family") ?: (fontForm?.getOrNull(1) as? String) ?: "Sans").lowercase()
        val style = (nested?.word("style") ?: family).lowercase()
        val font = when {
            "mono" in family -> TextFont.Mono
            "condensed" in family || "condensed" in style -> TextFont.Condensed
            "serif" in family && "sans" !in family -> TextFont.Serif
            else -> TextFont.Sans
        }
        val align = when (cfg.word("justify")) { "right" -> TextAlign.Right; "center" -> TextAlign.Center; else -> TextAlign.Left }
        TextInfo(text = text, size = size, color = color(cfg, "color") ?: android.graphics.Color.BLACK, font = font,
            bold = "bold" in style || "heavy" in style || "black" in style,
            italic = "italic" in style || "oblique" in style, align = align)
    }.getOrNull()

    /** A colour setting: GIMP 3's (color "R'G'B'A float" 16 "<bytes>" 0), or 2.10's (color-rgba r g b a). */
    private fun color(cfg: GimpOps.Config, name: String): Int? {
        val form = cfg.forms.firstOrNull { it.firstOrNull() == name }?.getOrNull(1) as? List<*> ?: return null
        fun argb(r: Double, g: Double, b: Double, a: Double) = android.graphics.Color.argb(
            (a * 255).toInt().coerceIn(0, 255), (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255), (b * 255).toInt().coerceIn(0, 255))
        return when (form.firstOrNull()) {
            "color-rgba", "color-rgb" -> {
                val v = form.drop(1).mapNotNull { it as? Double }
                if (v.size < 3) null else argb(v[0], v[1], v[2], v.getOrElse(3) { 1.0 })
            }
            "color" -> {
                val encoding = form.getOrNull(1) as? String ?: return null
                val raw = form.getOrNull(3) as? String ?: return null
                val bytes = ByteArray(raw.length) { raw[it].code.toByte() }
                val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val n = encoding.substringBefore(' ').count { it.isUpperCase() }
                val v = DoubleArray(n) { i ->
                    when {
                        encoding.endsWith("double") -> le.getDouble(i * 8)
                        encoding.endsWith("float") -> le.getFloat(i * 4).toDouble()
                        encoding.endsWith("u8") -> (bytes[i].toInt() and 255) / 255.0
                        else -> return null
                    }
                }
                val perceptual = '\'' in encoding
                fun srgb(x: Double) = if (perceptual) x else GimpOps.linearToSrgb(x.toFloat()).toDouble()
                when {
                    encoding.startsWith("R") && n >= 3 -> argb(srgb(v[0]), srgb(v[1]), srgb(v[2]), if (n >= 4) v[3] else 1.0)
                    encoding.startsWith("Y") -> argb(srgb(v[0]), srgb(v[0]), srgb(v[0]), if (n >= 2) v[1] else 1.0)
                    else -> null
                }
            }
            else -> null
        }
    }
}
