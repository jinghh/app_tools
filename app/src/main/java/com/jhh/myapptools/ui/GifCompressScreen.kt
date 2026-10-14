package com.jhh.myapptools.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jhh.myapptools.gif.GifCompressEvent
import com.jhh.myapptools.gif.GifCompressResult
import com.jhh.myapptools.gif.GifCompressor
import com.jhh.myapptools.util.formatBytes
import com.jhh.myapptools.gif.GifInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * GIF 压缩界面（文档 3.3 / 8.3）。
 * 输入已有 GIF 与目标大小，按级联策略压缩，每轮校验真实字节数；未达标明确提示。
 */
@Composable
fun GifCompressScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val compressor = remember { GifCompressor() }

    var inputFile by remember { mutableStateOf<File?>(null) }
    var inputInfo by remember { mutableStateOf<GifInspector.Info?>(null) }
    var inputBytes by remember { mutableStateOf(0L) }

    var targetMbText by remember { mutableStateOf("20") }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<GifCompressResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf(listOf<String>()) }

    fun appendLog(line: String) {
        logs = (logs + line).takeLast(60)
    }

    val pickGifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) { copyGifToCache(context, uri) }
                if (copied == null) {
                    errorText = "导入失败：无法读取所选文件"
                    return@launch
                }
                val info = withContext(Dispatchers.IO) { GifInspector.inspect(copied) }
                inputFile = copied
                inputBytes = copied.length()
                inputInfo = info
                result = null
                errorText = null
                if (info != null) {
                    appendLog(
                        "导入成功：${copied.name}（${formatBytes(copied.length())}，" +
                            "${info.frameCount} 帧，${info.width}x${info.height}）"
                    )
                } else {
                    appendLog("导入成功但无法解析 GIF 结构")
                }
            }
        }
    }

    var pendingSave by remember { mutableStateOf<GifCompressResult?>(null) }
    val saveGifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/gif")
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
                    Toast.makeText(context, "GIF 已保存成功", Toast.LENGTH_SHORT).show()
                    appendLog("已保存到：$uri")
                } else {
                    Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show()
                    appendLog("保存失败：$uri")
                }
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
        // 文档 3.3：UI 标注 MB，内部固定按十进制 MB（1 MB = 1,000,000 bytes）换算
        val targetBytes = (targetMb * 1_000_000).toLong()
        if (targetBytes >= inputBytes) {
            appendLog("目标大小不小于原始大小，仍按策略尝试（原参数重编码可能仍有收益）")
        }

        running = true
        progress = 0f
        stage = "准备中"
        result = null
        errorText = null
        appendLog("开始压缩：目标 ${targetMb}MB（${targetBytes} bytes）")

        val events = Channel<GifCompressEvent>(Channel.UNLIMITED)
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    compressor.compress(input, targetBytes) { events.trySend(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    events.trySend(
                        GifCompressEvent.Failure(
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
                    is GifCompressEvent.RoundStart -> {
                        stage = "第 ${event.round}/${event.totalRounds} 轮：${event.label}"
                    }

                    is GifCompressEvent.RoundProgress -> {
                        // 总进度 = 已完成轮次 + 当前轮内进度
                        progress =
                            ((event.round - 1) + event.fraction) / event.totalRounds
                        stage =
                            "第 ${event.round}/${event.totalRounds} 轮：${event.label}"
                    }

                    is GifCompressEvent.RoundDone -> {
                        appendLog(
                            "第 ${event.round} 轮完成：${event.label} → " +
                                "${formatBytes(event.bytes)}" +
                                if (event.metTarget) "（达标）" else "（未达标，继续）"
                        )
                    }

                    is GifCompressEvent.Success -> {
                        running = false
                        progress = 1f
                        result = event.result
                        stage = if (event.result.metTarget) "完成" else "未达标"
                    }

                    is GifCompressEvent.Failure -> {
                        running = false
                        stage = "失败"
                        errorText = event.error
                        appendLog("失败：${event.error}")
                    }

                    GifCompressEvent.Cancelled -> {
                        running = false
                        stage = "已取消"
                        appendLog("已取消，中间产物已清理")
                    }
                }
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
        Text("GIF 压缩", style = MaterialTheme.typography.headlineSmall)
        Text(
            "导入任意 GIF，按策略级联压缩至目标大小；原文件不会被覆盖",
            style = MaterialTheme.typography.bodySmall,
        )

        // 1. 导入
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("1. 选择 GIF", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { pickGifLauncher.launch(arrayOf("image/gif")) },
                    enabled = !running,
                ) { Text("从本地导入（SAF）") }
                inputInfo?.let { info ->
                    Text(
                        "原始大小 ${formatBytes(inputBytes)}\n" +
                            "帧数 ${info.frameCount} · 尺寸 ${info.width}x${info.height}",
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
                )
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
                    val ratio = if (r.originalBytes > 0) {
                        String.format(
                            Locale.US,
                            "%.1f%%",
                            r.compressedBytes * 100.0 / r.originalBytes
                        )
                    } else {
                        "-"
                    }
                    Text(
                        "原始 ${formatBytes(r.originalBytes)} → " +
                            "压缩后 ${formatBytes(r.compressedBytes)}（$ratio）\n" +
                            "最终参数：${r.finalLabel}\n" +
                            "尺寸 ${r.width}x${r.height} · 帧数 ${r.frameCount}\n" +
                            "轮次 ${r.roundsUsed} · 耗时 ${r.elapsedMs}ms",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!r.metTarget) {
                        Text(
                            "未达标：已达到最低参数仍无法压到目标大小。" +
                                "建议缩短片段或放宽目标大小（原文件未改动）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    GifPreview(
                        file = r.file,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Button(onClick = {
                        pendingSave = r
                        saveGifLauncher.launch("compressed_${System.currentTimeMillis()}.gif")
                    }) { Text("保存 GIF") }
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

private fun copyGifToCache(context: Context, uri: Uri): File? =
    copyUriToCache(context, uri, subDir = "gif-input", fallbackExt = "gif")
