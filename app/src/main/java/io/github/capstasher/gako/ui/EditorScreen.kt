package io.github.capstasher.gako.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.editor.EditorViewModel.EngineState
import io.github.capstasher.gako.ui.theme.EngravedLabel
import io.github.capstasher.gako.ui.theme.Gako
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onPickImage: () -> Unit,
    onPickFile: (asLayers: Boolean) -> Unit,
    onTakePhoto: () -> Unit,
    onPickFont: () -> Unit,
    onPickFolder: () -> Unit,
    onShare: (Uri) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val menu = rememberMenuState()
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }

    val roots = buildMenus(vm, MenuCallbacks(
        openImage = onPickImage,
        openFile = onPickFile,
        takePhoto = onTakePhoto,
        share = { scope.launch { vm.shareableUri()?.let(onShare) } },
        about = { showAbout = true },
        layersPanel = { LayersPanel(vm) },
        toolsPanel = { ToolPalette(vm) { menu.close() } },
    ))
    val session = vm.session
    val export = vm.exportSession
    val curves = vm.curves
    val text = vm.text
    val modal = session != null || export != null || curves != null || text != null

    val busyCancellable = vm.busy?.cancellable == true
    BackHandler(enabled = busyCancellable || modal || menu.path.isNotEmpty() || vm.input.hasPendingEdit()) {
        when {
            busyCancellable -> vm.cancelBusy()
            session != null -> vm.cancelSession()
            export != null -> vm.cancelExport()
            curves != null -> vm.cancelCurves()
            text != null -> vm.cancelText()
            menu.path.isNotEmpty() -> menu.pop()
            else -> vm.input.reset()
        }
    }

    // Everything inherits light text; nothing falls back to black on ink.
    CompositionLocalProvider(LocalContentColor provides Gako.Parchment) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Gako.Ink)) {
            // Side layout when there's width to spare: landscape phones, tablets, unfolded foldables.
            val wide = (maxWidth > maxHeight && maxWidth >= 600.dp) || maxWidth >= 840.dp
            // Tablets and unfolded foldables keep the Layers dock open, as GIMP does.
            val tablet = maxWidth >= 1000.dp
            val panelMax = maxHeight * 0.5f

            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        SideRail(roots, menu)
                        if (!modal && menu.path.isNotEmpty() && !(tablet && menu.path.first() == "layers")) {
                            SideSubmenu(roots, menu, onSearchPick = { vm.startFilter(it.name) })
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            CanvasArea(vm, menu, onPickImage, onTakePhoto, Modifier.weight(1f))
                            if (!modal && vm.doc != null) ToolOptionsBar(vm)
                        }
                        val side = Modifier.width(360.dp).fillMaxHeight()
                        when {
                            session != null -> ParamPanel(session, vm, side)
                            export != null -> ExportPanel(export, vm, onPickFolder, side)
                            curves != null -> CurvesPanel(curves, vm, side)
                            text != null -> TextPanel(text, vm, onPickFont, side)
                            tablet && vm.doc != null -> Column(Modifier.width(320.dp).fillMaxHeight().background(Gako.InkRaised)) {
                                Text("LAYERS", style = EngravedLabel, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                                LayersPanel(vm)
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        CanvasArea(vm, menu, onPickImage, onTakePhoto, Modifier.weight(1f))
                        val panel = Modifier.fillMaxWidth().heightIn(max = panelMax)
                        when {
                            session != null -> ParamPanel(session, vm, panel)
                            export != null -> ExportPanel(export, vm, onPickFolder, panel)
                            curves != null -> CurvesPanel(curves, vm, panel)
                            text != null -> TextPanel(text, vm, onPickFont, panel)
                            else -> BottomMenu(roots, menu, onSearchPick = { vm.startFilter(it.name) }) {
                                if (vm.doc != null) ToolOptionsBar(vm)
                            }
                        }
                    }
                }

                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
                vm.busy?.let { WorkingPill(it, vm::cancelBusy, Modifier.align(Alignment.TopCenter)) }
            }
        }

        if (showAbout) AboutDialog(vm) { showAbout = false }
        vm.dialog?.let { DialogHost(vm, it) }
        vm.confirm?.let { c ->
            AlertDialog(
                onDismissRequest = { vm.confirm = null },
                title = { Text(c.title) },
                text = { Text(c.message) },
                confirmButton = { TextButton(onClick = c.run) { Text(c.action, color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { vm.confirm = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun CanvasArea(vm: EditorViewModel, menu: MenuState, onPickImage: () -> Unit, onTakePhoto: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Gako.CanvasSurround), contentAlignment = Alignment.Center) {
        val doc = vm.doc
        if (doc == null) {
            EmptyState(vm, onPickImage, onTakePhoto)
            return@Box
        }
        ImageCanvas(vm, doc)
        if (vm.comparing) {
            Text(
                "ORIGINAL", style = EngravedLabel,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    .background(Gako.Ink.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        val modal = vm.session != null || vm.curves != null || vm.text != null || vm.exportSession != null
        if (!modal) Dock(vm, menu, Modifier.align(Alignment.BottomCenter))
    }
}

/** Floating over the bottom of the canvas: the tool, the colours, and undo/redo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dock(vm: EditorViewModel, menu: MenuState, modifier: Modifier) {
    val pill = Modifier.background(Gako.Ink.copy(alpha = 0.82f), RoundedCornerShape(24.dp))
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(pill.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val toolsOpen = menu.path.firstOrNull() == "tools"
            IconButton(onClick = { if (toolsOpen) menu.close() else menu.openRoot("tools") }) {
                Box(
                    Modifier.size(38.dp).background(if (toolsOpen) Gako.Oxblood else Gako.InkHigh, CircleShape)
                        .border(1.dp, Gako.Gold, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(vm.tool.icon, contentDescription = "Tool: ${vm.tool.label}", tint = Gako.Gold, modifier = Modifier.size(22.dp))
                }
            }
            // GIMP's FG/BG swatches: tap to edit, ⇄ swaps, long-press resets to black and white.
            Box(Modifier.size(width = 46.dp, height = 40.dp)) {
                Box(
                    Modifier.offset(x = 16.dp, y = 14.dp).size(26.dp).background(Color(vm.bg), RoundedCornerShape(4.dp))
                        .border(1.dp, Gako.Muted, RoundedCornerShape(4.dp))
                        .combinedClickable(onLongClick = vm::resetColors) { vm.dialog = io.github.capstasher.gako.editor.Dialog.PickColor(false) },
                )
                Box(
                    Modifier.size(26.dp).background(Color(vm.fg), RoundedCornerShape(4.dp))
                        .border(1.dp, Gako.Parchment, RoundedCornerShape(4.dp))
                        .combinedClickable(onLongClick = vm::resetColors) { vm.dialog = io.github.capstasher.gako.editor.Dialog.PickColor(true) },
                )
            }
            IconButton(onClick = vm::swapColors, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = "Swap colours", tint = Gako.Muted, modifier = Modifier.size(18.dp))
            }
        }
        // Which layer the tools work on (a classic "why isn't it painting?"); tap for the Layers panel.
        val active = vm.doc?.active
        Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            if (active != null) {
                Row(
                    pill.clip(RoundedCornerShape(24.dp)).clickable {
                        if (menu.path.firstOrNull() == "layers") menu.close() else menu.openRoot("layers")
                    }.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Layers, null, tint = Gako.Muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        active.name + if (vm.editingMask) " · mask" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active.visible) Gako.Parchment else Gako.Muted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val idle = vm.session == null && vm.busy == null
        Row(pill) {
            HistoryButton(true, History(vm.canUndo && idle, vm.canRedo && idle, vm::undo, vm::redo))
            HistoryButton(false, History(vm.canUndo && idle, vm.canRedo && idle, vm::undo, vm::redo))
        }
    }
}

@Composable
private fun EmptyState(vm: EditorViewModel, onPickImage: () -> Unit, onTakePhoto: () -> Unit) {
    Column(
        Modifier.widthIn(max = 360.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GAKO", style = MaterialTheme.typography.headlineMedium, color = Gako.Gold)
        Button(
            onClick = onPickImage,
            colors = ButtonDefaults.buttonColors(containerColor = Gako.Oxblood, contentColor = Gako.Parchment),
            shape = RoundedCornerShape(4.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Open")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onTakePhoto, shape = RoundedCornerShape(4.dp)) {
                Icon(Icons.Outlined.PhotoCamera, null, tint = Gako.Parchment)
                Spacer(Modifier.width(8.dp))
                Text("Camera", color = Gako.Parchment)
            }
            OutlinedButton(onClick = { vm.dialog = io.github.capstasher.gako.editor.Dialog.NewImage }, shape = RoundedCornerShape(4.dp)) {
                Icon(Icons.Outlined.NoteAdd, null, tint = Gako.Parchment)
                Spacer(Modifier.width(8.dp))
                Text("New", color = Gako.Parchment)
            }
        }
        Text(
            "One finger uses the tool. Two fingers pan and zoom.",
            style = MaterialTheme.typography.labelMedium, color = Gako.Muted, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        val engine = vm.engine
        if (engine is EngineState.Failed) {
            Text("Engine failed to start: ${engine.message}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center)
        }
    }
}

/**
 * No bars. If a job runs past a moment, a small pill says what it is, how far along, and ✕.
 * Quick edits never show it.
 */
@Composable
private fun WorkingPill(busy: EditorViewModel.Busy, onCancel: () -> Unit, modifier: Modifier) {
    var visible by remember(busy.label) { mutableStateOf(false) }
    LaunchedEffect(busy.label) {
        delay(400)
        visible = true
    }
    if (!visible) return
    Row(
        modifier
            .padding(top = 12.dp)
            .background(Gako.Ink.copy(alpha = 0.9f), RoundedCornerShape(50))
            .padding(start = 16.dp, end = if (busy.cancellable) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(busy.label, style = MaterialTheme.typography.labelLarge, color = Gako.Parchment,
            modifier = Modifier.padding(vertical = 10.dp))
        busy.progress?.let {
            Spacer(Modifier.width(10.dp))
            Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = Gako.Gold)
        }
        if (busy.cancellable) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = "Stop", tint = Gako.Muted)
            }
        }
    }
}

@Composable
private fun AboutDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    var check by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { check = vm.engineCheck() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GAKO") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GIMP Android Kanvas Operator", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Image processing by GEGL and babl, the engine of GIMP. " +
                        "${vm.operations.size} operations loaded.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(check ?: "Checking engine speed…", style = MaterialTheme.typography.bodySmall, color = Gako.Gold)
                Text(
                    "Free software under the GNU GPL v3 or later. An independent project, " +
                        "not made or endorsed by the GIMP team.",
                    style = MaterialTheme.typography.bodySmall, color = Gako.Muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
