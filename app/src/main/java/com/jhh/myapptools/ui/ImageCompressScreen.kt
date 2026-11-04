package com.jhh.myapptools.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jhh.myapptools.image.CompressMode
import com.jhh.myapptools.image.ImageBatchSummary
import com.jhh.myapptools.image.ImageCompressEvent
import com.jhh.myapptools.image.ImageCompressOptions
import com.jhh.myapptools.image.ImageCompressResult
import com.jhh.myapptools.image.ImageCompressor
import com.jhh.myapptools.image.ImageFormat
import com.jhh.myapptools.image.ImageInspector
import com.jhh.myapptools.image.ImageToolPrefs
import com.jhh.myapptools.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 图片压缩页（有损 / 无损 / 目标大小 + 尺寸与格式转换，支持批量）。
 *
 * 与「GIF 压缩」「视频压缩」的关系：
 * - 复用同一套「参数校验 → 执行 → 真实字节数校验 → 结果」交互骨架；
 * - 但引擎完全不碰 FFmpeg（平台侧只有 WebP 解码器、没有编码器），
 *   走 `Bitmap` 原生编解码，详见 `image/ImageCompressor.kt` 类注释。
 *
 * 状态刻意保持为界面级（与另外两个压缩页一致），旋转屏幕会回到空闲态——
 * 图片是秒级任务，这与 GIF/视频的长任务问题（优化建议 P1-3）不同，暂不引入进程级任务单例。
 */
