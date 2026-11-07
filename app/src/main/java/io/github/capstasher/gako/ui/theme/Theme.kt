package io.github.capstasher.gako.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Ink, oxblood and gold. The canvas surround stays neutral grey so colours read true. */
object Gako {
    val Ink = Color(0xFF141113)
    val InkRaised = Color(0xFF1E1A1C)
    val InkHigh = Color(0xFF2A2427)
    val Oxblood = Color(0xFF5A1520)
    val OxbloodBright = Color(0xFF8E2A36)
    val Gold = Color(0xFFD2A84E)
    val GoldDim = Color(0xFF8C7340)
    val Parchment = Color(0xFFEDE3CF)
    val Muted = Color(0xFFA89E93)
    val CanvasSurround = Color(0xFF262626)
}

private val scheme = darkColorScheme(
    primary = Gako.Gold,
    onPrimary = Gako.Ink,
    primaryContainer = Gako.Oxblood,
    onPrimaryContainer = Gako.Parchment,
    secondary = Gako.OxbloodBright,
    onSecondary = Gako.Parchment,
    secondaryContainer = Gako.InkHigh,
    onSecondaryContainer = Gako.Parchment,
    tertiary = Gako.GoldDim,
    background = Gako.Ink,
    onBackground = Gako.Parchment,
    surface = Gako.Ink,
    onSurface = Gako.Parchment,
    surfaceVariant = Gako.InkRaised,
    onSurfaceVariant = Gako.Muted,
    surfaceContainer = Gako.InkRaised,
    surfaceContainerHigh = Gako.InkHigh,
    surfaceContainerHighest = Gako.InkHigh,
    surfaceContainerLow = Gako.InkRaised,
    outline = Gako.GoldDim,
    outlineVariant = Color(0xFF3A3236),
    error = Color(0xFFE57373),
)

val Serif = FontFamily.Serif

private val typography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontFamily = Serif),
        labelSmall = labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

/** Small-caps-ish label used for menu breadcrumbs and section heads. */
val EngravedLabel = TextStyle(
    fontFamily = Serif,
    fontSize = 12.sp,
    letterSpacing = 2.sp,
    fontWeight = FontWeight.Medium,
    color = Gako.Gold,
)

@Composable
fun GakoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
