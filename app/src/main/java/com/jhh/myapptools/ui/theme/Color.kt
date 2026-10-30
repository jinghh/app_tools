package com.jhh.myapptools.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 品牌配色。
 *
 * 之前只有 `dynamicLightColorScheme()`（Android 12+ 取壁纸色）+ Material3 默认紫色兜底，
 * 结果是**同一个 App 在不同手机上颜色完全不同**，既没有识别度，也和启动图标的绿色对不上。
 *
 * 这里固定一套青绿系配色（与 `ic_launcher_background` 的渐变同源），
 * 动态取色降级为可选开关（见 [AppTheme] 的 dynamicColor 参数）。
 */

// ---- 品牌基准色 ----
val BrandTeal = Color(0xFF0D7A6F)
val BrandEmerald = Color(0xFF0E9F6E)
val BrandMint = Color(0xFF34D399)

val LightColors = lightColorScheme(
    primary = BrandTeal,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA7F3E4),
    onPrimaryContainer = Color(0xFF00201C),

    secondary = Color(0xFF4A635E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E1),
    onSecondaryContainer = Color(0xFF06201B),

    tertiary = Color(0xFF3F6374),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC3E8FB),
    onTertiaryContainer = Color(0xFF001F29),

    background = Color(0xFFFAFDFB),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFAFDFB),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    surfaceTint = BrandTeal,

    // ---- 以下 6 个 surfaceContainer* 必须显式给值 ----
    // Material3 的 Card / NavigationBar / 弹出面板用的是这一组；不填就会落回
    // 基线的**紫色调**默认值（#F7F2FA / #F3EDF7），导致"主色是青绿、卡片却是淡紫"。
    surfaceDim = Color(0xFFD9E2DE),
    surfaceBright = Color(0xFFFAFDFB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4FAF7),
    surfaceContainer = Color(0xFFEEF5F1),
    surfaceContainerHigh = Color(0xFFE8EFEB),
    surfaceContainerHighest = Color(0xFFE2EAE6),

    inverseSurface = Color(0xFF2D3331),
    inverseOnSurface = Color(0xFFEFF2F0),
    inversePrimary = Color(0xFF58DBC5),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF58DBC5),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF74F8E0),

    secondary = Color(0xFFB0CCC5),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF324B46),
    onSecondaryContainer = Color(0xFFCCE8E1),

    tertiary = Color(0xFFA7CCE0),
    onTertiary = Color(0xFF093544),
    tertiaryContainer = Color(0xFF264C5B),
    onTertiaryContainer = Color(0xFFC3E8FB),

    background = Color(0xFF0E1513),
    onBackground = Color(0xFFE0E3E1),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFE0E3E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    outline = Color(0xFF889390),
    outlineVariant = Color(0xFF3F4946),
    surfaceTint = Color(0xFF58DBC5),

    // 同浅色：不填会落回基线的深紫调默认值
    surfaceDim = Color(0xFF0E1513),
    surfaceBright = Color(0xFF343B38),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF161D1B),
    surfaceContainer = Color(0xFF1A211F),
    surfaceContainerHigh = Color(0xFF242B29),
    surfaceContainerHighest = Color(0xFF2F3634),

    inverseSurface = Color(0xFFE0E3E1),
    inverseOnSurface = Color(0xFF2D3331),
    inversePrimary = Color(0xFF0D7A6F),
)
