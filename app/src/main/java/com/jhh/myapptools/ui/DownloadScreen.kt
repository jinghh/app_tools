package com.jhh.myapptools.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jhh.myapptools.download.DownloadEvent
import com.jhh.myapptools.download.YtDlpDownloader
import com.jhh.myapptools.media.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * X 视频下载页。
 *
 * 能力边界（已核实，见 [com.jhh.myapptools.download.XMediaResolver] 注释）：
 * yt-dlp-android 库不支持下载前获取元信息与直链，故：
 * - 下载由 yt-dlp 自身完成（有进度，但**无法取消**）；
 * - 时长/分辨率在下载完成后由本地 [VideoMetadata] 读取；
 * - 下载完成后可「去转 GIF」把文件直接交给转换模块。
 */
@Composable
fun DownloadScreen(
    modifier: Modifier = Modifier,
    onSendToGif: (File) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember { YtDlpDownloader(context) }

    var url by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var downloadedMeta by remember { mutableStateOf<VideoMetadata.Info?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf(listOf<String>()) }

    fun appendLog(line: String) {
        logs = (logs + line).takeLast(60)
    }

    fun startDownload() {
        if (running) return
        val target = url.trim()
        if (target.isBlank()) return

        running = true
        progress = 0f
        errorText = null
        diagnostics = null
        downloadedFile = null
        downloadedMeta = null
        stage = "下载中…（首次需初始化 Python 运行时，可能较慢）"
        appendLog("开始下载：$target")

        val events = Channel<DownloadEvent>(Channel.UNLIMITED)
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    downloader.download(target) { events.trySend(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    events.trySend(
                        DownloadEvent.Failure(
                            "下载异常：${e.message ?: e.javaClass.simpleName}",
                            e.stackTraceToString(),
                        )
                    )
                } finally {
                    events.close()
                }
            }
        }
        scope.launch {
            for (event in events) {
                when (event) {
                    is DownloadEvent.Stage -> stage = event.text
                    is DownloadEvent.Log -> appendLog(event.line)
                    is DownloadEvent.Progress -> {
                        progress = event.fraction
                        if (event.line.isNotBlank()) stage = "下载中… ${event.line}"
                    }

                    is DownloadEvent.Downloaded -> {
                        running = false
                        progress = 1f
                        stage = "下载完成"
                        downloadedFile = event.file
                        appendLog(
                            "已下载：${event.file.name}（${formatBytes(event.file.length())}）"
                        )
                        val file = event.file
                        scope.launch {
                            downloadedMeta = withContext(Dispatchers.IO) { VideoMetadata.read(file) }
                        }
                    }

                    is DownloadEvent.Failure -> {
                        running = false
                        stage = "失败"
                        errorText = event.message
                        diagnostics = event.diagnostics
                        appendLog("失败：${event.message}")
                    }

                    DownloadEvent.Cancelled -> {
                        running = false
                        stage = "已取消（注意：后台任务可能仍在运行）"
                        appendLog("已取消")
                    }
                }
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
                appendLog("视频已保存到：$uri")
            } else {
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show()
                appendLog("保存失败：$uri")
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
        Text("X 视频下载", style = MaterialTheme.typography.headlineSmall)
        Text(
            "只处理你有权访问的公开内容；媒体地址有时效性，下载后请尽快处理。",
            style = MaterialTheme.typography.bodySmall,
        )

        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("1. 帖子链接", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("https://x.com/<user>/status/<id>") },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startDownload() },
                        enabled = !running && url.isNotBlank(),
                    ) { Text("开始下载") }
                    OutlinedButton(
                        onClick = { downloader.cancel() },
                        enabled = running,
                    ) { Text("取消") }
                }
                Text(
                    "说明：当前解析器（yt-dlp-android）不支持下载前获取清晰度列表，" +
                        "按默认最优格式下载；时长与分辨率在下载完成后本地读取。" +
                        "取消为“放弃等待”，后台任务可能仍会跑完。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (running || stage.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("2. 状态", style = MaterialTheme.typography.titleMedium)
                    if (running) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        if (running && progress > 0f) {
                            "$stage · ${(progress * 100).toInt()}%"
                        } else {
                            stage
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        downloadedFile?.let { file ->
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("3. 下载结果", style = MaterialTheme.typography.titleMedium)
                    val meta = downloadedMeta
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

        errorText?.let { err ->
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

        diagnostics?.let { diag ->
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("诊断信息（开发用）", style = MaterialTheme.typography.titleMedium)
                    Text(
                        diag.takeLast(4000),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        if (logs.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("yt-dlp 日志", style = MaterialTheme.typography.titleMedium)
                    Text(
                        logs.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
