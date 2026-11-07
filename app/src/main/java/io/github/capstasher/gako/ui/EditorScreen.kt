package io.github.capstasher.gako.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.editor.EditorViewModel.EngineState
import io.github.capstasher.gako.ui.theme.EngravedLabel
import io.github.capstasher.gako.ui.theme.Gako
import io.github.capstasher.gako.ui.theme.Serif
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(vm: EditorViewModel, onPickImage: () -> Unit, onShare: (Uri) -> Unit) {
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
    val session = vm.session

    BackHandler(enabled = session != null || menu.path.isNotEmpty()) {
        if (session != null) vm.cancelSession() else menu.pop()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Gako.Ink)) {
        // Side layout when there's width to spare: landscape phones, tablets, unfolded foldables.
        val wide = (maxWidth > maxHeight && maxWidth >= 600.dp) || maxWidth >= 840.dp
        val panelMax = maxHeight * 0.45f

        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    SideRail(roots, menu)
                    if (session == null && menu.path.isNotEmpty()) {
                        SideSubmenu(roots, menu, onSearchPick = { vm.startFilter(it.name) })
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(vm)
                        CanvasArea(vm, onPickImage, Modifier.weight(1f))
                    }
                    if (session != null) {
                        ParamPanel(session, vm, Modifier.width(360.dp).fillMaxHeight())
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TopBar(vm)
                    CanvasArea(vm, onPickImage, Modifier.weight(1f))
                    if (session != null) {
                        ParamPanel(session, vm, Modifier.fillMaxWidth().heightIn(max = panelMax))
                    } else {
                        BottomMenu(roots, menu, onSearchPick = { vm.startFilter(it.name) })
                    }
                }
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
        }

        vm.busy?.let { BusyOverlay(it, vm::cancelBusy) }
    }

    if (showAbout) AboutDialog(vm) { showAbout = false }
}

@Composable
private fun TopBar(vm: EditorViewModel) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("GAKO", fontFamily = Serif, fontSize = 20.sp, letterSpacing = 5.sp, color = Gako.Gold)
        Spacer(Modifier.width(12.dp))
        Text(
            if (vm.image != null) vm.documentName else "",
            style = MaterialTheme.typography.bodySmall,
            color = Gako.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = vm::undo, enabled = vm.canUndo && vm.session == null) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = vm::redo, enabled = vm.canRedo && vm.session == null) {
            Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
        }
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
        Modifier
            .widthIn(max = 360.dp)
            .padding(24.dp)
            .border(1.dp, Gako.GoldDim.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
            .padding(4.dp)
            .border(0.5.dp, Gako.GoldDim.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("GAKO", fontFamily = Serif, fontSize = 40.sp, letterSpacing = 12.sp, color = Gako.Gold)
        Text("GIMP ANDROID KANVAS OPERATOR", style = EngravedLabel.copy(fontSize = 10.sp, color = Gako.Muted),
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onPickImage,
            colors = ButtonDefaults.buttonColors(containerColor = Gako.Oxblood, contentColor = Gako.Parchment),
            shape = RoundedCornerShape(4.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, null)
            Spacer(Modifier.width(8.dp))
            Text("Open an image")
        }
        Spacer(Modifier.height(4.dp))
        when (engine) {
            EngineState.Starting -> Text("Warming up the engine…", style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
            is EngineState.Ready -> Text("${engine.operations.size} GEGL operations ready",
                style = MaterialTheme.typography.labelMedium, color = Gako.Muted)
            is EngineState.Failed -> Text("Engine failed to start: ${engine.message}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BusyOverlay(busy: EditorViewModel.Busy, onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = Gako.InkRaised, shape = RoundedCornerShape(8.dp), tonalElevation = 4.dp,
            modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(busy.label, style = MaterialTheme.typography.titleMedium)
                val p = busy.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(),
                        color = Gako.Gold, trackColor = Gako.InkHigh)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gako.Gold, trackColor = Gako.InkHigh)
                }
                if (busy.cancellable) {
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GAKO") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GIMP Android Kanvas Operator — stage 1", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Image processing by GEGL and babl, the engine of GIMP. " +
                        "${vm.operations.size} operations loaded.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "GAKO is free software under the GNU GPL v3 or later. It is an independent " +
                        "project, not made or endorsed by the GIMP team.",
                    style = MaterialTheme.typography.bodySmall, color = Gako.Muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
