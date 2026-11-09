package com.jhh.myapptools.ui

import android.content.Context

/**
 * 界面层的轻量偏好（只存字符串，够用即可）。
 *
 * 为什么不并到 `NotificationPrefs`：那个类语义是"通知设置"，
 * 把"上次用的目标大小"塞进去会让职责变混。
 *
 * 已持久化的东西盘点：
 * - 通知开关 → `NotificationPrefs`
 * - 转 GIF 的 fps/宽度/色数/抖动 → `GifToolPrefs`
 * - 是否携带 Cookie → `CookieStore`
 * - **目标大小（本类，C6）** —— 每次压缩都要重填一个数字，属于高频重复输入
 */
internal object AppPrefs {

    private const val NAME = "ui_prefs"

    const val KEY_TARGET_GIF = "target_mb_gif"
    const val KEY_TARGET_VIDEO = "target_mb_video"

    fun getString(context: Context, key: String, default: String): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(key, default)
            ?: default

    fun setString(context: Context, key: String, value: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }
}
