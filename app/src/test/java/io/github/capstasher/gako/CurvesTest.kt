package io.github.capstasher.gako

import io.github.capstasher.gako.editor.Curves
import io.github.capstasher.gako.editor.LevelsChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurvesTest {
    @Test fun identityCurveIsIdentity() {
        val lut = Curves.lut(listOf(0f to 0f, 1f to 1f))
        for (i in 0..255) assertEquals(i, lut[i])
    }

    @Test fun curveHitsItsPointsAndNeverOvershoots() {
        val lut = Curves.lut(listOf(0f to 0f, 0.25f to 0.6f, 0.5f to 0.65f, 1f to 1f))
        assertEquals(153, lut[64], 1)              // 0.6 * 255 at x = 0.25
        for (i in 1..255) assertTrue("monotone at $i", lut[i] >= lut[i - 1])
        assertTrue(lut.all { it in 0..255 })
    }

    @Test fun invertedCurve() {
        val lut = Curves.lut(listOf(0f to 1f, 1f to 0f))
        assertEquals(255, lut[0])
        assertEquals(0, lut[255])
    }

    @Test fun levelsStretchAndGamma() {
        val l = LevelsChannel(inLow = 0.2f, inHigh = 0.8f)
        assertEquals(0f, l.map(0.1f), 1e-4f)
        assertEquals(1f, l.map(0.9f), 1e-4f)
        assertEquals(0.5f, l.map(0.5f), 1e-4f)
        val g = LevelsChannel(gamma = 2f)
        assertTrue(g.map(0.25f) > 0.25f)           // gamma > 1 brightens midtones, as in GIMP
        val out = LevelsChannel(outLow = 0.1f, outHigh = 0.9f)
        assertEquals(0.1f, out.map(0f), 1e-4f)
        assertEquals(0.9f, out.map(1f), 1e-4f)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected±$tolerance, got $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
