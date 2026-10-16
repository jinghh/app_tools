package com.jhh.myapptools.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jhh.myapptools.R
import com.jhh.myapptools.download.CookieStore
import com.jhh.myapptools.download.DownloadCenter
import com.jhh.myapptools.download.NotificationPrefs
import com.jhh.myapptools.download.UrlExtractor
import com.jhh.myapptools.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频下载页（基于 yt-dlp，支持多站点）。
 *
 * 实测支持情况：
 * - ✅ X（Twitter）、✅ Facebook（公开视频）已真机验证；
 * - ✅ 小红书：公开笔记可直接下载；需登录的笔记导入浏览器 Cookie 后可下载（已端到端验证通过）；
 * - ❌ YouTube 返回 403（反爬指纹或签名挑战需 JS 运行时，属库的免费版/Android 限制）。
 *
 * 能力边界（已核实，见 [com.jhh.myapptools.download.XMediaResolver] 注释）：
 * yt-dlp-android 库不支持下载前获取元信息与直链，故：
 * - 下载由 yt-dlp 自身完成（有进度，但**无法取消**）；
 * - 时长/分辨率在下载完成后由本地 VideoMetadata 读取；
 * - 下载完成后可「去转 GIF」把文件直接交给转换模块。
 *
 * 后台下载：下载不跑在本界面的协程里，而是交给 [DownloadCenter] + 前台服务，
 * 因此切到后台不会被系统冻结/回收，界面重建后也能看到进度与结果。
 */
