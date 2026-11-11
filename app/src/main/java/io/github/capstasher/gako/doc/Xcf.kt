package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * GIMP's own XCF format. GAKO writes version 10 (GIMP 2.10 and later read it): 8-bit sRGB RGBA
 * layers in zlib-compressed 64×64 tiles, with offsets, opacity, visibility, lock-alpha and mode.
 * The reader takes any XCF from GIMP 1.x to 3.x: RGB/grey/indexed, every precision, RLE or zlib,
 * layer masks and groups (flattened into their children).
 */
private object XcfProps {
    const val END = 0
    const val COLORMAP = 1
    const val ACTIVE_LAYER = 2
    const val OPACITY = 6
    const val MODE = 7
    const val VISIBLE = 8
    const val LOCK_ALPHA = 10
    const val APPLY_MASK = 11
    const val OFFSETS = 15
    const val COMPRESSION = 17
    const val RESOLUTION = 19
    const val PARASITES = 21
    const val VECTORS = 25
    const val GROUP_ITEM = 29
    const val FLOAT_OPACITY = 33
    const val TILE = 64

    /** GIMP layer-mode numbers. GAKO writes the perceptual ("legacy") ones, which match its compositing. */
    val write = mapOf(
        LayerMode.Normal to 0, LayerMode.Multiply to 3, LayerMode.Screen to 4, LayerMode.Overlay to 23,
        LayerMode.Difference to 6, LayerMode.Addition to 7, LayerMode.Darken to 9, LayerMode.Lighten to 10,
        LayerMode.Hue to 11, LayerMode.Saturation to 12, LayerMode.Color to 13, LayerMode.Luminosity to 14,
        LayerMode.Dodge to 16, LayerMode.Burn to 17, LayerMode.HardLight to 18, LayerMode.SoftLight to 19,
        LayerMode.Exclusion to 52,
    )

    fun read(mode: Int): LayerMode = when (mode) {
        3, 30 -> LayerMode.Multiply
        4, 31 -> LayerMode.Screen
        5, 23 -> LayerMode.Overlay
        6, 32 -> LayerMode.Difference
        7, 33 -> LayerMode.Addition
        9, 35, 54 -> LayerMode.Darken
        10, 36, 55 -> LayerMode.Lighten
        11, 37, 24 -> LayerMode.Hue
        12, 38, 25 -> LayerMode.Saturation
        13, 39, 26 -> LayerMode.Color
        14, 40, 27, 56 -> LayerMode.Luminosity
        16, 42 -> LayerMode.Dodge
        17, 43 -> LayerMode.Burn
        18, 44 -> LayerMode.HardLight
        19, 45 -> LayerMode.SoftLight
        52 -> LayerMode.Exclusion
        else -> LayerMode.Normal
    }
}

object XcfWriter {

    private class Buf {
        var data = ByteArray(1 shl 16)
        var size = 0
        private fun ensure(n: Int) {
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
        }
        fun u8(v: Int) { ensure(1); data[size++] = v.toByte() }
        fun u32(v: Int) { ensure(4); setU32(size, v); size += 4 }
        fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
        fun bytes(b: ByteArray, n: Int = b.size) { ensure(n); System.arraycopy(b, 0, data, size, n); size += n }
        fun setU32(at: Int, v: Int) {
            data[at] = (v ushr 24).toByte(); data[at + 1] = (v ushr 16).toByte()
            data[at + 2] = (v ushr 8).toByte(); data[at + 3] = v.toByte()
        }
        fun string(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u32(b.size + 1); bytes(b); u8(0)
        }
        fun prop(type: Int, vararg ints: Int) {
            u32(type); u32(ints.size * 4); ints.forEach { u32(it) }
        }
    }

    /** Text layers keep their text in a parasite, which GIMP preserves but ignores. */
    const val TEXT_PARASITE = "gako-text"

    fun textToString(t: TextInfo): String = org.json.JSONObject().apply {
        put("text", t.text); put("size", t.size.toDouble()); put("color", t.color); put("font", t.font.name)
        put("bold", t.bold); put("italic", t.italic); put("align", t.align.name)
        put("outline", t.outline.toDouble()); put("outlineColor", t.outlineColor)
    }.toString()

