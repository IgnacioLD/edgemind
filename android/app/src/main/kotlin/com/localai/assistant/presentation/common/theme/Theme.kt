package com.localai.assistant.presentation.common.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val EdgeMindColorScheme = darkColorScheme(
    primary = EdgePrimary,
    onPrimary = EdgeOnPrimary,
    primaryContainer = EdgePrimaryContainer,
    onPrimaryContainer = EdgeOnPrimaryContainer,
    secondary = EdgeSecondary,
    onSecondary = EdgeOnSecondary,
    secondaryContainer = EdgeSecondaryContainer,
    onSecondaryContainer = EdgeOnSecondaryContainer,
    tertiary = EdgeSecondary,
    onTertiary = EdgeOnSecondary,
    background = EdgeBackground,
    onBackground = EdgeOnSurface,
    surface = EdgeSurface,
    onSurface = EdgeOnSurface,
    surfaceVariant = EdgeSurfaceVariant,
    onSurfaceVariant = EdgeOnSurfaceVariant,
    error = EdgeError,
    onError = EdgeOnError,
    errorContainer = EdgeErrorContainer,
    onErrorContainer = EdgeOnErrorContainer,
    outline = EdgeOutline,
    outlineVariant = EdgeOutlineSubtle,
)

// EdgeMind always renders in the custom dark scheme regardless of system setting — the brand
// is dark-first and a light variant would dilute the identity. Edge-to-edge so the deep
// background flows under the status bar instead of getting clipped by a colored top strip.
@Composable
fun LocalAIAssistantTheme(
    content: @Composable () -> Unit,
) {
    val colorScheme = EdgeMindColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = colorScheme.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