@Composable
fun DownloadScreen(
    modifier: Modifier = Modifier,
    onSendToGif: (File) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cookieStore = remember { CookieStore(context) }
    val versionName = remember { appVersionName(context) }

    // 状态来自进程级单例：切后台、界面重建都不丢
    val state by DownloadCenter.state.collectAsState()
    val running = state.running

    var cookieImported by remember { mutableStateOf(cookieStore.isImported()) }
    var cookieSize by remember { mutableStateOf(cookieStore.sizeBytes()) }
    var url by remember { mutableStateOf("") }

    /** 复制文本到剪贴板（Android 13+ 系统会额外提示"已复制"） */
    fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    // ---------- 通知提醒设置 ----------
    //
    // 技术约束：App **无法**自行授予或撤销通知权限，只能引导用户在系统弹窗或系统设置里操作。
    // 因此提供两个独立入口，且两者都不影响下载：
    // 1) 开关：控制"下载完成后是否提醒我"（App 内偏好，持久化）；
    // 2) 系统设置入口：控制系统层面的通知权限（可开启，也可完全关闭）。

    val notifyPrefs = remember { NotificationPrefs(context) }
    var resultNotify by remember { mutableStateOf(notifyPrefs.isResultNotifyEnabled()) }
    var permissionGranted by remember { mutableStateOf(notifyPrefs.isSystemPermissionGranted()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            // 系统已不再弹窗（被"不再询问"）时，下面的系统设置入口是唯一开启途径，故明确指引
            Toast.makeText(
                context,
                "未授予通知权限：下载不受影响。可随时用下方「打开系统通知设置」开启",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // 从系统设置返回时同步真实权限状态（用户可能刚在设置里改过）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionGranted = notifyPrefs.isSystemPermissionGranted()
    }

    /** 请求系统通知权限；系统已不再弹窗时（被永久拒绝）需到系统设置里手动开启 */
    fun requestNotificationPermission() {
        if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(NotificationPrefs.PERMISSION)
        }
    }

    fun openSystemNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) Toast.makeText(context, "无法打开系统通知设置", Toast.LENGTH_LONG).show()
    }

    fun onToggleResultNotify(enabled: Boolean) {
        resultNotify = enabled
        notifyPrefs.setResultNotifyEnabled(enabled)
        // 打开时顺带引导系统权限，否则开关显示为"开"但通知实际发不出来
        if (enabled) requestNotificationPermission()
    }

    // ---------- 登录 Cookie 导入 ----------

    val pickCookieLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val error = cookieStore.importFrom(uri)
        cookieImported = cookieStore.isImported()
        cookieSize = cookieStore.sizeBytes()
        if (error == null) {
            Toast.makeText(context, "Cookie 已导入", Toast.LENGTH_SHORT).show()
            DownloadCenter.appendLog("Cookie 已导入（${formatBytes(cookieSize)}）")
        } else {
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            DownloadCenter.appendLog("Cookie 导入结果：$error")
        }
    }

    fun startDownload() {
        if (running) return
        // 兜底再抽取一次：确保即使输入框残留多余文字也能正确下载
        val target = UrlExtractor.extract(url) ?: url.trim()
        if (target.isBlank()) return

        // 仅在用户没关掉提醒时请求权限（关掉后不再打扰）；授权是异步的，不阻塞下载
        if (resultNotify) requestNotificationPermission()
        DownloadCenter.start(context, target, cookieStore.pathOrNull())
    }

    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saveVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val source = pendingSave
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("openOutputStream 返回 null")
                }.isSuccess
            }
            if (ok) {
                Toast.makeText(context, "视频已保存成功", Toast.LENGTH_SHORT).show()
                DownloadCenter.appendLog("视频已保存到：$uri")
            } else {
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show()
                DownloadCenter.appendLog("保存失败：$uri")
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("视频下载", style = MaterialTheme.typography.headlineSmall)
            HelpIcon(title = "视频下载", text = HELP_PAGE)
        }

        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TitleWithHelp("1. 视频链接", "链接与下载说明", HELP_URL)
                OutlinedTextField(
                    value = url,
                    onValueChange = { raw ->
                        val extracted = UrlExtractor.extract(raw)
                        // 分享文本（如小红书）夹带中文与推广语时，自动只保留链接
                        if (extracted != null && extracted != raw.trim()) {
                            url = extracted
                            Toast.makeText(context, "已自动提取链接", Toast.LENGTH_SHORT).show()
                        } else {
                            url = raw
                        }
                    },
                    label = { Text("粘贴链接或分享文本（X / Facebook / 小红书等）") },
                    singleLine = true,
                    enabled = !running,
                    // 有内容时显示清除按钮；下载中隐藏（此时输入框整体为禁用态）
                    trailingIcon = {
                        if (url.isNotEmpty() && !running) {
                            IconButton(onClick = { url = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "清除输入",
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startDownload() },
                        enabled = !running && url.isNotBlank(),
                    ) { Text("开始下载") }
                    OutlinedButton(
                        onClick = { DownloadCenter.cancel() },
                        enabled = running,
                    ) { Text("取消") }
                }
            }
        }

        // 2. 登录 Cookie（可选）：小红书、微博等需要登录态的站点
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TitleWithHelp("2. 登录 Cookie（可选）", "登录 Cookie 说明", HELP_COOKIE)
                Text(
                    if (cookieImported) {
                        "状态：已导入（${formatBytes(cookieSize)}）"
                    } else {
                        "状态：未导入"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickCookieLauncher.launch(arrayOf("*/*")) },
                        enabled = !running,
                    ) { Text("导入 cookies.txt") }
                    if (cookieImported) {
                        OutlinedButton(
                            onClick = {
                                cookieStore.clear()
                                cookieImported = cookieStore.isImported()
                                cookieSize = cookieStore.sizeBytes()
                                Toast.makeText(context, "已清除 Cookie", Toast.LENGTH_SHORT).show()
                                DownloadCenter.appendLog("Cookie 已清除")
                            },
                            enabled = !running,
                        ) { Text("清除") }
                    }
                }
            }
        }

        // 3. 通知提醒：App 内偏好（结果通知）+ 系统权限入口，两者都不影响下载
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TitleWithHelp("3. 通知提醒（可选）", "通知提醒说明", HELP_NOTIFY)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("下载完成后提醒我", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = notifyStatusText(resultNotify, permissionGranted),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (resultNotify && !permissionGranted) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Switch(
                        checked = resultNotify,
                        onCheckedChange = { onToggleResultNotify(it) },
                    )
                }
                OutlinedButton(
                    onClick = { openSystemNotificationSettings() },
                ) {
                    Text("打开系统通知设置（可开启或完全关闭）")
                }
            }
        }

        if (running || state.stage.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("4. 状态", style = MaterialTheme.typography.titleMedium)
                    if (running) {
                        LinearProgressIndicator(
                            progress = { state.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        if (running && state.fraction > 0f) {
                            "${state.stage} · ${(state.fraction * 100).toInt()}%"
                        } else {
                            state.stage
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        state.file?.let { file ->
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("5. 下载结果", style = MaterialTheme.typography.titleMedium)
                    val meta = state.meta
                    Text(
                        "文件：${file.name}\n" +
                            "大小：${formatBytes(file.length())}\n" +
                            "时长：${meta?.durationMs?.let { "${it / 1000.0}s" } ?: "读取中…"}\n" +
                            "分辨率：${meta?.let { "${it.width}x${it.height}" } ?: "读取中…"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSendToGif(file) }) { Text("去转 GIF") }
                        OutlinedButton(onClick = {
                            pendingSave = file
                            saveVideoLauncher.launch(file.name)
                        }) { Text("保存到本地") }
                    }
                }
            }
        }

        state.error?.let { err ->
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("错误", style = MaterialTheme.typography.titleMedium)
                    Text(err, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        state.diagnostics?.let { diag ->
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SectionHeaderWithCopy(
                        title = "诊断信息（开发用）",
                        textToCopy = diag,
                        onCopy = { copyToClipboard("诊断信息", diag) },
                    )
                    Text(
                        diag.takeLast(4000),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        if (state.logs.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val logText = state.logs.joinToString("\n")
                    SectionHeaderWithCopy(
                        title = "yt-dlp 日志",
                        textToCopy = logText,
                        onCopy = { copyToClipboard("yt-dlp 日志", logText) },
                    )
                    Text(
                        logText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // 底部版本号，便于反馈问题时确认版本
        Text(
            text = "本地工具箱 v$versionName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
        )
    }
}

/**
 * 「?」帮助图标：点击弹出说明弹窗。
 *
 * 说明文案默认**不占版面**（原来 5 段长说明直接铺在页面上，把内容挤得很散），
 * 需要时点一下图标即可查看全文。
 */
@Composable
private fun HelpIcon(title: String, text: String) {
    var show by remember { mutableStateOf(false) }
    IconButton(
        onClick = { show = true },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_help),
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = { show = false }) { Text("知道了") }
            },
            title = { Text(title) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

/** 卡片标题 + 「?」帮助入口（标题行高度几乎不变，说明收进弹窗） */
@Composable
private fun TitleWithHelp(title: String, helpTitle: String, helpText: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HelpIcon(title = helpTitle, text = helpText)
    }
}

// ---------- 说明文案（默认折叠，点「?」查看）----------

private const val HELP_PAGE =
    "基于 yt-dlp，支持多种站点（X、Facebook 已实测可用）。\n\n" +
        "只处理你有权访问的公开内容；媒体地址有时效性，下载后请尽快处理。"

private const val HELP_URL =
    "· 可直接粘贴 App 的分享文本，会自动去掉链接以外的文字。\n\n" +
        "· 目前不支持下载前获取清晰度列表，按默认最优格式下载；时长与分辨率在下载完成后本地读取。\n\n" +
        "· 「取消」为放弃等待，后台任务可能仍会跑完。\n\n" +
        "· 下载期间可放心切到后台（已用前台服务保活）；完成后是否提醒见下方「通知提醒」。\n\n" +
        "· YouTube 链接会返回 403（当前解析库限制）；X 与 Facebook 已实测可用。"

private const val HELP_COOKIE =
    "用途：小红书、微博等站点需要登录态才能访问（否则会跳转登录页导致失败）。\n\n" +
        "用法：请用浏览器扩展（如 Get cookies.txt LOCALLY）导出 Netscape 格式的 cookies.txt 后在此导入。\n\n" +
        "隐私：文件仅保存在应用私有目录，不会写入日志或外传。"

private const val HELP_NOTIFY =
    "关闭通知不影响下载。\n\n" +
        "下载进行中的进度通知由系统对前台服务强制要求（正是它保证切后台不被中断），无法单独关闭。\n\n" +
        "若想完全不显示通知，可在系统设置里关闭本应用的通知权限——此时下载仍会正常进行。"

/**
 * 通知状态文案：必须分清"App 内偏好"与"系统权限"两层，否则用户会以为开关失灵。
 * （开关打开但系统权限被拒时，通知实际发不出来，必须明确告知。）
 */
private fun notifyStatusText(resultNotify: Boolean, permissionGranted: Boolean): String = when {
    !resultNotify -> "已关闭（不影响下载）"
    permissionGranted -> "已开启"
    else -> "系统通知权限未授予，通知不会显示"
}

/** 卡片标题 + 「复制」按钮（日志/诊断信息用） */
@Composable
private fun SectionHeaderWithCopy(
    title: String,
    textToCopy: String,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onCopy, enabled = textToCopy.isNotBlank()) {
            Text("复制")
        }
    }
}
