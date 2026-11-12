package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * OpenRaster (.ora): a zip of PNG layers plus stack.xml. GIMP, Krita and MyPaint all read it,
 * so it's GAKO's layered project format. Text layers keep their text in gako: attributes.
 */
object OraFile {

    /** OpenRaster's (SVG's) compositing operators; the first match is what reading picks. */
    private val modeNames = listOf(
        LayerMode.Normal to "svg:src-over", LayerMode.Multiply to "svg:multiply", LayerMode.Screen to "svg:screen",
        LayerMode.Overlay to "svg:overlay", LayerMode.Darken to "svg:darken", LayerMode.Lighten to "svg:lighten",
        LayerMode.Dodge to "svg:color-dodge", LayerMode.Burn to "svg:color-burn", LayerMode.HardLight to "svg:hard-light",
        LayerMode.SoftLight to "svg:soft-light", LayerMode.Difference to "svg:difference", LayerMode.Exclusion to "svg:exclusion",
        LayerMode.Hue to "svg:hue", LayerMode.Saturation to "svg:saturation", LayerMode.Color to "svg:color",
        LayerMode.Value to "svg:luminosity", LayerMode.Addition to "svg:plus",
        LayerMode.LchHue to "svg:hue", LayerMode.LchChroma to "svg:saturation", LayerMode.LchColor to "svg:color",
        LayerMode.LchLightness to "svg:luminosity", LayerMode.Luminance to "svg:luminosity",
        LayerMode.LumaDarken to "svg:darken", LayerMode.LumaLighten to "svg:lighten",
    )
    private fun opFor(mode: LayerMode) = modeNames.firstOrNull { it.first == mode }?.second ?: "svg:src-over"
    private fun modeFor(op: String?) = modeNames.firstOrNull { it.second == op }?.first ?: LayerMode.Normal

