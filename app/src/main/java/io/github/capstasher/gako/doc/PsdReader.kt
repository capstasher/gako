package io.github.capstasher.gako.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/**
 * Photoshop (.psd / .psb) files with their layers: RGB or greyscale, 8 or 16 bits, raw, RLE or ZIP
 * channels, layer masks, opacity, visibility and blend modes. Groups are flattened into their
 * children; adjustment layers and effects are skipped. Without readable layers, the merged image.
 */
object PsdReader {

    private val modes = mapOf(
        "norm" to LayerMode.Normal, "mul " to LayerMode.Multiply, "scrn" to LayerMode.Screen,
        "over" to LayerMode.Overlay, "sLit" to LayerMode.SoftLight, "hLit" to LayerMode.HardLight,
        "div " to LayerMode.Dodge, "idiv" to LayerMode.Burn, "dark" to LayerMode.Darken, "lite" to LayerMode.Lighten,
        "lddg" to LayerMode.Addition, "diff" to LayerMode.Difference, "smud" to LayerMode.Exclusion,
        "hue " to LayerMode.Hue, "sat " to LayerMode.Saturation, "colr" to LayerMode.Color, "lum " to LayerMode.Luminosity,
        "pass" to LayerMode.PassThrough,
    )

    fun read(input: InputStream, scratch: File? = null): DocState {
        val tmp = File.createTempFile("open", ".psd", scratch)
        try {
            tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            RandomAccessFile(tmp, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.BIG_ENDIAN)
                return Parser(map).parse()
            }
        } finally {
            tmp.delete()
        }
    }

    private class ChannelInfo(val id: Int, val length: Long)

    private class Record(
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val channels: List<ChannelInfo>, val mode: LayerMode, val opacity: Int, val visible: Boolean,
        var name: String, val maskTop: Int, val maskLeft: Int, val maskBottom: Int, val maskRight: Int,
        val maskDefault: Int, val maskDisabled: Boolean, val section: Int,
        /** A group's own blend mode from its section divider (pass-through, usually). */
        val sectionMode: LayerMode? = null,
    ) {
        val w get() = right - left
        val h get() = bottom - top
    }

    private class Parser(val buf: ByteBuffer) {
        var psb = false
        var depth = 8
        var mode = 3
        var width = 0
        var height = 0
        var channels = 0
        var mergedHasAlpha = false

        fun u8() = buf.get().toInt() and 0xFF
        fun u16() = buf.short.toInt() and 0xFFFF
        fun i16() = buf.short.toInt()
        fun i32() = buf.int
        fun u32() = buf.int.toLong() and 0xFFFFFFFFL
        fun len() = if (psb) buf.long else u32()
        fun key(): String = String(ByteArray(4).also { buf.get(it) }, Charsets.ISO_8859_1)

        // Every seek is checked: a damaged or hostile file must fail, never loop or seek backwards.
        fun goTo(pos: Long) {
            if (pos < 0 || pos > buf.limit()) error("The file is damaged (an offset points outside it)")
            buf.position(pos.toInt())
        }
        fun skip(n: Long) {
            if (n < 0) error("The file is damaged (negative length)")
            goTo(buf.position() + n)
        }
        fun remaining() = (buf.limit() - buf.position()).toLong()

        fun parse(): DocState {
            if (key() != "8BPS") error("Not a Photoshop file")
            val version = u16()
            psb = version == 2
            skip(6)
            channels = u16()
            height = i32()
            width = i32()
            depth = u16()
            mode = u16()
            val maxSide = if (psb) 300_000 else 30_000
            if (width !in 1..maxSide || height !in 1..maxSide) error("The file says it's ${width}×$height, which isn't possible")
            if (width.toLong() * height > 24_000_000L) error("That image is ${width}×$height; GAKO opens up to 24 megapixels")
            if (mode != 3 && mode != 1) error("GAKO opens RGB and greyscale PSDs (this one is ${modeName(mode)})")
            if (depth != 8 && depth != 16) error("GAKO opens 8 and 16-bit PSDs (this one is $depth-bit)")
            skip(u32())          // colour mode data
            skip(u32())          // image resources
            val layerSectionLength = len()
            if (layerSectionLength < 0 || layerSectionLength > remaining()) error("The file is damaged (layer section)")
            val layerSectionEnd = buf.position() + layerSectionLength
            val layers = if (layerSectionLength > 0) runCatching { readLayers(layerSectionEnd) }.getOrNull() else null
            goTo(layerSectionEnd)
            if (!layers.isNullOrEmpty()) {
                val list = layers.map { it.first }
                return DocState(width, height, list, list.last { !it.group }.id)
            }
            val merged = readMerged()
            return DocOps.fromBitmap(merged)
        }

        fun modeName(m: Int) = when (m) { 0 -> "bitmap"; 2 -> "indexed"; 4 -> "CMYK"; 7 -> "multichannel"; 8 -> "duotone"; 9 -> "Lab"; else -> "mode $m" }

        /** Layer info, or (16/32-bit files) the copy Photoshop keeps in an Lr16/Lr32 block at the end of the section. */
        fun readLayers(sectionEnd: Long): List<Pair<Layer, Boolean>> {
            val infoLength = len()
            if (infoLength < 0 || buf.position() + infoLength > sectionEnd) error("The file is damaged (layer info)")
            val infoEnd = buf.position() + infoLength
            if (infoLength > 0) {
                val layers = readLayerInfo(infoEnd)
                if (layers.isNotEmpty()) return layers
            }
            goTo(infoEnd)
            skip(u32())                                 // global layer mask info
            // Tagged blocks, rounded to 4 bytes here.
            while (buf.position() + 12 <= sectionEnd) {
                val sig = key()
                if (sig != "8BIM" && sig != "8B64") break
                val k = key()
                val l = if (psb && k in longKeys) buf.long else u32()
                if (l < 0 || buf.position() + l > sectionEnd) break
                val dataStart = buf.position().toLong()
                if (k == "Lr16" || k == "Lr32" || k == "Layr") return readLayerInfo(dataStart + l)
                goTo(dataStart + ((l + 3) and 3L.inv()).coerceAtMost(sectionEnd - dataStart))
            }
            return emptyList()
        }

        val longKeys = setOf("LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD")

        fun readLayerInfo(end: Long): List<Pair<Layer, Boolean>> {
            val raw = i16()
            mergedHasAlpha = raw < 0
            val count = kotlin.math.abs(raw)
            // Every record is at least 34 bytes: a count beyond that can't be real.
            if (count.toLong() * 34 > end - buf.position()) error("The file is damaged (layer count)")
            val records = ArrayList<Record>()
            repeat(count) { records += readRecord(end) }
            // Channel data follows in the same order.
            val pixels = records.map { r -> readLayerPixels(r) }
            // PSD lists layers bottom first, as GAKO does. A group's record comes after (above) its
            // children, closed by a divider (section 3) below them: walk top-down to know each
            // layer's groups, so children of a hidden or faded group come out hidden or faded.
            val out = ArrayList<Pair<Layer, Boolean>>()
            val stack = ArrayList<Long>()           // open groups, innermost last
            for (i in records.indices.reversed()) {
                val r = records[i]
                when (r.section) {
                    1, 2 -> {
                        val id = Layer.newId()
                        out += Layer(id, r.name.ifBlank { "Group" }, Bitmaps.blank(1, 1), visible = r.visible,
                            opacity = r.opacity / 255f, mode = r.sectionMode ?: r.mode, group = true,
                            parent = stack.lastOrNull(), expanded = r.section == 1) to false
                        stack += id
                        continue
                    }
                    3 -> { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); continue }
                }
                val (bmp, mask) = pixels[i] ?: continue
                out += Layer(Layer.newId(), r.name.ifBlank { "Layer ${out.size + 1}" }, bmp, r.left, r.top,
                    visible = r.visible, opacity = r.opacity / 255f, mode = r.mode,
                    mask = mask, maskEnabled = !r.maskDisabled, parent = stack.lastOrNull()) to false
            }
            // Empty groups would leave nothing to paint on; keep them only if there's a real layer.
            return if (out.any { !it.first.group }) out.asReversed() else emptyList()
        }

        fun readRecord(end: Long): Record {
            val top = i32(); val left = i32(); val bottom = i32(); val right = i32()
            val n = u16()
            if (n > 56) error("The file is damaged (channel count)")
            val chans = List(n) { ChannelInfo(i16(), len()).also { if (it.length < 0 || it.length > end) error("The file is damaged (channel length)") } }
            key()                                   // "8BIM"
            val blend = key()
            val opacity = u8()
            u8()                                    // clipping
            val flags = u8()
            u8()                                    // filler
            val extra = u32()
            if (buf.position() + extra > end) error("The file is damaged (layer record)")
            val extraEnd = buf.position() + extra
            // Layer mask data.
            var mTop = 0; var mLeft = 0; var mBottom = 0; var mRight = 0; var mDefault = 0; var mDisabled = false
            val maskLen = u32()
            if (maskLen > extraEnd - buf.position()) error("The file is damaged (layer mask)")
            if (maskLen >= 20) {
                val start = buf.position().toLong()
                mTop = i32(); mLeft = i32(); mBottom = i32(); mRight = i32()
                mDefault = u8()
                val mFlags = u8()
                mDisabled = mFlags and 2 != 0
                goTo(start + maskLen)
            } else skip(maskLen)
            skip(u32())                             // blending ranges
            val nameLen = u8()
            var name = String(ByteArray(nameLen).also { buf.get(it) }, Charsets.ISO_8859_1)
            skip(((nameLen + 1 + 3) / 4 * 4 - (nameLen + 1)).toLong())
            var section = 0
            var sectionMode: LayerMode? = null
            // Additional layer information: the Unicode name and group markers.
            while (buf.position() + 12 <= extraEnd) {
                val sig = key()
                if (sig != "8BIM" && sig != "8B64") break
                val k = key()
                val l = if (psb && k in longKeys) buf.long else u32()
                val dataStart = buf.position()
                if (l < 0 || dataStart + l > extraEnd) break
                when (k) {
                    "luni" -> {
                        val chars = u32()
                        if (chars in 0..((l - 4) / 2)) name = String(CharArray(chars.toInt()) { buf.char })
                    }
                    "lsct", "lsdk" -> {
                        section = i32()
                        if (l >= 12 && key() == "8BIM") sectionMode = key().let { if (it == "pass") LayerMode.PassThrough else modes[it] }
                    }
                }
                // Writers pad blocks to 2 or 4 bytes (or not at all): take whichever lands on the next block.
                val candidates = listOf(dataStart + l, dataStart + ((l + 1) and 1L.inv()), dataStart + ((l + 3) and 3L.inv()))
                val next = candidates.firstOrNull { p ->
                    p >= extraEnd || (p + 4 <= extraEnd && buf.getInt(p.toInt()).let { it == 0x3842494D || it == 0x38423634 })
                } ?: extraEnd
                goTo(maxOf(next, dataStart + 1L).coerceAtMost(extraEnd))
            }
            goTo(extraEnd)
            return Record(top, left, bottom, right, chans, modes[blend] ?: LayerMode.Normal, opacity,
                flags and 2 == 0, name, mTop, mLeft, mBottom, mRight, mDefault, mDisabled, section, sectionMode)
        }

        /** One layer's channels as an ARGB bitmap, plus its mask (layer-sized grey) if it has one. */
        fun readLayerPixels(r: Record): Pair<Bitmap, Bitmap?>? {
            val planes = HashMap<Int, ByteArray>()
            var maskPlane: ByteArray? = null
            for (c in r.channels) {
                val start = buf.position().toLong()
                if (c.length > remaining()) error("The file is damaged (channel data)")
                val isMask = c.id == -2
                val w = if (isMask) r.maskRight - r.maskLeft else r.w
                val h = if (isMask) r.maskBottom - r.maskTop else r.h
                if (w > 0 && h > 0 && w.toLong() * h <= 24_000_000L && c.length >= 2) {
                    val data = readChannel(w, h, c.length - 2)
                    if (isMask) maskPlane = data else if (c.id >= -1) planes[c.id] = data
                }
                goTo(start + c.length)
            }
            if (r.w <= 0 || r.h <= 0) return null
            if (r.w.toLong() * r.h > 24_000_000L) error("A layer is too big")
            val n = r.w * r.h
            val px = IntArray(n)
            val red = planes[0]; val green = planes[1] ?: red; val blue = planes[2] ?: red
            val alpha = planes[-1]
            if (red == null) return null
            for (i in 0 until n) {
                val a = alpha?.get(i)?.toInt()?.and(0xFF) ?: 0xFF
                px[i] = (a shl 24) or ((red[i].toInt() and 0xFF) shl 16) or ((green!![i].toInt() and 0xFF) shl 8) or (blue!![i].toInt() and 0xFF)
            }
            val bmp = Bitmap.createBitmap(r.w, r.h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(px, 0, r.w, 0, 0, r.w, r.h)
            val mask = maskPlane?.let { m ->
                val mw = r.maskRight - r.maskLeft
                val mh = r.maskBottom - r.maskTop
                val grey = IntArray(mw * mh) { val v = m[it].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
                val piece = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888).apply { setPixels(grey, 0, mw, 0, 0, mw, mh) }
                val d = r.maskDefault
                Bitmaps.blank(r.w, r.h, android.graphics.Color.rgb(d, d, d)).also {
                    Canvas(it).drawBitmap(piece, (r.maskLeft - r.left).toFloat(), (r.maskTop - r.top).toFloat(), null)
                }
            }
            return bmp to mask
        }

        /** One channel plane (w×h), 8-bit, from raw, RLE or ZIP data of [length] bytes. */
        fun readChannel(w: Int, h: Int, length: Long): ByteArray {
            val compression = u16()
            val bpc = depth / 8
            val raw = when (compression) {
                0 -> ByteArray(w * h * bpc).also { buf.get(it, 0, minOf(it.size.toLong(), length, remaining()).toInt()) }
                1 -> rle(w * bpc, h)
                2, 3 -> {
                    if (length > remaining()) error("The file is damaged (compressed channel)")
                    val src = ByteArray(length.toInt()).also { buf.get(it) }
                    val out = ByteArray(w * h * bpc)
                    val inflater = Inflater()
                    try {
                        inflater.setInput(src)
                        inflater.inflate(out)
                    } finally {
                        inflater.end()
                    }
                    if (compression == 3) unpredict(out, w * bpc, h, bpc)
                    out
                }
                else -> error("Unknown PSD compression $compression")
            }
            if (bpc == 1) return raw
            // 16-bit, big-endian: keep the high byte.
            return ByteArray(w * h) { raw[it * 2] }
        }

        /** PackBits rows, preceded by each row's byte count. */
        fun rle(rowBytes: Int, rows: Int): ByteArray {
            val counts = IntArray(rows) { if (psb) i32() else u16() }
            val out = ByteArray(rowBytes * rows)
            for (y in 0 until rows) {
                if (counts[y] < 0 || counts[y] > remaining()) error("The file is damaged (RLE row)")
                val end = buf.position() + counts[y]
                var x = 0
                while (buf.position() < end && x < rowBytes) {
                    val n = buf.get().toInt()
                    if (n >= 0) {
                        repeat(n + 1) { val b = buf.get(); if (x < rowBytes) out[y * rowBytes + x++] = b }
                    } else if (n != -128) {
                        val b = buf.get()
                        repeat(-n + 1) { if (x < rowBytes) out[y * rowBytes + x++] = b }
                    }
                }
                buf.position(end)
            }
            return out
        }

        /** ZIP-with-prediction stores each sample as the difference from the one to its left. */
        fun unpredict(data: ByteArray, rowBytes: Int, rows: Int, bpc: Int) {
            if (bpc == 1) {
                for (y in 0 until rows) for (x in 1 until rowBytes) {
                    val i = y * rowBytes + x
                    data[i] = (data[i] + data[i - 1]).toByte()
                }
            } else {
                val w = rowBytes / 2
                for (y in 0 until rows) for (x in 1 until w) {
                    val i = y * rowBytes + x * 2
                    val prev = ((data[i - 2].toInt() and 0xFF) shl 8) or (data[i - 1].toInt() and 0xFF)
                    val cur = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                    val v = (prev + cur) and 0xFFFF
                    data[i] = (v shr 8).toByte(); data[i + 1] = v.toByte()
                }
            }
        }

        /** The flattened image at the end of the file. */
        fun readMerged(): Bitmap {
            val compression = u16()
            val bpc = depth / 8
            val planes = minOf(channels, if (mode == 3) 4 else 2)
            val counts = if (compression == 1) IntArray(channels * height) { if (psb) i32() else u16() } else null
            val data = Array(planes) { ByteArray(width * height) }
            for (c in 0 until channels) {
                val plane = when (compression) {
                    0 -> ByteArray(width * height * bpc).also { buf.get(it) }
                    1 -> {
                        val out = ByteArray(width * height * bpc)
                        for (y in 0 until height) {
                            val n0 = counts!![c * height + y]
                            if (n0 < 0 || n0 > remaining()) error("The file is damaged (RLE row)")
                            val end = buf.position() + n0
                            var x = 0
                            val row = width * bpc
                            while (buf.position() < end && x < row) {
                                val n = buf.get().toInt()
                                if (n >= 0) repeat(n + 1) { val b = buf.get(); if (x < row) out[y * row + x++] = b }
                                else if (n != -128) { val b = buf.get(); repeat(-n + 1) { if (x < row) out[y * row + x++] = b } }
                            }
                            buf.position(end)
                        }
                        out
                    }
                    else -> error("Unsupported PSD image compression $compression")
                }
                if (c < planes) data[c] = if (bpc == 1) plane else ByteArray(width * height) { plane[it * 2] }
            }
            val n = width * height
            val px = IntArray(n)
            for (i in 0 until n) {
                val r = data[0][i].toInt() and 0xFF
                val g = if (mode == 3) data[1][i].toInt() and 0xFF else r
                val b = if (mode == 3) data[2][i].toInt() and 0xFF else r
                val aPlane = if (mode == 3) 3 else 1
                // An extra channel is transparency only when the file says so; otherwise it's a
                // saved selection or spot colour.
                val a = if (planes > aPlane && mergedHasAlpha) data[aPlane][i].toInt() and 0xFF else 0xFF
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, width, 0, 0, width, height) }
        }
    }
}
