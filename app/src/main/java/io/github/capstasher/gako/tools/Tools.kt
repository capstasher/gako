package io.github.capstasher.gako.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PanoramaFishEye
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.ui.graphics.vector.ImageVector

/** GIMP's toolbox, the parts that make sense under a finger. */
enum class Tool(val label: String, val icon: ImageVector, val group: ToolGroup, val hint: String) {
    Navigate("Navigate", Icons.Outlined.PanTool, ToolGroup.View, "Drag to pan, pinch to zoom"),
    Move("Move", Icons.Outlined.OpenWith, ToolGroup.Transform, "Drag to move the active layer"),
    Transform("Unified Transform", Icons.Outlined.OpenInFull, ToolGroup.Transform,
        "Drag to move, corners to scale, the top knob to rotate"),
    Crop("Crop", Icons.Outlined.Crop, ToolGroup.Transform, "Drag the edges, then apply"),
    RectSelect("Rectangle Select", Icons.Outlined.Rectangle, ToolGroup.Select, "Drag a rectangle"),
    EllipseSelect("Ellipse Select", Icons.Outlined.PanoramaFishEye, ToolGroup.Select, "Drag an ellipse"),
    FreeSelect("Free Select", Icons.Outlined.Gesture, ToolGroup.Select, "Draw around the area"),
    FuzzySelect("Fuzzy Select", Icons.Outlined.AutoFixNormal, ToolGroup.Select, "Tap a region of similar colour"),
    ColorSelect("Select by Colour", Icons.Outlined.InvertColors, ToolGroup.Select, "Tap a colour to select it everywhere"),
    Paintbrush("Paintbrush", Icons.Outlined.Brush, ToolGroup.Paint, "Paint with the foreground colour"),
    Pencil("Pencil", Icons.Outlined.Edit, ToolGroup.Paint, "Hard-edged lines"),
    Airbrush("Airbrush", Icons.Outlined.Draw, ToolGroup.Paint, "Paint builds up where you linger"),
    Eraser("Eraser", Icons.Outlined.CropFree, ToolGroup.Paint, "Erase to transparency"),
    Clone("Clone", Icons.Outlined.ContentCopy, ToolGroup.Paint, "Set a source, then paint a copy of it"),
    DodgeBurn("Dodge / Burn", Icons.Outlined.Tonality, ToolGroup.Paint, "Lighten or darken where you paint"),
    BucketFill("Bucket Fill", Icons.Outlined.FormatColorFill, ToolGroup.Paint, "Tap to fill"),
    Gradient("Gradient", Icons.Outlined.Gradient, ToolGroup.Paint, "Drag from start to end colour"),
    Text("Text", Icons.Outlined.TextFields, ToolGroup.Paint, "Tap to place text"),
    ColorPicker("Colour Picker", Icons.Outlined.Colorize, ToolGroup.View, "Tap to pick the foreground colour");

    /** A label short enough for the palette grid. */
    val short: String get() = when (this) {
        Transform -> "Transform"
        RectSelect -> "Rectangle"
        EllipseSelect -> "Ellipse"
        FreeSelect -> "Lasso"
        FuzzySelect -> "Fuzzy"
        ColorSelect -> "By Colour"
        Paintbrush -> "Brush"
        DodgeBurn -> "Dodge/Burn"
        BucketFill -> "Fill"
        ColorPicker -> "Picker"
        else -> label
    }

    val paints get() = this in setOf(Paintbrush, Pencil, Airbrush, Eraser, Clone, DodgeBurn)
    val selects get() = group == ToolGroup.Select
}

enum class ToolGroup(val label: String) { View("View"), Select("Select"), Paint("Paint"), Transform("Transform") }

enum class GradientShape(val label: String) { Linear("Linear"), Radial("Radial"), Mirrored("Mirrored") }
enum class GradientColors(val label: String) { FgBg("FG → BG"), FgTransparent("FG → Transparent") }
enum class FillArea(val label: String) { Similar("Similar colours"), Selection("Whole selection") }
enum class CropAspect(val label: String, val ratio: Float?) {
    Free("Free", null), Square("1:1", 1f), FourThree("4:3", 4f / 3f), ThreeTwo("3:2", 3f / 2f),
    SixteenNine("16:9", 16f / 9f), ThreeFour("3:4", 3f / 4f), NineSixteen("9:16", 9f / 16f),
}

/** Tool settings, shared by the tools that use them (as in GIMP's tool options). */
data class ToolOptions(
    val size: Float = 24f,
    val opacity: Float = 1f,
    val hardness: Float = 0.75f,
    val airRate: Float = 0.25f,
    val selectMode: io.github.capstasher.gako.doc.SelectMode = io.github.capstasher.gako.doc.SelectMode.Replace,
    val feather: Float = 0f,
    val threshold: Int = 15,
    val sampleMerged: Boolean = false,
    val diagonal: Boolean = false,
    val fillArea: FillArea = FillArea.Similar,
    val gradientShape: GradientShape = GradientShape.Linear,
    val gradientColors: GradientColors = GradientColors.FgBg,
    val cropAspect: CropAspect = CropAspect.Free,
    val burn: Boolean = false,
    val exposure: Float = 0.5f,
    val cloneAligned: Boolean = true,
)
