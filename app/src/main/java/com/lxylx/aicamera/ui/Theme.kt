package com.lxylx.aicamera.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 相机页专用强调色（相机界面固定黑色底） */
val Accent = Color(0xFFE9C46A)

/** 次要文字 */
val DimText = Color(0xFF9A9A9A)

/**
 * 固定深色主题（稳定版）。
 * 「跟随系统深浅色 + 动态取色」在部分机型上有启动风险，先回退。
 */
@Composable
fun AICameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            background = Color(0xFF0B0B0B),
            onBackground = Color.White,
            surface = Color(0xFF141414),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1E1E1E),
            secondary = Color(0xFF8AB4F8)
        ),
        content = content
    )
}
