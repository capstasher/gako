package io.github.capstasher.gako.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.editor.Channel
import io.github.capstasher.gako.editor.CurvesSession
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.editor.LevelsChannel
import io.github.capstasher.gako.ui.theme.Gako
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

private fun channelColor(ch: Channel) = when (ch) {
    Channel.Value -> Gako.Parchment
    Channel.Red -> Color(0xFFE05555)
    Channel.Green -> Color(0xFF55C060)
    Channel.Blue -> Color(0xFF5588E0)
    Channel.Alpha -> Gako.Muted
}

/** Colours > Curves and Colours > Levels. */
@Composable
fun CurvesPanel(c: CurvesSession, vm: EditorViewModel, modifier: Modifier = Modifier) {
    Column(modifier.background(Gako.InkRaised)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::cancelCurves) { Icon(Icons.Outlined.Close, "Cancel", tint = Gako.Muted) }
            Text(if (c.levels) "Levels" else "Curves", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (c.levels) IconButton(onClick = { c.autoLevels(); vm.updateCurvesPreview() }) {
                Icon(Icons.Outlined.AutoFixHigh, "Auto", tint = Gako.Muted)
            }
            IconButton(onClick = { c.reset(); vm.updateCurvesPreview() }) { Icon(Icons.Outlined.RestartAlt, "Reset channel", tint = Gako.Muted) }
            Box(
                Modifier.size(48.dp).pointerInput(Unit) {
                    detectTapGestures(onPress = { vm.comparing = true; tryAwaitRelease(); vm.comparing = false })
                },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Compare, "Hold to compare", tint = if (vm.comparing) Gako.Gold else Gako.Muted) }
            FilledIconButton(
                onClick = vm::applyCurves,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Gako.Gold, contentColor = Gako.Ink),
            ) { Icon(Icons.Outlined.Check, "Apply") }
        }
        HorizontalDivider(color = Gako.GoldDim.copy(alpha = 0.35f))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Channel.entries.forEach { ch -> OptChip(ch.label, c.channel == ch) { c.channel = ch } }
            }
            if (c.levels) LevelsControls(c, vm) else CurveEditor(c, vm)
        }
    }
}

