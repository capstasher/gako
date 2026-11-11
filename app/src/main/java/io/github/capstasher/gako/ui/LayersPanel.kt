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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Checkbox
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
        if (active != null && active.quickMask) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Quick Mask: painting edits the selection", style = MaterialTheme.typography.bodyMedium,
                    color = Gako.Parchment, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::toggleQuickMask) { Text("End", color = Gako.Gold) }
            }
        } else if (active != null) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ModePicker(active, onPick = { vm.setLayerMode(it) }, onLegacy = { vm.setLayerLegacy(it) })
                Slider(
                    value = active.opacity,
                    onValueChange = { vm.setLayerOpacityLive(it) },
                    onValueChangeFinished = { vm.commitLayerProperty() },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = Gako.Gold, activeTrackColor = Gako.Gold, inactiveTrackColor = Gako.InkHigh),
                )
                Text("${(active.opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = Gako.Gold,
                    modifier = Modifier.width(40.dp))
                if (!active.group) IconButton(onClick = { vm.setLockAlpha(!active.lockAlpha) }) {
                    Icon(if (active.lockAlpha) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                        contentDescription = "Lock alpha channel", tint = if (active.lockAlpha) Gako.Gold else Gako.Muted)
                }
            }
        }
        HorizontalDivider(color = Gako.InkHigh)
        val byId = io.github.capstasher.gako.doc.Tree.byId(d.layers)
        val rows = d.layers.asReversed().filter { !it.quickMask && !io.github.capstasher.gako.doc.Tree.collapsedAway(it, byId) }
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            items(rows, key = { it.id }) { layer ->
                LayerRow(vm, layer, layer.id == d.activeId, io.github.capstasher.gako.doc.Tree.depth(layer, byId),
                    shown = io.github.capstasher.gako.doc.Tree.shows(layer, byId))
            }
        }
        HorizontalDivider(color = Gako.InkHigh)
        val range = active?.let { io.github.capstasher.gako.doc.Tree.range(d.layers, it.id) }
        val canRaise = active != null && (active.parent != null || (range != null && range.last < d.layers.lastIndex))
        val canLower = active != null && (active.parent != null || (range != null && range.first > 0))
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { vm.dialog = Dialog.NewLayer }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Add, "New layer") }
            IconButton(onClick = vm::duplicateLayer, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ContentCopy, "Duplicate layer") }
            IconButton(onClick = { vm.moveLayer(1) }, enabled = canRaise, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.ArrowUpward, "Raise layer")
            }
            IconButton(onClick = { vm.moveLayer(-1) }, enabled = canLower, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.ArrowDownward, "Lower layer")
            }
            IconButton(onClick = vm::mergeDown, enabled = active?.group == true || io.github.capstasher.gako.doc.DocOps.mergeTarget(d) != null,
                modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Outlined.CallMerge, "Merge down")
            }
            IconButton(onClick = vm::deleteLayer, enabled = d.layers.size > 1, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Delete, "Delete layer") }
            MoreLayerActions(vm)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(vm: EditorViewModel, layer: Layer, active: Boolean, depth: Int, shown: Boolean) {
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
            .padding(start = 8.dp + 18.dp * depth, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.setLayerVisible(layer.id, !layer.visible) }) {
            Icon(if (layer.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = if (layer.visible) "Hide" else "Show",
                tint = if (layer.visible && shown) Gako.Parchment else Gako.Muted.copy(alpha = 0.5f))
        }
        if (layer.group) {
            // A group: a folder that opens and closes, instead of thumbnails.
            IconButton(onClick = { vm.setExpanded(layer.id, !layer.expanded) }, modifier = Modifier.size(44.dp)) {
                Icon(if (layer.expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                    contentDescription = if (layer.expanded) "Collapse group" else "Expand group",
                    tint = if (active) Gako.Gold else Gako.GoldDim, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(layer.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (shown) Gako.Parchment else Gako.Muted)
                val info = buildList {
                    add(layer.mode.label)
                    if (layer.opacity < 1f) add("${(layer.opacity * 100).roundToInt()}%")
                }
                Text(info.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
            }
            return@Row
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
                if (!layer.group && !layer.legacyFor(layer.mode)) add("linear")
                if (layer.opacity < 1f) add("${(layer.opacity * 100).roundToInt()}%")
                if (layer.lockAlpha) add("alpha locked")
                if (layer.mask != null && !layer.maskEnabled) add("mask off")
                if (active && vm.editingMask) add("editing mask")
            }
            if (info.isNotEmpty()) Text(info.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
        }
    }
}

/**
 * GIMP's layer mode menu: the modes in GIMP's groups, and (as GIMP's switch between its default
 * and legacy mode lists) whether the layer blends in linear light like GIMP 2.10+ or in sRGB.
 */
@Composable
private fun ModePicker(layer: Layer, onPick: (LayerMode) -> Unit, onLegacy: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val mode = layer.mode
    Box {
        TextButton(onClick = { open = true }) {
            Text(mode.label, color = Gako.Gold, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (mode != LayerMode.PassThrough) {
                val linear = !layer.legacyFor(mode)
                DropdownMenuItem(
                    text = { Text("Linear light (GIMP 2.10+)", color = Gako.Parchment) },
                    leadingIcon = { Checkbox(checked = linear, onCheckedChange = null, enabled = mode.legacyGimp != null) },
                    enabled = mode.legacyGimp != null,
                    onClick = { onLegacy(linear) },
                )
                HorizontalDivider(color = Gako.InkHigh)
            }
            var section = -1
            LayerMode.entries.filter { layer.group || it != LayerMode.PassThrough }.forEach { m ->
                if (section >= 0 && m.section != section) HorizontalDivider(color = Gako.InkHigh)
                section = m.section
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
    // A second page of the menu: "transform" or "stack" (null: the main list).
    var page by remember { mutableStateOf<String?>(null) }
    Box {
        IconButton(onClick = { open = true; page = null }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreVert, "More layer actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(label: String, action: () -> Unit) = @Composable {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
            }
            fun sub(label: String, to: String) = @Composable {
                DropdownMenuItem(text = { Text("$label  ›") }, onClick = { page = to })
            }
            if (page != null) {
                DropdownMenuItem(text = { Text("‹  Back", color = Gako.Muted) }, onClick = { page = null })
                if (page == "transform") {
                    io.github.capstasher.gako.doc.DocOps.LayerTurn.entries.forEach { t ->
                        item(t.label.removePrefix("Flip Layer ").removePrefix("Rotate Layer ").let {
                            if (t.name.startsWith("Flip")) "Flip $it" else "Rotate $it"
                        }) { vm.transformLayer(t) }()
                    }
                } else {
                    item("Raise to Top") { vm.restack(top = true) }()
                    item("Lower to Bottom") { vm.restack(top = false) }()
                }
                return@DropdownMenu
            }
            item("Rename…") { vm.doc?.activeId?.let { vm.dialog = Dialog.RenameLayer(it) } }()
            sub("Transform", "transform")()
            sub("Stack", "stack")()
            item("New Layer Group") { vm.newGroup() }()
            item("Put in New Group") { vm.groupActive() }()
            val a = vm.doc?.active
            if (a?.group == true) {
                item("Merge Layer Group") { vm.mergeGroup() }()
                item("New from Visible") { vm.newFromVisible() }()
                item("Merge Visible Layers") { vm.mergeVisible() }()
                item("Flatten Image") { vm.flattenImage() }()
                return@DropdownMenu
            }
            if (a?.mask == null) item("Add Layer Mask…") { vm.dialog = Dialog.AddMask }()
            else {
                item(if (vm.editingMask) "Edit Layer Pixels" else "Edit Layer Mask") { vm.chooseMaskEditing(!vm.editingMask) }()
                item(if (a.maskEnabled) "Disable Layer Mask" else "Enable Layer Mask") { vm.setMaskEnabled(!a.maskEnabled) }()
                item("Apply Layer Mask") { vm.applyMask() }()
                item("Delete Layer Mask") { vm.deleteMask() }()
            }
            item("Scale Layer…") { vm.dialog = Dialog.ScaleLayer }()
            item("Layer to Image Size") { vm.layerToImageSize() }()
            if (vm.doc?.selection != null) item("Crop to Selection") { vm.cropLayerToSelection() }()
            item("Crop to Content") { vm.cropLayerToContent() }()
            item("Alpha to Selection") { vm.alphaToSelection() }()
            item("New from Visible") { vm.newFromVisible() }()
            item("Merge Visible Layers") { vm.mergeVisible() }()
            item("Flatten Image") { vm.flattenImage() }()
        }
    }
}
