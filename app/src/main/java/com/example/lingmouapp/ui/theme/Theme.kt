package com.example.lingmouapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color.White,
    primaryContainer = Teal,
    onPrimaryContainer = Ink,
    secondary = TealDeep,
    tertiary = Amber,
    background = PageLight,
    onBackground = Ink,
    surface = SurfaceLight,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7ECF3),
    onSurfaceVariant = InkSoft,
    error = Rose
)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Ink,
    primaryContainer = TealDeep,
    onPrimaryContainer = Color.White,
    secondary = Teal,
    tertiary = Amber,
    background = PageDark,
    onBackground = Color(0xFFE6EAF2),
    surface = SurfaceDark,
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF2A3547),
    onSurfaceVariant = Color(0xFF9FB0C7),
    error = Rose
)

@Composable
fun LingMouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
