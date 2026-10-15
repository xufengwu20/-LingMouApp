package com.example.lingmouapp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 内置图标。
 *
 * 为什么不直接用 `androidx.compose.material.icons.Icons.Default.Home`：
 * `material-icons-core` 在 CMP 1.9 已不再随 material3 附带，
 * 单独引 1.7.3 会与 1.9.3 的运行时产生版本错配。两个图标自己画只有十几行，
 * 零额外依赖，Icon 的 tint 照常生效（选中色由 NavigationBarItem 控制）。
 */

/** 房子 —— 「模式」Tab */
internal val HomeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Home",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 3f)
            lineTo(3f, 10.5f)
            horizontalLineTo(5.5f)
            verticalLineTo(21f)
            horizontalLineTo(10f)
            verticalLineTo(14.5f)
            horizontalLineTo(14f)
            verticalLineTo(21f)
            horizontalLineTo(18.5f)
            verticalLineTo(10.5f)
            horizontalLineTo(21f)
            close()
        }
    }.build()
}

/** 人形 —— 「用户」Tab / 头像占位 */
internal val PersonIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Person",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // 头
            moveTo(12f, 12f)
            curveTo(14.2f, 12f, 16f, 10.2f, 16f, 8f)
            curveTo(16f, 5.8f, 14.2f, 4f, 12f, 4f)
            curveTo(9.8f, 4f, 8f, 5.8f, 8f, 8f)
            curveTo(8f, 10.2f, 9.8f, 12f, 12f, 12f)
            close()
            // 肩
            moveTo(12f, 14f)
            curveTo(9.3f, 14f, 4f, 15.3f, 4f, 18f)
            verticalLineTo(20f)
            horizontalLineTo(20f)
            verticalLineTo(18f)
            curveTo(20f, 15.3f, 14.7f, 14f, 12f, 14f)
            close()
        }
    }.build()
}
