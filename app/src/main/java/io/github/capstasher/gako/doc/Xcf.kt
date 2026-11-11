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
internal object XcfProps {
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
    const val PATHS = 23
    const val VECTORS = 25
    const val GROUP_ITEM = 29
    const val ITEM_PATH = 30
    const val GROUP_ITEM_FLAGS = 31
    const val PASS_THROUGH = 61
    const val FLOAT_OPACITY = 33
    const val TILE = 64

    const val COMPOSITE_MODE = 35
    const val COMPOSITE_SPACE = 36
    const val BLEND_SPACE = 37
    const val SELECTION = 4
    const val GUIDES = 18
    const val OLD_SAMPLE_POINTS = 27
    const val TEXT_LAYER_FLAGS = 26
    const val SAMPLE_POINTS = 39
    const val FILTER_REGION = 44
    const val FILTER_ARGUMENT = 45
    const val FILTER_CLIP = 46

    /** Image properties GAKO keeps as they are (none holds a file offset). */
    val keptImageProps = setOf(19, 20, 21, 22, 24, 40)        // resolution, tattoo, parasites, unit, user unit, item set
    /**
     * Item (layer, group, channel) properties GAKO keeps as they are: linked, edit/show mask, tattoo,
     * text layer flags, the locks, colour tag, item set membership, vector/link layer, transform.
     */
    val keptItemProps = setOf(9, 12, 13, 20, 26, 28, 32, 34, 41, 42, 47, 48, 49)
    /** Channel properties GAKO keeps (colour, opacity, visibility, show masked…) on top of those. */
    val keptChannelProps = keptItemProps + setOf(3, 6, 8, 14, 16, 33, 38)

    /** The mode number to write (GIMP's own numbers: see [LayerMode]). */
    fun modeFor(l: Layer): Int = l.gimpMode

