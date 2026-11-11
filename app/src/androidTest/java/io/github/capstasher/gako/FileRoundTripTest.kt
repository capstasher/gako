package io.github.capstasher.gako

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.capstasher.gako.TestImages.document
import io.github.capstasher.gako.TestImages.maxDiff
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.doc.LayerMode
import io.github.capstasher.gako.doc.OraFile
import io.github.capstasher.gako.doc.PsdReader
import io.github.capstasher.gako.doc.PsdWriter
import io.github.capstasher.gako.doc.XcfReader
import io.github.capstasher.gako.doc.XcfWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Write a document in each layered format and read it back. */
@RunWith(AndroidJUnit4::class)
class FileRoundTripTest {

    private fun DocState.named(name: String): Layer = layers.single { it.name == name }

    /** What every layered format keeps: order, names, offsets, sizes, opacity, visibility, groups. */
    private fun checkStructure(a: DocState, b: DocState) {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        assertEquals(a.layers.map { it.name }, b.layers.map { it.name })
        for (l in a.layers) {
            val r = b.named(l.name)
            assertEquals("${l.name} group", l.group, r.group)
            assertEquals("${l.name} visible", l.visible, r.visible)
            assertEquals("${l.name} opacity", l.opacity, r.opacity, 1f / 255)
            assertEquals("${l.name} parent", l.parent?.let { p -> a.layer(p)!!.name }, r.parent?.let { p -> b.layer(p)!!.name })
            if (!l.group) {
                assertEquals("${l.name} x", l.x, r.x)
                assertEquals("${l.name} y", l.y, r.y)
                assertEquals("${l.name} width", l.width, r.width)
                assertEquals("${l.name} height", l.height, r.height)
            }
        }
    }

    @Test fun xcfKeepsEverything() {
        val doc = document().let { d ->
            // A mode GAKO can't show (GIMP's Grain merge) on the hidden layer: it should survive.
            d.copy(layers = d.layers.map { if (it.name == "Top") it.copy(xcfMode = 47) else it })
        }
        val bytes = ByteArrayOutputStream().also { XcfWriter.write(doc, it) }.toByteArray()
        val back = XcfReader.read(ByteArrayInputStream(bytes))
        checkStructure(doc, back)
        for (l in doc.layers) {
            val r = back.named(l.name)
            assertEquals("${l.name} mode", l.mode, r.mode)
            if (l.group) continue
            assertEquals("${l.name} pixels", 0, maxDiff(l.bitmap, r.bitmap))
            assertEquals("${l.name} has mask", l.mask != null, r.mask != null)
            l.mask?.let { assertEquals("${l.name} mask", 0, maxDiff(it, r.mask!!)) }
        }
        assertEquals(47, back.named("Top").xcfMode)
        assertEquals("Inside", back.active?.name)
        // The path, point for point.
        assertEquals(1, back.paths.size)
        val s0 = doc.paths[0].strokes[0]
        val s1 = back.paths[0].strokes[0]
        assertEquals(doc.paths[0].name, back.paths[0].name)
        assertEquals(s0.closed, s1.closed)
        assertEquals(s0.points.size, s1.points.size)
        for (i in s0.points.indices) assertEquals(s0.points[i], s1.points[i], 0.001f)
    }

    @Test fun xcfWritesBackTheFilesModeOnlyWhileUnchanged() {
        val doc = document()
        // Read as GIMP 2.10's (linear) Multiply: written back as such…
        val kept = doc.copy(layers = doc.layers.map { if (it.name == "Group") it.copy(xcfMode = 30) else it })
        val back1 = XcfReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { XcfWriter.write(kept, it) }.toByteArray()))
        assertEquals(30, back1.named("Group").xcfMode)
        // …but once the mode is changed in GAKO, GAKO's own (legacy) number is written.
        val changed = kept.copy(layers = kept.layers.map { if (it.name == "Group") it.copy(mode = LayerMode.Screen) else it })
        val back2 = XcfReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { XcfWriter.write(changed, it) }.toByteArray()))
        assertEquals(LayerMode.Screen, back2.named("Group").mode)
        assertEquals(4, back2.named("Group").xcfMode)
    }

    @Test fun psdKeepsLayersGroupsAndMasks() {
        val doc = document()
        val bytes = ByteArrayOutputStream().also { PsdWriter.write(doc, it) }.toByteArray()
        val back = PsdReader.read(ByteArrayInputStream(bytes))
        checkStructure(doc, back)
        for (l in doc.layers) {
            val r = back.named(l.name)
            assertEquals("${l.name} mode", l.mode, r.mode)
            if (l.group) continue
            assertEquals("${l.name} pixels", 0, maxDiff(l.bitmap, r.bitmap))
            assertEquals("${l.name} has mask", l.mask != null, r.mask != null)
            l.mask?.let { assertEquals("${l.name} mask", 0, maxDiff(it, r.mask!!)) }
        }
    }

    @Test fun oraKeepsLayersAndGroups() {
        val doc = document()
        val bytes = ByteArrayOutputStream().also { OraFile.write(doc, it) }.toByteArray()
        val back = OraFile.read(ByteArrayInputStream(bytes))
        checkStructure(doc, back)
        // ORA has no masks: the masked layer comes back with its mask applied.
        assertNull(back.named("Inside").mask)
        assertEquals(0, maxDiff(doc.named("Background").bitmap, back.named("Background").bitmap))
        assertNotNull(back.named("Top"))
        assertTrue(back.named("Group").group)
    }
}
