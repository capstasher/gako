package io.github.capstasher.gako.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.ui.theme.Gako

/**
 * The image's paths, like GIMP's Paths dialog: tap one to edit it; ⋮ renames, duplicates,
 * reorders or deletes it. A path can have several strokes (for holes, or shapes in pieces).
 */
@Composable
fun PathsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val paths = vm.doc?.paths.orEmpty()
    var renaming by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paths") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (paths.isEmpty()) Text("No paths yet. With the Paths tool, tap to add corners and drag to add curves.",
                    style = MaterialTheme.typography.bodySmall, color = Gako.Muted, modifier = Modifier.padding(bottom = 8.dp))
                paths.forEachIndexed { i, p ->
                    val active = i == vm.pathIndex
                    if (renaming == i) {
                        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { vm.renamePath(i, name.text); renaming = null }),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRequester(focus))
                        androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
                        return@forEachIndexed
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (active) Gako.Gold.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(8.dp))
                            .clickable { vm.editPath(i); onDismiss() }
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, color = if (active) Gako.Gold else Gako.Parchment)
                            Text(if (p.strokes.size == 1) "1 stroke" else "${p.strokes.size} strokes",
                                style = MaterialTheme.typography.labelSmall, color = Gako.Muted)
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Path options", tint = Gako.GoldDim) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Rename…") }, {
                                    menu = false
                                    name = androidx.compose.ui.text.input.TextFieldValue(p.name, androidx.compose.ui.text.TextRange(0, p.name.length))
                                    renaming = i
                                })
                                DropdownMenuItem({ Text("Duplicate") }, { menu = false; vm.duplicatePath(i) })
                                if (i < paths.lastIndex) DropdownMenuItem({ Text("Move down") }, { menu = false; vm.movePath(i, 1) })
                                if (i > 0) DropdownMenuItem({ Text("Move up") }, { menu = false; vm.movePath(i, -1) })
                                DropdownMenuItem({ Text("Delete") }, { menu = false; vm.deletePath(i) })
                            }
                        }
                    }
                    // Strokes of the path being edited, when it has more than one.
                    if (active && p.strokes.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.strokes.indices.forEach { k ->
                                OptChip("Stroke ${k + 1}", k == vm.strokeIndex) { vm.editPath(i, k); onDismiss() }
                            }
                        }
                    }
                }
                Text("＋ New path", color = Gako.Gold, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { vm.newPath(); onDismiss() }.padding(vertical = 12.dp, horizontal = 10.dp))
                vm.activePath?.let { p ->
                    Text("＋ New stroke in “${p.name}”", color = Gako.Gold, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { vm.newStroke(); onDismiss() }
                            .padding(vertical = 12.dp, horizontal = 10.dp))
                }
            }
        },
        confirmButton = {
            if (renaming != null) TextButton(onClick = { vm.renamePath(renaming!!, name.text); renaming = null }) { Text("Rename") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = if (renaming != null) ({ TextButton(onClick = { renaming = null }) { Text("Cancel") } }) else null,
    )
}
