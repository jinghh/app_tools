package com.jhh.myapptools.title.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jhh.myapptools.ai.AiChatClient
import com.jhh.myapptools.ai.AiConfigStore
import com.jhh.myapptools.ai.ui.AiSettingsActivity
import com.jhh.myapptools.title.ExtractedFrames
import com.jhh.myapptools.title.FrameExtractor
import com.jhh.myapptools.title.GenerateOutcome
import com.jhh.myapptools.title.TitleBatch
import com.jhh.myapptools.title.TitleGenerator
import com.jhh.myapptools.title.TitleParams
import com.jhh.myapptools.ui.ScreenScaffold
import com.jhh.myapptools.ui.copyUriToCache
import com.jhh.myapptools.ui.queryDisplayName
import com.jhh.myapptools.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 爆款标题页：
 * - 由下载/GIF 结果卡片带素材进入；
 * - 也可直接打开（无 extra），在页内用 SAF 选择视频或 GIF。
 * 作为独立 Activity 使用时显示「返回」；嵌在「更多」导航的页签里时不显示。
 */
class TitleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_SOURCE_PATH)
        val source = path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
        if (path != null && source == null) {
            Toast.makeText(this, "素材不存在或已被清理，请重新选择", Toast.LENGTH_SHORT).show()
        }
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    TitleScreen(
                        initialSource = source,
                        onBack = { finish() },
                        onOpenSettings = {
                            startActivity(Intent(this@TitleActivity, AiSettingsActivity::class.java))
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE_PATH = "extra_source_path"

        fun start(context: Context, source: File) {
            context.startActivity(
                Intent(context, TitleActivity::class.java).putExtra(EXTRA_SOURCE_PATH, source.path),
            )
        }

        /** 直接打开标题页，进入后由用户自己选择资源 */
        fun start(context: Context) {
            context.startActivity(Intent(context, TitleActivity::class.java))
        }
    }
}

private class TitleDependencies(context: Context) {
    val extractor = FrameExtractor(context)
    val client = AiChatClient()
    val generator = TitleGenerator(client)
    val configStore = AiConfigStore(context)
}

