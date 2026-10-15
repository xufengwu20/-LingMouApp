package com.example.lingmouapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = NavyDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7F1),
    onPrimaryContainer = NavyDeep,
    secondary = Navy,
    onSecondary = Color.White,
    tertiary = Orange,
    onTertiary = Color.White,
    background = PageLight,
    onBackground = Ink,
    surface = PageLight,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF2F7),
    onSurfaceVariant = InkSoft,
    outline = Color(0xFFC7D0DC),
    error = Rose
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7CA4CE),
    onPrimary = Color(0xFF081422),
    primaryContainer = NavyDeep,
    onPrimaryContainer = Color(0xFFDCE7F3),
    secondary = Color(0xFF8FB0D6),
    onSecondary = Color(0xFF0A1626),
    tertiary = Orange,
    onTertiary = Color.White,
    background = PageDark,
    onBackground = Color(0xFFE6EAF2),
    surface = SurfaceDark,
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF233A52),
    onSurfaceVariant = Color(0xFF9FB0C7),
    outline = Color(0xFF3E5670),
    error = Color(0xFFE78484)
)

/**
 * 品牌配色，Android 与 Windows 共用。
 *
 * 深蓝 #1A3A5C + 白色背景 + 橙色 #E67E22 强调。
 * **故意不支持 Material You 动态取色**：`dynamicLightColorScheme(context)`
 * 需要 Android 的 Context，而 commonMain 里拿不到；为了两个平台共用同一套主题，
 * 这里只提供固定配色。
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
