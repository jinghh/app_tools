package com.jhh.myapptools.ui

import android.app.Activity
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jhh.myapptools.download.CookieStore
import com.jhh.myapptools.download.DownloadCenter
import com.jhh.myapptools.download.FailureReason
import com.jhh.myapptools.download.LoginSites
import com.jhh.myapptools.download.NotificationPrefs
import com.jhh.myapptools.download.UrlExtractor
import com.jhh.myapptools.download.YtDlpDownloader
import com.jhh.myapptools.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频下载页（基于 yt-dlp，支持多站点）。
 *
 * 布局分三层，避免「十来张等权卡片平铺」导致主任务被淹没：
 * 1. 主任务区（常驻）：链接输入 + 唯一主按钮 + 进度/结果/错误反馈，操作与结果零距离；
 * 2. 设置区（默认折叠）：Cookie 与登录、通知与存储，收起时只显示一行摘要；
 * 3. 诊断区（默认折叠）：诊断信息与 yt-dlp 日志，展开后限高、内部滚动。
 *
 * 状态仍全部来自进程级 [DownloadCenter]，界面只观察；切后台、界面重建都不丢。
 */
@Composable
fun DownloadScreen(
    modifier: Modifier = Modifier,
    /** 分享直达（ACTION_SEND）提取出的链接；填入输入框后由回调置空（见 MainActivity） */
    prefilledUrl: String? = null,
    onPrefillConsumed: () -> Unit = {},
    /** 剪贴板确认后的链接：填入输入框并**立即开始下载**（检测已提升到 MainActivity 全局） */
    autoDownloadUrl: String? = null,
    onAutoDownloadConsumed: () -> Unit = {},
    onSendToGif: (File) -> Unit = {},
    /** 下载完成后直接交给「视频压缩」页（免去手动保存再导入） */
    onSendToCompress: (File) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cookieStore = remember { CookieStore(context) }
    val versionName = remember { appVersionName(context) }

    // 状态来自进程级单例：切后台、界面重建都不丢
    val state by DownloadCenter.state.collectAsState()
    val running = state.running

    var cookieEntries by remember { mutableStateOf(cookieStore.list()) }
    var useCookie by remember { mutableStateOf(cookieStore.isUseCookieEnabled()) }
    var url by remember { mutableStateOf("") }

    // 随输入/列表变化重算，避免每帧做文件 IO
    val matchedCookie = remember(url, cookieEntries) { cookieStore.matchForUrl(url) }

    // ---------- 应用内登录 ----------

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val entries = cookieStore.list()
        cookieEntries = entries
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val platform = data.getStringExtra(LoginCookieActivity.EXTRA_PLATFORM)
            ?: return@rememberLauncherForActivityResult
        val count = data.getIntExtra(LoginCookieActivity.EXTRA_COOKIE_COUNT, 0)
        val name = entries.firstOrNull { it.platform == platform }?.displayName ?: platform
        val msg = "已从应用内登录导入 $name Cookie（$count 条）"
        Toast.makeText(context, "$msg，请点「重试下载」", Toast.LENGTH_LONG).show()
        DownloadCenter.appendLog(msg)
    }

    fun targetFor(rawUrl: String): LoginTarget? {
        val candidate = UrlExtractor.extract(rawUrl) ?: rawUrl.trim()
        if (!candidate.startsWith("http", ignoreCase = true)) return null
        val platform = cookieStore.platformOfUrl(candidate) ?: return null
        val site = LoginSites.find(platform) ?: return null
        return LoginTarget(candidate, platform, site.fallbackLoginUrl)
    }

    // 失败时优先用「本次下载实际使用的链接」：输入框是 remember，
    // Activity 在 WebView 期间被回收会丢，而 DownloadUiState.url 是进程级状态。
    val failedLoginTarget = remember(state.url, state.failureReason, url) {
        val maybeLogin = state.failureReason in setOf(
            FailureReason.LOGIN_REQUIRED,
            FailureReason.UNSUPPORTED,
            FailureReason.UNKNOWN,
        )
        if (maybeLogin) targetFor(state.url?.takeUnless { it.isBlank() } ?: url) else null
    }

    val proactiveLoginTarget = remember(url) { targetFor(url) }

    val unsupportedNote = remember(url) {
        val candidate = UrlExtractor.extract(url) ?: url.trim()
        cookieStore.platformOfUrl(candidate)?.let { LoginSites.unsupportedNote(it) }
    }

    fun openLogin(target: LoginTarget) {
        loginLauncher.launch(
            LoginCookieActivity.intent(
                context,
                target.entryUrl,
                target.platform,
                target.fallbackUrl,
            )
        )
    }

    fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    // ---------- 通知提醒 ----------

    val notifyPrefs = remember { NotificationPrefs(context) }
    var resultNotify by remember { mutableStateOf(notifyPrefs.isResultNotifyEnabled()) }
    var permissionGranted by remember { mutableStateOf(notifyPrefs.isSystemPermissionGranted()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            Toast.makeText(
                context,
                "未授予通知权限：下载不受影响。可在「通知与存储」里打开系统设置",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionGranted = notifyPrefs.isSystemPermissionGranted()
        DownloadCenter.refreshResumable(context)
        cookieEntries = cookieStore.list()
    }

    LaunchedEffect(prefilledUrl) {
        val link = prefilledUrl ?: return@LaunchedEffect
        url = link
        onPrefillConsumed()
        Toast.makeText(context, "已自动填入分享的链接", Toast.LENGTH_SHORT).show()
    }

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
        if (enabled) requestNotificationPermission()
    }

    // ---------- Cookie 导入 ----------

    val pickCookieLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val result = cookieStore.importFrom(uri)) {
            is CookieStore.ImportResult.Success -> {
                cookieEntries = cookieStore.list()
                val msg = "已导入 ${result.entry.displayName} Cookie" +
                    "（${formatBytes(result.entry.sizeBytes)}）"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                DownloadCenter.appendLog(msg)
            }

            is CookieStore.ImportResult.Warning -> {
                cookieEntries = cookieStore.list()
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                DownloadCenter.appendLog(
                    "已导入 ${result.entry.displayName}，但${result.message}"
                )
            }

            is CookieStore.ImportResult.Failure -> {
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                DownloadCenter.appendLog("Cookie 导入失败：${result.message}")
            }
        }
    }

    fun launchDownload(resume: Boolean, fixedUrl: String? = null) {
        if (running) return
        val target = fixedUrl ?: (UrlExtractor.extract(url) ?: url.trim())
        if (target.isBlank()) return
        if (resultNotify) requestNotificationPermission()
        val cookiePath =
            if (useCookie) cookieStore.matchForUrl(target)?.file?.absolutePath else null
        DownloadCenter.start(context, target, cookiePath, resume)
    }

    fun startDownload() = launchDownload(resume = false)

    fun resumeDownload() {
        if (running) return
        val target = YtDlpDownloader.resumeUrl(context)
        if (target.isNullOrBlank()) {
            Toast.makeText(context, "没有可续传的下载记录", Toast.LENGTH_SHORT).show()
            return
        }
        launchDownload(resume = true, fixedUrl = target)
    }

    fun retryAfterLogin() {
        if (running) return
        val target = state.url?.takeUnless { it.isBlank() }
            ?: YtDlpDownloader.resumeUrl(context)
            ?: url.trim()
        if (target.isBlank()) {
            Toast.makeText(context, "没有可重试的链接", Toast.LENGTH_SHORT).show()
            return
        }
        launchDownload(resume = false, fixedUrl = target)
    }

    LaunchedEffect(autoDownloadUrl) {
        val link = autoDownloadUrl ?: return@LaunchedEffect
        url = link
        if (running) {
            Toast.makeText(context, "已有下载任务进行中，链接已填入", Toast.LENGTH_LONG).show()
        } else {
            launchDownload(resume = false, fixedUrl = link)
        }
        onAutoDownloadConsumed()
    }

    fun quickSaveToDownloads(file: File) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                saveToPublicDownloads(context, file, mimeForFile(file))
            } != null
            if (ok) {
                Toast.makeText(context, "已保存到系统下载目录", Toast.LENGTH_SHORT).show()
                DownloadCenter.appendLog("已保存到系统下载目录：${file.name}")
            } else {
                Toast.makeText(context, "保存失败，可改用「另存为」", Toast.LENGTH_LONG).show()
                DownloadCenter.appendLog("保存到下载目录失败：${file.name}")
            }
        }
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

    // 三个旧入口（开始/继续/重试）收敛成一个；不用 remember 缓存 onClick，
    // 避免闭包捕获到旧的 running / url 值。
    val primary = when {
        running -> PrimaryAction("下载中…", false) { }
        state.error != null -> PrimaryAction("重试下载", true) { retryAfterLogin() }
        state.resumableBytes > 0L -> PrimaryAction(
            "继续下载（已下 ${formatBytes(state.resumableBytes)}）", true
        ) { resumeDownload() }

        else -> PrimaryAction("开始下载", url.isNotBlank()) { startDownload() }
    }

    val cookieSummary = cookieMatchText(
        useCookie = useCookie,
        hasUrl = url.isNotBlank(),
        matched = matchedCookie,
        entries = cookieEntries,
    )

    ScreenScaffold(
        modifier = modifier,
        title = "视频下载",
        titleExtra = { HelpIcon(title = "视频下载", text = HELP_PAGE) },
    ) {
        // ========== 1. 主任务区（常驻）==========
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TitleWithHelp("视频链接", "链接与下载说明", HELP_URL)
                OutlinedTextField(
                    value = url,
                    onValueChange = { raw ->
                        val extracted = UrlExtractor.extract(raw)
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

                Button(
                    onClick = primary.onClick,
                    enabled = primary.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(primary.label) }

                if (running) {
                    OutlinedButton(
                        onClick = { DownloadCenter.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("取消（放弃等待）") }
                }

                if (running || state.stage.isNotEmpty()) {
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

                state.error?.let { err ->
                    Text(
                        "错误",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(err, style = MaterialTheme.typography.bodyMedium)
                    failedLoginTarget?.let { target ->
                        OutlinedButton(
                            onClick = { openLogin(target) },
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("在 App 内登录并获取 Cookie") }
                    }
                    unsupportedNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    matchedCookie?.let { entry ->
                        Text(
                            "已检测到 ${entry.displayName} 的 Cookie，点上方主按钮重试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                state.file?.let { file ->
                    Text("下载结果", style = MaterialTheme.typography.titleSmall)
                    val meta = state.meta
                    Text(
                        "文件：${file.name}\n" +
                            "大小：${formatBytes(file.length())}\n" +
                            "时长：${meta?.durationMs?.let { "${it / 1000.0}s" } ?: "读取中…"}\n" +
                            "分辨率：${meta?.let { "${it.width}x${it.height}" } ?: "读取中…"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onSendToGif(file) },
                            modifier = Modifier.weight(1f),
                        ) { Text("去转 GIF", maxLines = 1) }
                        FilledTonalButton(
                            onClick = { onSendToCompress(file) },
                            modifier = Modifier.weight(1f),
                        ) { Text("视频压缩", maxLines = 1) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            OutlinedButton(
                                onClick = { quickSaveToDownloads(file) },
                                modifier = Modifier.weight(1f),
                            ) { Text("存到下载", maxLines = 1) }
                        }
                        OutlinedButton(
                            onClick = {
                                pendingSave = file
                                saveVideoLauncher.launch(file.name)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("另存为", maxLines = 1) }
                    }
                }

                Text(
                    cookieSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ========== 2. 设置区（默认折叠）==========
        CollapsibleSection(title = "Cookie 与登录", summary = cookieSummary) {
            Text("登录 Cookie", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("下载时携带 Cookie", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        cookieSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useCookie,
                    onCheckedChange = {
                        useCookie = it
                        cookieStore.setUseCookieEnabled(it)
                    },
                )
            }

            if (cookieEntries.isEmpty()) {
                Text("尚未导入", style = MaterialTheme.typography.bodySmall)
            } else {
                cookieEntries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatBytes(entry.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                cookieStore.clear(entry.platform)
                                cookieEntries = cookieStore.list()
                                val msg = "已清除 ${entry.displayName} Cookie 文件" +
                                    "（网页登录态需在登录页单独清除）"
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                DownloadCenter.appendLog(msg)
                            },
                            enabled = !running,
                        ) { Text("清除文件") }
                    }
                }
            }

            OutlinedButton(
                onClick = { proactiveLoginTarget?.let { openLogin(it) } },
                enabled = !running && proactiveLoginTarget != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("在 App 内登录（自动获取 Cookie）") }

            if (url.isNotBlank() && proactiveLoginTarget == null) {
                Text(
                    unsupportedNote
                        ?: "当前链接所在站点暂不支持应用内登录，可用下方的文件导入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = { pickCookieLauncher.launch(arrayOf("*/*")) },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导入 cookies.txt（自动识别平台）") }
        }

        CollapsibleSection(
            title = "通知与存储",
            summary = "通知：${if (resultNotify) "开" else "关"}",
        ) {
            Text("通知提醒", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("下载完成后提醒我", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        notifyStatusText(resultNotify, permissionGranted),
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
            OutlinedButton(onClick = { openSystemNotificationSettings() }) {
                Text("打开系统通知设置")
            }

            Text("存储空间", style = MaterialTheme.typography.titleSmall)
            var storageUsage by remember { mutableStateOf<Long?>(null) }
            fun refreshUsage() {
                scope.launch {
                    storageUsage = withContext(Dispatchers.IO) { AppStorage.totalBytes(context) }
                }
            }
            LaunchedEffect(Unit) { refreshUsage() }
            Text(
                text = storageUsage?.let { "缓存与产物共占用 ${formatBytes(it)}" } ?: "统计中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { AppStorage.clean(context) }
                        DownloadCenter.refreshResumable(context)
                        Toast.makeText(
                            context,
                            "已释放 ${formatBytes(freed)}",
                            Toast.LENGTH_SHORT,
                        ).show()
                        refreshUsage()
                    }
                },
                enabled = !running,
            ) { Text("清理缓存（保留最近结果）") }
            Text(
                "清理范围：下载残留与成品、转 GIF / 压缩产物、导入的临时副本；" +
                    "每个目录保留最新的一份。下载进行中不可用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ========== 3. 诊断区（默认折叠，展开后限高）==========
        val diagnostics = state.diagnostics
        // 日志最多 60 条，但每次重组都拼接没必要；按 logs 缓存
        val logText = remember(state.logs) { state.logs.joinToString("\n") }
        val diagBlocks = buildList {
            diagnostics?.let { add(DiagnosticsBlock("诊断信息", it.takeLast(4000))) }
            if (logText.isNotEmpty()) add(DiagnosticsBlock("yt-dlp 日志", logText))
        }
        DiagnosticsSection(
            blocks = diagBlocks,
            summary = "诊断：${if (diagnostics == null) "无" else "有内容"} · 日志 ${state.logs.size} 条",
            onCopy = { label, text -> copyToClipboard(label, text) },
        )

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

/** 唯一主按钮的状态：把「开始 / 继续 / 重试」三个入口收敛成一个 */
private data class PrimaryAction(
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
)

// ---------- 说明文案 ----------

private const val HELP_PAGE =
    "基于 yt-dlp，支持多种站点（X、Facebook 已实测可用）。\n\n" +
        "只处理你有权访问的公开内容；媒体地址有时效性，下载后请尽快处理。\n\n" +
        "页面只保留一个主按钮：有残留时显示「继续下载」，失败时显示「重试下载」。"

private const val HELP_URL =
    "· 可直接粘贴 App 的分享文本，会自动去掉链接以外的文字；在浏览器/微信里" +
        "「分享文本」到本 App 也可以直接填入。\n\n" +
        "· 目前不支持下载前获取清晰度列表，按默认最优格式下载；时长与分辨率在下载完成后本地读取。\n\n" +
        "· 「取消」为放弃等待，后台任务可能仍会跑完。\n\n" +
        "· 网络波动导致的失败会自动续传重试两次；格式不可用会自动换兼容格式重试一次。\n\n" +
        "· 下载期间可放心切到后台（已用前台服务保活）。\n\n" +
        "· YouTube 链接会返回 403（当前解析库限制）；X 与 Facebook 已实测可用。"

private const val HELP_COOKIE =
    "用途：小红书、微博等站点需要登录态才能访问（否则会跳转登录页导致失败）。\n\n" +
        "用法一（推荐，不需要电脑）：在「Cookie 与登录」里点「在 App 内登录」→ 正常登录 → " +
        "点「完成并导入」。App 会自动把登录 Cookie 转成标准格式并**按站点分别保存**；" +
        "下载失败需要登录时，错误下方也会直接出现这个入口。\n\n" +
        "用法二：用浏览器扩展（如 Get cookies.txt LOCALLY）导出 Netscape 格式的 cookies.txt " +
        "后在此导入，本 App 会从文件内容自动识别站点。\n\n" +
        "下载时只发送与当前链接**同站点**的那份 Cookie：下 X 视频不会带上小红书 Cookie，" +
        "反之亦然；也可以用开关整体关闭。\n\n" +
        "注意：登录页走 Android 系统网络栈，如需代理请在系统层使用 VPN 全局模式；" +
        "验证码、短信验证需本人完成。\n\n" +
        "隐私：Cookie 仅保存在应用私有目录，不写入日志、不外传；用完可在列表里单独清除，" +
        "登录页里也有「清除网页登录态」用于换账号。"

private const val HELP_NOTIFY =
    "关闭通知不影响下载。\n\n" +
        "下载进行中的进度通知由系统对前台服务强制要求（正是它保证切后台不被中断），无法单独关闭。\n\n" +
        "若想完全不显示通知，可在系统设置里关闭本应用的通知权限——此时下载仍会正常进行。"

private data class LoginTarget(
    val entryUrl: String,
    val platform: String,
    val fallbackUrl: String,
)

/** 让用户清楚「这一次到底会不会带 Cookie、带的是哪个平台的」 */
private fun cookieMatchText(
    useCookie: Boolean,
    hasUrl: Boolean,
    matched: CookieStore.Entry?,
    entries: List<CookieStore.Entry>,
): String = when {
    !useCookie -> if (entries.isEmpty()) "已关闭（未导入 Cookie）" else "已关闭，本次不带 Cookie"
    entries.isEmpty() -> "未导入任何 Cookie"
    matched != null -> "本次将使用：${matched.displayName}（${formatBytes(matched.sizeBytes)}）"
    hasUrl -> "本站点未匹配到 Cookie，本次不带"
    else -> "输入链接后自动匹配站点"
}

/** 通知状态文案：分清「App 内偏好」与「系统权限」两层 */
private fun notifyStatusText(resultNotify: Boolean, permissionGranted: Boolean): String = when {
    !resultNotify -> "已关闭（不影响下载）"
    permissionGranted -> "已开启"
    else -> "系统通知权限未授予，通知不会显示"
}