    fun textFromString(s: String): TextInfo? = runCatching {
        val o = org.json.JSONObject(s)
        TextInfo(o.getString("text"), o.getDouble("size").toFloat(), o.getInt("color"),
            TextFont.valueOf(o.getString("font")), o.getBoolean("bold"), o.getBoolean("italic"), TextAlign.valueOf(o.getString("align")),
            o.optDouble("outline", 0.0).toFloat(), o.optInt("outlineColor", -0x1000000))
    }.getOrNull()

    /** PROP_VECTORS (version 1): each path one Bézier stroke of handle-anchor-handle triplets. */
    private fun writePaths(b: Buf, paths: List<VectorPath>) {
        val p = Buf()
        p.u32(1)                       // version
        p.u32(0)                       // active path
        p.u32(paths.size)
        for (v in paths) {
            p.string(v.name)
            p.u32(0); p.u32(1); p.u32(0)   // tattoo, visible, linked
            p.u32(0)                   // parasites
            p.u32(1)                   // strokes
            p.u32(1)                   // Bézier stroke
            p.u32(if (v.closed) 1 else 0)
            p.u32(2)                   // floats per point: x, y
            p.u32(v.anchors * 3)
            for (i in 0 until v.anchors) {
                val o = i * 6
                p.u32(1); p.f32(v.points[o]); p.f32(v.points[o + 1])
                p.u32(0); p.f32(v.points[o + 2]); p.f32(v.points[o + 3])
                p.u32(1); p.f32(v.points[o + 4]); p.f32(v.points[o + 5])
            }
        }
        b.u32(XcfProps.VECTORS); b.u32(p.size); b.bytes(p.data, p.size)
    }

    /** Hierarchy → one level → zlib tiles. [bpp] 4 writes RGBA, 1 writes grey (the red channel). */
    private fun writeHierarchy(b: Buf, bmp: Bitmap, bpp: Int, deflater: Deflater, tileBuf: ByteArray, compressed: ByteArray) {
        val w = bmp.width
        val h = bmp.height
        b.u32(w); b.u32(h); b.u32(bpp)
        val levelSlot = b.size; b.u32(0)
        b.u32(0)
        b.setU32(levelSlot, b.size)
        b.u32(w); b.u32(h)
        val tilesX = (w + XcfProps.TILE - 1) / XcfProps.TILE
        val tilesY = (h + XcfProps.TILE - 1) / XcfProps.TILE
        val tileSlots = IntArray(tilesX * tilesY) { b.size.also { b.u32(0) } }
        b.u32(0)
        val row = IntArray(w * XcfProps.TILE)
        for (ty in 0 until tilesY) {
            val y0 = ty * XcfProps.TILE
            val th = minOf(XcfProps.TILE, h - y0)
            bmp.getPixels(row, 0, w, 0, y0, w, th)
            for (tx in 0 until tilesX) {
                val x0 = tx * XcfProps.TILE
                val tw = minOf(XcfProps.TILE, w - x0)
                var k = 0
                for (y in 0 until th) for (x in 0 until tw) {
                    val p = row[y * w + x0 + x]
                    if (bpp == 1) {
                        tileBuf[k++] = (p shr 16).toByte()
                    } else {
                        tileBuf[k++] = (p shr 16).toByte(); tileBuf[k++] = (p shr 8).toByte()
                        tileBuf[k++] = p.toByte(); tileBuf[k++] = (p ushr 24).toByte()
                    }
                }
                deflater.reset()
                deflater.setInput(tileBuf, 0, k)
                deflater.finish()
                var n = 0
                while (!deflater.finished()) n += deflater.deflate(compressed, n, compressed.size - n)
                b.setU32(tileSlots[ty * tilesX + tx], b.size)
                b.bytes(compressed, n)
            }
        }
    }

