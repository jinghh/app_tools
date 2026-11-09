package com.jhh.myapptools.download

import java.io.File

/** 下载/解析过程中的事件（解析器与下载器共用） */
sealed interface DownloadEvent {
    data class Stage(val text: String) : DownloadEvent
    data class Log(val line: String) : DownloadEvent
    data class Progress(val fraction: Float, val line: String) : DownloadEvent
    data class Downloaded(val file: File) : DownloadEvent

    /**
     * 下载失败。
     *
     * [reason] 是给界面用的**结构化**原因：此前界面只能拿 [message] 做字符串匹配，
     * 无法可靠判断是否该提供「应用内登录并导入 Cookie」入口。分类与文案由
     * `YtDlpDownloader.friendlyMessage()` 一次性产出，避免两处规则漂移。
     */
    data class Failure(
        val message: String,
        val diagnostics: String?,
        val reason: FailureReason = FailureReason.UNKNOWN,
    ) : DownloadEvent

    data object Cancelled : DownloadEvent
}

/**
 * 失败原因分类。
 *
 * 关键约束：`LOGIN_REQUIRED` 必须保守——只有诊断里出现「跳转到登录页」的特征才判它，
 * 裸 403 不能算（YouTube 的 403 是 TLS 指纹/JS 挑战，弹登录页只会让用户陷入死循环）。
 */
enum class FailureReason {
    /** 站点把链接重定向到了登录页，补上 Cookie 后通常可解 */
    LOGIN_REQUIRED,

    /** 需要 TLS 指纹伪装，当前免费版解析库不支持 */
    TLS_IMPERSONATION,
    NETWORK,
    RATE_LIMIT,
    NOT_FOUND,
    NO_VIDEO,
    UNSUPPORTED,
    UNKNOWN,
}

/** 面向用户的提示文案 + 对应的结构化原因 */
data class FriendlyError(val message: String, val reason: FailureReason)
