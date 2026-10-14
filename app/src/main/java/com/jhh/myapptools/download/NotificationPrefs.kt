package com.jhh.myapptools.download

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 下载通知偏好 + 系统通知权限状态。
 *
 * **重要技术约束**：App 无法自行授予或撤销通知权限——只能由用户在系统弹窗或系统设置里操作。
 * 因此这里必须区分两个独立概念，且**两者都不影响下载本身**：
 *
 * 1. [isResultNotifyEnabled]：App 内偏好，决定是否发送"下载完成/失败"的结果通知。
 *    用户关掉后，我们不再自动请求权限，也不再发结果通知（尊重选择）。
 * 2. [isSystemPermissionGranted]：系统层面的通知权限，决定通知能否真正显示出来。
 *
 * 另外，**下载进行中的进度通知无法通过本偏好关闭**：前台服务被系统强制要求显示通知，
 * 正是它保证切到后台时下载不被冻结。要完全关闭通知只能在系统设置里撤销权限
 * （此时服务仍可运行、下载照常，只是通知不显示）。
 */
internal class NotificationPrefs(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 是否发送下载结果通知（默认开启） */
    fun isResultNotifyEnabled(): Boolean = prefs.getBoolean(KEY_RESULT_NOTIFY, true)

    fun setResultNotifyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESULT_NOTIFY, enabled).apply()
    }

    /** 系统通知权限是否已授予（Android 13 以下的通知权限在安装时即授予，恒为 true） */
    fun isSystemPermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** 系统层面是否允许本应用发通知（用户可在系统设置里整体关闭） */
    fun areNotificationsEnabledInSystem(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    companion object {
        val PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS

        private const val PREFS_NAME = "download_notifications"
        private const val KEY_RESULT_NOTIFY = "result_notify_enabled"
    }
}
