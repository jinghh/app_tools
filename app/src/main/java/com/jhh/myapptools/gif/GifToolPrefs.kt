package com.jhh.myapptools.gif

import android.content.Context

/**
 * 转换参数记忆：把用户上次调过的 fps / 宽度 / 颜色数 / 抖动存下来，下次进页面直接恢复，
 * 免去每次重调（画质预设也以此为基础：预设给起点，手动微调后成为新的记忆值）。
 * 沿用 NetworkPrefs / NotificationPrefs 的 SharedPreferences 模式。
 */
internal class GifToolPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun fps(default: Int = 12): Int = prefs.getInt(KEY_FPS, default)

    fun width(default: Int = 480): Int = prefs.getInt(KEY_WIDTH, default)

    fun maxColors(default: Int = 256): Int = prefs.getInt(KEY_COLORS, default)

    fun dither(default: Boolean = true): Boolean = prefs.getBoolean(KEY_DITHER, default)

    fun save(fps: Int, width: Int, maxColors: Int, dither: Boolean) {
        prefs.edit()
            .putInt(KEY_FPS, fps)
            .putInt(KEY_WIDTH, width)
            .putInt(KEY_COLORS, maxColors)
            .putBoolean(KEY_DITHER, dither)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "gif_tool"
        const val KEY_FPS = "fps"
        const val KEY_WIDTH = "width"
        const val KEY_COLORS = "max_colors"
        const val KEY_DITHER = "dither"
    }
}