    fun read(mode: Int): LayerMode = LayerMode.ofGimp(mode).first
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
        /** A property kept from the file, as it was. */
        fun raw(p: XcfProp) { u32(p.type); u32(p.data.size); bytes(p.data) }
        /** PROP_PARASITES with [list] (nothing if it's empty). */
        fun parasites(list: List<XcfParasite>) {
            if (list.isEmpty()) return
            u32(XcfProps.PARASITES)
            val at = size; u32(0)
            for (p in list) { string(p.name); u32(p.flags); u32(p.data.size); bytes(p.data) }
            setU32(at, size - at - 4)
        }
    }

    /** Text layers keep their text in a parasite, which GIMP preserves but ignores. */
    const val TEXT_PARASITE = "gako-text"

    fun textToString(t: TextInfo): String = org.json.JSONObject().apply {
        put("text", t.text); put("size", t.size.toDouble()); put("color", t.color); put("font", t.font.name)
        put("bold", t.bold); put("italic", t.italic); put("align", t.align.name)
        put("outline", t.outline.toDouble()); put("outlineColor", t.outlineColor)
        t.fontFile?.let { put("fontFile", it) }
    }.toString()

    fun textFromString(s: String): TextInfo? = runCatching {
        val o = org.json.JSONObject(s)
        TextInfo(o.getString("text"), o.getDouble("size").toFloat(), o.getInt("color"),
            TextFont.valueOf(o.getString("font")), o.getBoolean("bold"), o.getBoolean("italic"), TextAlign.valueOf(o.getString("align")),
            o.optDouble("outline", 0.0).toFloat(), o.optInt("outlineColor", -0x1000000),
            if (o.has("fontFile")) o.getString("fontFile") else null)
    }.getOrNull()

    /** PROP_VECTORS (version 1): each path's Bézier strokes as handle-anchor-handle triplets. */
    private fun writePaths(b: Buf, paths: List<VectorPath>) {
        val p = Buf()
        p.u32(1)                       // version
        p.u32(0)                       // active path
        p.u32(paths.size)
        for (v in paths) {
            p.string(v.name)
            p.u32(0); p.u32(1); p.u32(0)   // tattoo, visible, linked
            p.u32(0)                   // parasites
            p.u32(v.strokes.size)
            for (st in v.strokes) {
                p.u32(1)               // Bézier stroke
                p.u32(if (st.closed) 1 else 0)
                p.u32(2)               // floats per point: x, y
                p.u32(st.anchors * 3)
                for (i in 0 until st.anchors) {
                    val o = i * 6
                    p.u32(1); p.f32(st.points[o]); p.f32(st.points[o + 1])
                    p.u32(0); p.f32(st.points[o + 2]); p.f32(st.points[o + 3])
                    p.u32(1); p.f32(st.points[o + 4]); p.f32(st.points[o + 5])
                }
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

    fun write(all: DocState, out: OutputStream, level: Int = 6) {
        val doc = all.forFiles()
        val b = Buf()
        b.bytes("gimp xcf v010".toByteArray(Charsets.US_ASCII)); b.u8(0)
        b.u32(doc.width); b.u32(doc.height); b.u32(0)     // RGB
        b.u32(150)                                         // 8-bit non-linear integer
        b.u32(XcfProps.COMPRESSION); b.u32(1); b.u8(2)     // zlib
        val kept = doc.gimp.props
        if (kept.none { it.type == XcfProps.RESOLUTION }) { b.u32(XcfProps.RESOLUTION); b.u32(8); b.f32(72f); b.f32(72f) }
        for (k in kept) b.raw(k)
        if (doc.gimp.guides.isNotEmpty()) {
            b.u32(XcfProps.GUIDES); b.u32(doc.gimp.guides.size * 5)
            for (g in doc.gimp.guides) { b.u32(g.position); b.u8(if (g.horizontal) 1 else 2) }
        }
        if (doc.gimp.samplePoints.isNotEmpty()) {
            // XCF 10 keeps sample points as x, y pairs.
            b.u32(XcfProps.OLD_SAMPLE_POINTS); b.u32(doc.gimp.samplePoints.size * 8)
            for (sp in doc.gimp.samplePoints) { b.u32(sp.x); b.u32(sp.y) }
        }
        if (doc.paths.isNotEmpty()) writePaths(b, doc.paths)
        b.u32(XcfProps.END); b.u32(0)

        val layers = doc.layers.asReversed()               // XCF lists the top layer first
        // Item paths: each item's index among its siblings, top first, from the top level down.
        val paths = HashMap<Long, List<Int>>()
        val siblingCount = HashMap<Long?, Int>()
        for (l in layers) {
            val k = siblingCount[l.parent] ?: 0
            siblingCount[l.parent] = k + 1
            paths[l.id] = (l.parent?.let { paths[it] } ?: emptyList()) + k
        }
        val slots = IntArray(layers.size) { b.size.also { b.u32(0) } }
        b.u32(0)                                           // end of layers
        // Saved channels, then the selection as GIMP saves it (a channel marked as the selection).
        // In Quick Mask the selection is being painted: that's what's saved (so a recovery keeps it).
        val selectionGrey = all.quickMask?.let { qm ->
            Bitmaps.blank(doc.width, doc.height, android.graphics.Color.BLACK).also {
                android.graphics.Canvas(it).drawBitmap(qm.bitmap, qm.x.toFloat(), qm.y.toFloat(), null)
            }
        } ?: doc.selection?.let { sel ->
            val bytes = Selections.bytesOf(sel.mask)
            val px = IntArray(bytes.size) { val v = bytes[it].toInt() and 255; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            Bitmaps.blank(doc.width, doc.height).also { it.setPixels(px, 0, doc.width, 0, 0, doc.width, doc.height) }
        }
        val selection = selectionGrey?.let {
            Channel("Selection Mask", it, listOf(XcfProp(XcfProps.SELECTION, ByteArray(0)), XcfProp(XcfProps.VISIBLE, byteArrayOf(0, 0, 0, 0))))
        }
        val channels = doc.gimp.channels.filter { it.bitmap.width == doc.width && it.bitmap.height == doc.height } + listOfNotNull(selection)
        val channelSlots = IntArray(channels.size) { b.size.also { b.u32(0) } }
        b.u32(0)                                           // end of channels

        val deflater = Deflater(level)
        val tileBuf = ByteArray(XcfProps.TILE * XcfProps.TILE * 4)
        val compressed = ByteArray(tileBuf.size * 2 + 64)
        layers.forEachIndexed { i, l ->
            b.setU32(slots[i], b.size)
            b.u32(l.width); b.u32(l.height); b.u32(1)      // RGBA
            b.string(l.name)
            // GIMP turns the layer into a group when it reads this, dropping what it read before: first.
            if (l.group) b.prop(XcfProps.GROUP_ITEM)
            if (l.id == doc.activeId) b.prop(XcfProps.ACTIVE_LAYER)
            b.prop(XcfProps.OPACITY, (l.opacity * 255).roundToInt().coerceIn(0, 255))
            b.u32(XcfProps.FLOAT_OPACITY); b.u32(4); b.f32(l.opacity)
            b.prop(XcfProps.VISIBLE, if (l.visible) 1 else 0)
            b.prop(XcfProps.LOCK_ALPHA, if (l.lockAlpha) 1 else 0)
            b.prop(XcfProps.OFFSETS, l.x, l.y)
            b.prop(XcfProps.MODE, XcfProps.modeFor(l))
            // GIMP's per-layer blend settings, when changed from the mode's own (GIMP reads <= 0 as "auto").
            if (l.blendSpace > 0) b.prop(XcfProps.BLEND_SPACE, l.blendSpace)
            if (l.compositeSpace > 0) b.prop(XcfProps.COMPOSITE_SPACE, l.compositeSpace)
            if (l.compositeMode > 0) b.prop(XcfProps.COMPOSITE_MODE, l.compositeMode)
            if (l.group) b.prop(XcfProps.GROUP_ITEM_FLAGS, if (l.expanded) 1 else 0)
            if (l.parent != null) paths[l.id]?.let { p -> b.prop(XcfProps.ITEM_PATH, *p.toIntArray()) }
            if (l.mask != null) b.prop(XcfProps.APPLY_MASK, if (l.maskEnabled) 1 else 0)
            val x = l.xcf
            // A GIMP text layer whose pixels GAKO changed: tell GIMP (it then keeps these pixels).
            val changed = x != null && !l.group && x.pixelsChanged(l.bitmap)
            // Its text rewritten in GAKO: GIMP's old text settings would bring the old words back, so
            // it goes to GIMP as an ordinary layer (GAKO's own text info keeps it editable here).
            val retexted = x?.gimpText != null && l.text != null && l.text != x.gimpText
            val gimpText = !retexted && x?.parasites?.any { it.name == GimpText.PARASITE } == true
            x?.props?.forEach { k ->
                when {
                    k.type == XcfProps.TEXT_LAYER_FLAGS && retexted -> Unit
                    k.type == XcfProps.TEXT_LAYER_FLAGS && changed && k.data.size == 4 ->
                        b.prop(XcfProps.TEXT_LAYER_FLAGS, java.nio.ByteBuffer.wrap(k.data).int or 2)
                    else -> b.raw(k)
                }
            }
            if (changed && gimpText && x!!.props.none { it.type == XcfProps.TEXT_LAYER_FLAGS }) b.prop(XcfProps.TEXT_LAYER_FLAGS, 2)
            val parasites = x?.parasites.orEmpty().filter { !(retexted && it.name == GimpText.PARASITE) }.toMutableList()
            l.text?.let { t -> parasites += XcfParasite(TEXT_PARASITE, 1, textToString(t).toByteArray(Charsets.UTF_8)) }
            b.parasites(parasites)
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
        channels.forEachIndexed { i, ch ->
            b.setU32(channelSlots[i], b.size)
            b.u32(ch.bitmap.width); b.u32(ch.bitmap.height)
            b.string(ch.name)
            for (k in ch.props) b.raw(k)
            if (ch.props.none { it.type == XcfProps.OPACITY }) b.prop(XcfProps.OPACITY, 255)
            b.u32(XcfProps.END); b.u32(0)
            val h = b.size; b.u32(0)
            b.setU32(h, b.size)
            writeHierarchy(b, ch.bitmap, 1, deflater, tileBuf, compressed)
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
        var opacity: Float = 1f, var visible: Boolean = true, var mode: LayerMode = LayerMode.Normal, var rawMode: Int? = null,
        var legacy: Boolean = true, var blendSpace: Int = 0, var compositeSpace: Int = 0, var compositeMode: Int = 0,
        var lockAlpha: Boolean = false, var active: Boolean = false, var group: Boolean = false,
        var applyMask: Boolean = true,
        var text: TextInfo? = null,
        var path: List<Int> = emptyList(),
        var expanded: Boolean = true,
        val kept: MutableList<XcfProp> = ArrayList(),
        val parasites: MutableList<XcfParasite> = ArrayList(),
        var effects: List<LayerEffect> = emptyList(),
    )

    private class Parser(val buf: ByteBuffer) {
        var version = 0
        var precision = 150
        var compression = 0
        var colormap: IntArray? = null
        val guides = ArrayList<Guide>()
        val samplePoints = ArrayList<SamplePoint>()
        val keptProps = ArrayList<XcfProp>()
        val wide get() = version >= 11

        fun ptr(): Long = if (wide) buf.long else buf.int.toLong() and 0xFFFFFFFFL
        fun u32() = buf.int

        // Checked seeks and counts: a damaged or hostile file must fail, never loop or run backwards.
        fun goTo(pos: Long) {
            if (pos < 0 || pos > buf.limit()) error("The file is damaged (an offset points outside it)")
            buf.position(pos.toInt())
        }
        fun count(n: Int, minBytesEach: Int): Int {
            if (n < 0 || n.toLong() * minBytesEach > buf.remaining()) error("The file is damaged (a count is too large)")
            return n
        }
        fun length(n: Int): Int {
            if (n < 0 || n > buf.remaining()) error("The file is damaged (a length is wrong)")
            return n
        }

        fun string(): String {
            val n = u32()
            if (n <= 0) return ""
            length(n)
            val b = ByteArray(n)
            buf.get(b)
            return String(b, 0, (n - 1).coerceAtLeast(0), Charsets.UTF_8)
        }

        var paths: List<VectorPath> = emptyList()

        /** The rest of a property as it is (from the current position, [len] bytes). */
        fun raw(type: Int, len: Int) = XcfProp(type, ByteArray(len).also { buf.get(it) })

        /** PROP_PARASITES: name, flags, size, data, until the end of the property. */
        fun parasites(end: Int): List<XcfParasite> {
            val out = ArrayList<XcfParasite>()
            while (buf.position() < end) {
                val name = string()
                val flags = u32()
                val size = length(u32())
                out += XcfParasite(name, flags, ByteArray(size).also { buf.get(it) })
            }
            return out
        }

        /** One Bézier stroke as PROP_VECTORS and GIMP 3's path items store it, or null if it isn't one. */
        fun stroke(): PathStroke? {
            val type = u32()
            val closed = u32() != 0
            val nf = u32()
            if (nf !in 2..6) error("The file is damaged (path point size)")
            val np = count(u32(), 4 + nf * 4)
            val pts = ArrayList<Float>()
            repeat(np) {
                u32()                      // anchor or handle; the order says which
                val f = FloatArray(nf) { buf.float }
                pts += f[0]; pts += f[1]
            }
            return if (type == 1 && np >= 3 && np % 3 == 0) PathStroke(pts.toFloatArray(), closed) else null
        }

        /** A GIMP 3 path item (XCF 18+): name, payload size, properties, version, strokes. */
        fun readPathItem(at: Long): VectorPath? {
            goTo(at)
            val name = string()
            length(u32())
            while (true) {
                val t = u32(); val len = length(u32())
                if (t == XcfProps.END) break
                goTo(buf.position().toLong() + len)
            }
            if (u32() != 1) return null
            val n = count(u32(), 16)
            val strokes = (0 until n).mapNotNull { stroke() }
            return if (strokes.isEmpty()) null else VectorPath(name, strokes)
        }

        /** PROP_VECTORS: every path with all its Bézier strokes. */
        fun readPaths(): List<VectorPath> {
            if (u32() != 1) return emptyList()
            u32()                                  // active
            val n = count(u32(), 24)
            val out = ArrayList<VectorPath>()
            repeat(n) {
                val name = string()
                u32(); u32(); u32()                // tattoo, visible, linked
                val parasites = count(u32(), 12)
                val strokes = count(u32(), 16)
                repeat(parasites) { string(); u32(); val size = length(u32()); goTo(buf.position() + size.toLong()) }
                val list = ArrayList<PathStroke>()
                repeat(strokes) { stroke()?.let { list += it } }
                if (list.isNotEmpty()) out += VectorPath(name, list)
            }
            return out
        }

        /**
         * PROP_PATHS, the older format GIMP still writes when it can: points typed 1 (anchor),
         * 2 (control) or 3 (start of a new stroke), as anchor, out, in, anchor…; two controls after a
         * stroke's last anchor close it.
         */
        fun readOldPaths(): List<VectorPath> {
            u32()                                  // active
            val n = count(u32(), 21)
            val out = ArrayList<VectorPath>()
            repeat(n) {
                val name = string()
                u32()                              // linked
                buf.get()                          // state
                val closedFlag = u32() != 0
                val np = count(u32(), 12)
                val version = u32()
                if (version >= 2) u32()            // dummy
                if (version >= 3) u32()            // tattoo
                val strokes = ArrayList<PathStroke>()
                val anchors = ArrayList<FloatArray>()  // in-x, in-y, x, y, out-x, out-y
                var controls = ArrayList<Pair<Float, Float>>()
                fun endStroke() {
                    if (anchors.isEmpty()) return
                    anchors.last().let { a -> controls.getOrNull(0)?.let { (x, y) -> a[4] = x; a[5] = y } }
                    val closed = controls.size >= 2 || (closedFlag && strokes.isEmpty())
                    if (controls.size >= 2) anchors.first().let { a -> a[0] = controls[1].first; a[1] = controls[1].second }
                    if (anchors.size >= 2) strokes += PathStroke(anchors.flatMap { it.asList() }.toFloatArray(), closed)
                    anchors.clear()
                    controls = ArrayList()
                }
                repeat(np) {
                    val type = u32()
                    val x: Float
                    val y: Float
                    if (version == 1) { x = u32().toFloat(); y = u32().toFloat() } else { x = buf.float; y = buf.float }
                    if (type == 3 && anchors.isNotEmpty()) endStroke()
                    if (type == 1 || type == 3) {
                        val a = floatArrayOf(x, y, x, y, x, y)
                        anchors.lastOrNull()?.let { prev ->
                            controls.getOrNull(0)?.let { (cx, cy) -> prev[4] = cx; prev[5] = cy }
                            controls.getOrNull(1)?.let { (cx, cy) -> a[0] = cx; a[1] = cy }
                        }
                        anchors += a
                        controls = ArrayList()
                    } else controls += x to y
                }
                endStroke()
                if (strokes.isNotEmpty()) out += VectorPath(name, strokes)
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
            if (w !in 1..262144 || h !in 1..262144 || w.toLong() * h > 24_000_000L) error("That image is ${w}×$h; GAKO opens up to 24 megapixels")
            val baseType = u32()
            if (version >= 4) precision = u32()
            if (version == 4) precision = when (precision) { 0 -> 150; 1 -> 250; 2 -> 300; 3 -> 500; else -> 600 }
            else if (version in 5..6) precision = when (precision) { 400 -> 500; 450 -> 550; 500 -> 600; 550 -> 650; else -> precision }
            else if (version < 4) precision = 150

            // Image properties.
            while (true) {
                val type = u32()
                val len = length(u32())
                val start = buf.position()
                when (type) {
                    XcfProps.END -> break
                    XcfProps.COMPRESSION -> compression = buf.get().toInt()
                    XcfProps.VECTORS -> paths = runCatching { readPaths() }.getOrDefault(emptyList())
                    XcfProps.PATHS -> if (paths.isEmpty()) paths = runCatching { readOldPaths() }.getOrDefault(emptyList())
                    XcfProps.COLORMAP -> {
                        val n = count(u32(), 3)
                        colormap = IntArray(n) {
                            val r = buf.get().toInt() and 0xFF; val g = buf.get().toInt() and 0xFF; val b = buf.get().toInt() and 0xFF
                            (r shl 16) or (g shl 8) or b
                        }
                    }
                    XcfProps.GUIDES -> repeat(len / 5) {
                        val pos = u32()
                        when (buf.get().toInt()) { 1 -> guides += Guide(true, pos); 2 -> guides += Guide(false, pos) }
                    }
                    XcfProps.SAMPLE_POINTS -> {
                        samplePoints.clear()
                        repeat(len / 20) { samplePoints += SamplePoint(u32(), u32(), u32()); u32(); u32() }
                    }
                    XcfProps.OLD_SAMPLE_POINTS -> if (samplePoints.isEmpty()) repeat(len / 8) { samplePoints += SamplePoint(u32(), u32()) }
                    in XcfProps.keptImageProps -> keptProps += raw(type, len)
                }
                goTo(start.toLong() + len)
            }
            val layerPtrs = mutableListOf<Long>()
            while (true) { val p = ptr(); if (p == 0L) break; layerPtrs += p }

            val layers = mutableListOf<Pair<Layer, Boolean>>()
            // Groups come before their children in the file (top to bottom): read in file order so
            // each child knows its groups, then stack bottom-up.
            val channelPtrs = mutableListOf<Long>()
            while (true) { val p = ptr(); if (p == 0L) break; channelPtrs += p }
            // GIMP 3 (XCF 18+) lists its paths as items after the channels.
            val pathPtrs = mutableListOf<Long>()
            if (version >= 18) while (buf.remaining() >= (if (wide) 8 else 4)) { val p = ptr(); if (p == 0L) break; pathPtrs += p }

            val read = layerPtrs.mapNotNull { readLayer(it, baseType) }
            layers += read.asReversed()
            if (layers.none { !it.first.group }) error("This XCF file has no layers GAKO can show")
            val active = layers.lastOrNull { it.second }?.first ?: layers.last { !it.first.group }.first

            var selection: Selection? = null
            val channels = ArrayList<Channel>()
            for (p in channelPtrs) {
                val (ch, isSelection) = runCatching { readChannel(p, w, h) }.getOrNull() ?: continue
                if (isSelection) {
                    val px = IntArray(w * h).also { ch.bitmap.getPixels(it, 0, w, 0, 0, w, h) }
                    selection = Selections.make(Selections.maskFromBytes(ByteArray(w * h) { (px[it] shr 16).toByte() }, w, h))
                } else channels += ch
            }
            if (paths.isEmpty() && pathPtrs.isNotEmpty())
                paths = pathPtrs.mapNotNull { runCatching { readPathItem(it) }.getOrNull() }
            val extras = GimpExtras(guides.filter { it.position in 0..(if (it.horizontal) h else w) },
                samplePoints.filter { it.x in 0 until w && it.y in 0 until h }, channels, keptProps)
            return DocState(w, h, Tree.normalize(layers.map { it.first }), active.id, selection = selection,
                paths = paths, gimp = extras)
        }

        /** Groups seen so far, by their item path → their layer id. */
        val groups = HashMap<List<Int>, Long>()
        /** Top-level items carry no item path; they're numbered in file order. */
        var topLevel = 0

        fun readLayer(at: Long, baseType: Int): Pair<Layer, Boolean>? {
            goTo(at)
            val w = u32(); val h = u32(); val type = u32()
            val info = LayerInfo(string(), w, h, type)
            var opacityFloat: Float? = null
            while (true) {
                val t = u32()
                val len = length(u32())
                val start = buf.position()
                when (t) {
                    XcfProps.END -> break
                    XcfProps.ACTIVE_LAYER -> info.active = true
                    XcfProps.OPACITY -> info.opacity = (u32() / 255f).coerceIn(0f, 1f)
                    XcfProps.FLOAT_OPACITY -> opacityFloat = buf.float
                    XcfProps.VISIBLE -> info.visible = u32() != 0
                    XcfProps.MODE -> {
                        val m = u32()
                        info.rawMode = m
                        LayerMode.ofGimp(m).let { (mode, legacy) -> info.mode = mode; info.legacy = legacy }
                    }
                    // Negative: "auto" (GIMP saves what auto meant, negated).
                    XcfProps.BLEND_SPACE -> info.blendSpace = u32().coerceAtLeast(0)
                    XcfProps.COMPOSITE_SPACE -> info.compositeSpace = u32().coerceAtLeast(0)
                    XcfProps.COMPOSITE_MODE -> info.compositeMode = u32().coerceAtLeast(0)
                    XcfProps.LOCK_ALPHA -> info.lockAlpha = u32() != 0
                    XcfProps.OFFSETS -> { info.x = u32(); info.y = u32() }
                    XcfProps.GROUP_ITEM -> info.group = true
                    XcfProps.GROUP_ITEM_FLAGS -> info.expanded = (u32() and 1) != 0
                    XcfProps.ITEM_PATH -> info.path = List(len / 4) { u32() }
                    XcfProps.PARASITES -> for (p in parasites(start + len)) {
                        if (p.name == XcfWriter.TEXT_PARASITE) info.text = XcfWriter.textFromString(String(p.data, Charsets.UTF_8))
                        else info.parasites += p
                    }
                    XcfProps.APPLY_MASK -> info.applyMask = u32() != 0
                    in XcfProps.keptItemProps -> info.kept += raw(t, len)
                }
                goTo(start.toLong() + len)
            }
            opacityFloat?.let { info.opacity = it.coerceIn(0f, 1f) }
            val hierarchy = ptr()
            val mask = ptr()
            if (version >= 20) {
                // GIMP 3's layer effects: offsets until a 0, the top (last applied) first.
                val at = ArrayList<Long>()
                while (true) { val p = ptr(); if (p == 0L) break; at += p }
                info.effects = at.asReversed().mapNotNull { runCatching { readEffect(it) }.getOrNull() }
            }
            if (info.path.isEmpty()) info.path = listOf(topLevel++)
            // The group this item is in: the one whose path is ours minus the last step.
            val parent = if (info.path.size > 1) groups[info.path.dropLast(1)] else null
            if (info.group) {
                val id = Layer.newId()
                groups[info.path] = id
                val g = Bitmaps.blank(1, 1)
                return Layer(id, info.name.ifEmpty { "Group" }, g, visible = info.visible,
                    xcf = extras(info, g),
                    opacity = info.opacity, mode = info.mode, legacy = info.legacy, blendSpace = info.blendSpace,
                    compositeSpace = info.compositeSpace, compositeMode = info.compositeMode,
                    group = true, parent = parent, expanded = info.expanded,
                    xcfMode = info.rawMode) to info.active
            }
            if (w <= 0 || h <= 0) return null
            val bmp = readHierarchy(hierarchy, type, baseType) ?: return null
            val maskBitmap = if (mask != 0L) readMask(mask) else null
            val xcf = extras(info, bmp)          // first: a GIMP text layer's text goes into info.text
            return Layer(Layer.newId(), info.name.ifEmpty { "Layer" }, bmp, info.x, info.y, info.visible,
                info.opacity, if (info.mode == LayerMode.PassThrough) LayerMode.Normal else info.mode,
                legacy = info.legacy, blendSpace = info.blendSpace, compositeSpace = info.compositeSpace,
                compositeMode = info.compositeMode, lockAlpha = info.lockAlpha, text = info.text,
                mask = maskBitmap?.takeIf { it.width == bmp.width && it.height == bmp.height },
                maskEnabled = info.applyMask, parent = parent, xcfMode = info.rawMode, xcf = xcf,
                effects = info.effects) to info.active
        }

        fun extras(info: LayerInfo, bmp: Bitmap): XcfLayerData? {
            if (info.kept.isEmpty() && info.parasites.isEmpty()) return null
            // A GIMP text layer: its text for GAKO's text tool (GIMP's pixels stay until it's edited).
            val isGimpText = info.parasites.any { it.name == GimpText.PARASITE }
            val gimpText = info.parasites.firstOrNull { it.name == GimpText.PARASITE }?.let { GimpText.parse(it.data) }
            if (info.text == null && gimpText != null) info.text = gimpText
            return XcfLayerData(info.kept.toList(), info.parasites.toList(),
                if (isGimpText && !info.group) XcfLayerData.checksum(bmp) else null, gimpText)
        }

        /** A saved channel (or the selection, if it says so): its grey pixels and the properties GAKO keeps. */
        fun readChannel(at: Long, imageW: Int, imageH: Int): Pair<Channel, Boolean>? {
            goTo(at)
            val cw = u32(); val ch = u32()
            val name = string()
            var selection = false
            val kept = ArrayList<XcfProp>()
            while (true) {
                val t = u32(); val len = length(u32())
                val start = buf.position()
                when (t) {
                    XcfProps.END -> break
                    XcfProps.SELECTION -> selection = true
                    in XcfProps.keptChannelProps -> kept += raw(t, len)
                    XcfProps.PARASITES -> kept += raw(t, len)
                }
                goTo(start.toLong() + len)
            }
            if (cw != imageW || ch != imageH) return null
            val bmp = readHierarchy(ptr(), -1, 1) ?: return null
            return Channel(name, bmp, kept) to selection
        }

        /** One GIMP 3 layer effect: name, icon, GEGL operation (and its version), settings, mask. */
        fun readEffect(at: Long): LayerEffect {
            goTo(at)
            val name = string()
            string()                                       // icon
            val operation = string()
            if (version >= 22) string()                    // the operation's version
            var active = true; var opacity = 1f; var mode = 62; var clip = true
            var blendSpace = 0; var compositeSpace = 0; var compositeMode = 0
            val args = LinkedHashMap<String, Any>()
            val unsupported = ArrayList<String>()
            while (true) {
                val t = u32(); val len = length(u32())
                val start = buf.position()
                when (t) {
                    XcfProps.END -> break
                    XcfProps.VISIBLE -> active = u32() != 0
                    XcfProps.OPACITY -> opacity = (u32() / 255f).coerceIn(0f, 1f)
                    XcfProps.FLOAT_OPACITY -> opacity = buf.float.coerceIn(0f, 1f)
                    XcfProps.MODE -> mode = u32()
                    XcfProps.BLEND_SPACE -> blendSpace = u32().coerceAtLeast(0)
                    XcfProps.COMPOSITE_SPACE -> compositeSpace = u32().coerceAtLeast(0)
                    XcfProps.COMPOSITE_MODE -> compositeMode = u32().coerceAtLeast(0)
                    XcfProps.FILTER_CLIP -> clip = u32() != 0
                    XcfProps.FILTER_ARGUMENT -> {
                        val arg = string()
                        when (u32()) {
                            1, 5, 7 -> args[arg] = u32()                       // int, enum, uint
                            2 -> args[arg] = u32() != 0                        // boolean
                            3 -> args[arg] = buf.float.toDouble()              // float
                            4 -> args[arg] = string()                          // string
                            8 -> color()?.let { args[arg] = it } ?: run { unsupported += arg }
                            6 -> args[arg] = string()                          // GIMP's serialized config
                            else -> unsupported += arg                         // gradient
                        }
                    }
                }
                goTo(start.toLong() + len)
            }
            val maskAt = ptr()
            val mask = if (maskAt != 0L) runCatching { readMask(maskAt) }.getOrNull() else null
            return LayerEffect(name, operation, args, unsupported, active, opacity, mode,
                blendSpace, compositeSpace, compositeMode, clip, mask)
        }

        /**
         * A GEGL colour as XCF stores it (babl encoding, bytes in the machine's order, ICC profile)
         * as the "rgba(…)" string GEGL reads (linear light), or null if it isn't RGB(A) or grey.
         */
        fun color(): String? {
            val encoding = string()
            val n = length(u32())
            val bytes = ByteArray(n).also { buf.get(it) }
            val profile = length(u32()); goTo(buf.position().toLong() + profile)
            val model = encoding.substringBefore(' ')
            val kind = encoding.substringAfter(' ', "")
            val channels = model.count { it.isUpperCase() }
            if (channels == 0 || n == 0) return null
            val size = n / channels
            val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val v = DoubleArray(channels) { i ->
                when {
                    kind == "double" && size == 8 -> le.getDouble(i * 8)
                    kind == "float" && size == 4 -> le.getFloat(i * 4).toDouble()
                    kind == "u8" && size == 1 -> (bytes[i].toInt() and 255) / 255.0
                    kind == "u16" && size == 2 -> (le.getShort(i * 2).toInt() and 0xFFFF) / 65535.0
                    else -> return null
                }
            }
            val perceptual = model.contains('\'')
            fun lin(x: Double) = if (!perceptual) x else if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
            val (r, g, b, a) = when {
                model.startsWith("R") && channels >= 3 -> listOf(lin(v[0]), lin(v[1]), lin(v[2]), if (channels >= 4) v[3] else 1.0)
                model.startsWith("Y") -> listOf(lin(v[0]), lin(v[0]), lin(v[0]), if (channels >= 2) v[1] else 1.0)
                else -> return null
            }
            return String.format(java.util.Locale.ROOT, "rgba(%.6f, %.6f, %.6f, %.6f)", r, g, b, a)
        }

        /** A layer mask channel, as an opaque grey bitmap. */
        fun readMask(at: Long): Bitmap? {
            goTo(at)
            u32(); u32()
            string()
            while (true) {
                val t = u32(); val len = length(u32())
                if (t == XcfProps.END) break
                goTo(buf.position().toLong() + len)
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
            goTo(at)
            val w = u32(); val h = u32(); val bpp = u32()
            if (w <= 0 || h <= 0 || w.toLong() * h > 24_000_000L || bpp !in 1..32) error("The file is damaged (a layer's size)")
            val level = ptr()
            goTo(level)
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
