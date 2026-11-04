package com.jhh.myapptools.image

import android.content.Context

/**
 * 图片压缩参数记忆（仿 `gif/GifToolPrefs.kt`）：
 * 上次用的模式 / 质量 / 目标大小 / 输出尺寸 / 输出格式，进页面直接恢复，
 * 免去每次重调（压缩是高频重复操作，参数却几乎不变）。
 */
internal class ImageToolPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次的压缩模式（CompressMode 的 name） */
    fun mode(default: String): String = prefs.getString(KEY_MODE, default) ?: default

    /** 质量滑块的 Float 值（字符串存储，兼容旧版输入框留下的纯数字） */
    fun quality(default: String): String = prefs.getString(KEY_QUALITY, default) ?: default

    fun target(default: String): String = prefs.getString(KEY_TARGET, default) ?: default

    /** 输出尺寸选择（SIZE_OPTIONS 的持久化 key） */
    fun size(default: String): String = prefs.getString(KEY_SIZE, default) ?: default

    /** 自定义长边输入框原文 */
    fun customEdge(default: String): String = prefs.getString(KEY_CUSTOM_EDGE, default) ?: default

    /** 输出格式选择（ImageFormat 的 name，KEEP 表示保持原格式） */
    fun format(default: String): String = prefs.getString(KEY_FORMAT, default) ?: default

    fun save(
        mode: String,
        quality: String,
        target: String,
        size: String,
        customEdge: String,
        format: String,
    ) {
        prefs.edit()
            .putString(KEY_MODE, mode)
            .putString(KEY_QUALITY, quality)
            .putString(KEY_TARGET, target)
            .putString(KEY_SIZE, size)
            .putString(KEY_CUSTOM_EDGE, customEdge)
            .putString(KEY_FORMAT, format)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "image_tool"
        const val KEY_MODE = "mode"
        const val KEY_QUALITY = "quality"
        const val KEY_TARGET = "target"
        const val KEY_SIZE = "size"
        const val KEY_CUSTOM_EDGE = "custom_edge"
        const val KEY_FORMAT = "format"
    }
}
