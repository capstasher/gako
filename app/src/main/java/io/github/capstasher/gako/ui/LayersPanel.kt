package io.github.capstasher.gako.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.doc.Compositor
import io.github.capstasher.gako.doc.Layer
import io.github.capstasher.gako.doc.LayerMode
import io.github.capstasher.gako.editor.Dialog
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.ui.theme.Gako
import kotlin.math.roundToInt

/** GIMP's Layers dock: the stack top to bottom, the active layer's mode and opacity, and actions. */
@Composable
fun LayersPanel(vm: EditorViewModel) {
    val d = vm.doc
    if (d == null) {
        Text("Open or create an image to see its layers.", color = Gako.Muted, modifier = Modifier.padding(16.dp))
        return
    }
    val active = d.active
    Column(Modifier.fillMaxWidth()) {
        if (active != null) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ModePicker(active.mode) { vm.setLayerMode(it) }
                Slider(
                    value = active.opacity,
                    onValueChange = { vm.setLayerOpacityLive(it) },
                    onValueChangeFinished = { vm.commitLayerProperty() },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = Gako.Gold, activeTrackColor = Gako.Gold, inactiveTrackColor = Gako.InkHigh),
                )
                Text("${(active.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = Gako.Gold,
                    modifier = Modifier.width(40.dp))
                IconButton(onClick = { vm.setLockAlpha(!active.lockAlpha) }) {
                    Icon(if (active.lockAlpha) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                        contentDescription = "Lock alpha channel", tint = if (active.lockAlpha) Gako.Gold else Gako.Muted)
                }
            }
        }
        HorizontalDivider(color = Gako.InkHigh)
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            items(d.layers.asReversed(), key = { it.id }) { layer ->
                LayerRow(vm, layer, layer.id == d.activeId)
            }
        }
        HorizontalDivider(color = Gako.InkHigh)
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { vm.dialog = Dialog.NewLayer }) { Icon(Icons.Outlined.Add, "New layer") }
            IconButton(onClick = vm::duplicateLayer) { Icon(Icons.Outlined.ContentCopy, "Duplicate layer") }
            IconButton(onClick = { vm.moveLayer(1) }, enabled = d.activeIndex < d.layers.lastIndex) {
                Icon(Icons.Outlined.ArrowUpward, "Raise layer")
            }
            IconButton(onClick = { vm.moveLayer(-1) }, enabled = d.activeIndex > 0) {
                Icon(Icons.Outlined.ArrowDownward, "Lower layer")
            }
            IconButton(onClick = vm::mergeDown, enabled = d.activeIndex > 0) {
                Icon(Icons.AutoMirrored.Outlined.CallMerge, "Merge down")
            }
            IconButton(onClick = vm::deleteLayer, enabled = d.layers.size > 1) { Icon(Icons.Outlined.Delete, "Delete layer") }
            MoreLayerActions(vm)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(vm: EditorViewModel, layer: Layer, active: Boolean) {
    val rev = if (active) vm.revision else 0
    val thumb = remember(layer.bitmap, rev) { Compositor.thumbnail(layer.bitmap, 96).asImageBitmap() }
    val maskThumb = layer.mask?.let { m -> remember(m, rev) { Compositor.thumbnail(m, 96).asImageBitmap() } }
    val onMask = active && vm.editingMask
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) Gako.Oxblood.copy(alpha = 0.55f) else Color.Transparent)
            .combinedClickable(
                onClick = { vm.setActiveLayer(layer.id) },
                onLongClick = { vm.dialog = Dialog.RenameLayer(layer.id) },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.setLayerVisible(layer.id, !layer.visible) }) {
            Icon(if (layer.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = if (layer.visible) "Hide" else "Show",
                tint = if (layer.visible) Gako.Parchment else Gako.Muted.copy(alpha = 0.5f))
        }
        Box(
            Modifier.size(44.dp).background(Color(0xFF777777), RoundedCornerShape(3.dp))
                .border(if (active && !onMask) 2.dp else 1.dp, if (active && !onMask) Gako.Gold else Gako.InkHigh, RoundedCornerShape(3.dp))
                .clickable { vm.setActiveLayer(layer.id); vm.chooseMaskEditing(false) },
            contentAlignment = Alignment.Center,
        ) {
            Image(thumb, contentDescription = "Edit layer", modifier = Modifier.size(42.dp))
        }
        if (maskThumb != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(44.dp).background(Color.Black, RoundedCornerShape(3.dp))
                    .border(if (onMask) 2.dp else 1.dp, if (onMask) Gako.Gold else if (layer.maskEnabled) Gako.InkHigh else Color(0xFFE05555),
                        RoundedCornerShape(3.dp))
                    .clickable { vm.setActiveLayer(layer.id); vm.chooseMaskEditing(true) },
                contentAlignment = Alignment.Center,
            ) {
                Image(maskThumb, contentDescription = "Edit layer mask", modifier = Modifier.size(42.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (layer.text != null) {
                    Icon(Icons.Outlined.TextFields, null, tint = Gako.GoldDim, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(layer.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (layer.visible) Gako.Parchment else Gako.Muted)
            }
            val info = buildList {
                if (layer.mode != LayerMode.Normal) add(layer.mode.label)
                if (layer.opacity < 1f) add("${(layer.opacity * 100).roundToInt()}%")
                if (layer.lockAlpha) add("alpha locked")
                if (layer.mask != null && !layer.maskEnabled) add("mask off")
                if (active && vm.editingMask) add("editing mask")
            }
            if (info.isNotEmpty()) Text(info.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
        }
    }
}

@Composable
private fun ModePicker(mode: LayerMode, onPick: (LayerMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(mode.label, color = Gako.Gold, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LayerMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label, color = if (m == mode) Gako.Gold else Gako.Parchment) },
                    onClick = { open = false; onPick(m) },
                )
            }
        }
    }
}

@Composable
private fun MoreLayerActions(vm: EditorViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "More layer actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(label: String, action: () -> Unit) = @Composable {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
            item("Rename…") { vm.doc?.activeId?.let { vm.dialog = Dialog.RenameLayer(it) } }()
            val a = vm.doc?.active
            if (a?.mask == null) item("Add Layer Mask…") { vm.dialog = Dialog.AddMask }()
            else {
                item(if (vm.editingMask) "Edit Layer Pixels" else "Edit Layer Mask") { vm.chooseMaskEditing(!vm.editingMask) }()
                item(if (a.maskEnabled) "Disable Layer Mask" else "Enable Layer Mask") { vm.setMaskEnabled(!a.maskEnabled) }()
                item("Apply Layer Mask") { vm.applyMask() }()
                item("Delete Layer Mask") { vm.deleteMask() }()
            }
            item("Scale Layer…") { vm.dialog = Dialog.ScaleLayer }()
            item("Layer to Image Size") { vm.layerToImageSize() }()
            item("Alpha to Selection") { vm.alphaToSelection() }()
            item("New from Visible") { vm.newFromVisible() }()
            item("Merge Visible Layers") { vm.mergeVisible() }()
            item("Flatten Image") { vm.flattenImage() }()
        }
    }
}
