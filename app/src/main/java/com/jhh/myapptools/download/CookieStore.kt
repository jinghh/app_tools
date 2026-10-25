package com.jhh.myapptools.download

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.URI

/**
 * 登录 Cookie 存储（Netscape cookies.txt 格式，**按平台分别保存**）。
 *
 * 为什么改成按平台存：
 * 之前是单一 `cookies.txt`，导入小红书后**所有**下载都会带上它 —— 下 X 视频时会把
 * 小红书的会话 cookie 发给 x.com。既没必要，也凭空扩大了凭证暴露面。
 * 现在每个平台各存一份 `filesDir/cookies/<注册域>.txt`，下载时按目标链接自动匹配，
 * 只把对应站点的 cookie 交给 yt-dlp；导入一次即长期保留，可随时单独清除。
 *
 * 安全约定：
 * - 文件保存在应用私有目录，不进入缓存清理范围；
 * - 绝不写入日志；
 * - 仅在下载时以文件路径传给 yt-dlp，不落任何外部存储。
 */
internal class CookieStore(private val context: Context) {

    /** 「下载时携带 Cookie」开关的持久化（代理/通知开关均已持久化，唯独它此前没有） */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 一个已导入的平台 */
    data class Entry(
        /** 归一化注册域，如 xiaohongshu.com */
        val platform: String,
        /** 展示名，如 小红书 */
        val displayName: String,
        val file: File,
        val sizeBytes: Long,
    )

    /** 导入结果 */
    sealed interface ImportResult {
        data class Success(val entry: Entry) : ImportResult

        /** 已导入，但格式可疑（仍写入，由 yt-dlp 自行判断） */
        data class Warning(val entry: Entry, val message: String) : ImportResult

        data class Failure(val message: String) : ImportResult
    }

    private val dir: File get() = File(context.filesDir, COOKIE_DIR).apply { mkdirs() }

    init {
        migrateLegacySingleFile()
    }

    /** 界面「下载时携带 Cookie」开关；实际是否带 Cookie 仍按平台自动匹配 */
    fun isUseCookieEnabled(): Boolean = prefs.getBoolean(KEY_USE_COOKIE, true)

