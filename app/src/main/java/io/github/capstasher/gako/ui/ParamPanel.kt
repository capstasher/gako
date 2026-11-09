package io.github.capstasher.gako.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.engine.Param
import io.github.capstasher.gako.ui.theme.EngravedLabel
import io.github.capstasher.gako.ui.theme.Gako
import java.util.Locale
import kotlin.random.Random

/** Auto-generated controls for whichever GEGL operation is being tuned. */
@Composable
fun ParamPanel(session: EditorViewModel.FilterSession, vm: EditorViewModel, modifier: Modifier = Modifier) {
    Column(modifier.background(Gako.InkRaised)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::cancelSession) {
                Icon(Icons.Outlined.Close, contentDescription = "Cancel", tint = Gako.Muted)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(session.op.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (session.working) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(Modifier.size(14.dp), color = Gako.Gold, strokeWidth = 2.dp)
                }
            }
            IconButton(onClick = vm::resetParams) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = "Reset", tint = Gako.Muted)
            }
            // Hold to see the original.
            Box(
                Modifier
                    .size(48.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            vm.comparing = true
                            tryAwaitRelease()
                            vm.comparing = false
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Compare, contentDescription = "Hold to compare",
                    tint = if (vm.comparing) Gako.Gold else Gako.Muted)
            }
            Spacer(Modifier.width(4.dp))
            FilledIconButton(
                onClick = vm::applySession,
                enabled = vm.busy == null,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Gako.Gold, contentColor = Gako.Ink),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = "Apply")
            }
        }
        HorizontalDivider(color = Gako.GoldDim.copy(alpha = 0.35f))
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            session.op.params.forEach { p ->
                ParamControl(p, session.values[p.name]) { vm.setParam(p.name, it) }
            }
            session.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ParamControl(p: Param, value: Any?, onChange: (Any?) -> Unit) {
    when (p) {
        is Param.Number -> NumberControl(p, (value as? Number)?.toDouble() ?: p.default, onChange)
        is Param.Toggle -> Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = value as? Boolean ?: p.default,
                onCheckedChange = { onChange(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Gako.Ink, checkedTrackColor = Gako.Gold),
            )
        }
        is Param.Choice -> Column(Modifier.padding(vertical = 4.dp)) {
            Text(p.label, style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val selected = value as? String ?: p.default
                p.options.forEach { (nick, label) ->
                    FilterChip(
                        selected = nick == selected,
                        onClick = { onChange(nick) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Gako.Oxblood,
                            selectedLabelColor = Gako.Parchment,
                        ),
                    )
                }
            }
        }
        is Param.Colour -> ColourControl(p, value as? String ?: p.default, onChange)
        is Param.Text -> OutlinedTextField(
            value = value as? String ?: p.default ?: "",
            onValueChange = { onChange(it) },
            label = { Text(p.label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

private fun formatNumber(p: Param.Number, v: Double): String =
    if (p.isInteger) v.toLong().toString()
    else String.format(Locale.UK, "%.${p.digits.coerceIn(0, 4)}f", v)

@Composable
private fun NumberControl(p: Param.Number, value: Double, onChange: (Any?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.isSeed) {
                IconButton(onClick = { onChange(Random.nextInt(0, Int.MAX_VALUE)) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Casino, contentDescription = "New seed", tint = Gako.GoldDim)
                }
            }
            Text(
                formatNumber(p, value) + unitSuffix(p.unit),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = Gako.Gold,
                modifier = Modifier
                    .clickable { editing = true }
                    .border(1.dp, Gako.InkHigh, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (!p.isSeed) {
            Slider(
                value = p.toSlider(value),
                onValueChange = { t ->
                    val v = p.fromSlider(t)
                    onChange(if (p.isInteger) v.toInt() else v)
                },
                colors = SliderDefaults.colors(
                    thumbColor = Gako.Gold,
                    activeTrackColor = Gako.Gold,
                    inactiveTrackColor = Gako.InkHigh,
                ),
            )
        }
    }
    if (editing) {
        NumberDialog(p, value, onDismiss = { editing = false }) { v ->
            editing = false
            onChange(if (p.isInteger) v.toInt() else v)
        }
    }
}

private fun unitSuffix(unit: String?) = when (unit) {
    "pixel-distance", "pixel-coordinate" -> " px"
    "degree" -> "°"
    else -> ""
}

@Composable
private fun NumberDialog(p: Param.Number, value: Double, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(formatNumber(p, value)) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.label) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(
                    "Range ${formatNumber(p, maxOf(p.min, -1e9))} to ${formatNumber(p, minOf(p.max, 1e9))}",
                    style = MaterialTheme.typography.labelSmall, color = Gako.Muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let { onSet(p.clamp(it)) } }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- colour

private fun parseHex(hex: String): Int {
    val h = hex.removePrefix("#")
    val v = h.toLongOrNull(16) ?: return android.graphics.Color.BLACK
    return when (h.length) {
        8 -> { // rrggbbaa -> aarrggbb
            val rgb = (v shr 8).toInt() and 0xFFFFFF
            val a = (v and 0xFF).toInt()
            (a shl 24) or rgb
        }
        6 -> (0xFF shl 24) or v.toInt()
        else -> android.graphics.Color.BLACK
    }
}

private fun toHex(argb: Int): String = String.format(
    Locale.UK, "#%02x%02x%02x%02x",
    (argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF,
)

@Composable
private fun ColourControl(p: Param.Colour, hex: String, onChange: (Any?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val argb = parseHex(hex)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box(
            Modifier
                .size(width = 56.dp, height = 32.dp)
                .background(Color(argb), RoundedCornerShape(6.dp))
                .border(1.dp, Gako.GoldDim, RoundedCornerShape(6.dp))
                .clickable { editing = true },
        )
    }
    if (editing) {
        ColourDialog(p.label, argb, onDismiss = { editing = false }) {
            editing = false
            onChange(toHex(it))
        }
    }
}

@Composable
private fun ColourDialog(title: String, initial: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    var a by remember { mutableFloatStateOf(((initial ushr 24) and 0xFF) / 255f) }
    val argb = android.graphics.Color.HSVToColor((a * 255).toInt(), floatArrayOf(h, s, v))

    @Composable
    fun channel(label: String, value: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
            Slider(value, set, valueRange = range, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Gako.Gold, activeTrackColor = Gako.Gold))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(40.dp)
                        .background(Color(argb), RoundedCornerShape(8.dp))
                        .border(1.dp, Gako.GoldDim, RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.height(8.dp))
                channel("H", h, 0f..360f) { h = it }
                channel("S", s, 0f..1f) { s = it }
                channel("V", v, 0f..1f) { v = it }
                channel("A", a, 0f..1f) { a = it }
                Text(toHex(argb), style = EngravedLabel, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSet(argb) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
