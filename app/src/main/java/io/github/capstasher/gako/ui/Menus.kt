package io.github.capstasher.gako.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
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
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.capstasher.gako.editor.EditorViewModel
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
) : MenuEntry

data class MenuAction(
    override val key: String,
    override val label: String,
    override val icon: ImageVector,
    val enabled: Boolean = true,
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
        "gegl:levels" to "Levels",
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
    val share: () -> Unit,
    val about: () -> Unit,
)

fun buildMenus(vm: EditorViewModel, callbacks: MenuCallbacks): List<MenuGroup> {
    val hasImage = vm.image != null
    val ops = vm.operations.filter { it.kind != OpKind.Composer }
    val byName = ops.associateBy { it.name }

    fun opAction(op: OpInfo, label: String = op.title) =
        MenuAction("op:${op.name}", label, iconFor(op), enabled = hasImage) { vm.startFilter(op.name) }

    // ---- File
    val file = MenuGroup("file", "File", Icons.Outlined.FolderOpen, listOf(
        MenuAction("open", "Open…", Icons.Outlined.FolderOpen) { callbacks.openImage() },
        MenuAction("export", "Export As…", Icons.Outlined.SaveAlt, enabled = hasImage) { vm.startExport() },
        MenuAction("share", "Share", Icons.Outlined.Share, enabled = hasImage) { callbacks.share() },
        MenuAction("about", "About", Icons.Outlined.Info) { callbacks.about() },
    ))

    // ---- Image
    val image = MenuGroup("image", "Image", Icons.Outlined.Image, listOf(
        MenuGroup("transform", "Transform", Icons.Outlined.Sync, listOf(
            MenuAction("rot-cw", "Rotate 90° ⟳", Icons.AutoMirrored.Outlined.RotateRight, hasImage) { vm.rotate(90f) },
            MenuAction("rot-ccw", "Rotate 90° ⟲", Icons.AutoMirrored.Outlined.RotateLeft, hasImage) { vm.rotate(-90f) },
            MenuAction("rot-180", "Rotate 180°", Icons.Outlined.Sync, hasImage) { vm.rotate(180f) },
            MenuAction("flip-h", "Flip Horizontally", Icons.Outlined.SwapHoriz, hasImage) { vm.flip(true) },
            MenuAction("flip-v", "Flip Vertically", Icons.Outlined.SwapVert, hasImage) { vm.flip(false) },
        )),
    ))

    // ---- Colours
    val curatedNames = colourShelves.flatMap { s -> s.ops.map { it.first } }.toSet()
    val colourGroups = colourShelves.mapNotNull { shelf ->
        val items = shelf.ops.mapNotNull { (name, label) -> byName[name]?.let { opAction(it, label) } }
        if (items.isEmpty()) null
        else MenuGroup("colours:${shelf.label.lowercase()}", shelf.label, shelf.icon, items)
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
    val filters = MenuGroup("filters", "Filters", Icons.Outlined.FilterVintage, filterGroups,
        searchable = ops.filter { op -> op.categories.none { it in setOf("transform", "svgfilter", "vector") } })

    return listOf(file, image, colours, filters)
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
