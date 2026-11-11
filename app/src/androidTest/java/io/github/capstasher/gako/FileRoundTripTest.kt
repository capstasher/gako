package io.github.capstasher.gako

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.capstasher.gako.TestImages.document
import io.github.capstasher.gako.TestImages.maxDiff
import io.github.capstasher.gako.doc.DocState
import io.github.capstasher.gako.doc.Guide
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
            // A mode GAKO draws as Normal (GIMP's Dissolve) on the hidden layer: it should survive.
            d.copy(layers = d.layers.map { if (it.name == "Top") it.copy(xcfMode = 1) else it })
        }
        val bytes = ByteArrayOutputStream().also { XcfWriter.write(doc, it) }.toByteArray()
        val back = XcfReader.read(ByteArrayInputStream(bytes))
        checkStructure(doc, back)
        for (l in doc.layers) {
            val r = back.named(l.name)
            assertEquals("${l.name} mode", l.mode, r.mode)
            assertEquals("${l.name} GIMP mode", l.gimpMode, r.gimpMode)
            if (l.group) continue
            assertEquals("${l.name} pixels", 0, maxDiff(l.bitmap, r.bitmap))
            assertEquals("${l.name} has mask", l.mask != null, r.mask != null)
            l.mask?.let { assertEquals("${l.name} mask", 0, maxDiff(it, r.mask!!)) }
        }
        assertEquals(1, back.named("Top").gimpMode)
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

    @Test fun xcfKeepsGimpsModeNumbers() {
        val doc = document()
        // GIMP 2.10+'s (linear) Multiply and its legacy one are different modes, and both stay what they were.
        for ((legacy, number) in listOf(false to 30, true to 3)) {
            val d = doc.copy(layers = doc.layers.map { if (it.name == "Group") it.copy(legacy = legacy) else it })
            val back = XcfReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { XcfWriter.write(d, it) }.toByteArray()))
            assertEquals(number, back.named("Group").gimpMode)
            assertEquals(legacy, back.named("Group").legacy)
        }
        // GIMP's legacy list has the default Overlay; its old legacy Overlay (5) is drawn and read as Soft light.
        assertEquals(23, Layer(1, "o", io.github.capstasher.gako.doc.Bitmaps.blank(1, 1), mode = LayerMode.Overlay, legacy = true).gimpMode)
        assertEquals(LayerMode.SoftLight to true, LayerMode.ofGimp(5))
        // A mode GAKO draws as Normal (Dissolve) is written back while the layer stays Normal…
        val kept = doc.copy(layers = doc.layers.map { if (it.name == "Top") it.copy(xcfMode = 1) else it })
        val back1 = XcfReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { XcfWriter.write(kept, it) }.toByteArray()))
        assertEquals(1, back1.named("Top").gimpMode)
        // …but once the mode is changed in GAKO, GAKO's number is written.
        val changed = kept.copy(layers = kept.layers.map { if (it.name == "Top") it.copy(mode = LayerMode.Screen) else it })
        val back2 = XcfReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { XcfWriter.write(changed, it) }.toByteArray()))
        assertEquals(LayerMode.Screen, back2.named("Top").mode)
        assertEquals(4, back2.named("Top").gimpMode)
    }

    /**
     * A file GIMP 3.2 made (`assets/gimp/fidelity.xcf`, XCF 19) with guides, a sample point, a saved
     * channel, a selection, a comment, 300 dpi, a colour-tagged locked layer with its own parasite, a
     * GIMP text layer and a path stored as a GIMP 3 path item: GAKO keeps all of it through a save,
     * and marks the text layer modified once its pixels change.
     */
    @Test fun xcfFromGimpKeepsWhatGakoDoesntEdit() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val original = ctx.context.assets.open("gimp/fidelity.xcf").use { XcfReader.read(it) }
        fun check(d: DocState) {
            assertEquals(setOf(Guide(true, 10), Guide(false, 30), Guide(true, 40)), d.gimp.guides.toSet())
            assertEquals(listOf(12 to 34), d.gimp.samplePoints.map { it.x to it.y })
            val ch = d.gimp.channels.single()
            assertEquals("Saved", ch.name)
            assertEquals(40, (ch.bitmap.getPixel(10, 5) shr 16) and 255)
            assertTrue("channel colour and opacity kept", ch.props.any { it.type == 16 } && ch.props.any { it.type == 6 })
            val sel = d.selection
            assertNotNull("the selection", sel)
            assertEquals(android.graphics.Rect(8, 8, 40, 32), sel!!.bounds)
            assertTrue("300 dpi", d.gimp.props.any { it.type == 19 && java.nio.ByteBuffer.wrap(it.data).float == 300f })
            assertTrue("the comment", d.gimp.props.any { it.type == 21 && String(it.data, Charsets.ISO_8859_1).contains("Made by GIMP") })
            val tagged = d.named("Tagged").xcf!!
            assertTrue("colour tag and lock", tagged.props.any { it.type == 34 } && tagged.props.any { it.type == 28 })
            assertEquals("kept", String(tagged.parasites.single { it.name == "gako-test-data" }.data))
            assertTrue("GIMP's text", d.named("Hi").xcf!!.parasites.any { it.name == "gimp-text-layer" })
            // …which GAKO's text tool can edit.
            assertEquals("Hi", d.named("Hi").text?.text)
            assertEquals(12f, d.named("Hi").text!!.size, 0f)
            assertEquals(android.graphics.Color.BLACK, d.named("Hi").text!!.color)
            assertEquals("Outline", d.paths.single().name)
            assertTrue(d.paths.single().strokes.single().closed)
        }
        check(original)
        // Showing it composites every layer (which marks Android bitmaps changed): not an edit.
        io.github.capstasher.gako.doc.Compositor.flatten(original)
        val again = XcfReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { XcfWriter.write(original, it) }.toByteArray()))
        check(again)
        // Paint on GIMP's text layer: GIMP must be told it was modified (flag 2), so it keeps the pixels.
        fun flags(d: DocState) = d.named("Hi").xcf!!.props.firstOrNull { it.type == 26 }?.let { java.nio.ByteBuffer.wrap(it.data).int } ?: 0
        assertEquals(0, flags(again) and 2)
        again.named("Hi").bitmap.setPixel(0, 0, android.graphics.Color.RED)
        val bytes = ByteArrayOutputStream().also { XcfWriter.write(again, it) }.toByteArray()
        assertEquals(2, flags(XcfReader.read(ByteArrayInputStream(bytes))) and 2)
        // For checking in GIMP itself.
        java.io.File(ctx.targetContext.cacheDir, "fidelity-gako.xcf").writeBytes(bytes)
        // Its text rewritten in GAKO: GIMP's old text settings go (else GIMP would bring back "Hi").
        val hi = again.named("Hi")
        val retexted = again.replace(hi.copy(text = hi.text!!.copy(text = "Hello")))
        val back = XcfReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { XcfWriter.write(retexted, it) }.toByteArray()))
        assertEquals("Hello", back.named("Hi").text?.text)
        assertTrue(back.named("Hi").xcf?.parasites.orEmpty().none { it.name == "gimp-text-layer" })
    }

    @Test fun quickMaskIsNeverSaved() {
        val doc = document()
        val qm = Layer(Layer.newId(), "Quick Mask", io.github.capstasher.gako.doc.Bitmaps.blank(64, 48, android.graphics.Color.WHITE),
            lockAlpha = true, quickMask = true)
        val on = doc.copy(layers = doc.layers + qm, activeId = qm.id)
        val xcf = XcfReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { XcfWriter.write(on, it) }.toByteArray()))
        val ora = OraFile.read(ByteArrayInputStream(ByteArrayOutputStream().also { OraFile.write(on, it) }.toByteArray()))
        val psd = PsdReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { PsdWriter.write(on, it) }.toByteArray()))
        for (back in listOf(xcf, ora, psd)) assertEquals(doc.layers.map { it.name }, back.layers.map { it.name })
        // XCF keeps what the Quick Mask held as the selection (here all of it), as GIMP keeps its Qmask.
        assertEquals(android.graphics.Rect(0, 0, 64, 48), xcf.selection?.bounds)
        assertEquals(0, maxDiff(io.github.capstasher.gako.doc.Compositor.flatten(doc), io.github.capstasher.gako.doc.Compositor.flatten(on)))
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
