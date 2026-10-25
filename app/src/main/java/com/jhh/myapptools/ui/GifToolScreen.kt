package com.jhh.myapptools.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jhh.myapptools.gif.FfmpegGifEngine
import com.jhh.myapptools.gif.GifToolPrefs
import com.jhh.myapptools.util.formatBytes
import com.jhh.myapptools.gif.GifEncodeEvent
import com.jhh.myapptools.gif.GifEncodeParams
import com.jhh.myapptools.gif.GifEncodeResult
import com.jhh.myapptools.media.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 视频转 GIF 主界面。
 * 引擎结论：纯 Java 编码已实测弃用（慢 29 倍），仅保留 FFmpeg 调色板链路。
 */
@Composable
fun GifToolScreen(
    modifier: Modifier = Modifier,
    /** 由下载模块直传的视频（已在私有目录，无需再经 SAF 导入） */
    preloadedVideo: File? = null,
    onPreloadConsumed: () -> Unit = {},
    /** 把转换结果直传给「GIF 压缩」页（免去手动保存再导入） */
    onSendToCompress: (File) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 引擎实例跨任务复用，内部状态在每次 encode 入口复位（见 GifEngine 接口约定）
    val engine = remember { FfmpegGifEngine() }

    var inputFile by remember { mutableStateOf<File?>(null) }
    var videoInfo by remember { mutableStateOf<VideoMetadata.Info?>(null) }

    // 参数记忆（P2-11）：从上次使用的值恢复；任何调整立即写回
    val gifPrefs = remember { GifToolPrefs(context) }
    var startSec by remember { mutableFloatStateOf(0f) }
    var endSec by remember { mutableFloatStateOf(0f) }

    var fps by remember { mutableIntStateOf(gifPrefs.fps()) }
    var width by remember { mutableIntStateOf(gifPrefs.width()) }
    var maxColors by remember { mutableIntStateOf(gifPrefs.maxColors()) }
    var dither by remember { mutableStateOf(gifPrefs.dither()) }

    /** 把当前四项参数写入记忆（与预设/微调共用，保证「最后使用值」总是最新的） */
    fun persistParams() = gifPrefs.save(fps, width, maxColors, dither)

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var stage by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<GifEncodeResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var ffmpegCommand by remember { mutableStateOf("") }

    fun appendLog(line: String) {
        logs = (logs + line).takeLast(60)
    }

    // ---------- 接收下载模块直传的视频 ----------

    LaunchedEffect(preloadedVideo) {
        val file = preloadedVideo ?: return@LaunchedEffect
        val info = withContext(Dispatchers.IO) { VideoMetadata.read(file) }
        inputFile = file
        videoInfo = info
        result = null
        errorText = null
        diagnostics = null
        if (info != null) {
            startSec = 0f
            endSec = if (info.durationMs <= 30_000L) info.durationMs / 1000f else 30f
            appendLog(
                "已接收下载的视频：${file.name}（${info.durationMs / 1000}s " +
                    "${info.width}x${info.height}）"
            )
        } else {
            appendLog("已接收下载的视频，但无法解析元信息：${file.name}")
        }
        onPreloadConsumed()
    }

    // ---------- SAF 导入 ----------

    val pickVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) { copyVideoToCache(context, uri) }
                if (copied == null) {
                    errorText = "导入失败：无法读取所选文件"
                    return@launch
                }
                val info = withContext(Dispatchers.IO) { VideoMetadata.read(copied) }
                inputFile = copied
                videoInfo = info
                result = null
                errorText = null
                diagnostics = null
                if (info != null) {
                    // 文档 2.3 规则：<=30s 全片；>30s 默认前 30s
                    startSec = 0f
                    endSec = if (info.durationMs <= 30_000L) {
                        info.durationMs / 1000f
                    } else {
                        30_000f / 1000f
                    }
                    appendLog(
                        "导入成功：${copied.name}（${info.durationMs / 1000}s " +
                            "${info.width}x${info.height}）"
                    )
                } else {
                    appendLog("导入成功但无法解析视频元信息")
                }
            }
        }
    }

    // ---------- 结果导出 ----------

    var pendingSave by remember { mutableStateOf<GifEncodeResult?>(null) }
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

    // ---------- 编码执行 ----------

    /**
     * 一键保存到系统「下载」目录（MediaStore，API 29+ 免权限）；
     * SAF 保留为「另存为」入口（P2-6）。
     */
    fun quickSaveToDownloads(file: File) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                saveToPublicDownloads(context, file, mimeForFile(file))
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

    fun startEncode() {
        val input = inputFile ?: return
        if (videoInfo == null) return
        val startMs = (startSec * 1000).toLong()
        val durationMs = ((endSec - startSec) * 1000).toLong()
        if (durationMs <= 0L) {
            errorText = "片段时长必须大于 0 秒"
            return
        }

        val outDir = File(context.cacheDir, "gif-output").apply { mkdirs() }
        val outFile = File(outDir, "gif_${System.currentTimeMillis()}.gif")
        val params = GifEncodeParams(
            startMs = startMs,
            durationMs = durationMs,
            fps = fps,
            width = width,
            maxColors = maxColors,
            dither = dither,
        )

        ffmpegCommand = FfmpegGifEngine.buildArgs(
            input, outFile, params, startMs / 1000.0, durationMs / 1000.0
        ).joinToString(" ")

        running = true
        progress = 0f
        stage = "准备中"
        result = null
        errorText = null
        diagnostics = null
        appendLog("开始编码：ffmpeg fps=$fps 宽=${width}px 片段=${durationMs}ms")

        // 引擎回调可能在任意线程；用 Channel 汇回主线程协程处理
        val events = Channel<GifEncodeEvent>(Channel.UNLIMITED)
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    engine.encode(input, outFile, params) { events.trySend(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 兜底：原生库缺失（如 32 位设备无 ffmpeg .so）等异常不得崩溃
                    events.trySend(
                        GifEncodeEvent.Failure(
                            "引擎异常：${e.message ?: e.javaClass.simpleName}",
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
                    is GifEncodeEvent.Progress -> {
                        progress = event.fraction
                        stage = event.stage
                    }

                    is GifEncodeEvent.Success -> {
                        running = false
                        progress = 1f
                        stage = "完成"
                        result = event.result
                        appendLog(
                            "完成：${formatBytes(event.result.bytes)}，" +
                                "${event.result.frameCount} 帧，" +
                                "${event.result.width}x${event.result.height}，" +
                                "耗时 ${event.result.elapsedMs}ms"
                        )
                    }

                    is GifEncodeEvent.Failure -> {
                        running = false
                        stage = "失败"
                        errorText = event.error
                        diagnostics = event.diagnostics
                        appendLog("失败：${event.error}")
                    }

                    GifEncodeEvent.Cancelled -> {
                        running = false
                        stage = "已取消"
                        appendLog("已取消")
                    }
                }
            }
        }
    }

    // ---------- UI ----------

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("视频转 GIF", style = MaterialTheme.typography.headlineSmall)

        // 1. 导入视频
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
                videoInfo?.let { info ->
                    Text(
                        "时长 ${info.durationMs / 1000}s · 分辨率 ${info.width}x${info.height}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (info.durationMs > 30_000L) {
                        Text(
                            "超过 30 秒：默认片段 0-30s，可手动调整",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                inputFile?.let { file ->
                    Text(
                        "已复制到私有目录：${file.name}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // 2. 视频预览
        inputFile?.let { file ->
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("2. 预览（Media3 ExoPlayer）", style = MaterialTheme.typography.titleMedium)
                    VideoPreview(
                        file = file,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }

        // 3. 截取区间
        videoInfo?.let { info ->
            val durationSec = info.durationMs / 1000f
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("3. 截取区间", style = MaterialTheme.typography.titleMedium)
                    Text("起点：${startSec.toInt()}s", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = startSec.coerceIn(0f, (endSec - 1f).coerceAtLeast(0f)),
                        onValueChange = { startSec = it },
                        valueRange = 0f..durationSec,
                        enabled = !running,
                    )
                    Text("终点：${endSec.toInt()}s", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = endSec.coerceIn(0f, durationSec),
                        onValueChange = { value ->
                            endSec = value
                                .coerceAtLeast(startSec + 1f)
                                .coerceAtMost(durationSec)
                        },
                        valueRange = 0f..durationSec,
                        enabled = !running,
                    )
                    Text(
                        "预计片段时长：${(endSec - startSec).toInt()}s",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // 4. 编码参数与引擎选择
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("4. 编码参数", style = MaterialTheme.typography.titleMedium)

                // 画质预设（P2-12）：一键写入四项参数（随之被参数记忆保存），之后仍可单独微调
                Text("画质预设", style = MaterialTheme.typography.bodyMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "小体积" to GifPreset(8, 360, 64, dither = false),
                        "平衡" to GifPreset(12, 480, 128, dither = true),
                        "高画质" to GifPreset(15, 640, 256, dither = true),
                    ).forEach { (label, preset) ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                fps = preset.fps
                                width = preset.width
                                maxColors = preset.maxColors
                                dither = preset.dither
                                persistParams()
                            },
                            enabled = !running,
                            label = { Text(label) },
                        )
                    }
                }

                ChipRow(
                    label = "帧率 FPS",
                    options = listOf(6, 8, 10, 12, 15, 20),
                    current = fps,
                    enabled = !running,
                ) { fps = it; persistParams() }

                ChipRow(
                    label = "输出宽度 px（保持宽高比，不放大）",
                    options = listOf(240, 320, 360, 480, 640),
                    current = width,
                    enabled = !running,
                ) { width = it; persistParams() }

                ChipRow(
                    label = "调色板颜色数（压缩模块将按此级联降参）",
                    options = listOf(256, 128, 64, 32),
                    current = maxColors,
                    enabled = !running,
                ) { maxColors = it; persistParams() }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Bayer 抖动", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = dither,
                        onCheckedChange = { dither = it; persistParams() },
                        enabled = !running,
                    )
                }
            }
        }

        // 5. 执行控制
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("5. 执行", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startEncode() },
                        enabled = !running && inputFile != null,
                    ) { Text("开始转换") }
                    OutlinedButton(
                        onClick = { engine.cancel() },
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
                if (ffmpegCommand.isNotEmpty()) {
                    Text(
                        "ffmpeg $ffmpegCommand",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 4,
                    )
                }
            }
        }

        // 6. 结果
        result?.let { r ->
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("6. 结果", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "文件 ${r.file.name}\n" +
                            "大小 ${formatBytes(r.bytes)}\n" +
                            "尺寸 ${r.width}x${r.height}\n" +
                            "帧数 ${r.frameCount}\n" +
                            "耗时 ${r.elapsedMs}ms",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    GifPreview(
                        file = r.file,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // 一键保存到系统「下载」目录（免权限）；SAF 保留为「另存为」（P2-6）
                            Button(onClick = { quickSaveToDownloads(r.file) }) { Text("保存 GIF") }
                            OutlinedButton(onClick = {
                                pendingSave = r
                                saveGifLauncher.launch("gif_${System.currentTimeMillis()}.gif")
                            }) { Text("另存为…") }
                        } else {
                            Button(onClick = {
                                pendingSave = r
                                saveGifLauncher.launch("gif_${System.currentTimeMillis()}.gif")
                            }) { Text("保存 GIF") }
                        }
                        OutlinedButton(onClick = { onSendToCompress(r.file) }) {
                            Text("压缩")
                        }
                    }
                }
            }
        }

        // 错误与诊断
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

        // 日志
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

@Composable
private fun ChipRow(
    label: String,
    options: List<Int>,
    current: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        // FlowRow 自适应换行：选项超出可用宽度时自动折行，避免最后一个 Chip 被挤压变形
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == current,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(option.toString()) },
                )
            }
        }
    }
}

private fun copyVideoToCache(context: Context, uri: Uri): File? =
    copyUriToCache(context, uri, subDir = "video-input", fallbackExt = "mp4")

/** 画质预设（P2-12）：一键写入的参数组合；写入后仍可单独微调 */
private data class GifPreset(val fps: Int, val width: Int, val maxColors: Int, val dither: Boolean)
