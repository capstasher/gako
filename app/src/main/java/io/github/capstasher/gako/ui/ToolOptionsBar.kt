package io.github.capstasher.gako.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.doc.SelectMode
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.tools.CropAspect
import io.github.capstasher.gako.tools.FillArea
import io.github.capstasher.gako.tools.GradientColors
import io.github.capstasher.gako.tools.GradientShape
import io.github.capstasher.gako.tools.Tool
import io.github.capstasher.gako.tools.ToolOptions
import io.github.capstasher.gako.ui.theme.Gako
import kotlin.math.pow
import kotlin.math.roundToInt

/** A numeric tool option: shown as a chip; tapping it opens a slider above the chips. */
private class NumOption(
    val key: String,
    val label: String,
    val value: Float,
    val min: Float,
    val max: Float,
    val gamma: Float = 1f,
    val format: (Float) -> String,
    val set: (Float) -> Unit,
) {
    fun toSlider() = ((value - min) / (max - min)).coerceIn(0f, 1f).pow(1f / gamma)
    fun fromSlider(t: Float) = min + (max - min) * t.coerceIn(0f, 1f).pow(gamma)
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

/** GIMP's Tool Options, as one thumb-high strip above the menu bar. */
@Composable
fun ToolOptionsBar(vm: EditorViewModel) {
    val tool = vm.tool
    val o = vm.options
    var expanded by remember(tool) { mutableStateOf<String?>(null) }
    fun set(f: (ToolOptions) -> ToolOptions) = vm.updateOptions(f(vm.options))

    val size = NumOption("size", "Size", o.size, 1f, 1000f, 2.6f, { "${it.roundToInt()} px" }) { v -> set { it.copy(size = v.coerceAtLeast(1f)) } }
    val opacity = NumOption("opacity", "Opacity", o.opacity, 0f, 1f, 1f, ::pct) { v -> set { it.copy(opacity = v) } }
    val hardness = NumOption("hardness", "Hardness", o.hardness, 0f, 1f, 1f, ::pct) { v -> set { it.copy(hardness = v) } }
    val rate = NumOption("rate", "Rate", o.airRate, 0.02f, 1f, 1f, ::pct) { v -> set { it.copy(airRate = v) } }
    val exposure = NumOption("exposure", "Exposure", o.exposure, 0.05f, 1f, 1f, ::pct) { v -> set { it.copy(exposure = v) } }
    val feather = NumOption("feather", "Feather", o.feather, 0f, 200f, 2f, { "${it.roundToInt()} px" }) { v -> set { it.copy(feather = v) } }
    val strength = NumOption("strength", if (tool == Tool.Smudge) "Rate" else "Strength", o.strength, 0f, 1f, 1f, ::pct) { v -> set { it.copy(strength = v) } }
    val corners = NumOption("corners", "Corners", o.cornerRadius, 0f, 500f, 2f, { "${it.roundToInt()} px" }) { v -> set { it.copy(cornerRadius = v) } }
    val angle = NumOption("angle", "Angle", o.brushAngle, -90f, 90f, 1f, { "${it.roundToInt()}°" }) { v -> set { it.copy(brushAngle = v) } }
    val smooth = NumOption("smooth", "Smooth", o.smoothing, 0f, 1f, 1f, ::pct) { v -> set { it.copy(smoothing = v) } }
    val threshold = NumOption("threshold", "Threshold", o.threshold.toFloat(), 0f, 255f, 1f, { "${it.roundToInt()}" }) { v -> set { it.copy(threshold = v.roundToInt()) } }

    val shaped = tool.paints && tool != Tool.Smudge && tool != Tool.Blur && tool != Tool.Heal && tool != Tool.Clone &&
        o.brushShape != io.github.capstasher.gako.tools.BrushShape.Round
    val base: List<NumOption> = when (tool) {
        Tool.Paintbrush, Tool.Eraser, Tool.Clone, Tool.Heal, Tool.FilterBrush -> listOf(size, opacity, hardness)
        Tool.Pencil -> listOf(size, opacity)
        Tool.Airbrush -> listOf(size, opacity, rate)
        Tool.DodgeBurn -> listOf(size, exposure, hardness)
        Tool.Smudge, Tool.Blur, Tool.Warp -> listOf(size, strength, hardness)
        Tool.RectSelect -> listOf(feather, corners)
        Tool.SelectBrush -> listOf(size, hardness, feather)
        Tool.EllipseSelect, Tool.FreeSelect -> listOf(feather)
        Tool.FuzzySelect, Tool.ColorSelect -> listOf(threshold, feather)
        Tool.BucketFill -> if (o.fillArea == FillArea.Similar) listOf(threshold, opacity) else listOf(opacity)
        Tool.Gradient -> listOf(opacity)
        else -> emptyList()
    }
    val numbers = base + (if (shaped) listOf(angle) else emptyList()) + (if (tool.paints) listOf(smooth) else emptyList())

    // While the size slider is open, show the brush's actual size on the canvas.
    val sizeOpen = expanded == "size"
    androidx.compose.runtime.DisposableEffect(sizeOpen) {
        vm.sizePreview = sizeOpen
        onDispose { vm.sizePreview = false }
    }
    Column(Modifier.fillMaxWidth().background(Gako.InkRaised)) {
        numbers.firstOrNull { it.key == expanded }?.let { n ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n.label, style = MaterialTheme.typography.labelMedium, color = Gako.Muted, modifier = Modifier.width(72.dp))
                Slider(
                    value = n.toSlider(),
                    onValueChange = { n.set(n.fromSlider(it)) },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Gako.Gold, activeTrackColor = Gako.Gold, inactiveTrackColor = Gako.InkHigh),
                )
                Text(n.format(n.value), style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = Gako.Gold, modifier = Modifier.width(64.dp).padding(start = 8.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (tool.paints) {
                var presetsOpen by remember { mutableStateOf(false) }
                OptChip("★ Brushes", presetsOpen) { presetsOpen = true }
                if (presetsOpen) PresetsDialog(vm) { presetsOpen = false }
            }
            numbers.forEach { n ->
                OptChip("${n.label} ${n.format(n.value)}", expanded == n.key) {
                    expanded = if (expanded == n.key) null else n.key
                }
            }
            if (tool.paints && tool != Tool.Smudge && tool != Tool.Blur && tool != Tool.Heal && tool != Tool.Clone && tool != Tool.FilterBrush) {
                io.github.capstasher.gako.tools.BrushShape.entries.forEach { b ->
                    OptChip(b.label, o.brushShape == b) { set { it.copy(brushShape = b) } }
                }
            }
            if (tool.paints && tool != Tool.Smudge && tool != Tool.Clone && tool != Tool.Heal) {
                val all = io.github.capstasher.gako.tools.Symmetry.entries
                OptChip(o.symmetry.label, o.symmetry != io.github.capstasher.gako.tools.Symmetry.Off) {
                    set { it.copy(symmetry = all[(all.indexOf(it.symmetry) + 1) % all.size]) }
                }
            }
            when (tool) {
                Tool.RectSelect, Tool.EllipseSelect, Tool.FreeSelect, Tool.FuzzySelect, Tool.ColorSelect, Tool.SelectBrush -> {
                    SelectMode.entries.forEach { m ->
                        OptChip(m.label, o.selectMode == m) { set { it.copy(selectMode = m) } }
                    }
                    if (tool == Tool.FuzzySelect || tool == Tool.ColorSelect) {
                        OptChip("Sample merged", o.sampleMerged) { set { it.copy(sampleMerged = !it.sampleMerged) } }
                    }
                    if (tool == Tool.FuzzySelect) OptChip("Diagonal", o.diagonal) { set { it.copy(diagonal = !it.diagonal) } }
                }
                Tool.BucketFill -> {
                    FillArea.entries.forEach { a -> OptChip(a.label, o.fillArea == a) { set { it.copy(fillArea = a) } } }
                    if (o.fillArea == FillArea.Similar) {
                        OptChip("Sample merged", o.sampleMerged) { set { it.copy(sampleMerged = !it.sampleMerged) } }
                        OptChip("Diagonal", o.diagonal) { set { it.copy(diagonal = !it.diagonal) } }
                    }
                }
                Tool.Gradient -> {
                    GradientShape.entries.forEach { g -> OptChip(g.label, o.gradientShape == g) { set { it.copy(gradientShape = g) } } }
                    GradientColors.entries.forEach { g -> OptChip(g.label, o.gradientColors == g) { set { it.copy(gradientColors = g) } } }
                }
                Tool.ColorPicker -> {
                    OptChip("Sample merged", o.sampleMerged) { set { it.copy(sampleMerged = !it.sampleMerged) } }
                    val c = vm.fg
                    val hex = String.format(java.util.Locale.US, "#%06X", c and 0xFFFFFF)
                    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(28.dp).background(androidx.compose.ui.graphics.Color(c), RoundedCornerShape(6.dp))
                            .border(1.dp, Gako.GoldDim, RoundedCornerShape(6.dp)),
                    )
                    Text(
                        "$hex  ${(c shr 16) and 0xFF}, ${(c shr 8) and 0xFF}, ${c and 0xFF}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = Gako.Parchment,
                        modifier = Modifier.clickable {
                            clip.setText(androidx.compose.ui.text.AnnotatedString(hex))
                            vm.say("Copied $hex")
                        }.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
                Tool.FilterBrush -> Text(vm.filterBrush?.let { "Painting: ${it.title}" } ?: "Open a filter, then tap its brush button",
                    style = MaterialTheme.typography.labelMedium, color = Gako.Gold)
                Tool.Paths -> {
                    val p = vm.input.path
                    var listOpen by remember { mutableStateOf(false) }
                    val ap = vm.activePath
                    val label = when {
                        ap == null -> "New path"
                        ap.strokes.size > 1 || vm.strokeIndex >= ap.strokes.size ->
                            "${ap.name} · ${if (vm.strokeIndex >= ap.strokes.size) "new stroke" else "stroke ${vm.strokeIndex + 1}"}"
                        else -> ap.name
                    }
                    OptChip("$label ▾", listOpen) { listOpen = true }
                    if (listOpen) PathsDialog(vm) { listOpen = false }
                    OptChip("To selection", false) { vm.input.pathToSelection() }
                    OptChip("Stroke ${o.size.roundToInt()} px", false) { vm.input.strokePath() }
                    OptChip("Fill", false) { vm.input.fillPath() }
                    OptChip(if (p.closed) "Open" else "Close", p.closed) { p.toggleClosed() }
                    OptChip("Undo point", false) { p.undoPoint() }
                    OptChip("Clear", false) { p.clear() }
                }
                Tool.DodgeBurn -> {
                    OptChip("Dodge", !o.burn) { set { it.copy(burn = false) } }
                    OptChip("Burn", o.burn) { set { it.copy(burn = true) } }
                }
                Tool.Clone, Tool.Heal -> {
                    OptChip(if (vm.input.cloneSource == null) "Set source…" else "New source", vm.input.settingCloneSource) {
                        vm.input.settingCloneSource = !vm.input.settingCloneSource
                    }
                    OptChip("Aligned", o.cloneAligned) { set { it.copy(cloneAligned = !it.cloneAligned) } }
                }
                Tool.Crop -> {
                    ApplyButton { vm.input.applyCrop() }
                    IconButton(onClick = { vm.input.startCrop() }) { Icon(Icons.Outlined.RestartAlt, "Reset", tint = Gako.Muted) }
                    CropAspect.entries.forEach { a -> OptChip(a.label, o.cropAspect == a) { set { it.copy(cropAspect = a) } } }
                }
                Tool.Warp -> io.github.capstasher.gako.tools.WarpMode.entries.forEach { m ->
                    OptChip(m.label, o.warpMode == m) { set { it.copy(warpMode = m) } }
                }
                Tool.Transform -> {
                    val d = vm.doc
                    val t = d?.let { vm.input.transformFor(it) }
                    if (t != null) {
                        ApplyButton { vm.input.applyTransform() }
                        IconButton(onClick = { vm.input.startTransform() }) { Icon(Icons.Outlined.RestartAlt, "Reset", tint = Gako.Muted) }
                        OptChip("Perspective", t.perspective) {
                            vm.input.setTransform(if (t.perspective) t.copy(quad = null) else t.copy(quad = t.cornerArray()))
                        }
                        if (!t.perspective) {
                        Text("${(kotlin.math.abs(t.sx) * 100).roundToInt()}%  ${t.angle.roundToInt()}°",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = Gako.Gold)
                        IconButton(onClick = { vm.input.setTransform(t.copy(sx = -t.sx)) }) {
                            Icon(Icons.Outlined.Flip, "Flip horizontally", tint = Gako.Parchment)
                        }
                        IconButton(onClick = { vm.input.setTransform(t.copy(sy = -t.sy)) }) {
                            Icon(Icons.Outlined.Flip, "Flip vertically", tint = Gako.Parchment, modifier = Modifier.rotate(90f))
                        }
                        OptChip("−90°", false) { vm.input.setTransform(t.copy(angle = t.angle - 90f)) }
                        OptChip("+90°", false) { vm.input.setTransform(t.copy(angle = t.angle + 90f)) }
                        }
                    }
                }
                else -> Unit
            }
            if (numbers.isEmpty() && tool !in setOf(Tool.Crop, Tool.Transform, Tool.RectSelect, Tool.EllipseSelect,
                    Tool.FreeSelect, Tool.FuzzySelect, Tool.ColorSelect, Tool.BucketFill, Tool.Gradient, Tool.ColorPicker, Tool.Paths,
                    Tool.SelectBrush)) {
                Text(tool.hint, style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
            }
        }
    }
}

@Composable
private fun ApplyButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Gako.Gold, contentColor = Gako.Ink),
    ) { Icon(Icons.Outlined.Check, contentDescription = "Apply") }
}

