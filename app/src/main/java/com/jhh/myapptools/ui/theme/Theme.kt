package com.jhh.myapptools.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 应用主题。
 *
 * 配色策略：**默认使用固定品牌色**（见 [LightColors] / [DarkColors]），
 * 动态取色（Android 12+ 跟随壁纸）降级为可选。
 *
 * 为什么不再默认动态取色：取色会让同一个 App 在每台设备上呈现完全不同的主色
 * （紫/蓝/粉…），既失去品牌识别，也和启动图标的青绿渐变对不上。
 * 若以后想要"跟随系统"，把 [dynamicColor] 传 true 即可，无需改其它代码。
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
