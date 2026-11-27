package com.hyperlyrics.mm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object YtmColors {
    val Obsidian = Color(0xFF030303)
    val SurfaceCard = Color(0xFF121215)
    val SurfaceElevated = Color(0xFF1B1B20)
    val Hairline = Color(0xFF282830)
    val Crimson = Color(0xFFFF0033)
    val CrimsonSoft = Color(0xFFFF4D6D)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFA8A8B3)
    val TextMuted = Color(0xFF70707B)
    val ActiveGreen = Color(0xFF2ED573)
    val WarningAmber = Color(0xFFFFB020)
}

private val YtmDarkColorScheme = darkColorScheme(
    primary = YtmColors.Crimson,
    onPrimary = Color.White,
    secondary = YtmColors.CrimsonSoft,
    onSecondary = Color.White,
    background = YtmColors.Obsidian,
    onBackground = YtmColors.TextPrimary,
    surface = YtmColors.SurfaceCard,
    onSurface = YtmColors.TextPrimary,
    surfaceVariant = YtmColors.SurfaceElevated,
    onSurfaceVariant = YtmColors.TextSecondary,
    outline = YtmColors.Hairline
)

@Composable
fun HyperLyricsYtmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = YtmDarkColorScheme,
        content = content
    )
}
