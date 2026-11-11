package io.github.capstasher.gako.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.BorderStyle
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BorderOuter
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.capstasher.gako.editor.Dialog
import io.github.capstasher.gako.editor.EditorViewModel
import io.github.capstasher.gako.tools.Tool
import io.github.capstasher.gako.tools.ToolGroup
import io.github.capstasher.gako.engine.OpInfo
import io.github.capstasher.gako.engine.OpKind

/** Everything in GAKO's bottom (or side) menus: groups nest, actions do something. */
sealed interface MenuEntry {
    val key: String
    val label: String
    val icon: ImageVector
}

data class MenuGroup(
    override val key: String,
    override val label: String,
    override val icon: ImageVector,
    val children: List<MenuEntry>,
    /** Groups that can be searched show a search field above their tiles. */
    val searchable: List<OpInfo> = emptyList(),
    /** Roots that aren't tabs in the bar (the tool palette opens from the canvas dock). */
    val inBar: Boolean = true,
    /** Shown instead of tiles (the Layers panel). */
    val panel: (@Composable () -> Unit)? = null,
) : MenuEntry

data class MenuAction(
    override val key: String,
    override val label: String,
    override val icon: ImageVector,
    val enabled: Boolean = true,
    /** Highlighted, e.g. the current tool. */
    val selected: Boolean = false,
    /** Close the menus afterwards (picking a tool), instead of staying put for the next action. */
    val closesMenu: Boolean = false,
    val onClick: () -> Unit,
) : MenuEntry

fun List<MenuGroup>.resolve(path: List<String>): List<MenuGroup> {
    val trail = mutableListOf<MenuGroup>()
    var level: List<MenuEntry> = this
    for (key in path) {
        val next = level.firstOrNull { it.key == key } as? MenuGroup ?: break
        trail += next
        level = next.children
    }
    return trail
}

private class FilterShelf(val key: String, val label: String, val icon: ImageVector, val categories: Set<String>)

/** GEGL categories folded into GIMP-style Filters submenus. First match wins. */
private val filterShelves = listOf(
    FilterShelf("blur", "Blur", Icons.Outlined.BlurOn, setOf("blur")),
    FilterShelf("enhance", "Enhance", Icons.Outlined.AutoFixHigh, setOf("enhance", "noise-reduction", "sharpen")),
    FilterShelf("distorts", "Distorts", Icons.Outlined.Waves, setOf("distort", "illusions")),
    FilterShelf("light", "Light & Shadow", Icons.Outlined.WbSunny, setOf("light")),
    FilterShelf("noise", "Noise", Icons.Outlined.Grain, setOf("noise")),
    FilterShelf("edge", "Edge-Detect", Icons.Outlined.BorderOuter, setOf("edge-detect")),
    FilterShelf("artistic", "Artistic", Icons.Outlined.Brush, setOf("artistic")),
    FilterShelf("map", "Map", Icons.Outlined.Map, setOf("map")),
    FilterShelf("render", "Render", Icons.Outlined.AutoAwesome, setOf("render", "fractal", "gradient", "pattern")),
    FilterShelf("generic", "Generic", Icons.Outlined.Functions, setOf("generic", "Generic", "math")),
)

/** Categories that don't belong in Filters (handled elsewhere or not usable yet). */
private val excludedFromFilters = setOf(
    "color", "transform", "compositors", "porter-duff", "blend", "svgfilter", "layout", "vector",
)

private class Curated(val label: String, val icon: ImageVector, val ops: List<Pair<String, String>>)

