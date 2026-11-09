package com.jhh.myapptools.download

import android.content.Context
import android.webkit.WebSettings

/**
 * 支持「应用内登录 → 自动获取 Cookie」的站点白名单。
 *
 * 为什么要有白名单：任意站点的失败都弹登录页没有意义，而且会诱导用户在陌生页面里
 * 输入账号。这里只登记**已知会因登录态失败、且补 Cookie 后确实有效**的站点。
 *
 * 明确不含 `youtube.com`：README 已记录它的 403 来自 TLS 指纹 / JS 签名挑战，
 * 登录也解不了，弹登录页只会让用户陷入死循环。
 *
 * 关于桌面 UA：小红书、抖音、Instagram 等在**移动 UA** 下只推「下载 App」、不提供
 * WAP 登录页，而站点是按 HTTP 请求头里的 `User-Agent` 分流的 —— 不换 UA 拿不到登录页。
 * Android WebView 没有官方的「请求桌面版站点」开关，改 UA + 开宽视口就是标准做法。
 * 这是**兼容性适配**（请求平台本来提供的 PC 版页面），不是伪装设备绕过风控：
 * 验证码 / 短信仍由本人完成，也不改 yt-dlp 下载端的 UA。
 *
 * 注意：key 必须是 [CookieStore.platformOfUrl] 的归一化结果。
 * `twitter.com` / `t.co` 会被别名映射成 `x.com`，无需单独登记；
 * 展示名、短链别名统一由 [CookieStore] 维护，此处不重复定义。
 */
internal object LoginSites {

    data class Site(
        val platform: String,
        /** 入口链接打不开时的兜底 **PC 版** 登录页；正常流程直接 loadUrl 原始链接让站点自己跳转 */
        val fallbackLoginUrl: String,
        /**
         * 登录态 Cookie 名提示。只用于「可能还没登录成功」的友好提示，
         * **不做硬校验**：站点随时会改名，硬判会把正常登录拦在门外。
         */
        val sessionCookieHints: Set<String>,
        /**
         * 是否请求桌面版页面。默认开启；个别站点若 PC 版反而不好用，置 false 单独关闭即可。
         * 只影响本 WebView 登录页，**不影响下载链路**（yt-dlp 的 UA 是另一回事）。
         */
        val desktopMode: Boolean = true,
        /**
         * 登录态还会挂在哪些「兄弟注册域」上。抖音的 Cookie 跨
         * `douyin.com` 与 `iesdouyin.com`，漏掉后者可能少 `ttwid` 等反爬必需项。
         * 导出器会按各自所属域写 Netscape 的第一列，不会把外域 Cookie 提升到主域。
         */
        val extraCookieDomains: Set<String> = emptySet(),
    )

    private val SITES = listOf(
        Site("xiaohongshu.com", "https://www.xiaohongshu.com/explore", setOf("web_session")),
        Site("x.com", "https://x.com/i/flow/login", setOf("auth_token")),
        Site("weibo.com", "https://passport.weibo.com/sso/signin", setOf("SUB", "SUBP")),
        Site("bilibili.com", "https://passport.bilibili.com/login", setOf("SESSDATA")),
        Site(
            "douyin.com",
            "https://www.douyin.com/",
            setOf("sessionid"),
            extraCookieDomains = setOf("iesdouyin.com"),
        ),
        Site("facebook.com", "https://www.facebook.com/login", setOf("c_user")),
        Site(
            "instagram.com",
            "https://www.instagram.com/accounts/login/",
            setOf("sessionid"),
        ),
    ).associateBy { it.platform }

    /**
     * 已知「登录 / 导入 Cookie 都解决不了」的站点，给界面一句准确说明。
     * 避免把 YouTube 的 TLS 指纹/JS 挑战说成「暂不支持」，更不引导用户去白导一次 Cookie。
     */
    private val UNSUPPORTED_NOTES = mapOf(
        "youtube.com" to "该站点的 403 属于解析库限制，登录或导入 Cookie 均无法解决（见页面帮助）。",
    )

    fun unsupportedNote(platform: String): String? = UNSUPPORTED_NOTES[platform.lowercase()]

    private val CHROME_VERSION = Regex("""Chrome/[\d.]+""")

    /**
     * 从系统 WebView 默认 UA 里取 Chrome 版本再拼桌面 UA。
     * 不写死具体版本号：写死一个过旧的 Chrome 版本本身就是容易被识别的异常特征，
     * 而系统 WebView 会随 Play 商店更新，取它的版本能一直保持合理。
     */
    fun desktopUserAgent(context: Context): String {
        // getDefaultUserAgent 是平台类型 String!，显式兜底避免个别实现返回 null 时 NPE
        val defaultUa = WebSettings.getDefaultUserAgent(context).orEmpty()
        val version = CHROME_VERSION.find(defaultUa)?.value ?: "Chrome/131.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) $version Safari/537.36"
    }

    fun find(platform: String): Site? = SITES[platform.lowercase()]
}
