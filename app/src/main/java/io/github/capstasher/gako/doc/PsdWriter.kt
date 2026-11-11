package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * Photoshop .psd with layers: 8-bit RGB, RLE-compressed, each layer with its position, name,
 * opacity, visibility, blend mode and layer mask, plus the flattened image for viewers that only
 * read that.
 */
object PsdWriter {

    /** Photoshop's nearest blend mode for each of GIMP's (GIMP's PSD export table); others are Normal. */
    private val modes = mapOf(
        LayerMode.Normal to "norm", LayerMode.Multiply to "mul ", LayerMode.Screen to "scrn", LayerMode.Overlay to "over",
        LayerMode.SoftLight to "sLit", LayerMode.HardLight to "hLit", LayerMode.Dodge to "div ", LayerMode.Burn to "idiv",
        LayerMode.Darken to "dark", LayerMode.Lighten to "lite", LayerMode.Addition to "lddg", LayerMode.Difference to "diff",
        LayerMode.Exclusion to "smud", LayerMode.Subtract to "fsub", LayerMode.Divide to "fdiv",
        LayerMode.LinearBurn to "lbrn", LayerMode.VividLight to "vLit", LayerMode.PinLight to "pLit",
        LayerMode.LinearLight to "lLit", LayerMode.HardMix to "hMix", LayerMode.LumaDarken to "dark",
        LayerMode.LumaLighten to "lite",
        LayerMode.Hue to "hue ", LayerMode.Saturation to "sat ", LayerMode.Color to "colr", LayerMode.Value to "lum ",
        LayerMode.LchHue to "hue ", LayerMode.LchChroma to "sat ", LayerMode.LchColor to "colr",
        LayerMode.LchLightness to "lum ", LayerMode.Luminance to "lum ", LayerMode.PassThrough to "pass",
    )

    /**
     * Writes [doc]. Channel data goes through a temporary file in [scratch] (one layer's worth in
     * memory at a time), since the section lengths must come before it.
     */
    fun write(doc: DocState, out: OutputStream, scratch: java.io.File? = null) {
        val tmp = java.io.File.createTempFile("psd", ".chan", scratch)
        try {
            val records = ByteArrayOutputStream()
            val li = DataOutputStream(records)
            // Groups become a divider record below their children and a folder record above them.
            val count = doc.layers.size + doc.layers.count { it.group }
            li.writeShort(-count)                    // negative: the merged image's alpha is transparency
            var channelBytes = 0L
            val empty = ByteArray(2)                 // an empty channel: raw compression, no data
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { chan ->
                fun emptyRecord(l: Layer, section: Int, name: String) {
                    repeat(4) { chan.write(empty); channelBytes += 2 }
                    writeRecord(li, l.copy(name = name), listOf(2, 2, 2, 2), false, section)
                }
                fun emit(parent: Long?) {
                    for (l in doc.layers) {
                        if (l.parent != parent) continue
                        if (l.group) {
                            emptyRecord(l, 3, "</Layer group>")
                            emit(l.id)
                            emptyRecord(l, if (l.expanded) 1 else 2, l.name)
                            continue
                        }
                        val planes = planesOf(l.bitmap)
                        val mask = l.mask?.let { redPlane(it) }
                        val encoded = planes.map { rle(it, l.width, l.height) } + listOfNotNull(mask?.let { rle(it, l.width, l.height) })
                        encoded.forEach { chan.write(it); channelBytes += it.size }
                        writeRecord(li, l, encoded.map { it.size }, mask != null)
                    }
                }
                emit(null)
            }
            li.flush()
            val recordBytes = records.size().toLong()
            var infoLength = recordBytes + channelBytes      // the layer count is the first 2 bytes of records
            val pad = (infoLength % 2).toInt()
            infoLength += pad

            val o = DataOutputStream(out.buffered(1 shl 16))
            o.writeBytes("8BPS"); o.writeShort(1); o.write(ByteArray(6))
            o.writeShort(4)                          // R, G, B and transparency
            o.writeInt(doc.height); o.writeInt(doc.width)
            o.writeShort(8); o.writeShort(3)         // 8-bit RGB
            o.writeInt(0)                            // colour mode data
            o.writeInt(0)                            // image resources
            o.writeInt((4 + infoLength + 4).toInt()) // layer and mask section
            o.writeInt(infoLength.toInt())
            records.writeTo(o)
            tmp.inputStream().buffered(1 shl 16).use { it.copyTo(o, 1 shl 16) }
            repeat(pad) { o.writeByte(0) }
            o.writeInt(0)                            // global layer mask info

            // The flattened image: one RLE block for all four planes.
            val flat = Compositor.flatten(doc)
            val (fa, fr, fg, fb) = planesOf(flat)
            val rows = listOf(fr, fg, fb, fa).map { rleRows(it, doc.width, doc.height) }
            o.writeShort(1)
            for (r in rows) for (row in r) o.writeShort(row.size)
            for (r in rows) for (row in r) o.write(row)
            o.flush()
        } finally {
            tmp.delete()
        }
    }

