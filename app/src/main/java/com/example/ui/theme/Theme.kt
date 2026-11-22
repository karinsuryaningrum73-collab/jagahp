package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val JagaCamDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = OnEmerald,
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = OnEmeraldContainer,
    secondary = CyanSecondary,
    onSecondary = OnCyan,
    secondaryContainer = CyanContainer,
    onSecondaryContainer = OnCyanContainer,
    tertiary = AmberTertiary,
    onTertiary = OnAmber,
    tertiaryContainer = AmberContainer,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = SlateSurfaceBright,
    outline = BorderSubtle,
    error = AlertRed,
    errorContainer = AlertRedContainer,
    onErrorContainer = OnAlertRedContainer
)

private val JagaCamShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun JagaCamTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = JagaCamDarkColorScheme,
        typography = JagaCamTypography,
        shapes = JagaCamShapes,
        content = content
    )
}