@Composable
fun ImageCompressScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ImageToolPrefs(context) }
    val compressor = remember { ImageCompressor(File(context.cacheDir, "image-output")) }

    var items by remember { mutableStateOf<List<PickedImage>>(emptyList()) }

    var mode by remember {
        mutableStateOf(
            runCatching { CompressMode.valueOf(prefs.mode(CompressMode.LOSSY.name)) }
                .getOrDefault(CompressMode.LOSSY)
        )
    }
    // 质量与目标大小用滑块：范围有限、需要即时反馈，输入框反而要多一步
    var quality by remember {
        mutableFloatStateOf(prefs.quality("80").toFloatOrNull()?.coerceIn(1f, 100f) ?: 80f)
    }
    var targetKb by remember {
        mutableFloatStateOf(prefs.target("500").toFloatOrNull()?.coerceAtLeast(10f) ?: 500f)
    }
    var sizeKey by remember {
        val saved = prefs.size(SIZE_OPTIONS.first().key)
        mutableStateOf(SIZE_OPTIONS.firstOrNull { it.key == saved }?.key ?: SIZE_OPTIONS.first().key)
    }
    var customEdgeText by remember { mutableStateOf(prefs.customEdge("1920")) }
    var formatKey by remember {
        val saved = prefs.format(FORMAT_OPTIONS.first().key)
        mutableStateOf(FORMAT_OPTIONS.firstOrNull { it.key == saved }?.key ?: FORMAT_OPTIONS.first().key)
    }

    var running by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var stageText by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ImageCompressResult>>(emptyList()) }
    var failures by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var summary by remember { mutableStateOf<ImageBatchSummary?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }

    fun appendLog(line: String) {
        logs = (logs + line).takeLast(60)
    }

    // 参数记忆：任何一项变化都落盘
    LaunchedEffect(mode, quality, targetKb, sizeKey, customEdgeText, formatKey) {
        prefs.save(
            mode = mode.name,
            quality = quality.toString(),
            target = targetKb.toString(),
            size = sizeKey,
            customEdge = customEdgeText,
            format = formatKey,
        )
    }

    // 目标大小滑块：上限跟随「最大那张原图」，下限用固定绝对值。
    // 下限不再按原图的 5% 算——那样会跟「输出尺寸」脱节：用户把长边缩到 640px 后
    // 实际 20-30KB 就够，却被原来的 5% 护栏挡住。能否达标交给引擎，未达标会明确标注。
    val maxSourceBytes = items.maxOfOrNull { it.file.length() } ?: 0L
    val targetUpperKb = if (maxSourceBytes > 0L) {
        (maxSourceBytes / 1000f).coerceAtLeast(MIN_TARGET_KB * 5)
    } else {
        2048f
    }
    val targetKbRange = MIN_TARGET_KB..targetUpperKb

    /** 目标占最大原图的比例；未导入图片时为 null */
    val targetRatio = if (maxSourceBytes > 0L) {
        (targetKb * 1000.0) / maxSourceBytes
    } else {
        null
    }

    // 换了更小的图或被清空时，把滑块值拉回有效区间，避免"设置值比原图还大"的假设置
    LaunchedEffect(targetKbRange.start, targetKbRange.endInclusive) {
        targetKb = targetKb.coerceIn(targetKbRange.start, targetKbRange.endInclusive)
    }

    // ---------- 导入（可多选）----------
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty() || running) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val picked = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val file = copyUriToCache(context, uri, "image-input", "jpg")
                        ?: return@mapNotNull null
                    PickedImage(file, ImageInspector.read(file))
                }
            }
            val merged = items + picked
            items = merged.take(MAX_BATCH)
            importing = false
            if (picked.isEmpty()) {
                errorText = "导入失败：无法读取所选文件"
            } else if (merged.size > MAX_BATCH) {
                errorText = "单批最多 $MAX_BATCH 张，已保留前 $MAX_BATCH 张"
            } else {
                errorText = null
                appendLog(
                    "已导入 ${picked.size} 张，共 ${formatBytes(picked.sumOf { it.file.length() })}"
                )
            }
        }
    }

    // ---------- 保存到相册 ----------
    fun saveToGallery(targets: List<ImageCompressResult>) {
        if (targets.isEmpty()) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                targets.count { saveToPublicPictures(context, it.file, it.format.mime) != null }
            }
            if (saved == targets.size) {
                Toast.makeText(context, "已保存 $saved 张到相册", Toast.LENGTH_SHORT).show()
                appendLog("已保存 $saved 张到相册（Pictures/MyAppTools）")
            } else if (saved > 0) {
                Toast.makeText(context, "已保存 $saved/${targets.size} 张", Toast.LENGTH_LONG).show()
                appendLog("部分保存成功：$saved/${targets.size}")
            } else {
                Toast.makeText(
                    context,
                    "保存失败：Android 9 及以下请用「另存为」选择位置",
                    Toast.LENGTH_LONG,
                ).show()
                appendLog("保存到相册失败（低于 Android 10 不支持免权限写入相册）")
            }
        }
    }

    // ---------- 另存为（单张）----------
    var pendingSave by remember { mutableStateOf<ImageCompressResult?>(null) }

    fun writeViaSaf(uri: android.net.Uri?) {
        val target = pendingSave ?: return
        if (uri == null) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        target.file.inputStream().use { it.copyTo(out) }
                    } ?: error("openOutputStream 返回 null")
                }.isSuccess
            }
            if (ok) {
                Toast.makeText(context, "图片已保存", Toast.LENGTH_SHORT).show()
                appendLog("已另存为：$uri")
            } else {
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_LONG).show()
                appendLog("另存失败：$uri")
            }
            pendingSave = null
        }
    }

    val saveJpegLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { writeViaSaf(it) }
    val savePngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { writeViaSaf(it) }
    val saveWebpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/webp")
    ) { writeViaSaf(it) }

    fun launchSaveAs(result: ImageCompressResult) {
        pendingSave = result
        val name = "compressed_${System.currentTimeMillis()}.${result.format.extension}"
        when (result.format) {
            ImageFormat.PNG -> savePngLauncher.launch(name)
            ImageFormat.WEBP -> saveWebpLauncher.launch(name)
            else -> saveJpegLauncher.launch(name)
        }
    }

    // ---------- 开始压缩 ----------
    fun startCompress() {
        if (items.isEmpty()) return
        // 整批拦截只在「所有图都是有损源」时才做，避免第一张是 JPEG 就把整批 PNG 也挡掉；
        // 混合格式交给引擎逐张处理，界面仍按第一张给红字提示
        val allLossySources = items.isNotEmpty() &&
            items.all { isLossySource(it.info?.format) }
        val options = buildOptions(
            mode = mode,
            quality = quality,
            targetKb = targetKb,
            sizeOption = SIZE_OPTIONS.first { it.key == sizeKey },
            customEdgeText = customEdgeText,
            format = FORMAT_OPTIONS.firstOrNull { it.key == formatKey }?.format,
            allLossySources = allLossySources,
        ) ?: run {
            errorText = optionsError(
                mode, sizeKey, customEdgeText, formatKey, allLossySources
            )
            return
        }

        val inputs = items.map { it.file }
        running = true
        stageText = "准备中"
        results = emptyList()
        failures = emptyList()
        summary = null
        errorText = null
        appendLog(
            "开始压缩 ${inputs.size} 张 · " + describeOptions(options)
        )

        val events = Channel<ImageCompressEvent>(Channel.UNLIMITED)
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    compressor.compress(inputs, options) { events.trySend(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    events.trySend(
                        ImageCompressEvent.ItemFailed(
                            0, inputs.size, "", "引擎异常：${e.message ?: e.javaClass.simpleName}"
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
                    is ImageCompressEvent.ItemStart ->
                        stageText = "第 ${event.index}/${event.total} 张 · ${event.name}"

                    is ImageCompressEvent.ItemStage ->
                        stageText = "第 ${event.index}/${event.total} 张 · ${event.stage}"

                    is ImageCompressEvent.ItemDone -> {
                        results = results + event.result
                        val r = event.result
                        appendLog(
                            "${r.file.name}：" + formatBytes(r.originalBytes) + " → " +
                                formatBytes(r.outputBytes) +
                                "（${signedPercent(r.savedPercent)}）"
                        )
                    }

                    is ImageCompressEvent.ItemFailed -> {
                        failures = failures + (event.name to event.reason)
                        appendLog("失败：${event.name} —— ${event.reason}")
                    }

                    is ImageCompressEvent.BatchDone -> {
                        summary = event.summary
                        running = false
                        stageText = "完成"
                        appendLog(
                            "完成：成功 ${event.summary.success} · 失败 ${event.summary.failed}" +
                                " · 用时 ${event.summary.elapsedMs}ms"
                        )
                    }

                    ImageCompressEvent.Cancelled -> {
                        running = false
                        stageText = "已取消"
                        appendLog("已取消（已完成的结果保留）")
                    }
                }
            }
        }
    }

    ScreenScaffold(modifier = modifier, title = "图片压缩") {
        // 1. 选择图片
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("选择图片", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { pickLauncher.launch(arrayOf("image/*")) },
                    enabled = !running && !importing,
                ) {
                    Text(if (importing) "导入中…" else "从本地导入（可多选）")
                }
                if (items.isNotEmpty()) {
                    Text(
                        "已选 ${items.size} 张，共 ${formatBytes(items.sumOf { it.file.length() })}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    items.forEachIndexed { index, picked ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    picked.file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    describeImage(picked),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // 导入时就标出不能处理的图，避免用户配好参数点开始才失败
                                pickedIssue(picked)?.let { issue ->
                                    Text(
                                        issue,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    items = items.filterIndexed { i, _ -> i != index }
                                },
                                enabled = !running,
                            ) { Text("移除") }
                        }
                    }
                }
            }
        }

        // 2. 压缩设置
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("压缩设置", style = MaterialTheme.typography.titleMedium)

                // 模式
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ChoiceButton(
                        label = "有损",
                        selected = mode == CompressMode.LOSSY,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    ) { mode = CompressMode.LOSSY }
                    ChoiceButton(
                        label = "无损",
                        selected = mode == CompressMode.LOSSLESS,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    ) { mode = CompressMode.LOSSLESS }
                    ChoiceButton(
                        label = "目标大小",
                        selected = mode == CompressMode.TARGET_SIZE,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    ) { mode = CompressMode.TARGET_SIZE }
                }

                if (mode == CompressMode.LOSSY) {
                    ParamSlider(
                        value = quality,
                        onValueChange = { quality = it },
                        valueRange = 1f..100f,
                        leftLabel = "原始 ${formatBytes(items.sumOf { it.file.length() })}",
                        rightLabel = "质量 ${quality.roundToInt()}",
                        enabled = !running,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(85f to "高 85", 75f to "中 75", 60f to "低 60").forEach { (value, label) ->
                            OutlinedButton(
                                onClick = { quality = value },
                                enabled = !running,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text(label, maxLines = 1, softWrap = false) }
                        }
                    }
                }

                if (mode == CompressMode.TARGET_SIZE) {
                    ParamSlider(
                        value = targetKb,
                        onValueChange = { targetKb = it },
                        valueRange = targetKbRange,
                        leftLabel = if (maxSourceBytes > 0L) {
                            "原图 ${formatBytes(maxSourceBytes)}"
                        } else {
                            "原图 --"
                        },
                        rightLabel = "设置 ${formatKb(targetKb)}",
                        enabled = !running,
                    )
                    // 占比对照：让用户知道「拖到这里意味着压到原图的百分之几」，
                    // 极低目标提前警示（会跑满编码轮次且结果可能不可用）
                    targetRatio?.let { ratio ->
                        val tooSmall = ratio <= LOW_TARGET_RATIO
                        Text(
                            text = if (tooSmall) {
                                "当前目标约为原图的 ${formatPercent(ratio)}，" +
                                    "多数图片无法达标，产物可能明显模糊"
                            } else {
                                "当前目标约为原图的 ${formatPercent(ratio)}" +
                                    "（${formatKb(targetKb)} / ${formatBytes(maxSourceBytes)}）"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (tooSmall) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(200f to "200KB", 500f to "500KB", 1024f to "1MB", 2048f to "2MB")
                            .forEach { (value, label) ->
                                OutlinedButton(
                                    onClick = {
                                        targetKb = value.coerceIn(
                                            targetKbRange.start,
                                            targetKbRange.endInclusive,
                                        )
                                    },
                                    enabled = !running,
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f),
                                ) { Text(label, maxLines = 1, softWrap = false) }
                            }
                    }
                }

                // 输出尺寸
                Text("输出尺寸", style = MaterialTheme.typography.bodyMedium)
                SIZE_OPTIONS.chunked(4).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowOptions.forEach { option ->
                            ChoiceButton(
                                label = option.label,
                                selected = sizeKey == option.key,
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                            ) { sizeKey = option.key }
                        }
                        repeat(4 - rowOptions.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
                if (SIZE_OPTIONS.first { it.key == sizeKey }.custom) {
                    OutlinedTextField(
                        value = customEdgeText,
                        onValueChange = {
                            customEdgeText = it.filter { c -> c.isDigit() }.take(5)
                        },
                        label = { Text("长边像素（按比例缩放）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 输出格式
                Text("输出格式", style = MaterialTheme.typography.bodyMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FORMAT_OPTIONS.forEach { option ->
                        ChoiceButton(
                            label = option.label,
                            selected = formatKey == option.key,
                            enabled = !running,
                            modifier = Modifier.weight(1f),
                        ) { formatKey = option.key }
                    }
                }

                val sourceFormat = items.firstOrNull()?.info?.format
                Text(
                    text = hintText(mode, formatKey, sizeKey, sourceFormat),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hintIsWarning(mode, formatKey, sizeKey, sourceFormat)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        // 3. 执行
        Card {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("执行", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startCompress() },
                        enabled = !running && !importing && items.isNotEmpty(),
                    ) { Text("开始压缩") }
                    OutlinedButton(
                        onClick = {
                            compressor.cancel()
                            appendLog("已请求取消，当前图片处理完即停止")
                        },
                        enabled = running,
                    ) { Text("取消") }
                }
                if (running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stageText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // 4. 结果
        summary?.let { s ->
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("结果", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "共 ${s.total} 张 · 成功 ${s.success} · 失败 ${s.failed}\n" +
                            "体积 ${formatBytes(s.originalBytes)} → ${formatBytes(s.outputBytes)}" +
                            "（${signedPercent(s.savedPercent)}）\n" +
                            "用时 ${s.elapsedMs}ms",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    results.forEach { r ->
                        Text(
                            "${r.file.name}\n" +
                                "  ${r.srcWidth}x${r.srcHeight} → ${r.outWidth}x${r.outHeight}" +
                                " · ${r.format.displayName}\n" +
                                "  ${formatBytes(r.originalBytes)} → ${formatBytes(r.outputBytes)}" +
                                "（${signedPercent(r.savedPercent)}）· ${r.finalLabel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!results.all { it.metTarget }) {
                        Text(
                            "部分图片未达标：已保留最低参数下的最佳结果",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (failures.isNotEmpty()) {
                        Text(
                            failures.joinToString("\n") { "${it.first}：${it.second}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    val actionPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { saveToGallery(results) },
                            contentPadding = actionPadding,
                            modifier = Modifier.weight(1f),
                        ) { Text("存到相册", maxLines = 1) }
                        if (results.size == 1) {
                            OutlinedButton(
                                onClick = { launchSaveAs(results.first()) },
                                contentPadding = actionPadding,
                                modifier = Modifier.weight(1f),
                            ) { Text("另存为", maxLines = 1) }
                        }
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
                    Text("提示", style = MaterialTheme.typography.titleMedium)
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

// ---------------------------------------------------------------- 参数模型与校验

/** 单批上限：串行处理下超过这个数量用户等待时间过长，也会让缓存占用失控 */
private const val MAX_BATCH = 30

/** 目标大小滑块下限（KB）：再小连 JPEG 头都放不下，也没有实用价值 */
private const val MIN_TARGET_KB = 10f

/** 目标低于原图这个比例时提前警示：多数图片无法达标且产物会明显模糊 */
private const val LOW_TARGET_RATIO = 0.02

private data class PickedImage(val file: File, val info: ImageInspector.Info?)

private data class SizeOption(
    val key: String,
    val label: String,
    val edge: Int? = null,
    val percent: Int? = null,
    val custom: Boolean = false,
)

private data class FormatOption(val key: String, val label: String, val format: ImageFormat?)

private val SIZE_OPTIONS = listOf(
    SizeOption(key = "keep", label = "原始"),
    SizeOption(key = "edge_2048", label = "2048px", edge = 2048),
    SizeOption(key = "edge_1920", label = "1920px", edge = 1920),
    SizeOption(key = "edge_1280", label = "1280px", edge = 1280),
    SizeOption(key = "edge_1080", label = "1080px", edge = 1080),
    SizeOption(key = "edge_640", label = "640px", edge = 640),
    SizeOption(key = "pct_75", label = "75%", percent = 75),
    SizeOption(key = "pct_50", label = "50%", percent = 50),
    SizeOption(key = "pct_25", label = "25%", percent = 25),
    SizeOption(key = "custom", label = "自定义", custom = true),
)

private val FORMAT_OPTIONS = listOf(
    FormatOption("keep", "原格式", null),
    FormatOption("jpeg", "JPEG", ImageFormat.JPEG),
    FormatOption("png", "PNG", ImageFormat.PNG),
    FormatOption("webp", "WebP", ImageFormat.WEBP),
)

/**
 * 组装引擎参数。输入不合法时返回 null，由 [optionsError] 给出具体原因——
 * 分成两个函数是为了让「为什么不能开始」这句话只有一处来源。
 */
private fun buildOptions(
    mode: CompressMode,
    quality: Float,
    targetKb: Float,
    sizeOption: SizeOption,
    customEdgeText: String,
    format: ImageFormat?,
    /** 是否整批都是 JPEG/WebP 有损源；整批拦截只在这种情况才成立 */
    allLossySources: Boolean,
): ImageCompressOptions? {
    val maxEdge: Int? = when {
        sizeOption.edge != null -> sizeOption.edge
        sizeOption.custom -> customEdgeText.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        else -> null
    }

    return when (mode) {
        CompressMode.LOSSY -> ImageCompressOptions(
            mode = mode,
            // 滑块是连续的，取整后再夹一次范围，保证传给引擎的一定是合法整数
            quality = quality.roundToInt().coerceIn(1, 100),
            maxEdge = maxEdge,
            scalePercent = sizeOption.percent,
            outputFormat = format,
        )

        CompressMode.LOSSLESS -> {
            // JPEG 无法无损重编码，必须显式选 PNG 或 WebP
            if (format == ImageFormat.JPEG) return null
            // 有损源（JPEG / 有损 WebP）保持「原格式」又要改尺寸时，引擎会被迫转成 PNG，
            // 体积可能暴涨 5-10 倍而用户毫无察觉。直接拦住，让选择权回到用户手上。
            if (format == null && allLossySources && sizeOption.key != "keep") {
                return null
            }
            ImageCompressOptions(
                mode = mode,
                maxEdge = maxEdge,
                scalePercent = sizeOption.percent,
                outputFormat = format,
            )
        }

        CompressMode.TARGET_SIZE -> ImageCompressOptions(
            mode = mode,
            targetBytes = (targetKb * 1000).toLong(),
            maxEdge = maxEdge,
            scalePercent = sizeOption.percent,
            outputFormat = format,
        )
    }
}

/** 滑块已经把数值限定在合法区间，这里只剩「组合不成立」的提示 */
private fun optionsError(
    mode: CompressMode,
    sizeKey: String,
    customEdgeText: String,
    formatKey: String,
    allLossySources: Boolean,
): String = when {
    mode == CompressMode.LOSSLESS && formatKey == "jpeg" ->
        "JPEG 不支持无损重编码，请把输出格式改为 PNG 或 WebP"

    mode == CompressMode.LOSSLESS && formatKey == "keep" && sizeKey != "keep" &&
        allLossySources ->
        "JPEG/WebP 有损源改尺寸时无法保持「原格式无损」（会转成 PNG 并显著增大体积）。" +
            "请改用「有损」，或显式选择 PNG/WebP 作为输出格式"

    sizeKey == "custom" &&
        (customEdgeText.trim().toIntOrNull()?.takeIf { it > 0 } == null) ->
        "自定义长边必须是大于 0 的整数"

    else -> "参数不合法，请检查设置"
}

private fun describeOptions(options: ImageCompressOptions): String {
    val parts = mutableListOf<String>()
    when (options.mode) {
        CompressMode.LOSSY -> parts += "有损 q${options.quality}"
        CompressMode.LOSSLESS -> parts += "无损"
        CompressMode.TARGET_SIZE -> parts += "目标 ${formatBytes(options.targetBytes)}"
    }
    parts += when {
        options.scalePercent != null -> "缩放 ${options.scalePercent}%"
        options.maxEdge != null -> "长边 ${options.maxEdge}px"
        else -> "原始尺寸"
    }
    parts += options.outputFormat?.displayName ?: "保持原格式"
    return parts.joinToString(" · ")
}

private fun describeImage(picked: PickedImage): String {
    val info = picked.info ?: return "无法识别的图片"
    return listOfNotNull(
        "${info.displayWidth}x${info.displayHeight}",
        info.format.displayName,
        formatBytes(info.bytes),
        if (info.hasAlpha) "含透明" else null,
    ).joinToString(" · ")
}

/** 这张图为什么不能处理；能处理返回 null */
private fun pickedIssue(picked: PickedImage): String? {
    val info = picked.info ?: return "无法识别的图片"
    if (info.isAnimated || info.format == ImageFormat.GIF) {
        return "动图不支持，请用「GIF 压缩」页"
    }
    if (info.tooLarge) return "图片过大（超过 1 亿像素），不支持"
    return null
}

/** 无损模式的收益说明：必须如实告知，避免用户以为「无损」也能减半、或意外把图压大 */
private fun hintText(
    mode: CompressMode,
    formatKey: String,
    sizeKey: String,
    sourceFormat: ImageFormat?,
): String = when {
    mode == CompressMode.LOSSLESS && formatKey == "keep" && sizeKey == "keep" ->
        "无损 + 原格式 + 原始尺寸：直接保留原图（不重编码），体积不变。" +
            "Android 平台没有无损再优化能力；要变小请用「有损」或改尺寸/格式。"

    mode == CompressMode.LOSSLESS && isLossySource(sourceFormat) ->
        "注意：JPEG/WebP 有损源改尺寸或转 PNG 无损需要重编码，体积可能增大 5-10 倍。" +
            "只想变小的话请改用「有损」。"

    mode == CompressMode.LOSSLESS ->
        "无损不改动像素；换了尺寸或格式就必须重编码，体积可能反而增大。" +
            "要明显变小请用「有损」或转 WebP 有损。"

    mode == CompressMode.TARGET_SIZE ->
        "自动二分搜索质量，仍超限再降尺寸；走完阶梯仍超限会保留最小结果并标注未达标。"

    else ->
        "有损会丢弃部分像素细节，质量越低体积越小。"
}

/** JPEG 与 WebP 有损都没有无损编码能力；无损 + 改尺寸时会被迫转成 PNG */
private fun isLossySource(sourceFormat: ImageFormat?): Boolean =
    sourceFormat == ImageFormat.JPEG || sourceFormat == ImageFormat.WEBP

/** 无损 + 有损源 + 改了尺寸/格式：属于可能把图压大的组合，用红色醒目提示 */
private fun hintIsWarning(
    mode: CompressMode,
    formatKey: String,
    sizeKey: String,
    sourceFormat: ImageFormat?,
): Boolean = mode == CompressMode.LOSSLESS && isLossySource(sourceFormat) &&
    (formatKey != "keep" || sizeKey != "keep")

@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val padding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            contentPadding = padding,
            modifier = modifier,
        ) { Text(label, maxLines = 1, softWrap = false) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = padding,
            modifier = modifier,
        ) { Text(label, maxLines = 1, softWrap = false) }
    }
}

/** KB 值展示：不足 1MB 用 KB，否则换算 MB（与 formatBytes 的十进制口径一致） */
private fun formatKb(kb: Float): String =
    if (kb < 1000f) {
        String.format(Locale.US, "%.0f KB", kb)
    } else {
        String.format(Locale.US, "%.2f MB", kb / 1000f)
    }

/** 占比展示：小数比例 → 百分比；极小值多留一位，避免显示成 0.0% */
private fun formatPercent(ratio: Double): String {
    val percent = ratio * 100
    return if (percent < 0.1) {
        String.format(Locale.US, "%.2f%%", percent)
    } else {
        String.format(Locale.US, "%.1f%%", percent)
    }
}

/** 压缩率展示：正数表示变小，负数表示反而变大（无损重编码可能发生） */
private fun signedPercent(percent: Int): String = when {
    percent > 0 -> String.format(Locale.US, "-%d%%", percent)
    percent < 0 -> String.format(Locale.US, "+%d%%", -percent)
    else -> "0%"
}
