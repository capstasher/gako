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

    private val modeNames = mapOf(
        LayerMode.Normal to "svg:src-over", LayerMode.Multiply to "svg:multiply", LayerMode.Screen to "svg:screen",
        LayerMode.Overlay to "svg:overlay", LayerMode.Darken to "svg:darken", LayerMode.Lighten to "svg:lighten",
        LayerMode.Dodge to "svg:color-dodge", LayerMode.Burn to "svg:color-burn", LayerMode.HardLight to "svg:hard-light",
        LayerMode.SoftLight to "svg:soft-light", LayerMode.Difference to "svg:difference", LayerMode.Exclusion to "svg:exclusion",
        LayerMode.Hue to "svg:hue", LayerMode.Saturation to "svg:saturation", LayerMode.Color to "svg:color",
        LayerMode.Luminosity to "svg:luminosity", LayerMode.Addition to "svg:plus",
    )

    fun write(doc: DocState, out: OutputStream) {
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
        doc.layers.asReversed().forEachIndexed { i, l ->
            xml.append("    <layer name=\"${esc(l.name)}\" src=\"data/layer$i.png\" x=\"${l.x}\" y=\"${l.y}\"")
            xml.append(" opacity=\"${String.format(Locale.US, "%.3f", l.opacity)}\"")
            xml.append(" visibility=\"${if (l.visible) "visible" else "hidden"}\"")
            xml.append(" composite-op=\"${modeNames[l.mode]}\"")
            if (l.id == doc.activeId) xml.append(" selected=\"true\"")
            if (l.lockAlpha) xml.append(" alpha-preserve=\"true\"")
            l.text?.let { t ->
                xml.append(" gako-text=\"${esc(t.text)}\" gako-size=\"${t.size}\" gako-color=\"${t.color}\"")
                xml.append(" gako-font=\"${t.font.name}\" gako-bold=\"${t.bold}\" gako-italic=\"${t.italic}\" gako-align=\"${t.align.name}\"")
                xml.append(" gako-outline=\"${t.outline}\" gako-outline-color=\"${t.outlineColor}\"")
            }
            xml.append("/>\n")
        }
        xml.append("  </stack>\n</image>\n")
        zip.putNextEntry(ZipEntry("stack.xml"))
        zip.write(xml.toString().toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()

        zip.setLevel(1)   // PNGs are already compressed
        doc.layers.asReversed().forEachIndexed { i, l ->
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
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "stack") {
                if (offsets.size > 1) offsets.removeLast()
                continue
            }
            if (parser.eventType != XmlPullParser.START_TAG) continue
            fun attr(n: String) = parser.getAttributeValue(null, n)
            when (parser.name) {
                "image" -> { w = attr("w")?.toIntOrNull() ?: 0; h = attr("h")?.toIntOrNull() ?: 0 }
                "stack" -> {
                    val (ox, oy) = offsets.last()
                    offsets.addLast((ox + (attr("x")?.toIntOrNull() ?: 0)) to (oy + (attr("y")?.toIntOrNull() ?: 0)))
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
                        )
                    }
                    entries += Entry(
                        name = attr("name") ?: "Layer",
                        src = attr("src") ?: continue,
                        x = ox + (attr("x")?.toIntOrNull() ?: 0),
                        y = oy + (attr("y")?.toIntOrNull() ?: 0),
                        opacity = attr("opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                        visible = attr("visibility") != "hidden",
                        mode = modeNames.entries.firstOrNull { it.value == attr("composite-op") }?.key ?: LayerMode.Normal,
                        selected = attr("selected") == "true",
                        lockAlpha = attr("alpha-preserve") == "true",
                        text = text,
                    )
                }
            }
        }
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val layers = entries.asReversed().mapNotNull { e ->
            val png = files[e.src] ?: return@mapNotNull null
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: return@mapNotNull null
            Layer(Layer.newId(), e.name, Bitmaps.mutable(bmp), e.x, e.y, e.visible, e.opacity, e.mode, e.lockAlpha, e.text) to e.selected
        }
        if (layers.isEmpty()) error("The OpenRaster file has no layers GAKO can read")
        if (w <= 0 || h <= 0) {
            w = layers.maxOf { it.first.x + it.first.width }
            h = layers.maxOf { it.first.y + it.first.height }
        }
        val active = layers.lastOrNull { it.second }?.first ?: layers.last().first
        return DocState(w, h, layers.map { it.first }, active.id)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("\n", "&#10;")
}