/** GIMP's Colours menu, mapped onto GEGL operations. Items that aren't available are hidden. */
private val colourShelves = listOf(
    Curated("Adjust", Icons.Outlined.Tune, listOf(
        "gegl:brightness-contrast" to "Brightness-Contrast",
        "gegl:exposure" to "Exposure",
        "gegl:shadows-highlights" to "Shadows-Highlights",
        "gegl:hue-chroma" to "Hue-Chroma",
        "gegl:saturation" to "Saturation",
        "gegl:color-temperature" to "Colour Temperature",
    )),
    Curated("Auto", Icons.Outlined.AutoFixHigh, listOf(
        "gegl:stretch-contrast" to "Stretch Contrast",
        "gegl:stretch-contrast-hsv" to "Stretch Contrast HSV",
        "gegl:color-enhance" to "Colour Enhance",
        "gegl:mantiuk06" to "Tone Map (Mantiuk)",
    )),
    Curated("Invert", Icons.Outlined.InvertColors, listOf(
        "gegl:invert-gamma" to "Invert",
        "gegl:invert-linear" to "Linear Invert",
        "gegl:value-invert" to "Value Invert",
    )),
    Curated("Monochrome", Icons.Outlined.FilterBAndW, listOf(
        "gegl:gray" to "Desaturate",
        "gegl:mono-mixer" to "Mono Mixer",
        "gegl:sepia" to "Sepia",
        "gegl:threshold" to "Threshold",
        "gegl:posterize" to "Posterize",
        "gegl:dither" to "Dither",
    )),
    Curated("Components", Icons.Outlined.Colorize, listOf(
        "gegl:channel-mixer" to "Channel Mixer",
        "gegl:component-extract" to "Extract Component",
        "gegl:color-exchange" to "Colour Exchange",
        "gegl:color-rotate" to "Rotate Colours",
        "gegl:color-to-alpha" to "Colour to Alpha",
    )),
)

class MenuCallbacks(
    val openImage: () -> Unit,
    val openFile: (asLayers: Boolean) -> Unit,
    val takePhoto: () -> Unit,
    val share: () -> Unit,
    val about: () -> Unit,
    val layersPanel: @Composable () -> Unit,
    val toolsPanel: @Composable () -> Unit,
)

