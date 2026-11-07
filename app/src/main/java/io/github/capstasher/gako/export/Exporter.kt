package io.github.capstasher.gako.export

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.heifwriter.HeifWriter
import io.github.capstasher.gako.export.encoders.BmpEncoder
import io.github.capstasher.gako.export.encoders.GifEncoder
import io.github.capstasher.gako.export.encoders.IcoEncoder
import io.github.capstasher.gako.export.encoders.OraEncoder
import io.github.capstasher.gako.export.encoders.PixelSource
import io.github.capstasher.gako.export.encoders.PnmEncoder
import io.github.capstasher.gako.export.encoders.TgaEncoder
import io.github.capstasher.gako.export.encoders.TiffEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

enum class ExportFormat(val label: String, val extensions: List<String>, val mime: String) {
    Png("PNG", listOf("png"), "image/png"),
    Jpeg("JPEG", listOf("jpg", "jpeg", "jpe"), "image/jpeg"),
    Webp("WebP", listOf("webp"), "image/webp"),
    Heif("HEIF", listOf("heic", "heif"), "image/heic"),
    Tiff("TIFF", listOf("tif", "tiff"), "image/tiff"),
    Gif("GIF", listOf("gif"), "image/gif"),
    Bmp("BMP", listOf("bmp"), "image/bmp"),
    Tga("TGA", listOf("tga"), "image/x-tga"),
    Ico("ICO", listOf("ico"), "image/x-icon"),
    Ppm("PPM", listOf("ppm", "pnm"), "image/x-portable-pixmap"),
    Ora("ORA", listOf("ora"), "image/openraster");

    val ext: String get() = extensions.first()

    /** Types the platform doesn't know; saved as plain files so nothing renames them. */
    val opaqueToSystem: Boolean get() = this == Tga || this == Ora

    companion object {
        fun forFileName(name: String): ExportFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

data class ExportOptions(
    val quality: Int = 90,          // JPEG, lossy WebP, HEIF
    val webpLossless: Boolean = false,
    val gifDither: Boolean = true,
    val tiffDeflate: Boolean = true,
    val tgaRle: Boolean = true,
)

/** Encodes a bitmap in any [ExportFormat]. Runs on a background thread. */
object Exporter {

    fun encode(bitmap: Bitmap, format: ExportFormat, options: ExportOptions, out: OutputStream, scratch: File) {
        scratch.mkdirs()
        val source = BitmapSource(bitmap)
        val alpha by lazy { bitmap.hasAlpha() && PixelSource.hasTransparency(source) }
        val buffered = out.buffered(256 * 1024)
        when (format) {
            ExportFormat.Png -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffered)
            ExportFormat.Jpeg -> flattened(bitmap).compress(Bitmap.CompressFormat.JPEG, options.quality, buffered)
            ExportFormat.Webp -> bitmap.compress(
                if (options.webpLossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY,
                if (options.webpLossless) 100 else options.quality,
                buffered,
            )
            ExportFormat.Heif -> {
                val tmp = File(scratch, "export.heic").apply { delete() }
                val flat = flattened(bitmap)
                val writer = HeifWriter.Builder(tmp.path, flat.width, flat.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(options.quality)
                    .setMaxImages(1)
                    .build()
                try {
                    writer.start()
                    writer.addBitmap(flat)
                    writer.stop(60_000)
                } finally {
                    writer.close()
                }
                tmp.inputStream().use { it.copyTo(buffered) }
                tmp.delete()
            }
            ExportFormat.Tiff -> {
                val tmp = File(scratch, "export.tif").apply { delete() }
                TiffEncoder.write(source, alpha,
                    if (options.tiffDeflate) TiffEncoder.COMPRESSION_DEFLATE else TiffEncoder.COMPRESSION_NONE, tmp)
                tmp.inputStream().use { it.copyTo(buffered) }
                tmp.delete()
            }
            ExportFormat.Gif -> GifEncoder.write(source, options.gifDither, buffered)
            ExportFormat.Bmp -> BmpEncoder.write(source, alpha, buffered)
            ExportFormat.Tga -> TgaEncoder.write(source, alpha, options.tgaRle, buffered)
            ExportFormat.Ppm -> PnmEncoder.write(source, buffered)
            ExportFormat.Ico -> {
                val icon = fitWithin(bitmap, 256)
                IcoEncoder.write(pngBytes(icon), icon.width, icon.height, buffered)
            }
            ExportFormat.Ora -> {
                val layer = File(scratch, "layer0.png").apply { delete() }
                layer.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                OraEncoder.write(layer, pngBytes(fitWithin(bitmap, 256)), bitmap.width, bitmap.height, "Background", buffered)
                layer.delete()
            }
        }
        buffered.flush()
    }

    private fun pngBytes(b: Bitmap): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun fitWithin(b: Bitmap, side: Int): Bitmap {
        if (b.width <= side && b.height <= side) return b
        val s = side.toDouble() / max(b.width, b.height)
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).roundToInt()), max(1, (b.height * s).roundToInt()), true)
    }

    /** For formats without alpha: composite over white, as GIMP's export does by default. */
    private fun flattened(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(android.graphics.Color.WHITE)
            drawBitmap(b, 0f, 0f, null)
        }
        out.setHasAlpha(false)
        return out
    }

    private class BitmapSource(private val b: Bitmap) : PixelSource {
        override fun width() = b.width
        override fun height() = b.height
        override fun readRow(y: Int, argb: IntArray) = b.getPixels(argb, 0, b.width, 0, y, b.width, 1)
    }
}
