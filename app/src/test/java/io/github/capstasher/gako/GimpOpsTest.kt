package io.github.capstasher.gako

import io.github.capstasher.gako.doc.GimpOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * GIMP's serialized operation settings, read as GIMP writes them into XCF layer effects. The
 * operations themselves are checked against GIMP's own output on a device (GimpModesTest);
 * Curves is checked here, since GIMP's scripting can't set a curve.
 */
class GimpOpsTest {

    /** Curves as GIMP saves it: per channel a curve with its sampled values. */
    private val curves = """
        (time 0)
        (trc perceptual)
        (channel value)
        (curve
            (curve-type smooth)
            (points 6 0 0 0.5 0.75 1 1)
            (point-types 3 smooth smooth smooth)
            (n-samples 5)
            (samples 5 0 0.5 0.75 0.90000000000000002 1))
        (channel red)
        (curve
            (curve-type smooth)
            (points 4 0 0 1 1)
            (point-types 2 smooth smooth)
            (n-samples 3)
            (samples 3 1 0.5 0))
        (channel green)
        (curve
            (curve-type smooth)
            (points 4 0 0 1 1)
            (point-types 2 smooth smooth)
            (n-samples 2)
            (samples 2 0 1))
    """.trimIndent()

    @Test fun curvesMapThroughTheChannelThenTheValueCurve() {
        val f = GimpOps.pixelFunction("gimp:curves", mapOf("config" to curves))
        assertNotNull(f)
        val p = floatArrayOf(0.5f, 0.5f, 0.125f, 0.6f)
        f!!(p)
        // Red: its own curve (inverted: 0.5 → 0.5), then the value curve (0.5 → 0.75).
        assertEquals(0.75f, p[0], 1e-5f)
        // Green: identity, then value: 0.5 → 0.75.
        assertEquals(0.75f, p[1], 1e-5f)
        // Blue: 0.125 lies between samples 0 (0) and 1 (0.5) of the value curve: 0.25.
        assertEquals(0.25f, p[2], 1e-5f)
        // Alpha has no curve here: unchanged.
        assertEquals(0.6f, p[3], 1e-6f)
        // Past the ends: the curve's end values.
        val q = floatArrayOf(0f, 1.2f, -0.1f, 1f)
        f(q)
        assertEquals(0f, q[1] - 1f, 1e-6f)
        assertEquals(0f, q[2], 1e-6f)
    }

    @Test fun readsGimpsConfigSyntax() {
        val c = GimpOps.Config.parse("(time 0)\n(brightness 0.20000000000000001)\n(contrast -0.29999999999999999)\n\u0000")
        assertEquals(0.2, c.num("brightness")!!, 1e-12)
        assertEquals(-0.3, c.num("contrast")!!, 1e-12)
        val l = GimpOps.Config.parse("(trc linear)\n(channel value)\n(gamma 1.5)\n(channel red)\n(gamma 0.7)")
        assertEquals("linear", l.word("trc"))
        assertEquals(1.5, l.section("channel", "value").num("gamma")!!, 0.0)
        assertEquals(0.7, l.section("channel", "red").num("gamma")!!, 0.0)
        assertEquals(null, l.section("channel", "blue").num("gamma"))
    }

    @Test fun gimpTextMarkupBecomesPlainText() {
        assertEquals("Tom & Jerry & <3 é", io.github.capstasher.gako.doc.GimpText.unmarkup(
            "<markup><b>Tom</b> &amp; Jerry &#38; &lt;3 &#xe9;</markup>"))
    }

    @Test fun brightnessContrastIsGimpsFormula() {
        // brightness 0.2 (halved), contrast 0.3: slant tan((0.3 + 1) π/4).
        val slant = Math.tan(1.3 * Math.PI / 4)
        assertEquals((0.5 + 0.5 * 0.1 - 0.5) * slant + 0.5, GimpOps.brightnessContrast(0.5, 0.1, slant), 1e-12)
    }
}