fun buildMenus(vm: EditorViewModel, callbacks: MenuCallbacks): List<MenuGroup> {
    val hasImage = vm.doc != null
    val hasSelection = vm.doc?.selection != null
    val ops = vm.operations.filter { it.kind != OpKind.Composer }
    val byName = ops.associateBy { it.name }

    fun opAction(op: OpInfo, label: String = op.title) =
        MenuAction("op:${op.name}", label, iconFor(op), enabled = hasImage) { vm.startFilter(op.name) }

    fun act(key: String, label: String, icon: ImageVector, enabled: Boolean = hasImage, onClick: () -> Unit) =
        MenuAction(key, label, icon, enabled, onClick = onClick)

    fun toolAction(t: Tool) = MenuAction("tool:${t.name}", t.label, t.icon, enabled = true,
        selected = vm.tool == t, closesMenu = true) { vm.selectTool(t) }

    // ---- File
    val file = MenuGroup("file", "File", Icons.Outlined.FolderOpen, listOf(
        act("new", "New…", Icons.Outlined.NoteAdd, true) { vm.dialog = Dialog.NewImage },
        act("open", "Open…", Icons.Outlined.FolderOpen, true) { callbacks.openFile(false) },
        act("open-photo", "Open Photo…", Icons.Outlined.PhotoLibrary, true) { callbacks.openImage() },
        act("camera", "Take Photo…", Icons.Outlined.PhotoCamera, true) { callbacks.takePhoto() },
        act("open-layers", "Open as Layers…", Icons.Outlined.Layers) { callbacks.openFile(true) },
        act("save", "Save", Icons.Outlined.Save) { vm.save() },
        act("save-as", "Save As…", Icons.Outlined.SaveAs) { vm.startExport(project = true) },
        act("export", "Export As…", Icons.Outlined.SaveAlt) { vm.startExport() },
        act("share", "Share", Icons.Outlined.Share) { callbacks.share() },
        act("about", "About", Icons.Outlined.Info, true) { callbacks.about() },
    ))

    // ---- Edit
    val edit = MenuGroup("edit", "Edit", Icons.Outlined.EditNote, listOf(
        act("history", "Undo History…", Icons.Outlined.History) { vm.dialog = Dialog.History },
        act("cut", "Cut", Icons.Outlined.ContentCut) { vm.cut() },
        act("copy", "Copy", Icons.Outlined.ContentCopy) { vm.copy() },
        act("copy-visible", "Copy Visible", Icons.Outlined.CopyAll) { vm.copy(merged = true) },
        act("paste", "Paste", Icons.Outlined.ContentPaste, vm.hasClipboard) { vm.paste() },
        act("paste-new", "Paste as New Image", Icons.Outlined.AddPhotoAlternate, vm.hasClipboard) { vm.paste(asNewImage = true) },
        act("clear", "Clear", Icons.Outlined.Backspace) { vm.clear() },
        act("fill-fg", "Fill with FG Colour", Icons.Outlined.FormatColorFill) { vm.fillWith(vm.fg, "Fill with FG Colour") },
        act("fill-bg", "Fill with BG Colour", Icons.Outlined.FormatColorReset) { vm.fillWith(vm.bg, "Fill with BG Colour") },
    ))

    // ---- Select
    val select = MenuGroup("select", "Select", Icons.Outlined.HighlightAlt, Tool.entries.filter { it.group == ToolGroup.Select }.map(::toolAction) + listOf(
        act("sel-all", "All", Icons.Outlined.SelectAll) { vm.selectAll() },
        act("sel-none", "None", Icons.Outlined.Deselect, hasSelection) { vm.selectNone() },
        act("sel-invert", "Invert", Icons.Outlined.FlipToBack) { vm.invertSelection() },
        act("sel-feather", "Feather…", Icons.Outlined.BlurOn, hasSelection) { vm.dialog = Dialog.ModifySelection(EditorViewModel.SelectionChange.Feather) },
        act("sel-grow", "Grow…", Icons.Outlined.ZoomOutMap, hasSelection) { vm.dialog = Dialog.ModifySelection(EditorViewModel.SelectionChange.Grow) },
        act("sel-shrink", "Shrink…", Icons.Outlined.ZoomInMap, hasSelection) { vm.dialog = Dialog.ModifySelection(EditorViewModel.SelectionChange.Shrink) },
        act("sel-border", "Border…", Icons.Outlined.BorderStyle, hasSelection) { vm.dialog = Dialog.ModifySelection(EditorViewModel.SelectionChange.Border) },
        act("sel-alpha", "From Layer Alpha", Icons.Outlined.Opacity) { vm.alphaToSelection() },
    ))

    // ---- Image
    val image = MenuGroup("image", "Image", Icons.Outlined.Image, listOf(
        MenuGroup("transform", "Transform", Icons.Outlined.Sync, listOf(
            act("rot-cw", "Rotate 90° ⟳", Icons.AutoMirrored.Outlined.RotateRight) { vm.rotate(1) },
            act("rot-ccw", "Rotate 90° ⟲", Icons.AutoMirrored.Outlined.RotateLeft) { vm.rotate(3) },
            act("rot-180", "Rotate 180°", Icons.Outlined.Sync) { vm.rotate(2) },
            act("flip-h", "Flip Horizontally", Icons.Outlined.SwapHoriz) { vm.flip(true) },
            act("flip-v", "Flip Vertically", Icons.Outlined.SwapVert) { vm.flip(false) },
        )),
        act("canvas-size", "Canvas Size…", Icons.Outlined.AspectRatio) { vm.dialog = Dialog.CanvasSize },
        act("scale-image", "Scale Image…", Icons.Outlined.PhotoSizeSelectLarge) { vm.dialog = Dialog.ScaleImage },
        act("crop-sel", "Crop to Selection", Icons.Outlined.Crop, hasSelection) { vm.cropToSelection() },
        act("crop-content", "Crop to Content", Icons.Outlined.CropFree) { vm.cropToContent() },
        act("merge-visible", "Merge Visible Layers", Icons.Outlined.CallMerge) { vm.mergeVisible() },
        act("flatten", "Flatten Image", Icons.Outlined.Layers) { vm.flattenImage() },
    ))

    // ---- Layers (a panel, not tiles)
    val layers = MenuGroup("layers", "Layers", Icons.Outlined.Layers, emptyList(), panel = callbacks.layersPanel)

    // ---- Colours
    val curatedNames = colourShelves.flatMap { s -> s.ops.map { it.first } }.toSet()
    val colourGroups = colourShelves.mapNotNull { shelf ->
        val items = shelf.ops.mapNotNull { (name, label) -> byName[name]?.let { opAction(it, label) } }
        val extra = when (shelf.label) {
            "Adjust" -> listOf(
                act("curves", "Curves…", Icons.Outlined.ShowChart) { vm.startCurves() },
                act("levels", "Levels…", Icons.Outlined.Equalizer) { vm.startCurves(levels = true) },
            )
            "Auto" -> listOf(act("white-balance", "White Balance", Icons.Outlined.WbSunny) { vm.whiteBalance() })
            else -> emptyList()
        }
        if (items.isEmpty() && extra.isEmpty()) null
        else MenuGroup("colours:${shelf.label.lowercase()}", shelf.label, shelf.icon, extra + items)
    }
    val moreColour = ops.filter { "color" in it.categories && it.name !in curatedNames }
        .sortedBy { it.title }
        .map { opAction(it) }
    val colours = MenuGroup("colours", "Colours", Icons.Outlined.Palette,
        colourGroups + listOfNotNull(
            if (moreColour.isNotEmpty()) MenuGroup("colours:more", "More", Icons.Outlined.MoreHoriz, moreColour) else null,
        ),
        searchable = ops.filter { "color" in it.categories },
    )

    // ---- Filters
    val filterOps = ops.filter { op -> op.categories.none { it in excludedFromFilters } && op.categories.isNotEmpty() }
    val shelved = mutableMapOf<String, MutableList<OpInfo>>()
    val other = mutableListOf<OpInfo>()
    for (op in filterOps) {
        val shelf = filterShelves.firstOrNull { s -> op.categories.any { it in s.categories } }
        if (shelf != null) shelved.getOrPut(shelf.key) { mutableListOf() } += op else other += op
    }
    val filterGroups = filterShelves.mapNotNull { s ->
        shelved[s.key]?.sortedBy { it.title }?.let { list ->
            MenuGroup("filters:${s.key}", s.label, s.icon, list.map { opAction(it) })
        }
    } + listOfNotNull(
        if (other.isNotEmpty()) MenuGroup("filters:other", "Other", Icons.Outlined.MoreHoriz,
            other.sortedBy { it.title }.map { opAction(it) }) else null,
    )
    val recent = vm.recentFilters.mapNotNull { byName[it] }
    val filterTop = listOfNotNull(
        vm.lastFilterName?.let { n ->
            act("repeat", "Repeat “${byName[n]?.title ?: n}”", Icons.Outlined.Replay) { vm.repeatLastFilter() }
        },
        if (recent.isNotEmpty()) MenuGroup("filters:recent", "Recently Used", Icons.Outlined.History, recent.map { opAction(it) }) else null,
    )
    val filters = MenuGroup("filters", "Filters", Icons.Outlined.FilterVintage, filterTop + filterGroups,
        searchable = ops.filter { op -> op.categories.none { it in setOf("transform", "svgfilter", "vector") } })

    // ---- Tools (opened from the canvas dock)
    val tools = MenuGroup("tools", "Tools", Icons.Outlined.Handyman, Tool.entries.map(::toolAction), inBar = false,
        panel = callbacks.toolsPanel)

    return listOf(file, edit, select, image, layers, colours, filters, tools)
}

private fun iconFor(op: OpInfo): ImageVector {
    val c = op.categories
    return when {
        "blur" in c -> Icons.Outlined.BlurOn
        "light" in c -> Icons.Outlined.WbSunny
        "noise" in c -> Icons.Outlined.Grain
        "distort" in c -> Icons.Outlined.Waves
        "artistic" in c -> Icons.Outlined.Brush
        "edge-detect" in c -> Icons.Outlined.BorderOuter
        "map" in c -> Icons.Outlined.Map
        "render" in c -> Icons.Outlined.AutoAwesome
        "enhance" in c || "sharpen" in c -> Icons.Outlined.AutoFixHigh
        "transparency" in c -> Icons.Outlined.Opacity
        "color" in c -> when {
            "contrast" in op.name -> Icons.Outlined.Contrast
            "exposure" in op.name -> Icons.Outlined.Exposure
            "invert" in op.name -> Icons.Outlined.InvertColors
            else -> Icons.Outlined.ColorLens
        }
        else -> Icons.Outlined.Texture
    }
}
