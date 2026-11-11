package io.github.capstasher.gako.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.ui.theme.EngravedLabel
import io.github.capstasher.gako.ui.theme.Gako

private val sections = listOf(
    "Touch" to listOf(
        "One finger" to "Uses the current tool (or pans, with Navigate)",
        "Two fingers" to "Pan, pinch to zoom, twist to turn the view",
        "Zoom chip" to "Tap: fit ↔ 100% · resets a turned view",
        "Hold ◐" to "In filters and curves: see the original",
        "Pen" to "Pressure; eraser end erases; fingers then only navigate",
    ),
    "Where things are" to listOf(
        "Tool button" to "Bottom-left of the image: all tools",
        "Colours" to "FG/BG swatches: tap to pick, ⇄ swaps, hold resets",
        "Layer pill" to "Which layer you're working on; tap for Layers",
        "Strip" to "The tool's options; tap a value for its slider",
        "Layers ⋮" to "Masks, groups, scale, crop to content…",
        "Filter 🖌" to "Paint a filter on instead of applying it everywhere",
    ),
    "Keyboard" to listOf(
        "Ctrl+Z / Y" to "Undo / redo",
        "Ctrl+S, ⇧S, E" to "Save, Save As, Export",
        "Ctrl+C, X, V" to "Copy, cut, paste (⇧: visible / as new image)",
        "Ctrl+A, ⇧A, I" to "Select all, none, invert",
        "Ctrl+J, ⇧J" to "Layer via copy / cut",
        "Ctrl+N, ⇧N, D" to "New image, new layer, duplicate",
        "Ctrl+F, M, L" to "Repeat filter, Curves, Levels",
        "P N A ⇧E" to "Brush, pencil, airbrush, eraser",
        "C ⇧H S ⇧D" to "Clone, heal, smudge, dodge/burn",
        "⇧B G T O" to "Fill, gradient, text, picker",
        "R E F U ⇧O" to "Rectangle, ellipse, lasso, fuzzy, by colour",
        "B M ⇧T ⇧C H" to "Paths, move, transform, crop, navigate",
        "[ ]  X  Del" to "Brush size, swap colours, clear",
        "Enter / Esc" to "Apply / cancel",
    ),
)

/** A cheat sheet: gestures, where things live, shortcuts. */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Using GAKO") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((title, rows) in sections) {
                    Text(title.uppercase(), style = EngravedLabel, modifier = Modifier.padding(top = 8.dp))
                    for ((k, v) in rows) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(k, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = Gako.Gold, modifier = Modifier.width(118.dp))
                            Text(v, style = MaterialTheme.typography.bodySmall, color = Gako.Parchment, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