    fun setUseCookieEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_COOKIE, value).apply()
    }

    /** 已导入的平台，按展示名排序 */
    fun list(): List<Entry> = dir.listFiles()
        ?.filter { it.isFile && it.length() > 0L && it.name.endsWith(".txt") }
        ?.mapNotNull { f ->
            val platform = f.name.removeSuffix(".txt")
            if (platform.isBlank()) null else Entry(platform, displayNameOf(platform), f, f.length())
        }
        ?.sortedBy { it.displayName }
        ?: emptyList()

    fun clear(platform: String) {
        runCatching { File(dir, "$platform.txt").takeIf { it.exists() }?.delete() }
    }

    /**
     * 按目标链接匹配应使用的 Cookie。
     *
     * 顺序：① 短链别名（xhslink.cn → xiaohongshu.com 等）→ ② 精确或后缀匹配。
     * 匹配不到返回 null（即本次不带 cookie，而不是"退而求其次拿别人的"）。
     */
    fun matchForUrl(url: String): Entry? {
        val host = hostOf(url) ?: return null
        val entries = list()
        if (entries.isEmpty()) return null

        HOST_ALIASES[host]?.let { aliased ->
            entries.firstOrNull { it.platform == aliased }?.let { return it }
        }
        return entries.firstOrNull { host == it.platform || host.endsWith("." + it.platform) }
    }

    /** 从 SAF 选择的文件导入；平台由文件内容自动识别 */
    fun importFrom(uri: Uri): ImportResult {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: return ImportResult.Failure("无法读取所选文件")
        } catch (e: Exception) {
            return ImportResult.Failure("导入失败：${e.message ?: e.javaClass.simpleName}")
        }

        if (text.isBlank()) return ImportResult.Failure("文件内容为空")

        val platform = detectPlatform(text)
            ?: return ImportResult.Failure("无法从文件中识别站点，请确认是浏览器导出的 Netscape cookies.txt")

        val file = File(dir, "$platform.txt")
        return try {
            file.writeText(text)
            val entry = Entry(platform, displayNameOf(platform), file, file.length())
            // Netscape 格式首行通常为 "# Netscape HTTP Cookie File" / "# HTTP Cookie File"
            val looksNetscape = text.lineSequence().take(5).any {
                it.contains("Netscape HTTP Cookie File", ignoreCase = true) ||
                    it.contains("HTTP Cookie File", ignoreCase = true)
            }
            if (looksNetscape) {
                ImportResult.Success(entry)
            } else {
                ImportResult.Warning(entry, "已导入，但文件不像标准 Netscape 格式，可能不被 yt-dlp 识别")
            }
        } catch (e: Exception) {
            ImportResult.Failure("写入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- 内部 ----------

    /** 旧版本只有一个 cookies.txt；迁移到新目录，避免用户重新导入 */
    private fun migrateLegacySingleFile() {
        val legacy = File(context.filesDir, LEGACY_FILE)
        if (!legacy.exists() || legacy.length() <= 0L) return
        val text = runCatching { legacy.readText() }.getOrNull() ?: return
        val platform = detectPlatform(text) ?: return
        runCatching {
            File(dir, "$platform.txt").writeText(text)
            legacy.delete()
        }
    }

    /** 从 cookie 行里统计出现最多的注册域 */
    private fun detectPlatform(text: String): String? {
        val counts = mutableMapOf<String, Int>()
        for (rawLine in text.lineSequence()) {
            // HttpOnly cookie 在 Netscape 格式里带 "#HttpOnly_" 前缀，不能当注释跳过
            val line = rawLine.removePrefix(HTTP_ONLY_PREFIX)
            if (line.isBlank() || line.startsWith("#")) continue
            val fields = line.split('\t')
            if (fields.size < 7) continue
            val domain = baseDomain(fields[0])
            if (domain.isNotEmpty()) counts[domain] = (counts[domain] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    private fun hostOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        return runCatching { URI(withScheme).host?.lowercase() }.getOrNull()
    }

    private companion object {
        const val COOKIE_DIR = "cookies"
        const val LEGACY_FILE = "cookies.txt"
        const val HTTP_ONLY_PREFIX = "#HttpOnly_"
        const val PREFS_NAME = "cookie_prefs"
        const val KEY_USE_COOKIE = "use_cookie"

        /** 双段公共后缀，取注册域时需多保留一级 */
        val MULTI_PART_SUFFIXES = setOf(
            "com.cn", "net.cn", "org.cn", "gov.cn",
            "co.uk", "com.hk", "com.tw", "com.au", "co.jp",
        )

        val DISPLAY_NAMES = mapOf(
            "xiaohongshu.com" to "小红书",
            "x.com" to "X（Twitter）",
            "twitter.com" to "X（Twitter）",
            "weibo.com" to "微博",
            "bilibili.com" to "哔哩哔哩",
            "douyin.com" to "抖音",
            "facebook.com" to "Facebook",
            "instagram.com" to "Instagram",
            "youtube.com" to "YouTube",
        )

        /** 短链域名 → 实际站点（xhslink.cn 会 302 到 xiaohongshu.com） */
        val HOST_ALIASES = mapOf(
            "xhslink.cn" to "xiaohongshu.com",
            "xhslink.com" to "xiaohongshu.com",
            "t.co" to "x.com",
            "twitter.com" to "x.com",
            "fb.watch" to "facebook.com",
            "fb.com" to "facebook.com",
            "b23.tv" to "bilibili.com",
            "v.douyin.com" to "douyin.com",
            "youtu.be" to "youtube.com",
        )

        fun displayNameOf(platform: String): String = DISPLAY_NAMES[platform] ?: platform

        /** www.xiaohongshu.com / .xiaohongshu.com → xiaohongshu.com */
        fun baseDomain(raw: String): String {
            val host = raw.trim().trimStart('.').lowercase()
            if (host.isEmpty()) return ""
            if (host.all { it.isDigit() || it == '.' }) return host
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val lastTwo = parts.takeLast(2).joinToString(".")
            return if (lastTwo in MULTI_PART_SUFFIXES) {
                parts.takeLast(3).joinToString(".")
            } else {
                lastTwo
            }
        }
    }
}
