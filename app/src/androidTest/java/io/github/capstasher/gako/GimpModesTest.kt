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
