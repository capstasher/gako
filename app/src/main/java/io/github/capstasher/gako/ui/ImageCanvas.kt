package io.github.capstasher.gako.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.capstasher.gako.ui.theme.Gako
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pinch to zoom, drag to pan, double-tap to toggle fit / 100%.
 * [bitmap] may be a downscaled preview: it's always drawn over the full image's footprint.
 */
@Composable
fun ImageCanvas(
    bitmap: Bitmap,
    imageWidth: Int,
    imageHeight: Int,
    marker: Offset?,
    onTap: ((Offset) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // 0 means "fit to view"; reset whenever the document changes shape.
    var scale by remember(imageWidth, imageHeight) { mutableFloatStateOf(0f) }
    var pan by remember(imageWidth, imageHeight) { mutableStateOf(Offset.Zero) }
    val tap by rememberUpdatedState(onTap)

    val image: ImageBitmap = remember(bitmap) {
        bitmap.setHasMipMap(true)
        bitmap.asImageBitmap()
    }
    val checker = remember { checkerBrush() }

    fun fit(w: Float, h: Float) = min(w / imageWidth, h / imageHeight) * 0.94f
    fun effective(w: Float, h: Float) = if (scale > 0f) scale else fit(w, h)

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(imageWidth, imageHeight) {
                detectTransformGestures { centroid, panChange, zoom, _ ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val s0 = effective(w, h)
                    val s1 = (s0 * zoom).coerceIn(fit(w, h) * 0.25f, 64f)
                    val c = centroid - Offset(w / 2, h / 2)
                    pan = c - (c - pan) * (s1 / s0) + panChange
                    scale = s1
                }
            }
            .pointerInput(imageWidth, imageHeight) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        if (scale > 0f && scale > fit(w, h) * 1.05f) {
                            scale = 0f
                            pan = Offset.Zero
                        } else {
                            val s0 = effective(w, h)
                            val s1 = maxOf(1f, fit(w, h) * 2.5f)
                            val c = at - Offset(w / 2, h / 2)
                            pan = c - (c - pan) * (s1 / s0)
                            scale = s1
                        }
                    },
                    onTap = { at ->
                        val handler = tap ?: return@detectTapGestures
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val s = effective(w, h)
                        val p = (at - Offset(w / 2, h / 2) - pan) / s + Offset(imageWidth / 2f, imageHeight / 2f)
                        if (p.x in 0f..imageWidth.toFloat() && p.y in 0f..imageHeight.toFloat()) handler(p)
                    },
                )
            },
    ) {
        val s = effective(size.width, size.height)
        val dw = imageWidth * s
        val dh = imageHeight * s
        val topLeft = Offset(size.width / 2 + pan.x - dw / 2, size.height / 2 + pan.y - dh / 2)

        drawRect(checker, topLeft, Size(dw, dh))
        val pixelsPerTexel = dw / bitmap.width
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
            dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
            // Show crisp pixels when zoomed in, smooth mipmapped sampling when zoomed out.
            filterQuality = if (pixelsPerTexel >= 2f) FilterQuality.None else FilterQuality.High,
        )
        drawRect(Color.Black.copy(alpha = 0.6f), topLeft, Size(dw, dh), style = Stroke(1f))

        marker?.let { m ->
            val c = topLeft + Offset(m.x * s, m.y * s)
            drawCircle(Color.Black.copy(alpha = 0.55f), radius = 15f, center = c, style = Stroke(5f))
            drawCircle(Gako.Gold, radius = 15f, center = c, style = Stroke(2.5f))
            drawLine(Gako.Gold, c - Offset(24f, 0f), c - Offset(8f, 0f), strokeWidth = 2.5f)
            drawLine(Gako.Gold, c + Offset(8f, 0f), c + Offset(24f, 0f), strokeWidth = 2.5f)
            drawLine(Gako.Gold, c - Offset(0f, 24f), c - Offset(0f, 8f), strokeWidth = 2.5f)
            drawLine(Gako.Gold, c + Offset(0f, 8f), c + Offset(0f, 24f), strokeWidth = 2.5f)
        }
    }
}

private fun checkerBrush(): ShaderBrush {
    val cell = 12
    val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
    val light = android.graphics.Color.rgb(0x99, 0x99, 0x99)
    val dark = android.graphics.Color.rgb(0x66, 0x66, 0x66)
    for (y in 0 until cell * 2) for (x in 0 until cell * 2) {
        bmp.setPixel(x, y, if ((x / cell + y / cell) % 2 == 0) light else dark)
    }
    return ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