@Composable
fun OptChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Gako.Parchment,
            selectedContainerColor = Gako.Oxblood,
            selectedLabelColor = Gako.Parchment,
        ),
    )
}

/** The toolbox: every tool in a compact grid, grouped the way GIMP groups them. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ToolPalette(vm: EditorViewModel, onPicked: () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tool.entries.sortedBy { it.group.ordinal }.forEach { t ->
            val selected = vm.tool == t
            Column(
                Modifier.width(66.dp).clickable { vm.selectTool(t); onPicked() }.padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(40.dp).background(if (selected) Gako.Oxblood else Gako.InkHigh, androidx.compose.foundation.shape.CircleShape)
                        .then(if (selected) Modifier.border(1.dp, Gako.Gold, androidx.compose.foundation.shape.CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = if (selected) Gako.Gold else Gako.Parchment, modifier = Modifier.size(22.dp))
                }
                Text(t.short, style = MaterialTheme.typography.labelSmall, color = if (selected) Gako.Gold else Gako.Parchment,
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** Brush presets: tap to use, “Save current” to keep this brush, long-press a saved one to delete it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PresetsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Brushes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (naming) {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
                    androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                            if (name.isNotBlank()) { vm.savePreset(name); naming = false }
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                } else {
                    Text("Save current brush…", color = Gako.Gold, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { naming = true; name = "" }.padding(vertical = 12.dp))
                }
                if (vm.presetList.any { !it.builtIn })
                    Text("Long-press a saved brush to delete it", style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
                vm.presetList.forEach { p ->
                    if (deleting == p.name) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Delete “${p.name}”?", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Keep") }
                            androidx.compose.material3.TextButton(onClick = { vm.deletePreset(p.name); deleting = null }) { Text("Delete") }
                        }
                        return@forEach
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onLongClick = { if (!p.builtIn) deleting = p.name }) { vm.usePreset(p); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(p.tool.icon, null, tint = Gako.GoldDim, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${p.tool.short} · ${p.size.roundToInt()} px · ${p.shape.label}" +
                                (if (p.opacity < 1f) " · ${(p.opacity * 100).roundToInt()}%" else "") +
                                (if (p.builtIn) "" else " · saved"),
                                style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (naming) androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { vm.savePreset(name); naming = false }) { Text("Save") }
            else androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = if (naming) ({ androidx.compose.material3.TextButton(onClick = { naming = false }) { Text("Cancel") } }) else null,
    )
}
