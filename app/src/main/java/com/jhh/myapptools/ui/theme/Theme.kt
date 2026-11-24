package com.jhh.myapptools.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

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

    // AppTypography 里的 TextStyle 没有设 color，Compose 会回退到 Color.Unspecified（黑色），
    // 导致深色主题下标题/正文全黑。这里按当前 colorScheme 统一注入 onSurface，
    // 所有 Text 自动跟随主题；个别需要弱化/强调的地方仍可在调用点显式覆盖 color。
    val themedTypography = AppTypography.copy(
        headlineLarge = AppTypography.headlineLarge.withColor(colorScheme.onSurface),
        headlineMedium = AppTypography.headlineMedium.withColor(colorScheme.onSurface),
        headlineSmall = AppTypography.headlineSmall.withColor(colorScheme.onSurface),
        titleLarge = AppTypography.titleLarge.withColor(colorScheme.onSurface),
        titleMedium = AppTypography.titleMedium.withColor(colorScheme.onSurface),
        titleSmall = AppTypography.titleSmall.withColor(colorScheme.onSurface),
        bodyLarge = AppTypography.bodyLarge.withColor(colorScheme.onSurface),
        bodyMedium = AppTypography.bodyMedium.withColor(colorScheme.onSurface),
        bodySmall = AppTypography.bodySmall.withColor(colorScheme.onSurface),
        labelLarge = AppTypography.labelLarge.withColor(colorScheme.onSurface),
        labelMedium = AppTypography.labelMedium.withColor(colorScheme.onSurface),
        labelSmall = AppTypography.labelSmall.withColor(colorScheme.onSurface),
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = themedTypography,
        content = content,
    )
}

private fun TextStyle.withColor(color: Color): TextStyle = copy(color = color)
