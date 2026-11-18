package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val StudioConsoleColorScheme = darkColorScheme(
    primary = ElectricSky,
    onPrimary = StudioCharcoal,
    primaryContainer = ElectricSkyContainer,
    onPrimaryContainer = CrispOffWhite,
    secondary = WarmAmber,
    onSecondary = StudioCharcoal,
    secondaryContainer = WarmAmberContainer,
    onSecondaryContainer = CrispOffWhite,
    tertiary = PitchEmerald,
    onTertiary = StudioCharcoal,
    error = DownbeatCrimson,
    onError = CrispOffWhite,
    background = StudioCharcoal,
    onBackground = CrispOffWhite,
    surface = AnodizedSlate,
    onSurface = CrispOffWhite,
    surfaceVariant = ConsoleSurface,
    onSurfaceVariant = MutedSlateText,
    surfaceContainerHigh = ConsoleSurfaceBright,
    outline = HardwareBorder,
    outlineVariant = LcdBorder
)

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun HarmonyDirectorTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioConsoleColorScheme,
        typography = HarmonyTypography,
        shapes = StudioShapes,
        content = content
    )
}
