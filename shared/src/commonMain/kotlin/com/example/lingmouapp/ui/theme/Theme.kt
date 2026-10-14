package com.example.lingmouapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

/**
 * 品牌配色，Android 与 Windows 共用。
 *
 * **故意不支持 Material You 动态取色**：`dynamicLightColorScheme(context)`
 * 需要 Android 的 Context，而 commonMain 里拿不到。为了两个平台共用同一套主题，
 * 这里只提供固定配色 —— 代价是 Android 少了个性化取色，换来的是界面代码零分支。
 */
@Composable
fun LingMouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
