package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * GIMP's own colour operations (GIMP 3's app/operations/gimpoperation*.c), which GEGL doesn't have:
 * Brightness-Contrast, Levels, Curves, Hue-Saturation, Colorize, Color Balance, Threshold,
 * Posterize and Desaturate. GIMP 3 files use them as layer effects; each works in the same colour
 * space as in GIMP (linear light, sRGB or HSL), with GIMP's settings as GIMP saves them: a
 * serialized config ("(brightness 0.2)\n(contrast 0.3)" …) or plain arguments.
 */
object GimpOps {

    val supported = setOf(
        "gimp:brightness-contrast", "gimp:levels", "gimp:curves", "gimp:hue-saturation", "gimp:colorize",
        "gimp:color-balance", "gimp:threshold", "gimp:posterize", "gimp:desaturate",
    )

    /**
     * [op] over [src] (ARGB_8888) into a new bitmap, from GIMP's [args] (the effect's saved
     * arguments; a "config" one holds the serialized settings). Null if the settings can't be read.
     */
    fun apply(op: String, args: Map<String, Any>, src: Bitmap): Bitmap? {
        val f = pixelFunction(op, args) ?: return null
        val w = src.width
        val h = src.height
        val out = Bitmaps.blank(w, h)
        val row = IntArray(w)
        val px = FloatArray(4)
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)              // unpremultiplied
            for (x in 0 until w) {
                val c = row[x]
                val a = c ushr 24
                if (a == 0) { row[x] = 0; continue }
                px[0] = ((c shr 16) and 255) / 255f; px[1] = ((c shr 8) and 255) / 255f
                px[2] = (c and 255) / 255f; px[3] = a / 255f
                f(px)
                row[x] = (q(px[3]) shl 24) or (q(px[0]) shl 16) or (q(px[1]) shl 8) or q(px[2])
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    private fun q(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    // ---- in GAKO's Colours menu: GIMP's dialogs' settings, in GIMP's units

    private fun num(name: String, label: String, default: Double, min: Double, max: Double, digits: Int = 0) =
        io.github.capstasher.gako.engine.Param.Number(name, label, "", null, default, min, max, min, max, 1.0, digits,
            isInteger = digits == 0 && min == kotlin.math.floor(min) && max == kotlin.math.floor(max) && default == kotlin.math.floor(default),
            isSeed = false)

    private fun choice(name: String, label: String, default: String, vararg options: Pair<String, String>) =
        io.github.capstasher.gako.engine.Param.Choice(name, label, "", null, default, options.toList())

    /** GIMP's colour tools as filter dialogs (GIMP's own ranges: degrees, percent, 0–255). */
    val dialogs: Map<String, io.github.capstasher.gako.engine.OpDescription> = listOf(
        io.github.capstasher.gako.engine.OpDescription("gimp:brightness-contrast", "Brightness-Contrast",
            "GIMP's Brightness-Contrast", listOf(num("brightness", "Brightness", 0.0, -127.0, 127.0), num("contrast", "Contrast", 0.0, -127.0, 127.0)), 0),
        io.github.capstasher.gako.engine.OpDescription("gimp:hue-saturation", "Hue-Saturation", "GIMP's Hue-Saturation", listOf(
            choice("range", "Adjust", "all", "all" to "All colours", "red" to "Red", "yellow" to "Yellow", "green" to "Green",
                "cyan" to "Cyan", "blue" to "Blue", "magenta" to "Magenta"),
            num("hue", "Hue (°)", 0.0, -180.0, 180.0), num("lightness", "Lightness", 0.0, -100.0, 100.0),
            num("saturation", "Saturation", 0.0, -100.0, 100.0), num("overlap", "Overlap", 0.0, 0.0, 100.0)), 0),
        io.github.capstasher.gako.engine.OpDescription("gimp:colorize", "Colorize", "GIMP's Colorize", listOf(
            num("hue", "Hue (°)", 180.0, 0.0, 360.0), num("saturation", "Saturation", 50.0, 0.0, 100.0),
            num("lightness", "Lightness", 0.0, -100.0, 100.0)), 0),
        io.github.capstasher.gako.engine.OpDescription("gimp:color-balance", "Color Balance", "GIMP's Color Balance", listOf(
            choice("range", "Adjust", "midtones", "shadows" to "Shadows", "midtones" to "Midtones", "highlights" to "Highlights"),
            num("cyan-red", "Cyan ↔ Red", 0.0, -100.0, 100.0), num("magenta-green", "Magenta ↔ Green", 0.0, -100.0, 100.0),
            num("yellow-blue", "Yellow ↔ Blue", 0.0, -100.0, 100.0),
            io.github.capstasher.gako.engine.Param.Toggle("preserve-luminosity", "Preserve luminosity", "", null, true)), 0),
        io.github.capstasher.gako.engine.OpDescription("gimp:threshold", "Threshold", "GIMP's Threshold", listOf(
            choice("channel", "Channel", "value", "value" to "Value", "red" to "Red", "green" to "Green", "blue" to "Blue",
                "luminance" to "Luminance"),
            num("low", "Low", 127.0, 0.0, 255.0), num("high", "High", 255.0, 0.0, 255.0)), 0),
        io.github.capstasher.gako.engine.OpDescription("gimp:posterize", "Posterize", "GIMP's Posterize",
            listOf(num("levels", "Levels", 3.0, 2.0, 256.0)), 0),
        io.github.capstasher.gako.engine.OpDescription("gimp:desaturate", "Desaturate", "GIMP's Desaturate", listOf(
            choice("mode", "Shade of grey from", "luminance", "lightness" to "Lightness (HSL)", "luma" to "Luma",
                "average" to "Average (HSI Intensity)", "luminance" to "Luminance", "value" to "Value (HSV)")), 0),
    ).associateBy { it.name }

    /** [op] with a dialog's [values] (see [dialogs]) over [src], or null if it isn't one of them. */
    fun applyDialog(op: String, values: Map<String, Any?>, src: Bitmap): Bitmap? {
        fun d(k: String, def: Double = 0.0) = (values[k] as? Number)?.toDouble() ?: def
        fun w(k: String, def: String) = values[k] as? String ?: def
        val args: Map<String, Any> = when (op) {
            "gimp:brightness-contrast" -> mapOf("config" to "(brightness ${d("brightness") / 127.0})\n(contrast ${d("contrast") / 127.0})")
            "gimp:hue-saturation" -> mapOf("config" to "(range ${w("range", "all")})\n(hue ${d("hue") / 180.0})\n" +
                "(saturation ${d("saturation") / 100.0})\n(lightness ${d("lightness") / 100.0})\n(overlap ${d("overlap") / 100.0})")
            "gimp:colorize" -> mapOf("hue" to d("hue", 180.0) / 360.0, "saturation" to d("saturation", 50.0) / 100.0,
                "lightness" to d("lightness") / 100.0)
            "gimp:color-balance" -> mapOf("config" to "(range ${w("range", "midtones")})\n(cyan-red ${d("cyan-red") / 100.0})\n" +
                "(magenta-green ${d("magenta-green") / 100.0})\n(yellow-blue ${d("yellow-blue") / 100.0})\n" +
                "(preserve-luminosity ${if (values["preserve-luminosity"] != false) "yes" else "no"})")
            "gimp:threshold" -> mapOf("channel" to listOf("value", "red", "green", "blue", "alpha", "luminance").indexOf(w("channel", "value")).coerceAtLeast(0),
                "low" to d("low", 127.0) / 255.0, "high" to d("high", 255.0) / 255.0)
            "gimp:posterize" -> mapOf("levels" to d("levels", 3.0).toInt())
            "gimp:desaturate" -> mapOf("mode" to listOf("lightness", "luma", "average", "luminance", "value").indexOf(w("mode", "luminance")).coerceAtLeast(0))
            else -> return null
        }
        return apply(op, args, src)
    }

    /** A per-pixel function (so a lambda after local values isn't read as their argument). */
    private inline fun px(noinline f: (FloatArray) -> Unit) = f

    /** What [op] does to one pixel: straight RGBA in sRGB (0..1), changed in place. Null if unreadable. */
    fun pixelFunction(op: String, args: Map<String, Any>): ((FloatArray) -> Unit)? {
        val config = (args["config"] as? String)?.let { Config.parse(it) }
        fun num(name: String, default: Double) = (args[name] as? Number)?.toDouble() ?: config?.num(name) ?: default
        return when (op) {
            "gimp:brightness-contrast" -> {
                val brightness = (config?.num("brightness") ?: num("brightness", 0.0)) / 2.0
                val slant = tan(((config?.num("contrast") ?: num("contrast", 0.0)) + 1) * PI / 4)
                // A point filter without a TRC setting works in linear light.
                linear { p -> for (c in 0..2) p[c] = brightnessContrast(p[c].toDouble(), brightness, slant).toFloat() }
            }
            "gimp:levels" -> levels(config ?: return null)
            "gimp:curves" -> curves(config ?: return null)
            "gimp:hue-saturation" -> hueSaturation(config ?: return null)
            "gimp:colorize" -> {
                val hue = num("hue", 0.5).toFloat(); val sat = num("saturation", 0.5).toFloat()
                val light = num("lightness", 0.0).toFloat()
                val toLin = srgbToLinear
                px { p ->
                    // Luminance of the linear colour; the result is HSL turned back as is (as GIMP has always done).
                    var lum = gimpLuminance(toLin(p[0]), toLin(p[1]), toLin(p[2]))
                    if (light > 0f) { lum = lum * (1f - light); lum += 1f - (1f - light) }
                    else if (light < 0f) lum *= (light + 1f)
                    hslToRgb(hue, sat, lum, p)
                    for (c in 0..2) p[c] = linearToSrgb(p[c])     // GIMP's output is read as linear
                }
            }
            "gimp:color-balance" -> colorBalance(config ?: return null)
            "gimp:threshold" -> {
                val channel = (args["channel"] as? Number)?.toInt() ?: 0
                val low = num("low", 0.5); val high = num("high", 1.0)
                px { p ->
                    val v = when (channel) {
                        1 -> p[0]; 2 -> p[1]; 3 -> p[2]; 4 -> p[3]
                        5 -> gimpLuminance(p[0], p[1], p[2])                  // luminance
                        6 -> min(p[0], min(p[1], p[2]))                        // RGB
                        else -> max(p[0], max(p[1], p[2]))                     // value
                    }
                    // GIMP compares its float pixel with the double settings.
                    val o = if (v.toDouble() >= low && v.toDouble() <= high) 1f else 0f
                    p[0] = o; p[1] = o; p[2] = o
                }
            }
            "gimp:posterize" -> {
                val levels = ((args["levels"] as? Number)?.toFloat() ?: 3f) - 1f
                if (levels <= 0f) return null
                px { p -> for (c in 0..3) p[c] = Math.rint((p[c] * levels).toDouble()).toFloat() / levels }
            }
            "gimp:desaturate" -> when ((args["mode"] as? Number)?.toInt() ?: 0) {
                3 -> linear { p -> val v = RED_Y * p[0] + GREEN_Y * p[1] + BLUE_Y * p[2]; p[0] = v; p[1] = v; p[2] = v }
                1 -> { p -> val v = RED_Y * p[0] + GREEN_Y * p[1] + BLUE_Y * p[2]; p[0] = v; p[1] = v; p[2] = v }
                2 -> { p -> val v = (p[0] + p[1] + p[2]) / 3; p[0] = v; p[1] = v; p[2] = v }
                4 -> { p -> val v = max(p[0], max(p[1], p[2])); p[0] = v; p[1] = v; p[2] = v }
                else -> { p -> val v = (max(p[0], max(p[1], p[2])) + min(p[0], min(p[1], p[2]))) / 2; p[0] = v; p[1] = v; p[2] = v }
            }
            else -> null
        }
    }

    // ---- colour spaces (babl's sRGB TRC and luminance)

    /** GIMP_RGB_LUMINANCE (libgimpcolor), in double as GIMP's macro is: its weights add up to exactly 1. */
    fun gimpLuminance(r: Float, g: Float, b: Float) = (r * 0.22248840 + g * 0.71690369 + b * 0.06060791).toFloat()

    const val RED_Y = 0.2224879f
    const val GREEN_Y = 0.7169039f
    const val BLUE_Y = 0.0606083f

    val srgbToLinear: (Float) -> Float = { v -> if (v > 0.04045f) ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() else v / 12.92f }
    val linearToSrgb: (Float) -> Float = { v -> if (v > 0.0031308f) (1.055 * v.toDouble().pow(1 / 2.4) - 0.055).toFloat() else v * 12.92f }

    /** [f] run on the pixel in linear light. */
    private fun linear(f: (FloatArray) -> Unit): (FloatArray) -> Unit = { p ->
        for (c in 0..2) p[c] = srgbToLinear(p[c])
        f(p)
        for (c in 0..2) p[c] = linearToSrgb(p[c])
    }

    /** GIMP's TRC setting ("linear", "non-linear", "perceptual"): linear light or not. */
    private fun inTrc(trc: String?, f: (FloatArray) -> Unit) = if (trc == "linear") linear(f) else f

    fun brightnessContrast(value: Double, brightness: Double, slant: Double): Double {
        var v = value
        v = if (brightness < 0.0) v * (1.0 + brightness) else v + (1.0 - v) * brightness
        return (v - 0.5) * slant + 0.5
    }

    // ---- Levels

    private val channels = listOf("value", "red", "green", "blue", "alpha")

    private fun levels(cfg: Config): (FloatArray) -> Unit {
        val per = channels.map { ch ->
            val s = cfg.section("channel", ch)
            doubleArrayOf(s.num("low-input") ?: 0.0, s.num("high-input") ?: 1.0, s.num("gamma") ?: 1.0,
                s.num("low-output") ?: 0.0, s.num("high-output") ?: 1.0)
        }
        val clampIn = cfg.word("clamp-input") == "yes"
        val clampOut = cfg.word("clamp-output") == "yes"
        fun map(v: Double, l: DoubleArray): Double {
            var x = if (l[1] != l[0]) (v - l[0]) / (l[1] - l[0]) else v - l[0]
            if (clampIn) x = x.coerceIn(0.0, 1.0)
            val inv = 1.0 / l[2]
            if (inv != 1.0 && x > 0) x = x.pow(inv)
            x = if (l[4] >= l[3]) x * (l[4] - l[3]) + l[3] else l[3] - x * (l[3] - l[4])
            if (clampOut) x = x.coerceIn(0.0, 1.0)
            return x
        }
        return inTrc(cfg.word("trc")) { p ->
            for (c in 0..3) {
                var v = map(p[c].toDouble(), per[c + 1])
                if (c != 3) v = map(v, per[0])
                p[c] = v.toFloat()
            }
        }
    }

    // ---- Curves (GIMP saves each curve's samples, so no spline is needed)

    private fun curves(cfg: Config): (FloatArray) -> Unit {
        val curves = channels.map { ch -> cfg.section("channel", ch).list("curve")?.let { Config(it) }?.numbers("samples") }
        fun map(curve: DoubleArray?, v: Float): Float {
            if (curve == null || curve.size < 2) return if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
            return when {
                v > 0f && v < 1f -> {
                    val x = v * (curve.size - 1)
                    val i = x.toInt()
                    val f = x - i
                    ((1 - f) * curve[i] + f * curve[i + 1]).toFloat()
                }
                v >= 1f -> curve.last().toFloat()
                else -> curve.first().toFloat()
            }
        }
        return inTrc(cfg.word("trc") ?: "perceptual") { p ->
            for (c in 0..2) p[c] = map(curves[0], map(curves[c + 1], p[c]))
            p[3] = map(curves[4], p[3])
        }
    }

    // ---- HSL (babl's, from sRGB values) and Hue-Saturation, Color Balance

    fun rgbToHsl(r: Float, g: Float, b: Float, out: FloatArray) {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx - mn < 1e-10f) { out[0] = 0f; out[1] = 0f; out[2] = l; return }
        val d = mx - mn; val s = mx + mn
        out[1] = if (l > 0.5f) d / (2f - s) else d / s
        var h = when {
            mx - r < 1e-10f -> (g - b) / d + (if (g < b) 6f else 0f)
            mx - g < 1e-10f -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h /= 6f
        out[0] = h; out[2] = l
    }

    private fun hue2(p: Float, q: Float, h0: Float): Float {
        var h = h0
        if (h < 0f) h += 1f
        if (h > 1f) h -= 1f
        return when {
            h < 1f / 6 -> p + (q - p) * 6f * h
            h < 1f / 2 -> q
            h < 2f / 3 -> p + (q - p) * (2f / 3 - h) * 6f
            else -> p
        }
    }

    /** babl's HSL → sRGB. */
    fun hslToRgbBabl(h0: Float, s: Float, l: Float, out: FloatArray) {
        if (s < 1e-7f) { out[0] = l; out[1] = l; out[2] = l; return }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        var h = h0 % 1f
        if (h < 0f) h += 1f
        out[0] = hue2(p, q, h + 1f / 3); out[1] = hue2(p, q, h); out[2] = hue2(p, q, h - 1f / 3)
    }

    /** GIMP's own HSL → RGB (gimp_hsl_to_rgb), as Colorize uses it. */
    fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
        if (s == 0f) { out[0] = l; out[1] = l; out[2] = l; return }
        val m2 = if (l <= 0.5f) l * (1 + s) else l + s - l * s
        val m1 = 2 * l - m2
        fun v(hue0: Float): Float {
            var hue = hue0
            if (hue > 6f) hue -= 6f else if (hue < 0f) hue += 6f
            return when {
                hue < 1f -> m1 + (m2 - m1) * hue
                hue < 3f -> m2
                hue < 4f -> m1 + (m2 - m1) * (4f - hue)
                else -> m1
            }
        }
        out[0] = v(h * 6 + 2); out[1] = v(h * 6); out[2] = v(h * 6 - 2)
    }

    private val ranges = listOf("all", "red", "yellow", "green", "cyan", "blue", "magenta")

    private fun hueSaturation(cfg: Config): (FloatArray) -> Unit {
        val hue = FloatArray(7); val sat = FloatArray(7); val light = FloatArray(7)
        ranges.forEachIndexed { i, r ->
            val s = cfg.section("range", r)
            hue[i] = (s.num("hue") ?: 0.0).toFloat(); sat[i] = (s.num("saturation") ?: 0.0).toFloat()
            light[i] = (s.num("lightness") ?: 0.0).toFloat()
        }
        val overlap = ((cfg.num("overlap") ?: 0.0) / 2.0).toFloat()
        fun wrap(v: Float) = if (v < 0) v + 1f else if (v > 1f) v - 1f else v
        fun mapSat(r: Int, v: Float) = (v * (sat[0] + sat[r] + 1f)).coerceIn(0f, 1f)
        fun mapLight(r: Int, v: Float): Float { val a = light[0] + light[r]; return if (a < 0) v * (a + 1f) else v + a * (1f - v) }
        val hsl = FloatArray(3)
        return { p ->
            rgbToHsl(p[0], p[1], p[2], hsl)
            val h = hsl[0] * 6f
            var hueIdx = 0; var second = 0; var useSecond = false; var pi = 0f; var si = 0f
            for (k in 0 until 7) {
                val t = k + 0.5f
                if (h < t + overlap) {
                    hueIdx = k
                    if (overlap > 0f && h > t - overlap) {
                        useSecond = true; second = k + 1
                        si = (h - t + overlap) / (2f * overlap); pi = 1f - si
                    } else useSecond = false
                    break
                }
            }
            if (hueIdx >= 6) { hueIdx = 0; useSecond = false }
            if (second >= 6) second = 0
            hueIdx++; second++
            if (useSecond) {
                val v = hue[hueIdx] * pi + hue[second] * si
                hsl[0] = wrap(hsl[0] + (hue[0] + v) / 2f)
                val s0 = hsl[1]; val l0 = hsl[2]
                hsl[1] = mapSat(hueIdx, s0) * pi + mapSat(second, s0) * si
                hsl[2] = mapLight(hueIdx, l0) * pi + mapLight(second, l0) * si
            } else if (hsl[1] <= 0f) {
                val a = light[0]
                hsl[2] = if (a < 0) hsl[2] * (a + 1f) else hsl[2] + a * (1f - hsl[2])
            } else {
                hsl[0] = wrap(hsl[0] + (hue[0] + hue[hueIdx]) / 2f)
                hsl[2] = mapLight(hueIdx, hsl[2])
                hsl[1] = mapSat(hueIdx, hsl[1])
            }
            hslToRgbBabl(hsl[0], hsl[1], hsl[2], p)
        }
    }

    private fun colorBalance(cfg: Config): (FloatArray) -> Unit {
        val names = listOf("shadows", "midtones", "highlights")
        val cr = FloatArray(3); val mg = FloatArray(3); val yb = FloatArray(3)
        names.forEachIndexed { i, r ->
            val s = cfg.section("range", r)
            cr[i] = (s.num("cyan-red") ?: 0.0).toFloat(); mg[i] = (s.num("magenta-green") ?: 0.0).toFloat()
            yb[i] = (s.num("yellow-blue") ?: 0.0).toFloat()
        }
        val preserve = cfg.word("preserve-luminosity") == "yes"
        val a = 0.25f; val b = 0.333f; val scale = 0.7f
        fun map(v: Float, l: Float, s: Float, m: Float, h: Float): Float {
            val sh = s * ((l - b) / -a + 0.5f).coerceIn(0f, 1f) * scale
            val mi = m * ((l - b) / a + 0.5f).coerceIn(0f, 1f) * ((l + b - 1f) / -a + 0.5f).coerceIn(0f, 1f) * scale
            val hi = h * ((l + b - 1f) / a + 0.5f).coerceIn(0f, 1f) * scale
            return (v + sh + mi + hi).coerceIn(0f, 1f)
        }
        val hsl = FloatArray(3)
        return { p ->
            rgbToHsl(p[0], p[1], p[2], hsl)
            val l = hsl[2]
            p[0] = map(p[0], l, cr[0], cr[1], cr[2])
            p[1] = map(p[1], l, mg[0], mg[1], mg[2])
            p[2] = map(p[2], l, yb[0], yb[1], yb[2])
            if (preserve) {
                rgbToHsl(p[0], p[1], p[2], hsl)
                hslToRgbBabl(hsl[0], hsl[1], l, p)
            }
        }
    }

    /**
     * GIMP's serialized config: "(name value…)" forms, some nested; "(channel red)" or
     * "(range red)" starts the settings for that part, which follow it.
     */
    class Config(val forms: List<List<Any>>) {
        /** The first form named [name]'s single value as a number. */
        fun num(name: String) = forms.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)?.let { (it as? Double) ?: (it as? String)?.toDoubleOrNull() }
        fun word(name: String) = forms.firstOrNull { it.firstOrNull() == name }?.getOrNull(1) as? String
        /** A form's values after its count, as numbers: "(samples 256 0 0.0039…)". */
        fun numbers(name: String): DoubleArray? {
            val f = forms.firstOrNull { it.firstOrNull() == name } ?: return null
            val vals = f.drop(1).mapNotNull { it as? Double }
            val n = vals.firstOrNull()?.toInt() ?: return null
            return vals.drop(1).take(n).toDoubleArray().takeIf { it.size == n }
        }
        /** The forms inside a nested form "(name (a 1) (b 2))". */
        @Suppress("UNCHECKED_CAST")
        fun list(name: String): List<List<Any>>? =
            forms.firstOrNull { it.firstOrNull() == name }?.drop(1)?.filterIsInstance<List<Any>>()
        /** The forms after "([key] [value])" up to the next "([key] …)". */
        fun section(key: String, value: String): Config {
            val start = forms.indexOfFirst { it.firstOrNull() == key && it.getOrNull(1) == value }
            if (start < 0) return Config(emptyList())
            val end = forms.drop(start + 1).indexOfFirst { it.firstOrNull() == key }.let { if (it < 0) forms.size else start + 1 + it }
            return Config(forms.subList(start + 1, end))
        }

        companion object {
            fun parse(text: String): Config {
                var i = 0
                fun skip() { while (i < text.length && (text[i].isWhitespace() || text[i] == '\u0000')) i++ }
                fun atom(): Any {
                    if (text[i] == '"') {
                        val sb = StringBuilder(); i++
                        while (i < text.length && text[i] != '"') { if (text[i] == '\\' && i + 1 < text.length) i++; sb.append(text[i]); i++ }
                        i++
                        return sb.toString()
                    }
                    val st = i
                    while (i < text.length && !text[i].isWhitespace() && text[i] != '(' && text[i] != ')') i++
                    val s = text.substring(st, i)
                    return s.toDoubleOrNull() ?: s
                }
                fun form(): List<Any> {
                    i++                                    // '('
                    val out = ArrayList<Any>()
                    while (true) {
                        skip()
                        if (i >= text.length) return out
                        when (text[i]) {
                            ')' -> { i++; return out }
                            '(' -> out.add(form())
                            else -> out.add(atom())
                        }
                    }
                }
                val top = ArrayList<List<Any>>()
                while (true) {
                    skip()
                    if (i >= text.length) break
                    if (text[i] == '(') top += form() else i++
                }
                return Config(top)
            }
        }
    }
}
