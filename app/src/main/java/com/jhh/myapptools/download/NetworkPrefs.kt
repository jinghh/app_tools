package com.jhh.myapptools.download

import android.content.Context

/**
 * 网络设置（目前只有代理）。
 *
 * 为什么 App 内要单独提供代理：yt-dlp 跑在**内嵌 CPython** 里，用的是 Python 的网络栈，
 * 它只从环境变量（http_proxy / https_proxy）读代理，**不读 Android 的系统代理设置**
 * （Wi-Fi 里配的"代理"对内嵌 Python 完全无效）。所以要让 yt-dlp 走代理，只有两条路：
 * 1. 用 VPN 类应用（VpnService 全局接管流量）—— 网络层生效，本设置留空即可；
 * 2. 在这里显式填代理地址 —— 通过 yt-dlp 的 `--proxy` 参数传下去。
 *
 * 留空 = 不传 `--proxy`，保持 yt-dlp 的默认行为。
 */
internal class NetworkPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("network", Context.MODE_PRIVATE)

    /** 原始输入（可能不含 scheme） */
    fun proxy(): String = prefs.getString(KEY_PROXY, "").orEmpty().trim()

    fun setProxy(value: String) {
        prefs.edit().putString(KEY_PROXY, value.trim()).apply()
    }

    /** 交给 yt-dlp 的 `--proxy` 值；未配置或格式非法时返回 null（不传参） */
    fun normalizedProxyOrNull(): String? = normalize(proxy())

    companion object {
        private const val KEY_PROXY = "proxy"

        /**
         * yt-dlp 支持的代理协议：http(s) / socks4 / socks5(h)。
         * 主机部分允许域名、IPv4，以及带方括号的 IPv6；端口必须是纯数字，
         * 否则 `host:abc` 这类会被误判为合法，进而产生难懂的下游报错。
         */
        private val PROXY_REGEX = Regex(
            "^(https?|socks4|socks5h?)://(?:[A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:]+\\])(?::\\d{1,5})?\$",
            RegexOption.IGNORE_CASE,
        )

        /** 允许用户只填 host:port，自动补 http:// */
        fun normalize(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            val withScheme = if ("://" in value) value else "http://$value"
            return if (PROXY_REGEX.matches(withScheme)) withScheme else null
        }

        /** 界面校验用：空串视为合法（等于不启用） */
        fun isValid(raw: String): Boolean = raw.isBlank() || normalize(raw) != null
    }
}
