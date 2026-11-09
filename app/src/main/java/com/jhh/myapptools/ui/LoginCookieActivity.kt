package com.jhh.myapptools.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jhh.myapptools.download.CookieStore
import com.jhh.myapptools.download.LoginSites
import com.jhh.myapptools.download.WebViewCookieExporter
import com.jhh.myapptools.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * 应用内登录页：让用户在 WebView 里正常登录目标站点，然后把登录态自动转成
 * Netscape cookies.txt 存入 [CookieStore]，免去电脑上用浏览器扩展导出 Cookie。
 *
 * 关键设计：
 * - 直接 `loadUrl(**失败时的原始链接**)`，由站点自己 302 到登录页，比对每个站点
 *   硬编码一个登录 URL 更贴近真实流程，也自然覆盖短链（xhslink.cn → xiaohongshu.com）；
 * - [WebViewClient] 顺路收集本次访问过的所有 host，导出时逐个读 Cookie，
 *   这样 SSO / 跨域跳转的 Cookie 不会漏；
 * - **不自动判定登录完成**：SPA 有多轮跳转，自动抓容易存进一份匿名 Cookie。
 *   抓取/转换/保存全自动，但由用户点一次「完成并导入 Cookie」触发，流程才确定；
 * - 平台在启动时由入口 URL 归一化后固定传入，不依赖 WebView 最终停在哪个域。
 *
 * 隐私：Cookie 只写应用私有目录，不写日志、不外传；`allowBackup=false` 已在 Manifest 全局生效。
 */
class LoginCookieActivity : ComponentActivity() {

    /** 由 factory 赋值，仅用于 onDestroy 销毁；与 Activity 同生命周期，不构成泄漏 */
    private var webViewRef: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryUrl = intent.getStringExtra(EXTRA_ENTRY_URL)?.trim().orEmpty()
        val platform = intent.getStringExtra(EXTRA_PLATFORM)?.trim().orEmpty()
        val fallbackUrl = intent.getStringExtra(EXTRA_FALLBACK_LOGIN_URL)?.trim().orEmpty()

