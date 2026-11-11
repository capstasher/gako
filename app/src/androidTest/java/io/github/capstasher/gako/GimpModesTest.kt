package io.github.capstasher.gako

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.XcfReader
import io.github.capstasher.gako.doc.XcfWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * GAKO against GIMP itself. `assets/gimp/modes.zip` holds small XCFs that GIMP 3.2 made (every
 * layer mode, legacy and default, at two opacities over opaque, see-through and empty backdrops;
 * groups; masks; blend and composite spaces set by hand), and `expected.bin` (gzipped text) what GIMP drew for
 * each. GAKO opens each file and must draw the same. Regenerate both with
 * `scripts/gimp_modes_oracle.py` (see its header).
 */
@RunWith(AndroidJUnit4::class)
class GimpModesTest {

    private fun cases(): Map<String, ByteArray> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val files = HashMap<String, ByteArray>()
        ZipInputStream(assets.open("gimp/modes.zip")).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                files[e.name.removeSuffix(".xcf")] = zip.readBytes()
            }
        }
        return files
    }

    private fun expected(): Map<String, ByteArray> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return GZIPInputStream(assets.open("gimp/expected.bin")).bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val (name, hex) = line.split(' ')
                name to ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
    }

    /**
     * How far a colour channel may be off: 1 (rounding) where opaque. GAKO keeps pixels
     * premultiplied in 8 bits, so a see-through pixel's colour is only known to about
     * 255 / alpha / 2 either way, before and after.
     */
    private fun tolerance(alpha: Int) = if (alpha == 255) 1 else 1 + (255 + alpha - 1) / max(alpha, 1)

    /**
     * LCh Hue with a grey layer, and LCh Chroma over a grey backdrop: the hue is undefined, and
     * GIMP's comes from babl's rounding noise (it tints white yellow); GAKO leaves those alone.
     * Columns are the layer's colours, rows the backdrop's (see the oracle script).
     */
    private fun undefinedHue(case: String, x: Int, y: Int) =
        (case.startsWith("m24_") && x in setOf(0, 1, 2, 10)) || (case.startsWith("m25_") && y in setOf(0, 1, 4, 10))

    @Test fun drawsEveryLayerModeLikeGimp() {
        val files = cases()
        val want = expected()
        assertEquals("every case has GIMP's result", want.keys, files.keys)
        val failures = ArrayList<String>()
        for ((name, xcf) in files.toSortedMap()) {
            val doc = XcfReader.read(ByteArrayInputStream(xcf))
            val got = Compositor.flatten(doc)
            val exp = want.getValue(name)
            var worst = 0
            var where = ""
            for (y in 0 until doc.height) for (x in 0 until doc.width) {
                if (undefinedHue(name, x, y)) continue
                val i = (y * doc.width + x) * 4
                val ea = exp[i + 3].toInt() and 255
                val p = got.getPixel(x, y)
                val ga = p ushr 24
                var off = abs(ea - ga) - 1                    // alpha: rounding only
                // Colour: only where it's visible enough to be known.
                if (ea >= 16 && ga >= 16) for (c in 0..2) {
                    val e = exp[i + c].toInt() and 255
                    val g = (p shr (16 - 8 * c)) and 255
                    off = max(off, abs(e - g) - tolerance(minOf(ea, ga)))
                }
                if (off > worst) {
                    worst = off
                    where = "($x,$y) GIMP ${(0..3).map { exp[i + it].toInt() and 255 }} GAKO ${
                        listOf((p shr 16) and 255, (p shr 8) and 255, p and 255, p ushr 24)}"
                }
            }
            if (worst > 0) failures += "$name: off by $worst more than allowed at $where"
        }
        assertTrue("${failures.size} of ${files.size} differ from GIMP:\n" + failures.joinToString("\n"), failures.isEmpty())
    }


    /**
     * GIMP 3 layer effects (`assets/gimp/effects.xcf`, XCF 22): a Gaussian blur then a half-strength
     * Invert on one layer (at 80%), an unclipped drop shadow on a small one. GAKO reads them, applies
     * them with its own GEGL, and draws what GIMP 3.2 drew (`effects.rgba`).
     */
    @Test fun appliesGimp3LayerEffects() = kotlinx.coroutines.runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation()
        assertEquals(null, io.github.capstasher.gako.engine.Engine.start(ctx.targetContext))
        val doc = ctx.context.assets.open("gimp/effects.xcf").use { XcfReader.read(it) }
        val blurred = doc.layers.single { it.name == "Blurred" }
        assertEquals(listOf("gegl:gaussian-blur", "gegl:invert-gamma"), blurred.effects.map { it.operation })
        assertEquals(2.5, blurred.effects[0].args["std-dev-x"] as Double, 0.001)
        assertEquals(0.5f, blurred.effects[1].opacity, 0.001f)
        val shadow = doc.layers.single { it.name == "Shadowed" }.effects.single()
        assertEquals(false, shadow.clip)
        assertTrue("the shadow's colour", (shadow.args["color"] as String).startsWith("rgba(0.1"))
        val ops = io.github.capstasher.gako.engine.Engine.operations().map { it.name }.toSet()
        val out = io.github.capstasher.gako.engine.Effects.apply(doc, ops)
        assertEquals(3, out.applied)
        assertTrue(out.skipped.isEmpty())
        assertTrue("no effects left", out.doc.layers.all { it.effects.isEmpty() })
        val got = Compositor.flatten(out.doc)
        val want = ctx.context.assets.open("gimp/effects.rgba").readBytes()
        var worst = 0
        var where = ""
        for (y in 0 until doc.height) for (x in 0 until doc.width) {
            val i = (y * doc.width + x) * 4
            val p = got.getPixel(x, y)
            for (c in 0..2) {
                val off = abs((want[i + c].toInt() and 255) - ((p shr (16 - 8 * c)) and 255))
                if (off > worst) { worst = off; where = "($x,$y)" }
            }
        }
        // Rounding (GAKO's GEGL may also be a different release from GIMP's).
        assertTrue("off by $worst at $where", worst <= 2)
    }

    /**
     * GIMP's own colour operations as GIMP 3 layer effects (`assets/gimp/ops.zip`, made by
     * `scripts/gimp_ops_oracle.py`): Brightness-Contrast, Levels (linear and perceptual),
     * Hue-Saturation with overlap, Colorize, Color Balance, Threshold, Posterize and every
     * Desaturate mode, each drawn as GIMP 3.2 drew it.
     */
    @Test fun appliesGimpColourEffects() = kotlinx.coroutines.runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val files = HashMap<String, ByteArray>()
        ZipInputStream(assets.open("gimp/ops.zip")).use { z ->
            while (true) { val e = z.nextEntry ?: break; files[e.name.removeSuffix(".xcf")] = z.readBytes() }
        }
        val want = GZIPInputStream(assets.open("gimp/ops-expected.bin")).bufferedReader().readLines().filter { it.isNotBlank() }
            .associate { l -> l.substringBefore(' ') to l.substringAfter(' ').chunked(2).map { it.toInt(16) } }
        val failures = ArrayList<String>()
        for ((name, xcf) in files.toSortedMap()) {
            val doc = XcfReader.read(ByteArrayInputStream(xcf))
            // No GEGL needed: these are GAKO's port of GIMP's own operations.
            val out = io.github.capstasher.gako.engine.Effects.apply(doc, emptySet())
            if (out.applied != 1) { failures += "$name: applied ${out.applied}, skipped ${out.skipped}"; continue }
            val got = Compositor.flatten(out.doc)
            val exp = want.getValue(name)
            var worst = 0; var where = ""
            for (y in 0 until doc.height) for (x in 0 until doc.width) {
                val i = (y * doc.width + x) * 4
                val p = got.getPixel(x, y)
                val a = exp[i + 3]
                var off = abs(a - (p ushr 24)) - 1
                if (a >= 16) for (c in 0..2) off = max(off, abs(exp[i + c] - ((p shr (16 - 8 * c)) and 255)) - tolerance(a))
                if (off > worst) { worst = off; where = "($x,$y) GIMP ${exp.subList(i, i + 4)} GAKO ${listOf((p shr 16) and 255, (p shr 8) and 255, p and 255, p ushr 24)}" }
            }
            if (worst > 0) failures += "$name: off by $worst more than allowed at $where"
            // The Colours menu's dialogs (GIMP's units) give the same as the effect.
            dialogFor[name]?.let { values ->
                val op = doc.layers.single().effects.single().operation
                val fromDialog = io.github.capstasher.gako.doc.GimpOps.applyDialog(op, values, doc.layers.single().bitmap)!!
                val layer = out.doc.layers.single().bitmap
                if (TestImages.maxDiff(layer, fromDialog) > 1) failures += "$name: the dialog's result differs from the effect's (${doc.layers.single().effects.single().args})"
            }
        }
        assertTrue("${failures.size} of ${files.size} differ from GIMP:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** The same settings as some of the oracle's cases, as the Colours menu's dialogs take them. */
    private val dialogFor = mapOf(
        "bc_up" to mapOf("brightness" to 0.2 * 127, "contrast" to 0.3 * 127),
        "colorize" to mapOf("hue" to 180.0, "saturation" to 50.0, "lightness" to 0.0),     // its defaults (see the script)
        "threshold" to mapOf("channel" to "value", "low" to 102.0, "high" to 204.0),
        "posterize" to mapOf("levels" to 4),
        "desaturate_luma" to mapOf("mode" to "luma"),
        "balance_plain" to mapOf("range" to "highlights", "yellow-blue" to -60.0, "preserve-luminosity" to false),
    )

    @Test fun xcfKeepsModesAndBlendSettings() {
        for ((name, xcf) in cases()) {
            if (!name.startsWith("m") && !name.startsWith("cs_") && !name.startsWith("bs_") && !name.startsWith("cm_")) continue
            val doc = XcfReader.read(ByteArrayInputStream(xcf))
            val again = XcfReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { XcfWriter.write(doc, it) }.toByteArray()))
            doc.layers.zip(again.layers).forEach { (a, b) ->
                assertEquals("$name mode", a.gimpMode, b.gimpMode)
                assertEquals("$name blend space", a.blendSpace, b.blendSpace)
                assertEquals("$name composite space", a.compositeSpace, b.compositeSpace)
                assertEquals("$name composite mode", a.compositeMode, b.compositeMode)
            }
        }
    }
}