    fun write(doc: DocState, out: OutputStream, level: Int = 6) {
        val b = Buf()
        b.bytes("gimp xcf v010".toByteArray(Charsets.US_ASCII)); b.u8(0)
        b.u32(doc.width); b.u32(doc.height); b.u32(0)     // RGB
        b.u32(150)                                         // 8-bit non-linear integer
        b.u32(XcfProps.COMPRESSION); b.u32(1); b.u8(2)     // zlib
        b.u32(XcfProps.RESOLUTION); b.u32(8); b.f32(72f); b.f32(72f)
        if (doc.paths.isNotEmpty()) writePaths(b, doc.paths)
        b.u32(XcfProps.END); b.u32(0)

        val layers = doc.layers.asReversed()               // XCF lists the top layer first
        val slots = IntArray(layers.size) { b.size.also { b.u32(0) } }
        b.u32(0)                                           // end of layers
        b.u32(0)                                           // no channels

        val deflater = Deflater(level)
        val tileBuf = ByteArray(XcfProps.TILE * XcfProps.TILE * 4)
        val compressed = ByteArray(tileBuf.size * 2 + 64)
        layers.forEachIndexed { i, l ->
            b.setU32(slots[i], b.size)
            b.u32(l.width); b.u32(l.height); b.u32(1)      // RGBA
            b.string(l.name)
            if (l.id == doc.activeId) b.prop(XcfProps.ACTIVE_LAYER)
            b.prop(XcfProps.OPACITY, (l.opacity * 255).roundToInt().coerceIn(0, 255))
            b.u32(XcfProps.FLOAT_OPACITY); b.u32(4); b.f32(l.opacity)
            b.prop(XcfProps.VISIBLE, if (l.visible) 1 else 0)
            b.prop(XcfProps.LOCK_ALPHA, if (l.lockAlpha) 1 else 0)
            b.prop(XcfProps.OFFSETS, l.x, l.y)
            b.prop(XcfProps.MODE, XcfProps.write[l.mode] ?: 0)
            if (l.mask != null) b.prop(XcfProps.APPLY_MASK, if (l.maskEnabled) 1 else 0)
            l.text?.let { t ->
                // One parasite: name, flags (1 = persistent), size, data.
                val name = TEXT_PARASITE.toByteArray(Charsets.UTF_8)
                val data = textToString(t).toByteArray(Charsets.UTF_8)
                b.u32(XcfProps.PARASITES); b.u32(4 + name.size + 1 + 4 + 4 + data.size)
                b.string(TEXT_PARASITE); b.u32(1); b.u32(data.size); b.bytes(data)
            }
            b.u32(XcfProps.END); b.u32(0)
            val hierarchySlot = b.size; b.u32(0)
            val maskSlot = b.size; b.u32(0)

            b.setU32(hierarchySlot, b.size)
            writeHierarchy(b, l.bitmap, 4, deflater, tileBuf, compressed)

            l.mask?.let { m ->
                // A layer mask is a channel: size, name, properties, then 1-byte grey tiles.
                b.setU32(maskSlot, b.size)
                b.u32(m.width); b.u32(m.height)
                b.string("${l.name} mask")
                b.prop(XcfProps.OPACITY, 255)
                b.prop(XcfProps.VISIBLE, 0)
                b.u32(XcfProps.END); b.u32(0)
                val h = b.size; b.u32(0)
                b.setU32(h, b.size)
                writeHierarchy(b, m, 1, deflater, tileBuf, compressed)
            }
        }
        deflater.end()
        out.write(b.data, 0, b.size)
        out.flush()
    }
}

object XcfReader {

