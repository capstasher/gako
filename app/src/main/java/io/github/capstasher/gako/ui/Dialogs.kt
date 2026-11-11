package io.github.capstasher.gako.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.asImageBitmap
import io.github.capstasher.gako.doc.Compositor
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.editor.Dialog
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.ui.theme.Gako
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DialogHost(vm: EditorViewModel, dialog: Dialog) {
    val close = { vm.dialog = null }
    val d = vm.doc
    when (dialog) {
        Dialog.NewImage -> NewImageDialog(vm, close)
        Dialog.CanvasSize -> if (d != null) CanvasSizeDialog(vm, d.width, d.height, close) else close()
        Dialog.RotateImage -> if (d != null) RotateDialog(vm, close) else close()
        Dialog.ScaleImage -> if (d != null) SizeDialog("Scale Image", d.width, d.height, close) { w, h -> vm.scaleImage(w, h) } else close()
        Dialog.ScaleLayer -> d?.active?.let { a -> SizeDialog("Scale Layer", a.width, a.height, close) { w, h -> vm.scaleLayer(w, h) } } ?: close()
        is Dialog.RenameLayer -> d?.layer(dialog.id)?.let { l ->
            TextDialog("Layer Name", l.name, close) { vm.renameLayer(l.id, it) }
        } ?: close()
        is Dialog.ModifySelection -> AmountDialog(dialog.kind.label.removeSuffix(" Selection"), close) {
            vm.modifySelection(dialog.kind, it)
        }
        Dialog.NewLayer -> NewLayerDialog(vm, close)
        Dialog.History -> HistoryDialog(vm, close)
        Dialog.NewGuide -> if (d != null) NewGuideDialog(vm, d.width, d.height, close) else close()
        Dialog.Channels -> if (d != null) ChannelsDialog(vm, close) else close()
        Dialog.AddMask -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Add Layer Mask") },
            text = {
                Column {
                    Text("Initialize the mask to", style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
                    EditorViewModel.MaskInit.entries.forEach { m ->
                        Text(m.label, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { close(); vm.addMask(m) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        is Dialog.PickColor -> ColorPickerDialog(
            if (dialog.foreground) "Foreground Colour" else "Background Colour",
            if (dialog.foreground) vm.fg else vm.bg,
            onDismiss = close,
        ) { c ->
            if (dialog.foreground) vm.setForeground(c) else vm.setBackground(c)
            close()
        }
    }
}

@Composable
private fun field(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gako.Gold, cursorColor = Gako.Gold, focusedLabelColor = Gako.Gold),
        modifier = modifier,
    )
}

/** Image > Transform > Arbitrary Rotation: an angle (clockwise), optionally cropping the empty corners away. */
@Composable
private fun RotateDialog(vm: EditorViewModel, close: () -> Unit) {
    var text by remember { mutableStateOf("0") }
    var crop by remember { mutableStateOf(true) }
    val angle = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it > -360f && it < 360f }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Arbitrary Rotation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field(text, "Angle (°, clockwise)", { text = it }, Modifier.fillMaxWidth(), number = false)
                Slider(value = (angle ?: 0f).coerceIn(-45f, 45f), onValueChange = { text = "%.1f".format(java.util.Locale.ROOT, it) },
                    valueRange = -45f..45f,
                    colors = SliderDefaults.colors(thumbColor = Gako.Gold, activeTrackColor = Gako.Gold, inactiveTrackColor = Gako.InkHigh))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Crop away the empty corners", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(crop, { crop = it }, colors = SwitchDefaults.colors(checkedThumbColor = Gako.Ink, checkedTrackColor = Gako.Gold))
                }
                Text("To level a photo along a line in it, use the Measure tool's Straighten.",
                    style = MaterialTheme.typography.bodySmall, color = Gako.Muted,
                    modifier = Modifier.clickable { close(); vm.selectTool(io.github.capstasher.gako.tools.Tool.Measure) })
            }
        },
        confirmButton = {
            TextButton(enabled = angle != null && angle != 0f, onClick = { close(); vm.rotateBy(angle!!, crop) }) { Text("Rotate") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

private fun parseSize(s: String) = s.trim().toIntOrNull()?.takeIf { it in 1..16384 }

@Composable
private fun NewImageDialog(vm: EditorViewModel, close: () -> Unit) {
    var w by remember { mutableStateOf("1920") }
    var h by remember { mutableStateOf("1080") }
    var fill by remember { mutableIntStateOf(0) }
    val presets = listOf("1080×1920" to (1080 to 1920), "1920×1080" to (1920 to 1080), "2048²" to (2048 to 2048),
        "A4 300 ppi" to (2480 to 3508), "4K" to (3840 to 2160), "1024²" to (1024 to 1024))
    val fills = listOf("White", "Black", "Transparent", "Foreground", "Background")
    val pw = parseSize(w)
    val ph = parseSize(h)
    AlertDialog(
        onDismissRequest = close,
        title = { Text("New Image") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presets.forEach { (label, size) ->
                        OptChip(label, w == size.first.toString() && h == size.second.toString()) {
                            w = size.first.toString(); h = size.second.toString()
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(w, "Width", { w = it }, Modifier.weight(1f))
                    field(h, "Height", { h = it }, Modifier.weight(1f))
                }
                Text("Fill with", style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fills.forEachIndexed { i, f -> OptChip(f, fill == i) { fill = i } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pw != null && ph != null, onClick = {
                val color = when (fill) {
                    0 -> android.graphics.Color.WHITE
                    1 -> android.graphics.Color.BLACK
                    2 -> null
                    3 -> vm.fg
                    else -> vm.bg
                }
                close()
                vm.newImage(pw!!, ph!!, color)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun SizeDialog(title: String, w0: Int, h0: Int, close: () -> Unit, onSet: (Int, Int) -> Unit) {
    var w by remember { mutableStateOf(w0.toString()) }
    var h by remember { mutableStateOf(h0.toString()) }
    var linked by remember { mutableStateOf(true) }
    val pw = parseSize(w)
    val ph = parseSize(h)
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    field(w, "Width", {
                        w = it
                        if (linked) it.toIntOrNull()?.let { nw -> h = (nw.toLong() * h0 / w0).toInt().coerceAtLeast(1).toString() }
                    }, Modifier.weight(1f))
                    IconButton(onClick = { linked = !linked }) {
                        Icon(if (linked) Icons.Outlined.Link else Icons.Outlined.LinkOff, "Keep proportions",
                            tint = if (linked) Gako.Gold else Gako.Muted)
                    }
                    field(h, "Height", {
                        h = it
                        if (linked) it.toIntOrNull()?.let { nh -> w = (nh.toLong() * w0 / h0).toInt().coerceAtLeast(1).toString() }
                    }, Modifier.weight(1f))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(25, 50, 75, 150, 200).forEach { p ->
                        OptChip("$p%", false) {
                            w = (w0 * p / 100).coerceAtLeast(1).toString(); h = (h0 * p / 100).coerceAtLeast(1).toString()
                        }
                    }
                }
                Text("Currently $w0 × $h0 px", style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
            }
        },
        confirmButton = {
            TextButton(enabled = pw != null && ph != null && pw.toLong() * ph <= EditorViewModel.MAX_PIXELS,
                onClick = { close(); onSet(pw!!, ph!!) }) { Text("Scale") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun CanvasSizeDialog(vm: EditorViewModel, w0: Int, h0: Int, close: () -> Unit) {
    var w by remember { mutableStateOf(w0.toString()) }
    var h by remember { mutableStateOf(h0.toString()) }
    var anchor by remember { mutableIntStateOf(4) }   // 0..8, centre
    var resize by remember { mutableStateOf(true) }
    val pw = parseSize(w)
    val ph = parseSize(h)
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Canvas Size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(w, "Width", { w = it }, Modifier.weight(1f))
                    field(h, "Height", { h = it }, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (r in 0..2) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in 0..2) {
                                val i = r * 3 + c
                                Box(Modifier.size(26.dp)
                                    .background(if (anchor == i) Gako.Gold else Gako.Muted.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                                    .border(1.dp, Gako.GoldDim, RoundedCornerShape(3.dp))
                                    .clickable { anchor = i })
                            }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Text("Where the image sits on the new canvas", style = MaterialTheme.typography.bodySmall, color = Gako.Muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Resize full-canvas layers", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(resize, { resize = it }, colors = SwitchDefaults.colors(checkedThumbColor = Gako.Ink, checkedTrackColor = Gako.Gold))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pw != null && ph != null && pw.toLong() * ph <= EditorViewModel.MAX_PIXELS, onClick = {
                val nw = pw!!; val nh = ph!!
                val dx = when (anchor % 3) { 0 -> 0; 1 -> (nw - w0) / 2; else -> nw - w0 }
                val dy = when (anchor / 3) { 0 -> 0; 1 -> (nh - h0) / 2; else -> nh - h0 }
                close()
                vm.canvasSize(nw, nh, dx, dy, resize)
            }) { Text("Resize") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun TextDialog(title: String, initial: String, close: () -> Unit, onSet: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = { field(t, "Name", { t = it }, Modifier.fillMaxWidth(), number = false) },
        confirmButton = { TextButton(enabled = t.isNotBlank(), onClick = { close(); onSet(t) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun AmountDialog(title: String, close: () -> Unit, onSet: (Float) -> Unit) {
    var t by remember { mutableStateOf("10") }
    val v = t.replace(',', '.').toFloatOrNull()?.takeIf { it > 0 && it <= 2000 }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = { field(t, "Pixels", { t = it }, Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = v != null, onClick = { close(); onSet(v!!) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

/**
 * GIMP's Channels dialog, for saved selections: each channel's thumbnail and name; tap one to make it
 * the selection, or add, subtract, intersect, delete. Channels from GIMP files show here too.
 */
@Composable
private fun ChannelsDialog(vm: EditorViewModel, close: () -> Unit) {
    val d = vm.doc ?: return
    val channels = d.gimp.channels
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Channels") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (channels.isEmpty()) Text("No channels yet. Select > Save to Channel keeps the selection here; " +
                    "channels in GIMP files show here too.", style = MaterialTheme.typography.bodyMedium, color = Gako.Muted)
                channels.forEachIndexed { i, ch ->
                    var more by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { close(); vm.channelToSelection(i, io.github.capstasher.gako.doc.SelectMode.Replace) }) {
                        val thumb = remember(ch.bitmap) { Compositor.thumbnail(ch.bitmap, 96).asImageBitmap() }
                        androidx.compose.foundation.Image(thumb, null, Modifier.size(40.dp).border(1.dp, Gako.InkHigh))
                        Text(ch.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 10.dp),
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Box {
                            TextButton(onClick = { more = true }) { Text("⋯", color = Gako.Gold) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                listOf(io.github.capstasher.gako.doc.SelectMode.Add to "Add to selection",
                                    io.github.capstasher.gako.doc.SelectMode.Subtract to "Subtract from selection",
                                    io.github.capstasher.gako.doc.SelectMode.Intersect to "Intersect with selection").forEach { (m, label) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { more = false; close(); vm.channelToSelection(i, m) })
                                }
                                DropdownMenuItem(text = { Text("Delete channel") }, onClick = { more = false; vm.deleteChannel(i) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = d.selection != null, onClick = { vm.saveSelectionToChannel() }) { Text("Save selection") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

/** Image > Guides > New Guide: across or down the image, at a position in pixels or percent. */
@Composable
private fun NewGuideDialog(vm: EditorViewModel, w: Int, h: Int, close: () -> Unit) {
    var horizontal by remember { mutableStateOf(true) }
    var percent by remember { mutableStateOf(true) }
    var t by remember { mutableStateOf("50") }
    val size = if (horizontal) h else w
    val v = t.trim().replace(',', '.').toFloatOrNull()
    val px = v?.let { if (percent) Math.round(it * size / 100f) else Math.round(it) }?.takeIf { it in 0..size }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("New Guide") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(horizontal, { horizontal = true }, { Text("Horizontal") })
                    FilterChip(!horizontal, { horizontal = false }, { Text("Vertical") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(percent, { if (!percent && v != null) t = "%.1f".format(java.util.Locale.ROOT, v * 100f / size); percent = true }, { Text("%") })
                    FilterChip(!percent, { if (percent && v != null) t = Math.round(v * size / 100f).toString(); percent = false }, { Text("px") })
                }
                field(t, if (percent) "Position (% of the ${if (horizontal) "height" else "width"})" else "Position (pixels from the ${if (horizontal) "top" else "left"})",
                    { t = it }, Modifier.fillMaxWidth(), number = false)
            }
        },
        confirmButton = { TextButton(enabled = px != null, onClick = { close(); vm.addGuide(horizontal, px!!) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

@Composable
private fun NewLayerDialog(vm: EditorViewModel, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("New Layer") },
        text = {
            Column {
                Text("Fill with", style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
                EditorViewModel.NewFill.entries.forEach { f ->
                    Text(f.label, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { close(); vm.newLayer(f) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- colour

private val palette = listOf(
    0xFF000000, 0xFF404040, 0xFF808080, 0xFFC0C0C0, 0xFFFFFFFF, 0xFF7F0000, 0xFFFF0000, 0xFFFF7F00,
    0xFFFFFF00, 0xFF7FFF00, 0xFF00FF00, 0xFF00FF7F, 0xFF00FFFF, 0xFF007FFF, 0xFF0000FF, 0xFF7F00FF,
    0xFFFF00FF, 0xFFFF007F, 0xFF5A1520, 0xFFD2A84E, 0xFFEDE3CF, 0xFF8B5A2B, 0xFFFFC0CB, 0xFF2F4F4F,
).map { it.toInt() }

private val recentColors = ArrayDeque<Int>()
private var recentLoaded = false

/** Recent colours live in preferences, so they survive restarts. */
private fun loadRecent(context: android.content.Context) {
    if (recentLoaded) return
    recentLoaded = true
    context.getSharedPreferences("tools", 0).getString("recentColors", "")!!.split(',')
        .mapNotNull { it.toLongOrNull(16)?.toInt() }.take(12).forEach { recentColors.addLast(it) }
}

private fun rememberRecent(context: android.content.Context, c: Int) {
    recentColors.remove(c)
    recentColors.addFirst(c)
    while (recentColors.size > 12) recentColors.removeLast()
    context.getSharedPreferences("tools", 0).edit()
        .putString("recentColors", recentColors.joinToString(",") { Integer.toHexString(it) }).apply()
}

/** Hue bar, saturation/value square, hex entry, a palette and recent colours. */
@Composable
fun ColorPickerDialog(title: String, initial: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val hsv0 = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    var hex by remember { mutableStateOf(hexOf(initial)) }
    val context = androidx.compose.ui.platform.LocalContext.current
    remember { loadRecent(context) }
    fun setColor(c: Int) {
        val f = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
        hue = f[0]; sat = f[1]; value = f[2]; hex = hexOf(c)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Saturation (x) × value (y) for the current hue.
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1.6f).border(1.dp, Gako.GoldDim)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun at(p: Offset) {
                                    sat = (p.x / size.width).coerceIn(0f, 1f)
                                    value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                                    hex = hexOf(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
                                }
                                at(down.position)
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { at(it.position); it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                        },
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val c = Offset(sat * size.width, (1 - value) * size.height)
                    drawCircle(Color.Black, 9.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                }
                Canvas(
                    Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun at(p: Offset) {
                                hue = (p.x / size.width).coerceIn(0f, 1f) * 360f
                                hex = hexOf(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
                            }
                            at(down.position)
                            do {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { at(it.position); it.consume() }
                            } while (ev.changes.any { it.pressed })
                        }
                    },
                ) {
                    drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
                    val x = hue / 360f * size.width
                    drawRect(Color.White, Offset(x - 2.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 56.dp, height = 40.dp).background(Color(argb), RoundedCornerShape(6.dp))
                        .border(1.dp, Gako.GoldDim, RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    field(hex, "Hex", { t ->
                        hex = t
                        parseHex(t)?.let { c ->
                            val f = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
                            hue = f[0]; sat = f[1]; value = f[2]
                        }
                    }, Modifier.weight(1f), number = false)
                }
                SwatchRow(palette, ::setColor)
                if (recentColors.isNotEmpty()) SwatchRow(recentColors.toList(), ::setColor)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                rememberRecent(context, argb)
                onSet(argb)
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwatchRow(colors: List<Int>, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { c ->
            Box(Modifier.size(28.dp).background(Color(c), CircleShape).border(1.dp, Gako.InkHigh, CircleShape).clickable { onPick(c) })
        }
    }
}

private fun hexOf(c: Int) = String.format(Locale.US, "#%06x", c and 0xFFFFFF)

private fun parseHex(t: String): Int? {
    val h = t.trim().removePrefix("#")
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
}

@Suppress("unused")
private fun Float.r() = roundToInt()

/** GIMP's Undo History: every step, the current one highlighted; tap one to go back (or forward) to it. */
@Composable
private fun HistoryDialog(vm: EditorViewModel, close: () -> Unit) {
    val labels = vm.historyLabels
    val pos = vm.historyPosition
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Undo History") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                item {
                    HistoryRow("Opened image", pos == 0, done = true) { vm.jumpToHistory(0) }
                }
                items(labels.size) { i ->
                    HistoryRow(labels[i], pos == i + 1, done = i < pos) { vm.jumpToHistory(i + 1) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

@Composable
private fun HistoryRow(label: String, current: Boolean, done: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = when { current -> Gako.Gold; done -> Gako.Parchment; else -> Gako.Muted.copy(alpha = 0.6f) },
        modifier = Modifier.fillMaxWidth()
            .background(if (current) Gako.Oxblood.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
    )
}
