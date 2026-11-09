package io.github.capstasher.gako.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
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
    onPickFolder: () -> Unit,
    onShare: (Uri) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val menu = rememberMenuState()
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val roots = buildMenus(vm, MenuCallbacks(
        openImage = onPickImage,
        share = { scope.launch { vm.shareableUri()?.let(onShare) } },
        about = { showAbout = true },
    ))
    val idle = vm.session == null && vm.busy == null
    val history = History(
        canUndo = vm.canUndo && idle,
        canRedo = vm.canRedo && idle,
        undo = vm::undo,
        redo = vm::redo,
    )
    val session = vm.session
    val export = vm.exportSession

    val busyCancellable = vm.busy?.cancellable == true
    BackHandler(enabled = busyCancellable || session != null || export != null || menu.path.isNotEmpty()) {
        when {
            busyCancellable -> vm.cancelBusy()
            session != null -> vm.cancelSession()
            export != null -> vm.cancelExport()
            else -> menu.pop()
        }
    }

    // Everything inherits light text; nothing falls back to black on ink.
    CompositionLocalProvider(LocalContentColor provides Gako.Parchment) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Gako.Ink)) {
            // Side layout when there's width to spare: landscape phones, tablets, unfolded foldables.
            val wide = (maxWidth > maxHeight && maxWidth >= 600.dp) || maxWidth >= 840.dp
            val panelMax = maxHeight * 0.5f

            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        SideRail(roots, menu, history)
                        if (session == null && export == null && menu.path.isNotEmpty()) {
                            SideSubmenu(roots, menu, onSearchPick = { vm.startFilter(it.name) })
                        }
                        CanvasArea(vm, onPickImage, Modifier.weight(1f).fillMaxHeight())
                        when {
                            session != null -> ParamPanel(session, vm, Modifier.width(360.dp).fillMaxHeight())
                            export != null -> ExportPanel(export, vm, onPickFolder, Modifier.width(360.dp).fillMaxHeight())
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        CanvasArea(vm, onPickImage, Modifier.weight(1f))
                        when {
                            session != null -> ParamPanel(session, vm, Modifier.fillMaxWidth().heightIn(max = panelMax))
                            export != null -> ExportPanel(export, vm, onPickFolder, Modifier.fillMaxWidth().heightIn(max = panelMax))
                            else -> BottomMenu(roots, menu, history, onSearchPick = { vm.startFilter(it.name) })
                        }
                    }
                }

                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
                vm.busy?.let { WorkingPill(it, vm::cancelBusy, Modifier.align(Alignment.TopCenter)) }
            }
        }

        if (showAbout) AboutDialog(vm) { showAbout = false }
    }
}

@Composable
private fun CanvasArea(vm: EditorViewModel, onPickImage: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Gako.CanvasSurround), contentAlignment = Alignment.Center) {
        val img = vm.image
        if (img == null) {
            EmptyState(vm.engine, onPickImage)
            return@Box
        }
        val session = vm.session
        val shown = if (vm.comparing) img else vm.preview ?: img
        val marker = session?.point?.let { (px, py) ->
            val x = (session.values[px.name] as? Number)?.toFloat() ?: return@let null
            val y = (session.values[py.name] as? Number)?.toFloat() ?: return@let null
            if (px.unit == "relative-coordinate") Offset(x * img.width, y * img.height) else Offset(x, y)
        }
        ImageCanvas(
            bitmap = shown,
            imageWidth = img.width,
            imageHeight = img.height,
            marker = marker,
            onTap = if (session?.point != null) { p -> vm.tapImage(p.x, p.y) } else null,
        )
        if (vm.comparing) {
            Text(
                "ORIGINAL", style = EngravedLabel,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    .background(Gako.Ink.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(engine: EngineState, onPickImage: () -> Unit) {
    Column(
        Modifier.widthIn(max = 360.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onPickImage,
            colors = ButtonDefaults.buttonColors(containerColor = Gako.Oxblood, contentColor = Gako.Parchment),
            shape = RoundedCornerShape(4.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Open")
        }
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
