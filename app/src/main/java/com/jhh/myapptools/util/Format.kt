package com.jhh.myapptools.util

import java.util.Locale

/**
 * 体积格式化。
 *
 * 文档 3.3 约定：UI 标注 MB，内部按十进制 MB（1 MB = 1,000,000 bytes）固定换算。
 *
 * 放在 util 包而非 ui 包，是为了让 download 等非界面模块也能复用，
 * 避免出现 `ui → download → ui` 的包级循环依赖。
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1_000_000 -> String.format(Locale.US, "%.1f KB", bytes / 1000.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
}
