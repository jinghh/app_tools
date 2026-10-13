package com.jhh.myapptools.download

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 登录 Cookie 存储（Netscape cookies.txt 格式）。
 *
 * 用途：部分站点（如小红书、微博、B 站高清晰度）需要登录态才能访问，
 * yt-dlp 支持通过 `--cookies` 读取用户自己的浏览器 Cookie 文件。
 * 这属于"以你自己的身份访问你有权查看的内容"，不涉及绕过任何访问控制。
 *
 * 安全约定：
 * - 文件保存在应用私有目录（filesDir），不进入缓存清理范围；
 * - 绝不写入日志（日志侧已有敏感词过滤）；
 * - 仅在下载时以文件名参数传给 yt-dlp，不落任何外部存储。
 */
internal class CookieStore(private val context: Context) {

    private val cookieFile: File get() = File(context.filesDir, "cookies.txt")

    /** 是否已导入可用 Cookie */
    fun isImported(): Boolean = cookieFile.exists() && cookieFile.length() > 0L

    /** 已导入文件大小（字节），未导入返回 0 */
    fun sizeBytes(): Long = if (isImported()) cookieFile.length() else 0L

    /** 传给 yt-dlp 的 --cookies 参数值；未导入时为 null */
    fun pathOrNull(): String? = if (isImported()) cookieFile.absolutePath else null

    /**
     * 从 SAF 选择的文件导入。
     * @return null 表示成功；否则返回面向用户的失败原因
     */
    fun importFrom(uri: Uri): String? {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: return "无法读取所选文件"

            if (text.isBlank()) return "文件内容为空"

            // Netscape 格式首行通常为 "# Netscape HTTP Cookie File" 或 "# HTTP Cookie File"；
            // 不符合时仍允许导入（由 yt-dlp 自行判断），但提示可能不可用
            val looksNetscape = text.lineSequence().take(5).any {
                it.contains("Netscape HTTP Cookie File", ignoreCase = true) ||
                    it.contains("HTTP Cookie File", ignoreCase = true)
            }

            cookieFile.writeText(text)

            if (looksNetscape) {
                null
            } else {
                "已导入，但文件不像标准 Netscape 格式，可能不被 yt-dlp 识别"
            }
        } catch (e: Exception) {
            "导入失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun clear() {
        runCatching { if (cookieFile.exists()) cookieFile.delete() }
    }
}
