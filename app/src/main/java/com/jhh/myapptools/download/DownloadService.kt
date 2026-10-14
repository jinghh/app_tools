package com.jhh.myapptools.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jhh.myapptools.MainActivity
import com.jhh.myapptools.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 下载前台服务：**唯一职责是把进程保持在前台状态**，使下载在 App 退到后台时
 * 不被 Cached Apps Freezer 冻结、也不被系统回收（原因详见 [DownloadCenter] 注释）。
 *
 * 它不做下载本身（下载跑在 [DownloadCenter] 的进程级作用域里），只做两件事：
 * 1. 显示/更新进度通知（前台服务必须有可见通知）；
 * 2. 下载结束时把进度通知换成一条**结果通知**（完成 / 失败），供用户切出 App 后也能知道结果。
 *
 * 通知分两个渠道：
 * - [CHANNEL_PROGRESS] 低优先级、静音、常驻（进度用）；
 * - [CHANNEL_RESULT] 默认优先级、可自动取消（结果提醒用，会有提示音/横幅）。
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        // 收集器放在 onCreate：每个服务实例只启动一次。
        // 若放在 onStartCommand，stopSelf() 后服务被复用时会出现两个收集器 → 重复发结果通知。
        observeDownloadState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 之后必须在 5 秒内调用 startForeground，否则 ANR/崩溃。
        // 这里用当前状态先占位，随后由收集器持续更新。
        // （DownloadCenter 保证调用本服务前状态已置为 RUNNING，不会一创建就自我停止）
        promoteToForeground(DownloadCenter.state.value)
        // 进程被杀后不自动重启：当前 yt-dlp 路径无法续传，重启无意义
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 观察下载状态并驱动通知。
     *
     * 去重规则：**进度每次都更新**（否则通知会停在初始进度）；**终态只在阶段真正变化时
     * 处理一次**（下载成功后 DownloadCenter 还会补一次元信息更新，会产生第二次 SUCCESS
     * 发射，不去重会重复弹出「下载完成」通知）。
     */
    private fun observeDownloadState() {
        scope.launch {
            var lastPhase: DownloadPhase? = null
            DownloadCenter.state.collect { state ->
                val phaseChanged = state.phase != lastPhase
                lastPhase = state.phase
                when (state.phase) {
                    DownloadPhase.RUNNING -> updateProgressNotification(state)

                    DownloadPhase.SUCCESS -> if (phaseChanged) {
                        // 先撤掉常驻进度通知，再发一条独立的结果通知
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        notifyResult(
                            title = "下载完成",
                            text = state.file?.name ?: "视频已保存到应用缓存",
                            success = true,
                        )
                        stopSelf()
                    }

                    DownloadPhase.FAILED -> if (phaseChanged) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        notifyResult(
                            title = "下载失败",
                            text = state.error ?: "请回到 App 查看详细原因",
                            success = false,
                        )
                        stopSelf()
                    }

                    // 取消 / 空闲：安静退出，不打扰用户（App 内已有状态提示）
                    DownloadPhase.CANCELLED, DownloadPhase.IDLE -> if (phaseChanged) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    // ---------- 前台服务 ----------

    private fun promoteToForeground(state: DownloadUiState) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(state),
            // Android 14+ 要求声明类型，下载属于"数据传输"；ServiceCompat 在低版本会忽略该参数
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun updateProgressNotification(state: DownloadUiState) {
        if (!canPostNotifications()) return
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID_PROGRESS, buildProgressNotification(state))
        }
    }

    private fun buildProgressNotification(state: DownloadUiState): Notification {
        val percent = (state.fraction * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在下载视频")
            .setContentText(state.stage.ifBlank { "下载中…" })
            .setProgress(100, percent, state.fraction <= 0f)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    /** 下载结束后的结果通知（切出 App 也能知道结果） */
    private fun notifyResult(title: String, text: String, success: Boolean) {
        if (!canPostNotifications()) return
        // 用户在 App 内关掉了结果通知：尊重其选择，不打扰。
        // 注意：这里的开关只作用于"结果通知"；进度通知是前台服务的强制要求，不受其影响。
        if (!NotificationPrefs(this).isResultNotifyEnabled()) return

        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply {
                if (!success) setSubText("可在 App 内查看诊断信息")
            }
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_RESULT, notification)
        }
    }

    /** 点击通知打开 App 的「视频下载」页 */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DOWNLOAD)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---------- 通知渠道 ----------

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannels()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "下载进度",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示正在进行的下载进度（常驻、静音）"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                "下载结果",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "下载完成或失败时提醒"
            }
        )
    }

    /**
     * Android 13+ 通知需运行时授权。未授权时前台服务仍可运行（进程保护不受影响），
     * 只是通知不显示——所以这里只跳过发通知，绝不影响下载本身。
     */
    private fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    companion object {
        private const val CHANNEL_PROGRESS = "download_progress"
        private const val CHANNEL_RESULT = "download_result"
        private const val NOTIFICATION_ID_PROGRESS = 1001
        private const val NOTIFICATION_ID_RESULT = 1002

        /** 启动前台服务（必须在 App 处于前台时调用，Android 12+ 的后台启动限制） */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java),
            )
        }
    }
}
