package com.stride.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Stride's own palette — teal brand, not borrowed from any other fitness app. */
object Palette {
    val Brand = Color(0xFF00BFA6)
    val BrandDeep = Color(0xFF00897B)
    val Gold = Color(0xFFFFC83D)      // personal records
    val Start = Color(0xFF34C759)
    val Finish = Color(0xFFFF453A)
    val Planned = Color(0xFF7C8CFF)   // routes you're following
    val Climb = Color(0xFF8E8EFF)
}

private val Dark = darkColorScheme(
    primary = Palette.Brand,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF00382F),
    onPrimaryContainer = Color(0xFF7DF8E0),
    secondary = Palette.Planned,
    background = Color(0xFF101114),
    onBackground = Color(0xFFE7E8EA),
    surface = Color(0xFF101114),
    onSurface = Color(0xFFE7E8EA),
    surfaceVariant = Color(0xFF1C1E22),
    onSurfaceVariant = Color(0xFFA9ADB4),
    surfaceContainer = Color(0xFF17191C),
    surfaceContainerHigh = Color(0xFF1E2024),
    surfaceContainerHighest = Color(0xFF26292E),
    outline = Color(0xFF3A3E45),
    outlineVariant = Color(0xFF2A2D32),
    error = Color(0xFFFF6B60),
)

private val Light = lightColorScheme(
    primary = Palette.BrandDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F5EA),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4C5BD4),
    background = Color(0xFFF7F7F5),
    surface = Color(0xFFF7F7F5),
    surfaceVariant = Color(0xFFEDEEEB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F1EE),
    surfaceContainerHighest = Color(0xFFE6E7E4),
    onSurfaceVariant = Color(0xFF5B5F66),
    outline = Color(0xFFC6C8CC),
    outlineVariant = Color(0xFFE0E1E3),
)

private val Type = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Black),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/** Big tabular numbers for stats. */
val StatNumber = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 26.sp, letterSpacing = (-0.3).sp)

@Composable
fun StrideTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