@Composable
internal fun TitleScreen(
    modifier: Modifier = Modifier,
    initialSource: File? = null,
    /** 独立 Activity 使用时传返回回调；嵌在页签里时留空 */
    onBack: (() -> Unit)? = null,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deps = remember { TitleDependencies(context) }

    // 路径存进 savedState：切换页签/旋转后仍能恢复用户选过的素材
    var sourcePath by rememberSaveable { mutableStateOf(initialSource?.path) }
    var source by remember { mutableStateOf(sourcePath?.let(::File)?.takeIf { it.isFile }) }
    var frames by remember { mutableStateOf<List<File>>(emptyList()) }
    var frameCount by remember { mutableIntStateOf(8) }
    var description by rememberSaveable {
        mutableStateOf(initialSource?.let { it.name.substringBeforeLast('.', it.name) } ?: "")
    }
    var minChars by rememberSaveable { mutableIntStateOf(30) }
    var maxChars by rememberSaveable { mutableIntStateOf(100) }
    var batch by remember { mutableStateOf<TitleBatch?>(null) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(false) }
    var extractJob by remember { mutableStateOf<Job?>(null) }
    var genJob by remember { mutableStateOf<Job?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val displayName = queryDisplayName(context, uri)
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val isGif = mime?.contains("gif", ignoreCase = true) == true ||
                displayName?.endsWith(".gif", ignoreCase = true) == true
            val copied = withContext(Dispatchers.IO) {
                copyUriToCache(context, uri, "title-input", if (isGif) "gif" else "mp4")
            }
            if (copied == null) {
                errorText = "无法读取所选资源，请换一个文件"
                return@launch
            }
            sourcePath = copied.path
            source = copied
            description = copied.name.substringBeforeLast('.', copied.name)
            batch = null
            frames = emptyList()
            errorText = null
            status = "已选择：${copied.name}"
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasKey = deps.configStore.hasKey()
    }

    DisposableEffect(Unit) {
        onDispose {
            extractJob?.cancel()
            genJob?.cancel()
            deps.extractor.cancel()
            deps.client.cancel()
        }
    }

    fun runExtract() {
        val current = source ?: return
        extractJob?.cancel()
        deps.extractor.cancel()
        errorText = null
        status = "正在抽取关键帧…"
        progress = 0f
        extracting = true
        frames = emptyList()
        val requested = frameCount
        extractJob = scope.launch {
            // IO 回调 -> Channel -> 主线程写 Compose state，避免跨线程写状态
            val events = Channel<Float>(Channel.CONFLATED)
            val work = async(Dispatchers.IO) {
                try {
                    runCatching { deps.extractor.extract(current, requested) { events.trySend(it) } }
                } finally {
                    events.close()
                }
            }
            for (p in events) progress = p
            val outcome = work.await()
            extracting = false
            progress = 1f
            outcome.fold(
                onSuccess = { extracted: ExtractedFrames ->
                    frames = extracted.files
                    status = "已生成 ${extracted.files.size} 张关键帧，可预览"
                },
                onFailure = { e ->
                    if (e !is CancellationException) {
                        errorText = e.message ?: "抽帧失败"
                        status = ""
                    }
                },
            )
        }
    }

    fun runGenerate(nextRound: Int) {
        val current = source ?: return
        val snapshot = frames.toList()
        if (snapshot.isEmpty()) {
            errorText = "请先抽取关键帧"
            return
        }
        if (!deps.configStore.hasKey()) {
            errorText = "请先在「模型设置」中配置 DeepSeek API Key"
            return
        }
        if (minChars < 1 || maxChars > TITLE_MAX || minChars > maxChars) {
            errorText = "字数范围必须为 1~$TITLE_MAX，且最小值不大于最大值"
            return
        }
        genJob?.cancel()
        deps.client.cancel()
        errorText = null
        status = "正在生成标题…"
        generating = true
        val config = deps.configStore.load()
        val params = TitleParams(
            minChars = minChars,
            maxChars = maxChars,
            description = description.trim(),
        )
        val sourceName = current.name
        genJob = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                deps.generator.generate(config, snapshot, sourceName, params, nextRound)
            }
            generating = false
            when (outcome) {
                is GenerateOutcome.Success -> {
                    batch = outcome.batch
                    status = "第 ${outcome.batch.round + 1} 批 · ${outcome.batch.items.size} 条标题"
                }
                is GenerateOutcome.Failure -> {
                    errorText = outcome.message
                    status = ""
                }
                GenerateOutcome.Cancelled -> status = ""
            }
        }
    }

    fun cancelRunning() {
        extractJob?.cancel()
        genJob?.cancel()
        deps.extractor.cancel()
        deps.client.cancel()
        extracting = false
        generating = false
        status = "已取消"
    }

    fun copyTitle(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("爆款标题", text))
        Toast.makeText(context, "已复制标题", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(source) {
        if (source != null) runExtract()
    }

    ScreenScaffold(
        modifier = modifier,
        title = "爆款标题",
        onBack = onBack,
        titleExtra = {
            TextButton(onClick = onOpenSettings) { Text("模型设置") }
        },
    ) {
        if (source == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("选择视频或 GIF", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "App 会从素材中抽取关键帧供 AI 分析。原始文件不会上传，只会发送压缩后的关键帧。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { pickLauncher.launch(arrayOf("video/*", "image/gif")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("从本机选择资源") }
                }
            }
            errorText?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("素材：${source?.name ?: ""}", style = MaterialTheme.typography.bodyMedium)
                    Text("关键帧数量", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(4, 6, 8, 12).forEach { n ->
                            FilterChip(
                                selected = frameCount == n,
                                onClick = { frameCount = n },
                                label = { Text("${n} 张") },
                            )
                        }
                    }
                    if (extracting) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (frames.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            frames.forEach { FrameThumbnail(it) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pickLauncher.launch(arrayOf("video/*", "image/gif")) },
                            enabled = !extracting && !generating,
                            modifier = Modifier.weight(1f),
                        ) { Text("更换资源", maxLines = 1) }
                        FilledTonalButton(
                            onClick = { runExtract() },
                            enabled = !extracting && !generating,
                            modifier = Modifier.weight(1f),
                        ) { Text("重新抽帧", maxLines = 1) }
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("资源描述补充（可选）") },
                placeholder = { Text("画面不足以说明内容时，在这里补充主题、人物、亮点") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("标题字数（去标点后）", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = minChars.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { minChars = it } },
                            label = { Text("最少") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = maxChars.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { maxChars = it } },
                            label = { Text("最多（默认 100）") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { runGenerate(0) },
                            enabled = !generating && !extracting && frames.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text("生成爆款标题", maxLines = 1) }
                        OutlinedButton(
                            onClick = { batch?.let { runGenerate(it.round + 1) } },
                            enabled = !generating && !extracting && batch != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("换一批", maxLines = 1) }
                    }
                    Text(
                        "点击生成会把 ${frames.size} 张关键帧、资源描述与文件名发送到你配置的服务商；" +
                            "原始视频/GIF 不上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (generating || extracting) {
                        OutlinedButton(onClick = { cancelRunning() }, modifier = Modifier.fillMaxWidth()) {
                            Text("取消")
                        }
                    }
                    if (!hasKey) {
                        TextButton(onClick = onOpenSettings) { Text("尚未配置 API Key，点此配置") }
                    }
                }
            }

            errorText?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }

            batch?.let { current ->
                Text("生成结果 · 点击复制", style = MaterialTheme.typography.titleMedium)
                current.items.forEach { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${item.charCount} 字" + if (item.valid) "" else " · 字数超出设定范围",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (item.valid) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            TextButton(onClick = { copyTitle(item.text) }) { Text("复制") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameThumbnail(file: File) {
    // inSampleSize=2：640px 缩到 320px 用于 80dp 预览，避免 8 张大图常驻内存
    val bitmap = remember(file.path) {
        runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                ?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "关键帧",
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
    }
}

private const val TITLE_MAX = 200