    fun read(input: InputStream, scratch: File? = null): DocState {
        val buffered = input.buffered(1 shl 16)
        buffered.mark(4)
        val magic = ByteArray(2).also { buffered.read(it) }
        buffered.reset()
        val stream = if (magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) GZIPInputStream(buffered, 1 shl 16) else buffered
        val tmp = File.createTempFile("open", ".xcf", scratch)
        try {
            tmp.outputStream().use { stream.copyTo(it, 1 shl 16) }
            RandomAccessFile(tmp, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.BIG_ENDIAN)
                return Parser(map).parse()
            }
        } finally {
            tmp.delete()
        }
    }

    private class LayerInfo(
        val name: String, val w: Int, val h: Int, val type: Int, var x: Int = 0, var y: Int = 0,
        var opacity: Float = 1f, var visible: Boolean = true, var mode: LayerMode = LayerMode.Normal,
        var lockAlpha: Boolean = false, var active: Boolean = false, var group: Boolean = false,
        var applyMask: Boolean = true,
        var text: TextInfo? = null,
    )

    private class Parser(val buf: ByteBuffer) {
        var version = 0
        var precision = 150
        var compression = 0
        var colormap: IntArray? = null
        val wide get() = version >= 11

        fun ptr(): Long = if (wide) buf.long else buf.int.toLong() and 0xFFFFFFFFL
        fun u32() = buf.int
        fun string(): String {
            val n = u32()
            if (n <= 0) return ""
            val b = ByteArray(n)
            buf.get(b)
            return String(b, 0, (n - 1).coerceAtLeast(0), Charsets.UTF_8)
        }

        var paths: List<VectorPath> = emptyList()

        /** PROP_VECTORS: keeps each path's first Bézier stroke. */
        fun readPaths(): List<VectorPath> {
            if (u32() != 1) return emptyList()
            u32()                                  // active
            val n = u32()
            val out = ArrayList<VectorPath>()
            repeat(n) {
                val name = string()
                u32(); u32(); u32()                // tattoo, visible, linked
                val parasites = u32()
                val strokes = u32()
                repeat(parasites) { string(); u32(); val size = u32(); buf.position(buf.position() + size) }
                var first: VectorPath? = null
                repeat(strokes) {
                    val type = u32()
                    val closed = u32() != 0
                    val nf = u32()
                    val np = u32()
                    val pts = ArrayList<Float>()
                    repeat(np) {
                        u32()                      // anchor or handle; the order says which
                        val f = FloatArray(nf) { buf.float }
                        pts += f[0]; pts += f[1]
                    }
                    if (first == null && type == 1 && np >= 3 && np % 3 == 0) first = VectorPath(name, pts.toFloatArray(), closed)
                }
                first?.let { out += it }
            }
            return out
        }

        fun parse(): DocState {
            val head = ByteArray(14).also { buf.get(it) }
            val sig = String(head, 0, 9, Charsets.US_ASCII)
            if (sig != "gimp xcf ") error("Not a GIMP XCF file")
            val v = String(head, 9, 4, Charsets.US_ASCII)
            version = if (v == "file") 0 else v.removePrefix("v").toIntOrNull() ?: error("Unknown XCF version $v")
            val w = u32()
            val h = u32()
            val baseType = u32()
            if (version >= 4) precision = u32()
            if (version == 4) precision = when (precision) { 0 -> 150; 1 -> 250; 2 -> 300; 3 -> 500; else -> 600 }
            else if (version in 5..6) precision = when (precision) { 400 -> 500; 450 -> 550; 500 -> 600; 550 -> 650; else -> precision }
            else if (version < 4) precision = 150

            // Image properties.
            while (true) {
                val type = u32()
                val len = u32()
                val start = buf.position()
                when (type) {
                    XcfProps.END -> break
                    XcfProps.COMPRESSION -> compression = buf.get().toInt()
                    XcfProps.VECTORS -> paths = runCatching { readPaths() }.getOrDefault(emptyList())
                    XcfProps.COLORMAP -> {
                        val n = u32()
                        colormap = IntArray(n) {
                            val r = buf.get().toInt() and 0xFF; val g = buf.get().toInt() and 0xFF; val b = buf.get().toInt() and 0xFF
                            (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                buf.position(start + len)
            }
            val layerPtrs = mutableListOf<Long>()
            while (true) { val p = ptr(); if (p == 0L) break; layerPtrs += p }

            val layers = mutableListOf<Pair<Layer, Boolean>>()
            for (p in layerPtrs.asReversed()) {
                val l = readLayer(p, baseType) ?: continue
                layers += l
            }
            if (layers.isEmpty()) error("This XCF file has no layers GAKO can show")
            val active = layers.lastOrNull { it.second }?.first ?: layers.last().first
            return DocState(w, h, layers.map { it.first }, active.id, paths = paths)
        }

        fun readLayer(at: Long, baseType: Int): Pair<Layer, Boolean>? {
            buf.position(at.toInt())
            val w = u32(); val h = u32(); val type = u32()
            val info = LayerInfo(string(), w, h, type)
            var opacityFloat: Float? = null
            while (true) {
                val t = u32()
                val len = u32()
                val start = buf.position()
                when (t) {
                    XcfProps.END -> break
                    XcfProps.ACTIVE_LAYER -> info.active = true
                    XcfProps.OPACITY -> info.opacity = (u32() / 255f).coerceIn(0f, 1f)
                    XcfProps.FLOAT_OPACITY -> opacityFloat = buf.float
                    XcfProps.VISIBLE -> info.visible = u32() != 0
                    XcfProps.MODE -> info.mode = XcfProps.read(u32())
                    XcfProps.LOCK_ALPHA -> info.lockAlpha = u32() != 0
                    XcfProps.OFFSETS -> { info.x = u32(); info.y = u32() }
                    XcfProps.GROUP_ITEM -> info.group = true
                    XcfProps.PARASITES -> while (buf.position() < start + len) {
                        val name = string()
                        u32()
                        val size = u32()
                        val data = ByteArray(size).also { buf.get(it) }
                        if (name == XcfWriter.TEXT_PARASITE) info.text = XcfWriter.textFromString(String(data, Charsets.UTF_8))
                    }
                    XcfProps.APPLY_MASK -> info.applyMask = u32() != 0
                }
                buf.position(start + len)
            }
            opacityFloat?.let { info.opacity = it.coerceIn(0f, 1f) }
            val hierarchy = ptr()
            val mask = ptr()
            if (info.group || w <= 0 || h <= 0) return null
            val bmp = readHierarchy(hierarchy, type, baseType) ?: return null
            val maskBitmap = if (mask != 0L) readMask(mask) else null
            return Layer(Layer.newId(), info.name.ifEmpty { "Layer" }, bmp, info.x, info.y, info.visible,
                info.opacity, info.mode, info.lockAlpha, info.text,
                mask = maskBitmap?.takeIf { it.width == bmp.width && it.height == bmp.height },
                maskEnabled = info.applyMask) to info.active
        }

        /** A layer mask channel, as an opaque grey bitmap. */
        fun readMask(at: Long): Bitmap? {
            buf.position(at.toInt())
            u32(); u32()
            string()
            while (true) {
                val t = u32(); val len = u32()
                if (t == XcfProps.END) break
                buf.position(buf.position() + len)
            }
            return readHierarchy(ptr(), -1, 1)
        }

        /** Bytes per channel and whether values are linear light, from the precision. */
        // Precision = component type (hundreds: 1 u8, 2 u16, 3 u32, 5 half, 6 float, 7 double) + TRC
        // (00 linear, 50 non-linear, 75 perceptual in GIMP 3).
        fun componentSize() = when (precision / 100) { 1 -> 1; 2, 5 -> 2; 7 -> 8; else -> 4 }
        fun isFloat() = precision / 100 in 5..7
        fun isLinear() = precision % 100 == 0

        fun readHierarchy(at: Long, layerType: Int, baseType: Int): Bitmap? {
            buf.position(at.toInt())
            val w = u32(); val h = u32(); val bpp = u32()
            val level = ptr()
            buf.position(level.toInt())
            val lw = u32(); val lh = u32()
            if (lw != w || lh != h) return null
            val tilesX = (w + XcfProps.TILE - 1) / XcfProps.TILE
            val tilesY = (h + XcfProps.TILE - 1) / XcfProps.TILE
            val offsets = LongArray(tilesX * tilesY) { ptr() }
            val cs = componentSize()
            val channels = bpp / cs
            // layerType -1 means a mask/channel: one grey component.
            val type = if (layerType < 0) 2 else layerType
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val tile = ByteArray(XcfProps.TILE * XcfProps.TILE * bpp)
            val px = IntArray(XcfProps.TILE * XcfProps.TILE)
            // Colour is converted to sRGB; mask values are opacities and stay as they are.
            val toSrgb = if (isLinear() && layerType >= 0) IntArray(4096) { linearToSrgb(it / 4095.0) } else null
            val inflater = Inflater()
            for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
                val i = ty * tilesX + tx
                val tw = minOf(XcfProps.TILE, w - tx * XcfProps.TILE)
                val th = minOf(XcfProps.TILE, h - ty * XcfProps.TILE)
                val n = tw * th * bpp
                val start = offsets[i].toInt()
                val end = if (i + 1 < offsets.size && offsets[i + 1] > 0) offsets[i + 1].toInt() else minOf(buf.limit(), start + n * 2 + 1024)
                when (compression) {
                    0 -> { buf.position(start); buf.get(tile, 0, n) }
                    1 -> rle(start, tile, tw * th, bpp)
                    2 -> {
                        val src = ByteArray((end - start).coerceAtLeast(0))
                        buf.position(start); buf.get(src)
                        inflater.reset(); inflater.setInput(src)
                        var got = 0
                        while (got < n && !inflater.finished()) {
                            val r = inflater.inflate(tile, got, n - got)
                            if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                            got += r
                        }
                    }
                    else -> error("Unsupported XCF compression $compression")
                }
                for (p in 0 until tw * th) {
                    fun comp(c: Int): Int {
                        val v = component(tile, p * bpp + c * cs, cs)
                        return if (toSrgb != null && !(type % 2 == 1 && c == channels - 1)) toSrgb[(v * 4095).roundToInt().coerceIn(0, 4095)]
                        else (v * 255).roundToInt().coerceIn(0, 255)
                    }
                    px[p] = when (type) {
                        0 -> (0xFF shl 24) or (comp(0) shl 16) or (comp(1) shl 8) or comp(2)
                        1 -> (comp(3) shl 24) or (comp(0) shl 16) or (comp(1) shl 8) or comp(2)
                        2 -> { val g = comp(0); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                        3 -> { val g = comp(0); (comp(1) shl 24) or (g shl 16) or (g shl 8) or g }
                        4, 5 -> {
                            val idx = tile[p * bpp].toInt() and 0xFF
                            val rgb = colormap?.getOrNull(idx) ?: 0
                            val a = if (type == 5) tile[p * bpp + 1].toInt() and 0xFF else 0xFF
                            (a shl 24) or rgb
                        }
                        else -> 0
                    }
                }
                out.setPixels(px, 0, tw, tx * XcfProps.TILE, ty * XcfProps.TILE, tw, th)
            }
            inflater.end()
            if (baseType < 0) Unit
            return out
        }

        /** One component as 0..1. XCF stores multi-byte components big-endian. */
        fun component(b: ByteArray, at: Int, size: Int): Double = when (size) {
            1 -> (b[at].toInt() and 0xFF) / 255.0
            2 -> {
                val v = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
                if (isFloat()) halfToFloat(v).toDouble() else v / 65535.0
            }
            4 -> {
                val v = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                    ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
                if (isFloat()) java.lang.Float.intBitsToFloat(v).toDouble() else (v.toLong() and 0xFFFFFFFFL) / 4294967295.0
            }
            else -> {
                var v = 0L
                for (k in 0 until 8) v = (v shl 8) or (b[at + k].toLong() and 0xFF)
                java.lang.Double.longBitsToDouble(v)
            }
        }.coerceIn(0.0, 1.0)

        /** XCF RLE: each byte of the pixel is its own run-length stream. */
        fun rle(start: Int, out: ByteArray, pixels: Int, bpp: Int) {
            buf.position(start)
            for (c in 0 until bpp) {
                var i = 0
                while (i < pixels) {
                    var n = buf.get().toInt() and 0xFF
                    if (n >= 128) {
                        val count = if (n == 128) buf.short.toInt() and 0xFFFF else 256 - n
                        repeat(count) { if (i < pixels) out[(i++) * bpp + c] = buf.get() else buf.get() }
                    } else {
                        val count = if (n == 127) buf.short.toInt() and 0xFFFF else n + 1
                        val v = buf.get()
                        repeat(count) { if (i < pixels) out[(i++) * bpp + c] = v }
                    }
                    n = 0
                }
            }
        }
    }

    private fun linearToSrgb(v: Double): Int {
        val s = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (s * 255).roundToInt().coerceIn(0, 255)
    }

    private fun halfToFloat(h: Int): Float {
        val s = (h shr 15) and 1
        val e = (h shr 10) and 0x1F
        val m = h and 0x3FF
        val v = when (e) {
            0 -> m / 1024f * 2f.pow(-14)
            31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1 + m / 1024f) * 2f.pow(e - 15)
        }
        return if (s == 1) -v else v
    }
}
