package com.jhh.myapptools

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jhh.myapptools.download.UrlExtractor
import com.jhh.myapptools.ui.DownloadScreen
import com.jhh.myapptools.ui.GifCompressScreen
import com.jhh.myapptools.ui.GifToolScreen
import com.jhh.myapptools.ui.VideoCompressScreen
import com.jhh.myapptools.ui.theme.AppTheme
import java.io.File

class MainActivity : ComponentActivity() {

    /** 通知点击等外部入口请求的页签；变化时 Compose 会切过去 */
    private var requestedTab by mutableIntStateOf(TAB_DOWNLOAD)

    /** 分享直达（ACTION_SEND）提取出的链接；下载页填充后置空 */
    private var pendingShareUrl by mutableStateOf<String?>(null)

    /**
     * 窗口获得焦点的次数。
     *
     * 剪贴板检测必须挂在它上面，而不是 ON_RESUME：
     * 1. Android 10+ 只有窗口获得焦点时才允许读剪贴板（onResume 时往往还没焦点，读到 null）；
     * 2. 冷启动时组合发生在 onResume 之后，`LifecycleEventEffect(ON_RESUME)` 永远收不到首次事件。
     * 每次重新获得焦点（冷启动、从别的 App 切回来、关掉弹窗）都会 +1，Compose 侧据此检测。
     */
    private var windowFocusTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTab = intent.readTabExtra()
        pendingShareUrl = intent.sharedUrl()
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen(
                    requestedTab = requestedTab,
                    sharedUrl = pendingShareUrl,
                    onSharedUrlConsumed = { pendingShareUrl = null },
                    focusTick = windowFocusTick,
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) windowFocusTick++
    }

    /** 点击通知/外部分享进入时，Activity 已在栈顶 → 走这里切页签，不重建界面 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = intent.readTabExtra()
        intent.sharedUrl()?.let { pendingShareUrl = it }
    }

    private fun Intent?.readTabExtra(): Int =
        this?.getIntExtra(EXTRA_TAB, TAB_DOWNLOAD) ?: TAB_DOWNLOAD

    /** 分享文本里的第一条链接；不是 ACTION_SEND 或没有链接则返回 null */
    private fun Intent?.sharedUrl(): String? =
        this?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { UrlExtractor.extract(it) }

    companion object {
        const val EXTRA_TAB = "extra_tab"
        /** 页签按使用频率排序：X 下载（最常用，默认页）→ 视频转 GIF → GIF 压缩 → 视频压缩 */
        const val TAB_DOWNLOAD = 0
        const val TAB_GIF = 1
        const val TAB_COMPRESS = 2
        const val TAB_VIDEO_COMPRESS = 3
    }
}

