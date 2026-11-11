package io.github.capstasher.gako.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.export.ExportFormat
import io.github.capstasher.gako.ui.theme.Gako
import kotlin.math.roundToInt

/** GIMP's Export Image, folded into a panel: name, folder, file type, then that type's options. */
@Composable
fun ExportPanel(
    session: EditorViewModel.ExportSession,
    vm: EditorViewModel,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(Gako.InkRaised)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::cancelExport) {
                Icon(Icons.Outlined.Close, contentDescription = "Cancel", tint = Gako.Muted)
            }
            Text(session.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilledIconButton(
                onClick = vm::runExport,
                enabled = vm.busy == null && session.name.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Gako.Gold, contentColor = Gako.Ink),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = "Export")
            }
        }
        HorizontalDivider(color = Gako.GoldDim.copy(alpha = 0.35f))

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = session.name,
                onValueChange = session::rename,
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gako.Gold, cursorColor = Gako.Gold,
                    focusedLabelColor = Gako.Gold),
                modifier = Modifier.fillMaxWidth(),
            )

            Label("Folder")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip(if (session.format.opaqueToSystem) "Download/GAKO" else "Pictures/GAKO", selected = !vm.exportToFolder) {
                    vm.exportToFolder = false
                }
                val folder = vm.exportFolder
                Chip(folder?.name ?: "Choose…", selected = vm.exportToFolder) {
                    // Tapping the chosen folder again picks a different one.
                    if (folder == null || vm.exportToFolder) onPickFolder() else vm.exportToFolder = true
                }
            }

            Label("File type")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Save Project offers GIMP's XCF and OpenRaster (both keep layers); Export everything flat.
                val project = session.title != "Export Image"
                ExportFormat.entries.filter { if (project) it.layered else it != ExportFormat.Xcf }.forEach { f ->
                    Chip(f.label, selected = session.format == f) { session.choose(f) }
                }
            }

            FormatOptions(session)
            val o = session.options
            val d = vm.doc
            if (!session.format.layered && d != null) {
                Label("Size")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100, 75, 50, 25).forEach { p ->
                        val w = (d.width * p / 100).coerceAtLeast(1)
                        val h = (d.height * p / 100).coerceAtLeast(1)
                        Chip(if (p == 100) "Full · $w×$h" else "$p% · $w×$h", selected = o.scale == p) {
                            session.options = o.copy(scale = p)
                        }
                    }
                }
            }
            val meta = vm.metadata
            if (meta != null && io.github.capstasher.gako.export.Metadata.supports(session.format)) {
                Toggle("Keep photo info (camera, date, exposure)", o.keepMetadata) { session.options = o.copy(keepMetadata = it) }
                if (o.keepMetadata && io.github.capstasher.gako.export.Metadata.hasLocation(meta)) {
                    Toggle("Include location", o.keepLocation) { session.options = o.copy(keepLocation = it) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun FormatOptions(session: EditorViewModel.ExportSession) {
    val o = session.options
    when (session.format) {
        ExportFormat.Jpeg, ExportFormat.Heif -> Quality(o.quality) { session.options = o.copy(quality = it) }
        ExportFormat.Webp -> {
            Toggle("Lossless", o.webpLossless) { session.options = o.copy(webpLossless = it) }
            if (!o.webpLossless) Quality(o.quality) { session.options = o.copy(quality = it) }
        }
        ExportFormat.Gif -> Toggle("Dither", o.gifDither) { session.options = o.copy(gifDither = it) }
        ExportFormat.Tga -> Toggle("RLE compression", o.tgaRle) { session.options = o.copy(tgaRle = it) }
        ExportFormat.Tiff -> {
            Label("Compression")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("None", selected = !o.tiffDeflate) { session.options = o.copy(tiffDeflate = false) }
                Chip("Deflate", selected = o.tiffDeflate) { session.options = o.copy(tiffDeflate = true) }
            }
        }
        else -> Unit
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Gako.Parchment,
            selectedContainerColor = Gako.Oxblood,
            selectedLabelColor = Gako.Parchment,
        ),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Gako.Ink, checkedTrackColor = Gako.Gold))
    }
}

@Composable
private fun Quality(value: Int, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quality", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("$value", style = MaterialTheme.typography.bodyMedium, color = Gako.Gold)
            Spacer(Modifier.width(4.dp))
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(1, 100)) },
            valueRange = 1f..100f,
            colors = SliderDefaults.colors(thumbColor = Gako.Gold, activeTrackColor = Gako.Gold, inactiveTrackColor = Gako.InkHigh),
        )
    }
}
