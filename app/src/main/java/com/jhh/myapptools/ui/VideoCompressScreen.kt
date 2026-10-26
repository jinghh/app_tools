package com.jhh.myapptools.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jhh.myapptools.media.VideoMetadata
import com.jhh.myapptools.util.formatBytes
import com.jhh.myapptools.video.VideoCompressEvent
import com.jhh.myapptools.video.VideoCompressResult
import com.jhh.myapptools.video.VideoCompressor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频压缩（目标大小）。
 *
 * 场景：微信发视频有 25MB 上限，直接给「压到 25MB」预设。
 * 策略沿用 GIF 压缩的「参数级联 + 真实字节数校验、达标即停」；
 * 编码器为硬件 H.264（ffmpeg-kit-min 不含 libx264），失败自动回落 mpeg4。
 */
@Composable
fun VideoCompressScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val compressor = remember { VideoCompressor() }

    var inputFile by remember { mutableStateOf<File?>(null) }
    var inputInfo by remember { mutableStateOf<VideoMetadata.Info?>(null) }
    var inputBytes by remember { mutableStateOf(0L) }

    /** 默认 25MB：微信发视频的上限 */
    var targetMbText by remember { mutableStateOf("25") }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<VideoCompressResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf(listOf<String>()) }

    fun appendLog(line: String) {
        logs = (logs + line).takeLast(60)
    }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = withContext(Dispatchers.IO) { copyVideoToCache(context, uri) }
            if (copied == null) {
                errorText = "导入失败：无法读取所选文件"
                return@launch
            }
            val info = withContext(Dispatchers.IO) { VideoMetadata.read(copied) }
            inputFile = copied
            inputBytes = copied.length()
            inputInfo = info
            result = null
            errorText = null
            if (info != null) {
                appendLog(
                    "导入成功：${copied.name}（${formatBytes(copied.length())}，" +
                        "${info.durationMs / 1000}s，${info.width}x${info.height}）"
                )
            } else {
                appendLog("导入成功但无法读取视频信息")
            }
        }
    }

    var pendingSave by remember { mutableStateOf<VideoCompressResult?>(null) }
    val saveVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val target = pendingSave
        if (uri != null && target != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            target.file.inputStream().use { it.copyTo(out) }
                        } ?: error("openOutputStream 返回 null")
                    }.isSuccess
                }
                if (ok) {
                    Toast.makeText(context, "视频已保存成功", Toast.LENGTH_SHORT).show()
                    appendLog("已保存到：$uri")
                } else {
                    Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show()
                    appendLog("保存失败：$uri")
                }
            }
        }
    }

    /** 一键保存到系统「下载」目录（MediaStore，API 29+ 免权限） */
    fun quickSaveToDownloads(file: File) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                saveToPublicDownloads(context, file, "video/mp4")
            } != null
            if (ok) {
                Toast.makeText(context, "已保存到系统下载目录", Toast.LENGTH_SHORT).show()
                appendLog("已保存到系统下载目录：${file.name}")
            } else {
                Toast.makeText(context, "保存失败，可改用「另存为…」选择位置", Toast.LENGTH_LONG).show()
                appendLog("保存到下载目录失败：${file.name}")
            }
        }
    }

    fun startCompress() {
        val input = inputFile ?: return
        val targetMb = targetMbText.trim().toDoubleOrNull()
        if (targetMb == null || targetMb <= 0) {
            errorText = "目标大小必须是大于 0 的数字（MB）"
            return
        }
        // UI 标注 MB，内部按十进制 MB（1MB = 1,000,000 bytes）换算
        val targetBytes = (targetMb * 1_000_000).toLong()
        if (targetBytes >= inputBytes) {
            appendLog("目标不小于原文件，仍按阶梯尝试（首轮重编码可能有小幅收益）")
        }

        running = true
        progress = 0f
        stage = "准备中"
        result = null
        errorText = null
        appendLog("开始压缩：目标 ${targetMb}MB（$targetBytes bytes）")

        val events = Channel<VideoCompressEvent>(Channel.UNLIMITED)
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    compressor.compress(input, targetBytes) { events.trySend(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    events.trySend(
                        VideoCompressEvent.Failure(
                            "压缩引擎异常：${e.message ?: e.javaClass.simpleName}",
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
                    is VideoCompressEvent.RoundStart -> {
                        stage = event.label
                        appendLog("第 ${event.round}/${event.totalRounds} 轮：${event.label}")
                    }

                    is VideoCompressEvent.RoundProgress -> {
                        // 整体进度按轮次均分，避免每轮都从 0 跳回
                        val per = 1f / event.totalRounds
                        progress = ((event.round - 1) * per + event.fraction * per)
                            .coerceIn(0f, 0.99f)
                    }

                    is VideoCompressEvent.RoundDone -> {
                        appendLog(
                            "${if (event.metTarget) "达标" else "未达标"}：" +
                                "${formatBytes(event.bytes)}（${event.label}）"
                        )
                    }

                    is VideoCompressEvent.Success -> {
                        result = event.result
                        progress = 1f
                        running = false
                        stage = "完成"
                        appendLog(
                            "完成：${formatBytes(event.result.compressedBytes)}" +
                                "，用时 ${event.result.elapsedMs}ms"
                        )
                    }

                    is VideoCompressEvent.Failure -> {
                        errorText = event.error
                        event.diagnostics?.let { appendLog(it.takeLast(1200)) }
                        running = false
                        stage = "失败"
                    }

                    VideoCompressEvent.Cancelled -> {
                        running = false
                        stage = "已取消"
                        appendLog("已取消")
                    }
                }
            }
        }
    }

    fun logRoundHeader(round: Int, total: Int) {
        // 保留接口：轮次开始时的额外日志（当前并入 RoundStart 处理）
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("视频压缩", style = MaterialTheme.typography.headlineSmall)

        // 1. 导入
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("1. 选择视频", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { pickVideoLauncher.launch(arrayOf("video/*")) },
                    enabled = !running,
                ) { Text("从本地导入（SAF）") }
                inputInfo?.let { info ->
                    Text(
                        "原始大小 ${formatBytes(inputBytes)}\n" +
                            "时长 ${info.durationMs / 1000}s · 尺寸 ${info.width}x${info.height}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // 2. 目标大小
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("2. 目标大小", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = targetMbText,
                    onValueChange = { targetMbText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("目标大小（MB，十进制 1MB=1,000,000B）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 三个预设等分宽度 + 收窄内边距，避免「50MB」被挤成两行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val presets = listOf("25" to "微信 25MB", "10" to "10MB", "50" to "50MB")
                    presets.forEach { (value, label) ->
                        OutlinedButton(
                            onClick = { targetMbText = value },
                            enabled = !running,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(label, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // 3. 执行
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("3. 执行", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startCompress() },
                        enabled = !running && inputFile != null,
                    ) { Text("开始压缩") }
                    OutlinedButton(
                        onClick = { compressor.cancel() },
                        enabled = running,
                    ) { Text("取消") }
                }
                if (running) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "$stage · ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // 4. 结果
        result?.let { r ->
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("4. 结果", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "文件 ${r.file.name}\n" +
                            "大小 ${formatBytes(r.compressedBytes)}" +
                            "（原 ${formatBytes(r.originalBytes)}）\n" +
                            "参数 ${r.finalLabel}\n" +
                            "轮次 ${r.roundsUsed} · 用时 ${r.elapsedMs}ms",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!r.metTarget) {
                        Text(
                            "未达标：已保留最低参数下的最佳结果",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { quickSaveToDownloads(r.file) }) {
                            Text("保存到下载目录")
                        }
                        OutlinedButton(onClick = {
                            pendingSave = r
                            saveVideoLauncher.launch("compressed_${System.currentTimeMillis()}.mp4")
                        }) { Text("另存为…") }
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

        if (logs.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("日志", style = MaterialTheme.typography.titleMedium)
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
