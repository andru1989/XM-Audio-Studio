package io.github.andru1989.xmaudiostudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colours from shared/design-tokens/tokens.json. */
@Immutable
data class StudioColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val onSurface: Color,
    val muted: Color,
    val gridLine: Color,
    /** Settings stored in the headphones. */
    val deviceEq: Color,
    /** Local DSP processing (never headphone settings). */
    val dspEq: Color,
    val target: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
)

private val Dark = StudioColors(
    background = Color(0xFF0E1116), surface = Color(0xFF161B22), surfaceElevated = Color(0xFF1E242D),
    onSurface = Color(0xFFE8EDF2), muted = Color(0xFF8B96A3), gridLine = Color(0xFF2A313B),
    deviceEq = Color(0xFF7CC4FF), dspEq = Color(0xFFF2B66D), target = Color(0xFFC9A7FF),
    success = Color(0xFF5BD69A), warning = Color(0xFFF2B66D), danger = Color(0xFFFF6B6B),
)

private val Light = StudioColors(
    background = Color(0xFFF6F7F9), surface = Color(0xFFFFFFFF), surfaceElevated = Color(0xFFF0F2F5),
    onSurface = Color(0xFF12161C), muted = Color(0xFF5C6773), gridLine = Color(0xFFDDE2E8),
    deviceEq = Color(0xFF1E7FD8), dspEq = Color(0xFFC27A1A), target = Color(0xFF7B4FD6),
    success = Color(0xFF1E9E61), warning = Color(0xFFB7791F), danger = Color(0xFFD64545),
)

val LocalStudioColors = staticCompositionLocalOf { Dark }

object Studio {
    val colors: StudioColors @Composable get() = LocalStudioColors.current
}

@Composable
fun XmAudioStudioTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.deviceEq, onPrimary = c.background, background = c.background, onBackground = c.onSurface,
            surface = c.surface, onSurface = c.onSurface, surfaceVariant = c.surfaceElevated, onSurfaceVariant = c.muted,
            secondary = c.target, tertiary = c.dspEq, error = c.danger, outline = c.gridLine,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceElevated, surfaceContainerLow = c.surface,
            secondaryContainer = c.deviceEq.copy(alpha = 0.22f), onSecondaryContainer = c.onSurface,
        )
    } else {
        lightColorScheme(
            primary = c.deviceEq, onPrimary = Color.White, background = c.background, onBackground = c.onSurface,
            surface = c.surface, onSurface = c.onSurface, surfaceVariant = c.surfaceElevated, onSurfaceVariant = c.muted,
            secondary = c.target, tertiary = c.dspEq, error = c.danger, outline = c.gridLine,
            surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceElevated, surfaceContainerLow = c.surface,
            secondaryContainer = c.deviceEq.copy(alpha = 0.16f), onSecondaryContainer = c.onSurface,
        )
    }
    CompositionLocalProvider(LocalStudioColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
