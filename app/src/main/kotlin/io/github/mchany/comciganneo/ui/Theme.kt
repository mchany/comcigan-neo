package io.github.mchany.comciganneo.ui

import android.os.Build
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.mchany.comciganneo.model.AppPreferences
import io.github.mchany.comciganneo.model.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF36675B), onPrimary = Color.White,
    primaryContainer = Color(0xFFC5EBDD), onPrimaryContainer = Color(0xFF0D372E),
    secondary = Color(0xFF51645D), secondaryContainer = Color(0xFFD4E8DE),
    tertiary = Color(0xFF51617B), tertiaryContainer = Color(0xFFD9E3FF),
    background = Color(0xFFF6FBF7), surface = Color(0xFFF6FBF7),
    surfaceVariant = Color(0xFFDBE5DD), onSurface = Color(0xFF18201C),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA0D0BE), primaryContainer = Color(0xFF1B5043),
    onPrimaryContainer = Color(0xFFC5EBDD), secondaryContainer = Color(0xFF354B42),
    background = Color(0xFF101512), surface = Color(0xFF101512),
    surfaceVariant = Color(0xFF404942),
)

@Composable
fun NeoTheme(preferences: AppPreferences = AppPreferences(), content: @Composable () -> Unit) {
    val dark = when (preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val view = LocalView.current
    SideEffect {
        context.activity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val colors = if (preferences.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) Dark else Light
    MaterialTheme(colorScheme = colors, content = content)
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
