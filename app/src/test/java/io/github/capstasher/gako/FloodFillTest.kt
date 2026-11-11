package io.github.capstasher.gako

import io.github.capstasher.gako.doc.Selections
import org.junit.Assert.assertEquals
import org.junit.Test

class FloodFillTest {
    private val R = 0xFFFF0000.toInt()
    private val B = 0xFF0000FF.toInt()

    /** A 6×5 picture: a red ring of blue, with a blue hole in the middle. */
    private val w = 6
    private val h = 5
    private val px = intArrayOf(
        B, B, B, B, B, B,
        B, R, R, R, R, B,
        B, R, B, B, R, B,
        B, R, R, R, R, B,
        B, B, B, B, B, B,
    )

    private fun count(mask: ByteArray) = mask.count { it.toInt() != 0 }

    @Test fun fillsOnlyTheConnectedRegion() {
        val outer = Selections.floodRegion(px, w, h, 0, 0, threshold = 0)
        assertEquals(18, count(outer))             // the border, not the hole
        assertEquals(0, outer[2 * w + 2].toInt())
        val hole = Selections.floodRegion(px, w, h, 2, 2, threshold = 0)
        assertEquals(2, count(hole))
        val ring = Selections.floodRegion(px, w, h, 1, 1, threshold = 0)
        assertEquals(10, count(ring))
    }

    @Test fun thresholdLetsSimilarColoursIn() {
        val near = 0xFFFF0A0A.toInt()
        val img = intArrayOf(R, near, B)
        assertEquals(1, count(Selections.floodRegion(img, 3, 1, 0, 0, threshold = 5)))
        assertEquals(2, count(Selections.floodRegion(img, 3, 1, 0, 0, threshold = 10)))
    }

    @Test fun diagonalNeighbours() {
        val img = intArrayOf(
            R, B,
            B, R,
        )
        assertEquals(1, count(Selections.floodRegion(img, 2, 2, 0, 0, threshold = 0)))
        assertEquals(2, count(Selections.floodRegion(img, 2, 2, 0, 0, threshold = 0, diagonal = true)))
    }

    @Test fun byColourSelectsEverywhere() {
        assertEquals(10, count(Selections.byColor(px, R, 0)))
        assertEquals(20, count(Selections.byColor(px, B, 0)))
    }

    @Test fun transparentPixelsMatchWhateverTheirColour() {
        val img = intArrayOf(0x00FF0000, 0x0000FF00, R)
        assertEquals(2, count(Selections.floodRegion(img, 3, 1, 0, 0, threshold = 0)))
    }
}