    fun write(all: DocState, out: OutputStream) {
        val doc = all.forFiles()
        val zip = ZipOutputStream(out.buffered(256 * 1024))
        val mime = "image/openraster".toByteArray(StandardCharsets.US_ASCII)
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = mime.size.toLong()
            compressedSize = mime.size.toLong()
            crc = CRC32().apply { update(mime) }.value
        })
        zip.write(mime)
        zip.closeEntry()

        val xml = StringBuilder()
        xml.append("<?xml version='1.0' encoding='UTF-8'?>\n")
        xml.append("<image version=\"0.0.5\" w=\"${doc.width}\" h=\"${doc.height}\" xres=\"72\" yres=\"72\">\n  <stack>\n")
        // Pixel layers are numbered top to bottom; groups become nested stacks.
        val pixelLayers = doc.layers.asReversed().filter { !it.group }
        val index = pixelLayers.withIndex().associate { (i, l) -> l.id to i }
        var writeLayer: (Layer, Int, String) -> Unit = { _, _, _ -> }
        fun emit(parent: Long?, indent: String) {
            for (l in doc.layers.asReversed()) {
                if (l.parent != parent) continue
                if (l.group) {
                    xml.append("$indent<stack name=\"${esc(l.name)}\"")
                    xml.append(" opacity=\"${String.format(Locale.US, "%.3f", l.opacity)}\"")
                    xml.append(" visibility=\"${if (l.visible) "visible" else "hidden"}\"")
                    // Pass-through groups aren't isolated (OpenRaster 0.0.5's isolation="auto").
                    xml.append(" isolation=\"${if (l.mode == LayerMode.PassThrough) "auto" else "isolate"}\"")
                    xml.append(" composite-op=\"${opFor(l.mode)}\"")
                    if (l.id == doc.activeId) xml.append(" selected=\"true\"")
                    if (!l.expanded) xml.append(" gako-collapsed=\"true\"")
                    xml.append(">\n")
                    emit(l.id, "$indent  ")
                    xml.append("$indent</stack>\n")
                    continue
                }
                writeLayer(l, index[l.id]!!, indent)
            }
        }
        fun writeLayerXml(l: Layer, i: Int, indent: String) {
            xml.append("$indent<layer name=\"${esc(l.name)}\" src=\"data/layer$i.png\" x=\"${l.x}\" y=\"${l.y}\"")
            xml.append(" opacity=\"${String.format(Locale.US, "%.3f", l.opacity)}\"")
            xml.append(" visibility=\"${if (l.visible) "visible" else "hidden"}\"")
            xml.append(" composite-op=\"${opFor(l.mode)}\"")
            if (l.id == doc.activeId) xml.append(" selected=\"true\"")
            if (l.lockAlpha) xml.append(" alpha-preserve=\"true\"")
            l.text?.let { t ->
                xml.append(" gako-text=\"${esc(t.text)}\" gako-size=\"${t.size}\" gako-color=\"${t.color}\"")
                xml.append(" gako-font=\"${t.font.name}\" gako-bold=\"${t.bold}\" gako-italic=\"${t.italic}\" gako-align=\"${t.align.name}\"")
                xml.append(" gako-outline=\"${t.outline}\" gako-outline-color=\"${t.outlineColor}\"")
                t.fontFile?.let { xml.append(" gako-font-file=\"${esc(it)}\"") }
            }
            xml.append("/>\n")
        }
        writeLayer = ::writeLayerXml
        emit(null, "    ")
        xml.append("  </stack>\n</image>\n")
        zip.putNextEntry(ZipEntry("stack.xml"))
        zip.write(xml.toString().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        zip.setLevel(1)   // PNGs are already compressed
        pixelLayers.forEachIndexed { i, l ->
            zip.putNextEntry(ZipEntry("data/layer$i.png"))
            // OpenRaster has no layer masks: bake them in.
            (if (l.activeMask != null) Compositor.applyMask(l) else l.bitmap).compress(Bitmap.CompressFormat.PNG, 100, zip)
            zip.closeEntry()
        }
        val merged = Compositor.flatten(doc)
        zip.putNextEntry(ZipEntry("mergedimage.png"))
        merged.compress(Bitmap.CompressFormat.PNG, 100, zip)
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("Thumbnails/thumbnail.png"))
        Compositor.thumbnail(merged, 256).compress(Bitmap.CompressFormat.PNG, 100, zip)
        zip.closeEntry()
        zip.finish()
        zip.flush()
    }

    private class Entry(
        val name: String, val src: String, val x: Int, val y: Int, val opacity: Float, val visible: Boolean,
        val mode: LayerMode, val selected: Boolean, val lockAlpha: Boolean, val text: TextInfo?,
        val parent: Long? = null, val groupId: Long? = null, val expanded: Boolean = true,
    )

    fun read(input: InputStream): DocState {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered(256 * 1024)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) {
                    val bytes = ByteArrayOutputStream().also { zip.copyTo(it) }.toByteArray()
                    files[e.name] = bytes
                }
            }
        }
        val stack = files["stack.xml"] ?: error("Not an OpenRaster file (no stack.xml)")
        val parser = Xml.newPullParser()
        parser.setInput(stack.inputStream(), "UTF-8")
        var w = 0
        var h = 0
        val entries = mutableListOf<Entry>()     // top to bottom
        val offsets = ArrayDeque<Pair<Int, Int>>()
        offsets.addLast(0 to 0)
        // Nested stacks are groups (the outermost one is the image itself).
        val groups = ArrayDeque<Long?>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "stack") {
                if (offsets.size > 1) offsets.removeLast()
                groups.removeLastOrNull()
                continue
            }
            if (parser.eventType != XmlPullParser.START_TAG) continue
            fun attr(n: String) = parser.getAttributeValue(null, n)
            when (parser.name) {
                "image" -> { w = attr("w")?.toIntOrNull() ?: 0; h = attr("h")?.toIntOrNull() ?: 0 }
                "stack" -> {
                    val (ox, oy) = offsets.last()
                    offsets.addLast((ox + (attr("x")?.toIntOrNull() ?: 0)) to (oy + (attr("y")?.toIntOrNull() ?: 0)))
                    if (groups.isEmpty()) groups.addLast(null)        // the image's own stack
                    else {
                        val id = Layer.newId()
                        // OpenRaster's default isolation is "auto" (pass-through) unless a blend needs a group of its own.
                        val op = attr("composite-op")
                        val passThrough = attr("isolation") != "isolate" && (op == null || op == "svg:src-over")
                        entries += Entry(
                            name = attr("name") ?: "Group", src = "", x = 0, y = 0,
                            opacity = attr("opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                            visible = attr("visibility") != "hidden",
                            mode = if (passThrough) LayerMode.PassThrough
                                   else modeFor(attr("composite-op")),
                            selected = attr("selected") == "true", lockAlpha = false, text = null,
                            parent = groups.last(), groupId = id, expanded = attr("gako-collapsed") != "true",
                        )
                        groups.addLast(id)
                    }
                }
                "layer" -> {
                    val (ox, oy) = offsets.last()
                    val text = attr("gako-text")?.let { t ->
                        TextInfo(
                            text = t,
                            size = attr("gako-size")?.toFloatOrNull() ?: 48f,
                            color = attr("gako-color")?.toIntOrNull() ?: -0x1000000,
                            font = runCatching { TextFont.valueOf(attr("gako-font")!!) }.getOrDefault(TextFont.Sans),
                            bold = attr("gako-bold") == "true",
                            italic = attr("gako-italic") == "true",
                            align = runCatching { TextAlign.valueOf(attr("gako-align")!!) }.getOrDefault(TextAlign.Left),
                            outline = attr("gako-outline")?.toFloatOrNull() ?: 0f,
                            outlineColor = attr("gako-outline-color")?.toIntOrNull() ?: -0x1000000,
                            fontFile = attr("gako-font-file"),
                        )
                    }
                    entries += Entry(
                        name = attr("name") ?: "Layer",
                        src = attr("src") ?: continue,
                        x = ox + (attr("x")?.toIntOrNull() ?: 0),
                        y = oy + (attr("y")?.toIntOrNull() ?: 0),
                        opacity = attr("opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                        visible = attr("visibility") != "hidden",
                        mode = modeFor(attr("composite-op")),
                        selected = attr("selected") == "true",
                        lockAlpha = attr("alpha-preserve") == "true",
                        text = text,
                        parent = groups.lastOrNull(),
                    )
                }
            }
        }
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        if (w.toLong() * h > 24_000_000L) error("That image is ${w}×$h; GAKO opens up to 24 megapixels")
        val layers = entries.asReversed().mapNotNull { e ->
            if (e.groupId != null) {
                return@mapNotNull Layer(e.groupId, e.name, Bitmaps.blank(1, 1), visible = e.visible, opacity = e.opacity,
                    mode = e.mode, group = true, parent = e.parent, expanded = e.expanded).gammaBlended() to e.selected
            }
            val png = files[e.src] ?: return@mapNotNull null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            if (bounds.outWidth.toLong() * bounds.outHeight > 24_000_000L) error("A layer in that file is too big for GAKO (24 megapixels)")
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: return@mapNotNull null
            Layer(Layer.newId(), e.name, Bitmaps.mutable(bmp), e.x, e.y, e.visible, e.opacity, e.mode, e.lockAlpha, e.text,
                parent = e.parent).gammaBlended() to e.selected
        }
        if (layers.none { !it.first.group }) error("The OpenRaster file has no layers GAKO can read")
        if (w <= 0 || h <= 0) {
            w = layers.filter { !it.first.group }.maxOf { it.first.x + it.first.width }
            h = layers.filter { !it.first.group }.maxOf { it.first.y + it.first.height }
        }
        val active = layers.lastOrNull { it.second }?.first ?: layers.last { !it.first.group }.first
        return DocState(w, h, Tree.normalize(layers.map { it.first }), active.id)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("\n", "&#10;")
}