@Composable
private fun MainScreen(
    requestedTab: Int,
    sharedUrl: String?,
    onSharedUrlConsumed: () -> Unit,
    /** 窗口获得焦点的次数（每次 +1 触发一次剪贴板检测） */
    focusTick: Int,
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(requestedTab) }
    // 通知点击等外部请求：切到指定页签（不做重建，保留已有界面状态）
    LaunchedEffect(requestedTab) { tab = requestedTab }

    // ---------- 跨页签直传（免去手动保存再导入）----------
    /** 下载 / 压缩的产物 → 转 GIF */
    var pendingVideo by remember { mutableStateOf<File?>(null) }
    /** 转 GIF 的产物 → GIF 压缩 */
    var pendingGif by remember { mutableStateOf<File?>(null) }
    /** 下载的产物 → 视频压缩 */
    var pendingCompressVideo by remember { mutableStateOf<File?>(null) }

    // ---------- 全局剪贴板检测 ----------
    //
    // 原来检测写在下载页里：只有下载页真正被组合时才会执行，从通知/分享直接进入别的页签
    // 就检测不到。现在提到 App 级：
    // - 每次 App 回到前台（含冷启动）都读一次剪贴板；
    // - 检测到链接 → 弹窗询问，确认后切到下载页、填入链接并**立即开始下载**；
    // - 同一个链接在一次运行内只打扰一次（确认或忽略后记住），进程重启后重新检测。
    //
    // 注意：Android 12+ 读剪贴板时系统会弹出"已粘贴自…"的提示，这是系统行为，无法关闭。
    var clipUrl by remember { mutableStateOf<String?>(null) }
    // 用 rememberSaveable：横竖屏切换/进程重建后不会对同一个链接再弹一次
    var handledClipUrl by rememberSaveable { mutableStateOf<String?>(null) }
    /** 剪贴板确认后的链接：交给下载页填入并自动开始下载，消费后置空 */
    var autoDownloadUrl by remember { mutableStateOf<String?>(null) }

    // 分享进来的链接已经自动填过，不必再对同一个链接弹剪贴板询问
    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) handledClipUrl = sharedUrl
    }

    // 每次窗口重新获得焦点（冷启动 / 从别的 App 切回）检测一次
    LaunchedEffect(focusTick) {
        if (focusTick == 0) return@LaunchedEffect
        val link = readClipboardLink(context) ?: return@LaunchedEffect
        // 排除已处理过的、以及刚由分享直接填过的链接，避免重复打扰
        if (link != handledClipUrl && link != sharedUrl) {
            clipUrl = link
        }
    }

    clipUrl?.let { link ->
        AlertDialog(
            onDismissRequest = {
                handledClipUrl = link
                clipUrl = null
            },
            title = { Text("检测到视频链接") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("剪贴板中的链接，是否立即下载？")
                    Text(
                        link,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    handledClipUrl = link
                    clipUrl = null
                    autoDownloadUrl = link
                    tab = MainActivity.TAB_DOWNLOAD
                }) { Text("下载") }
            },
            dismissButton = {
                TextButton(onClick = {
                    handledClipUrl = link
                    clipUrl = null
                }) { Text("忽略") }
            },
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_DOWNLOAD,
                    onClick = { tab = MainActivity.TAB_DOWNLOAD },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    label = { Text("视频下载") },
                )
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_GIF,
                    onClick = { tab = MainActivity.TAB_GIF },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("转 GIF") },
                )
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_COMPRESS,
                    onClick = { tab = MainActivity.TAB_COMPRESS },
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    label = { Text("GIF 压缩") },
                )
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_VIDEO_COMPRESS,
                    onClick = { tab = MainActivity.TAB_VIDEO_COMPRESS },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text("视频压缩") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            MainActivity.TAB_DOWNLOAD -> DownloadScreen(
                modifier = Modifier.padding(padding),
                prefilledUrl = sharedUrl,
                onPrefillConsumed = onSharedUrlConsumed,
                autoDownloadUrl = autoDownloadUrl,
                onAutoDownloadConsumed = { autoDownloadUrl = null },
                onSendToGif = { file ->
                    pendingVideo = file
                    tab = MainActivity.TAB_GIF
                },
                onSendToCompress = { file ->
                    pendingCompressVideo = file
                    tab = MainActivity.TAB_VIDEO_COMPRESS
                },
            )

            MainActivity.TAB_GIF -> GifToolScreen(
                modifier = Modifier.padding(padding),
                preloadedVideo = pendingVideo,
                onPreloadConsumed = { pendingVideo = null },
                onSendToCompress = { file ->
                    pendingGif = file
                    tab = MainActivity.TAB_COMPRESS
                },
            )

            MainActivity.TAB_COMPRESS -> GifCompressScreen(
                modifier = Modifier.padding(padding),
                preloadedGif = pendingGif,
                onPreloadConsumed = { pendingGif = null },
            )

            else -> VideoCompressScreen(
                modifier = Modifier.padding(padding),
                preloadedVideo = pendingCompressVideo,
                onPreloadConsumed = { pendingCompressVideo = null },
                // 压缩结果 → 转 GIF：与「下载 → 转 GIF」同一套 pendingVideo 中转
                onSendToGif = { file ->
                    pendingVideo = file
                    tab = MainActivity.TAB_GIF
                },
            )
        }
    }
}

/** 读取剪贴板文本并提取其中的链接；无内容 / 无链接 / 读取失败均返回 null */
private fun readClipboardLink(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.text
        ?.toString()
        ?.let { UrlExtractor.extract(it) }
}.getOrNull()
