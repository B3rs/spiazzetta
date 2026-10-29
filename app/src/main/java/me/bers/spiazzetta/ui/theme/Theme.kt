package me.bers.spiazzetta.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = GhBlue,
    onPrimary = GhSurface,
    primaryContainer = GhBlueSoft,
    onPrimaryContainer = GhBlue,
    secondary = GhBlue,
    onSecondary = GhSurface,
    secondaryContainer = GhBlueSoft,
    onSecondaryContainer = GhBlue,
    tertiary = GhGreen,
    onTertiary = GhSurface,
    tertiaryContainer = GhGreenSoft,
    onTertiaryContainer = GhGreen,
    background = GhCanvas,
    onBackground = GhInk,
    surface = GhSurface,
    onSurface = GhInk,
    surfaceVariant = GhSurfaceMuted,
    onSurfaceVariant = GhMuted,
    outline = GhMuted,
    outlineVariant = GhLine,
    error = GhRed,
    onError = GhSurface,
    errorContainer = GhRedSoft,
    onErrorContainer = GhRed,
    inverseSurface = GhInk,
    inverseOnSurface = GhSurface,
    inversePrimary = GhBlueLight,
)

private val DarkColors = darkColorScheme(
    primary = GhBlueDark,
    onPrimary = GhNightCanvas,
    primaryContainer = GhBlueSoftDark,
    onPrimaryContainer = GhBlueDark,
    secondary = GhBlueDark,
    onSecondary = GhNightCanvas,
    secondaryContainer = GhBlueSoftDark,
    onSecondaryContainer = GhBlueDark,
    tertiary = GhGreenDark,
    onTertiary = GhNightCanvas,
    tertiaryContainer = GhNightSurfaceMuted,
    onTertiaryContainer = GhGreenDark,
    background = GhNightCanvas,
    onBackground = GhNightInk,
    surface = GhNightSurface,
    onSurface = GhNightInk,
    surfaceVariant = GhNightSurfaceMuted,
    onSurfaceVariant = GhNightMuted,
    outline = GhNightMuted,
    outlineVariant = GhNightLine,
    error = GhRedDark,
    onError = GhNightCanvas,
    errorContainer = GhNightSurfaceMuted,
    onErrorContainer = GhRedDark,
    inverseSurface = GhNightInk,
    inverseOnSurface = GhNightCanvas,
    inversePrimary = GhBlue,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun SpiazzettaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = PiazzettaTypography, content = content)
}