    /** One layer record; [section] 1/2 is a group folder (open/closed), 3 the end of a group. */
    private fun writeRecord(li: DataOutputStream, l: Layer, sizes: List<Int>, hasMask: Boolean, section: Int = 0) {
        if (section != 0) { li.writeInt(0); li.writeInt(0); li.writeInt(0); li.writeInt(0) }
        else { li.writeInt(l.y); li.writeInt(l.x); li.writeInt(l.y + l.height); li.writeInt(l.x + l.width) }
        li.writeShort(sizes.size)
        val ids = intArrayOf(-1, 0, 1, 2) + if (hasMask) intArrayOf(-2) else intArrayOf()
        for ((k, id) in ids.withIndex()) { li.writeShort(id); li.writeInt(sizes[k]) }
        li.writeBytes("8BIM")
        li.writeBytes(if (section != 0 && l.mode == LayerMode.PassThrough) "pass" else modes[l.mode]?.takeIf { it != "pass" } ?: "norm")
        li.writeByte((l.opacity * 255).toInt().coerceIn(0, 255))
        li.writeByte(0)                              // clipping
        li.writeByte(if (l.visible) 0 else 2)        // flags: hidden
        li.writeByte(0)
        val extra = ByteArrayOutputStream()
        val ex = DataOutputStream(extra)
        if (hasMask) {
            ex.writeInt(20)
            ex.writeInt(l.y); ex.writeInt(l.x); ex.writeInt(l.y + l.height); ex.writeInt(l.x + l.width)
            ex.writeByte(255)                        // outside the mask: shown
            ex.writeByte(if (l.maskEnabled) 0 else 2)
            ex.writeShort(0)                         // padding
        } else ex.writeInt(0)
        ex.writeInt(0)                               // blending ranges
        val name = l.name.toByteArray(Charsets.ISO_8859_1).let { if (it.size > 255) it.copyOf(255) else it }
        ex.writeByte(name.size); ex.write(name)
        repeat((4 - (name.size + 1) % 4) % 4) { ex.writeByte(0) }
        // The real (Unicode) name.
        val uni = ByteArrayOutputStream().also { u ->
            DataOutputStream(u).apply { writeInt(l.name.length); l.name.forEach { writeChar(it.code) } }
        }.toByteArray()
        ex.writeBytes("8BIM"); ex.writeBytes("luni"); ex.writeInt(uni.size); ex.write(uni)
        if (section != 0) {
            // Section divider: type, then (for folders) the group's own blend mode.
            ex.writeBytes("8BIM"); ex.writeBytes("lsct")
            if (section == 3) { ex.writeInt(4); ex.writeInt(3) }
            else { ex.writeInt(12); ex.writeInt(section); ex.writeBytes("8BIM"); ex.writeBytes(modes[l.mode] ?: "norm") }
        }
        ex.flush()
        li.writeInt(extra.size()); li.write(extra.toByteArray())
    }

    /** A, R, G, B planes (unpremultiplied), in Photoshop's channel order -1, 0, 1, 2. */
    private fun planesOf(b: Bitmap): List<ByteArray> {
        val w = b.width
        val h = b.height
        val a = ByteArray(w * h); val r = ByteArray(w * h); val g = ByteArray(w * h); val bl = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]; val i = y * w + x
                a[i] = (p ushr 24).toByte(); r[i] = (p shr 16).toByte(); g[i] = (p shr 8).toByte(); bl[i] = p.toByte()
            }
        }
        // The flattened image wants R, G, B, A; layers want A, R, G, B — callers pick.
        return listOf(a, r, g, bl)
    }

    private fun redPlane(b: Bitmap): ByteArray {
        val w = b.width
        val out = ByteArray(w * b.height)
        val row = IntArray(w)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) out[y * w + x] = (row[x] shr 16).toByte()
        }
        return out
    }

    /** A channel's data: compression 1, the row byte counts, then the PackBits rows. */
    private fun rle(plane: ByteArray, w: Int, h: Int): ByteArray {
        val rows = rleRows(plane, w, h)
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeShort(1)
        for (r in rows) d.writeShort(r.size)
        for (r in rows) d.write(r)
        d.flush()
        return bos.toByteArray()
    }

    private fun rleRows(plane: ByteArray, w: Int, h: Int): List<ByteArray> = List(h) { y -> packBits(plane, y * w, w) }

    /** PackBits: runs of 2–128 equal bytes, literals of 1–128. */
    private fun packBits(src: ByteArray, start: Int, n: Int): ByteArray {
        val out = ByteArrayOutputStream(n / 2 + 8)
        var i = 0
        while (i < n) {
            var run = 1
            while (i + run < n && run < 128 && src[start + i + run] == src[start + i]) run++
            if (run >= 2) {
                out.write(1 - run)
                out.write(src[start + i].toInt())
                i += run
            } else {
                var lit = 1
                while (i + lit < n && lit < 128 &&
                    !(i + lit + 1 < n && src[start + i + lit] == src[start + i + lit + 1])) lit++
                out.write(lit - 1)
                out.write(src, start + i, lit)
                i += lit
            }
        }
        return out.toByteArray()
    }
}