        if (platform.isEmpty() || (!isHttpUrl(entryUrl) && !isHttpUrl(fallbackUrl))) {
            Toast.makeText(this, "登录参数无效，请从下载页重新进入", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        enableEdgeToEdge()
        setContent {
            AppTheme {
                LoginCookieScreen(
                    platform = platform,
                    displayName = CookieStore.displayNameOf(platform),
                    entryUrl = entryUrl,
                    fallbackUrl = fallbackUrl,
                    onWebViewReady = { webViewRef = it },
                    onCancel = { finish() },
                    onImported = { count ->
                        setResult(
                            RESULT_OK,
                            Intent()
                                .putExtra(EXTRA_PLATFORM, platform)
                                .putExtra(EXTRA_COOKIE_COUNT, count),
                        )
                        finish()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        // 不在这里 removeAllCookies：那会在读取之前把刚登录的 Cookie 清掉。
        // 清除登录态由页面上的显式按钮负责。
        webViewRef?.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        webViewRef = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ENTRY_URL = "extra_login_entry_url"
        const val EXTRA_PLATFORM = "extra_login_platform"
        const val EXTRA_FALLBACK_LOGIN_URL = "extra_login_fallback_url"
        const val EXTRA_COOKIE_COUNT = "extra_cookie_count"

        fun intent(
            context: Context,
            entryUrl: String,
            platform: String,
            fallbackUrl: String?,
        ): Intent = Intent(context, LoginCookieActivity::class.java).apply {
            putExtra(EXTRA_ENTRY_URL, entryUrl)
            putExtra(EXTRA_PLATFORM, platform)
            putExtra(EXTRA_FALLBACK_LOGIN_URL, fallbackUrl.orEmpty())
        }
    }
}

@Composable
private fun LoginCookieScreen(
    platform: String,
    displayName: String,
    entryUrl: String,
    fallbackUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onCancel: () -> Unit,
    onImported: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 同步集合：WebViewClient 回调在主线程写；导出前先取快照，再切到 IO 线程
    val visitedHosts = remember { Collections.synchronizedSet(mutableSetOf<String>()) }
    val site = remember(platform) { LoginSites.find(platform) }

    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun collectHost(raw: String?) {
        if (raw.isNullOrBlank()) return
        val host = runCatching { Uri.parse(raw).host }.getOrNull()
        if (!host.isNullOrBlank()) visitedHosts.add(host.lowercase())
    }

    fun importCookies() {
        if (busy) return
        busy = true
        errorText = null
        val hosts = visitedHosts.toSet()
        scope.launch {
            val result = WebViewCookieExporter.export(
                context.applicationContext,
                hosts,
                platform,
                site?.sessionCookieHints.orEmpty(),
            )
            busy = false
            result.fold(
                onSuccess = { outcome ->
                    if (!outcome.looksLoggedIn) {
                        Toast.makeText(
                            context,
                            "已导入，但未检测到常见登录态 Cookie；若下载仍失败，请重新登录一次",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    onImported(outcome.count)
                },
                onFailure = { errorText = it.message ?: "导入失败，请重试" },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        var menuExpanded by remember { mutableStateOf(false) }

        // 顶部只保留「取消 / 标题 / 更多」；主操作固定到底部，WebView 滚动时也够得着
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            Text(
                "登录 $displayName",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("清除网页登录态（换账号）") },
                        onClick = {
                            menuExpanded = false
                            // 全局清理：不影响已保存给下载用的 cookies.txt 文件
                            CookieManager.getInstance()?.apply {
                                removeAllCookies(null)
                                flush()
                            }
                            Toast.makeText(
                                context,
                                "已清除 App 内网页登录态，请重新进入登录页",
                                Toast.LENGTH_LONG,
                            ).show()
                            onCancel()
                        },
                    )
                }
            }
        }

        errorText?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        AndroidView(
            factory = { ctx ->
                val startUrl = if (isHttpUrl(entryUrl)) entryUrl else fallbackUrl
                WebView(ctx).apply {
                    onWebViewReady(this)

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    // 移动 UA 下部分站点只推「下载 App」、没有 WAP 登录页；
                    // 站点按 HTTP 头的 UA 分流，因此按站点请求 PC 版页面。
                    // 只作用于本 WebView 登录页：不改 yt-dlp 下载端 UA，也不绕过任何人机校验。
                    if (site?.desktopMode == true) {
                        settings.userAgentString = LoginSites.desktopUserAgent(ctx)
                        // PC 页面在手机窄屏上要能操作：宽视口 + 整体缩放适配 + 手势缩放
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                    }
                    // 其余保持 WebView 默认：不开 file access、不放行 mixed content

                    CookieManager.getInstance()?.let { cm ->
                        cm.setAcceptCookie(true)
                        // SSO / 验证码 SDK 依赖第三方 Cookie，Android 21+ 默认是关的
                        cm.setAcceptThirdPartyCookies(this, true)
                    }
                    // 否则页面里的 alert/confirm（登录错误提示）不会显示
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun doUpdateVisitedHistory(
                            view: WebView?, url: String?, isReload: Boolean,
                        ) {
                            collectHost(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            collectHost(url)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            // 登录页可能跳 weixin:// 等外部 scheme，WebView 直接加载会失败
                            val scheme = request?.url?.scheme?.lowercase() ?: return false
                            return scheme != "http" && scheme != "https"
                        }
                    }

                    collectHost(startUrl)
                    loadUrl(startUrl)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        // 底部固定主按钮：登录页再长也不会把它滚出屏幕
        Button(
            onClick = { importCookies() },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(if (busy) "导入中…" else "完成并导入 Cookie")
        }
    }
}

private fun isHttpUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) ||
        url.startsWith("http://", ignoreCase = true)
