package com.jhh.myapptools.download

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把 WebView 的登录态导出成 yt-dlp 能读的 Netscape cookies.txt。
 *
 * 为什么可行：`CookieManager.getCookie(url)` 走的是 Chromium 网络栈，**包含 HttpOnly
 * Cookie**（登录态几乎都是 HttpOnly），这与 `document.cookie` 不同。
 *
 * 但它只返回 `name=value; ...`，不返回 domain / path / expires / secure 属性，
 * 因此这里按 Netscape 7 列补齐。取舍与风险：
 * - domain 按 Cookie 所属注册域写父域 `.<domain>` + includeSubdomains=TRUE：yt-dlp 访问
 *   页面/API 子域时都能带上，但会把原本只属于某个子域的 Cookie 也发给同站其他子域；
 * - 只收集**平台自身及站点声明的兄弟注册域**（如抖音的 `iesdouyin.com`）及其子域，
 *   第三方（验证码/CDN/SSO 外域）一律排除，避免把外域凭证提升到主域；
 * - expires 必须填远期值：填 0 会被 `http.cookiejar.clear_expired_cookies()` 当作
 *   1970 年的过期项清掉，导致 Cookie 静默失效；
 * - 属性是补齐的近似值，且 WebView（Chrome UA / Chromium TLS）与 yt-dlp
 *   （Python requests）指纹不同，站点风控仍可能拒绝，这点代码无法保证。
 */
internal object WebViewCookieExporter {

    data class Outcome(
        /** 成功写入的 Cookie 条数 */
        val count: Int,
        /** 是否命中站点配置的登录态 Cookie 名；仅用于提示，不阻断导入 */
        val looksLoggedIn: Boolean,
    )

    private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"
    private const val VALID_FOR_SECONDS = 30L * 24 * 3600

    /**
     * @param hosts               本次登录访问过的所有 host（由 WebViewClient 收集）
     * @param platform            已归一化的注册域，决定写入哪个文件
     * @param sessionCookieHints  站点配置的登录态 Cookie 名，可为空
     */
    suspend fun export(
        context: Context,
        hosts: Set<String>,
        platform: String,
        sessionCookieHints: Set<String> = emptySet(),
    ): Result<Outcome> = withContext(Dispatchers.IO) {
        runCatching {
            // getCookie() 会阻塞等 Cookie 存储加载，必须在 IO 线程；flush 确保写入已落地
            val manager = CookieManager.getInstance() ?: error("CookieManager 不可用")
            manager.flush()

            val normalized = platform.trim().trimStart('.').lowercase()
            check(normalized.isNotBlank()) { "站点无效" }

            // 主域 + 站点配置里声明的兄弟注册域（如抖音的 iesdouyin.com）
            val allDomains = listOf(normalized) +
                LoginSites.find(normalized)?.extraCookieDomains.orEmpty()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
            val expires = System.currentTimeMillis() / 1000 + VALID_FOR_SECONDS
            val rows = mutableListOf<String>()
            val names = mutableSetOf<String>()
            // 去重键带上 domain：不同注册域下的同名 Cookie（如 ttwid）要各自保留
            val seen = mutableSetOf<Pair<String, String>>()

            hosts.asSequence()
                .map { it.trim().lowercase() }
                .filter { host ->
                    host.isNotBlank() && !host.contains('/') && !host.contains(':') &&
                        allDomains.any { host == it || host.endsWith(".$it") }
                }
                .distinct()
                // 固定遍历顺序：hosts 来自 HashSet，不排序会让同名 Cookie 的取舍随机
                .sorted()
                .forEach { host ->
                    // 该 host 属于哪个注册域就按哪个域写第一列，
                    // 不能把 iesdouyin.com 的 Cookie 提升成 .douyin.com
                    val owner = allDomains.firstOrNull { host == it || host.endsWith(".$it") }
                        ?: return@forEach
                    val domain = ".$owner"
                    val raw = runCatching { manager.getCookie("https://$host/") }
                        .getOrNull()
                        .orEmpty()
                    if (raw.isBlank()) return@forEach

                    raw.split(';')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach { pair ->
                            val name = pair.substringBefore('=')
                            if (name.isEmpty() || !seen.add(domain to name)) return@forEach
                            names.add(name)
                            // Cookie 值常含 '='（base64 / 签名），不能用 split('=') 拆
                            val value = pair.substringAfter('=', missingDelimiterValue = "")
                            rows += listOf(
                                domain, "TRUE", "/", "TRUE", expires.toString(), name, value,
                            ).joinToString("\t")
                        }
                }

            check(rows.isNotEmpty()) { "未读取到 Cookie，请确认已完成登录" }

            val text = buildString {
                append(NETSCAPE_HEADER).append('\n')
                rows.forEach { append(it).append('\n') }
            }

            // 复用 CookieStore 的按平台落盘 + 原子写入，与 SAF 导入路径行为完全一致
            val appContext = context.applicationContext
            when (val result = CookieStore(appContext).saveForPlatform(normalized, text)) {
                is CookieStore.ImportResult.Success,
                is CookieStore.ImportResult.Warning -> Unit

                is CookieStore.ImportResult.Failure -> error(result.message)
            }

            val looksLoggedIn = sessionCookieHints.isEmpty() ||
                names.any { name ->
                    sessionCookieHints.any { it.equals(name, ignoreCase = true) }
                }
            Outcome(rows.size, looksLoggedIn)
        }
    }
}
