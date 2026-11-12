package io.github.capstasher.gako.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.doc.TextAlign
import io.github.capstasher.gako.doc.TextFont
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.editor.TextSession
import io.github.capstasher.gako.ui.theme.Gako
import kotlin.math.pow
import kotlin.math.roundToInt

/** The text tool's editor. The text is drawn live on the canvas; drag it there to move it. */
@Composable
fun TextPanel(t: TextSession, vm: EditorViewModel, onAddFont: () -> Unit, modifier: Modifier = Modifier) {
    var pickColor by remember { mutableStateOf(false) }
    var pickFont by remember { mutableStateOf(false) }
    var pickOutline by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(t) { if (t.info.text.isEmpty()) runCatching { focus.requestFocus() } }
    val info = t.info
    Column(modifier.background(Gako.InkRaised)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::cancelText) { Icon(Icons.Outlined.Close, "Cancel", tint = Gako.Muted) }
            Text(if (t.layerId == null) "Text" else "Edit Text", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilledIconButton(
                onClick = { vm.commitText(fromDone = true) },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Gako.Gold, contentColor = Gako.Ink),
            ) { Icon(Icons.Outlined.Check, "Done") }
        }
        HorizontalDivider(color = Gako.GoldDim.copy(alpha = 0.35f))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = info.text,
                onValueChange = { t.info = info.copy(text = it) },
                placeholder = { Text("Type here") },
                minLines = 2,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gako.Gold, cursorColor = Gako.Gold),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = ((info.size - 4f) / 996f).coerceIn(0f, 1f).pow(1 / 2.5f),
                    onValueChange = { s ->
                        val size = 4f + 996f * s.pow(2.5f)
                        // The outline keeps its proportion to the letters.
                        t.info = t.info.copy(size = size, outline = t.info.outline * size / t.info.size.coerceAtLeast(1f))
                    },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Gako.Gold, activeTrackColor = Gako.Gold, inactiveTrackColor = Gako.InkHigh),
                )
                Text("${info.size.roundToInt()} px", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = Gako.Gold, modifier = Modifier.width(64.dp).padding(start = 6.dp))
                Box(Modifier.size(36.dp).background(Color(info.color), RoundedCornerShape(6.dp))
                    .border(1.dp, Gako.GoldDim, RoundedCornerShape(6.dp)).clickable { pickColor = true })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Outline", Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = (info.outline / (info.size * 0.25f).coerceAtLeast(1f)).coerceIn(0f, 1f),
                    onValueChange = { v -> t.info = t.info.copy(outline = v * (t.info.size * 0.25f).coerceAtLeast(1f)) },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Gako.Gold, activeTrackColor = Gako.Gold, inactiveTrackColor = Gako.InkHigh),
                )
                Text("${info.outline.roundToInt()} px", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = Gako.Gold, modifier = Modifier.width(64.dp).padding(start = 6.dp))
                Box(Modifier.size(36.dp).background(Color(info.outlineColor), RoundedCornerShape(6.dp))
                    .border(1.dp, Gako.GoldDim, RoundedCornerShape(6.dp)).clickable { pickOutline = true })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextFont.entries.forEach { f -> OptChip(f.label, info.font == f && info.fontFile == null) { t.info = t.info.copy(font = f, fontFile = null) } }
                OptChip(info.fontFile?.let { io.github.capstasher.gako.editor.Fonts.pretty(java.io.File(it).nameWithoutExtension.substringBefore('-')) } ?: "More fonts…",
                    info.fontFile != null) { pickFont = true }
                OptChip("Bold", info.bold) { t.info = t.info.copy(bold = !t.info.bold) }
                OptChip("Italic", info.italic) { t.info = t.info.copy(italic = !t.info.italic) }
                TextAlign.entries.forEach { a -> OptChip(a.name, info.align == a) { t.info = t.info.copy(align = a) } }
            }
            Text("Drag on the image to move the text.", style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
        }
    }
    if (pickFont) {
        FontDialog(
            current = info.fontFile,
            onDismiss = { pickFont = false },
            onAdd = { pickFont = false; onAddFont() },
        ) { path ->
            t.info = t.info.copy(fontFile = path)
            pickFont = false
        }
    }
    if (pickOutline) {
        ColorPickerDialog("Outline Colour", info.outlineColor, onDismiss = { pickOutline = false }) { c ->
            t.info = t.info.copy(outlineColor = c)
            pickOutline = false
        }
    }
    if (pickColor) {
        ColorPickerDialog("Text Colour", info.color, onDismiss = { pickColor = false }) { c ->
            t.info = t.info.copy(color = c)
            pickColor = false
            vm.setForeground(c)
        }
    }
}

/** Every font on the phone (and the ones added), each shown in itself. */
@Composable
private fun FontDialog(current: String?, onDismiss: () -> Unit, onAdd: () -> Unit, onPick: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fonts = remember {
        io.github.capstasher.gako.editor.Fonts.user(context.filesDir) + io.github.capstasher.gako.editor.Fonts.system()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fonts") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                item {
                    Text("Add a font file (TTF or OTF)…", color = Gako.Gold, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(vertical = 12.dp))
                }
                items(fonts.size) { i ->
                    val f = fonts[i]
                    val face = remember(f.path) { io.github.capstasher.gako.editor.Fonts.load(f.path) }
                    Text(
                        f.name,
                        fontFamily = face?.let { androidx.compose.ui.text.font.FontFamily(it) },
                        fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp),
                        color = if (f.path == current) Gako.Gold else Gako.Parchment,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(f.path) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