@Composable
private fun CurveEditor(c: CurvesSession, vm: EditorViewModel) {
    val ch = c.channel
    val pts = c.points[ch]!!
    val lut = c.lut(ch)
    val hist = c.histogram.of(ch)
    val color = channelColor(ch)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().height(230.dp).border(1.dp, Gako.InkHigh)
                .pointerInput(ch) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        fun toCurve(p: Offset) = (p.x / w).coerceIn(0f, 1f) to (1f - p.y / h).coerceIn(0f, 1f)
                        val current = c.points[ch]!!.sortedBy { it.first }.toMutableList()
                        val tol = 28.dp.toPx()
                        var index = current.indexOfFirst { (x, y) -> hypot(x * w - down.position.x, (1 - y) * h - down.position.y) < tol }
                        if (index < 0) {
                            val p = toCurve(down.position)
                            current += p
                            current.sortBy { it.first }
                            index = current.indexOf(p)
                        }
                        c.points = c.points + (ch to current.toList())
                        vm.updateCurvesPreview()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            val list = c.points[ch]!!.toMutableList()
                            if (index !in list.indices) break
                            val lo = if (index > 0) list[index - 1].first + 0.01f else 0f
                            val hi = if (index < list.lastIndex) list[index + 1].first - 0.01f else 1f
                            val raw = change.position
                            val outside = raw.y < -h * 0.12f || raw.y > h * 1.12f
                            if (outside && list.size > 2) {
                                list.removeAt(index)
                                c.points = c.points + (ch to list)
                                vm.updateCurvesPreview()
                                break
                            }
                            val (x, y) = toCurve(raw)
                            list[index] = x.coerceIn(lo, maxOf(lo, hi)) to y
                            c.points = c.points + (ch to list)
                            vm.updateCurvesPreview()
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val max = (hist.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
            val bw = w / 256f
            for (i in 0 until 256) {
                val v = ln(1f + hist[i]) / ln(1f + max)
                drawRect(Gako.InkHigh, Offset(i * bw, h * (1 - v)), Size(bw + 0.5f, h * v))
            }
            for (k in 1..3) {
                drawLine(Gako.InkHigh, Offset(w * k / 4, 0f), Offset(w * k / 4, h))
                drawLine(Gako.InkHigh, Offset(0f, h * k / 4), Offset(w, h * k / 4))
            }
            drawLine(Gako.Muted.copy(alpha = 0.3f), Offset(0f, h), Offset(w, 0f))
            val path = Path()
            for (i in 0 until 256) {
                val x = i / 255f * w
                val y = h - lut[i] / 255f * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(2.dp.toPx()))
            pts.forEach { (x, y) ->
                drawCircle(Gako.Ink, 7.dp.toPx(), Offset(x * w, (1 - y) * h))
                drawCircle(color, 7.dp.toPx(), Offset(x * w, (1 - y) * h), style = Stroke(2.dp.toPx()))
            }
        }
    }
    Text("Tap to add a point, drag to move it, drag it off the top or bottom to remove it.",
        style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelsControls(c: CurvesSession, vm: EditorViewModel) {
    val ch = c.channel
    val l = c.levelValues[ch]!!
    val hist = c.histogram.of(ch)
    val color = channelColor(ch)
    fun set(n: LevelsChannel) {
        c.levelValues = c.levelValues + (ch to n)
        vm.updateCurvesPreview()
    }
    val sliderColors = SliderDefaults.colors(thumbColor = Gako.Gold, activeTrackColor = color, inactiveTrackColor = Gako.InkHigh)
    Canvas(Modifier.fillMaxWidth().height(90.dp).border(1.dp, Gako.InkHigh)) {
        val max = (hist.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val bw = size.width / 256f
        for (i in 0 until 256) {
            val v = ln(1f + hist[i]) / ln(1f + max)
            drawRect(color.copy(alpha = 0.6f), Offset(i * bw, size.height * (1 - v)), Size(bw + 0.5f, size.height * v))
        }
        drawLine(Gako.Gold, Offset(l.inLow * size.width, 0f), Offset(l.inLow * size.width, size.height), 2f)
        drawLine(Gako.Gold, Offset(l.inHigh * size.width, 0f), Offset(l.inHigh * size.width, size.height), 2f)
    }
    Text("Input levels", style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
    RangeSlider(
        value = l.inLow..l.inHigh,
        onValueChange = { r -> if (r.endInclusive - r.start > 0.01f) set(l.copy(inLow = r.start, inHigh = r.endInclusive)) },
        colors = sliderColors,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Gamma", Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        // Log scale, 0.1 … 10, centre = 1.0
        Slider(
            value = (ln(l.gamma) / ln(10f) + 1f) / 2f,
            onValueChange = { t -> set(l.copy(gamma = Math.pow(10.0, (t * 2 - 1).toDouble()).toFloat())) },
            modifier = Modifier.weight(1f), colors = sliderColors,
        )
        Text(String.format(java.util.Locale.US, "%.2f", l.gamma), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = Gako.Gold, modifier = Modifier.width(48.dp))
    }
    Text("Output levels", style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
    RangeSlider(
        value = l.outLow..l.outHigh,
        onValueChange = { r -> set(l.copy(outLow = r.start, outHigh = r.endInclusive)) },
        colors = sliderColors,
    )
    Row {
        Text("In ${(l.inLow * 255).roundToInt()} – ${(l.inHigh * 255).roundToInt()}", style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
        Spacer(Modifier.weight(1f))
        Text("Out ${(l.outLow * 255).roundToInt()} – ${(l.outHigh * 255).roundToInt()}", style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
    }
    @Suppress("unused") val unused = abs(0)
}
