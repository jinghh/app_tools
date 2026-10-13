package com.jhh.myapptools.download

import android.content.Context
import dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback
import dev.ffmpegkit_maintained.ytdlp.LogCallback
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpException
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * 由 yt-dlp 自身完成下载的**兜底实现**：
 * 当解析出的最佳格式是分片流（m3u8 等，无法用单一 HTTP GET 取回）时使用。
 * 常规单文件直链（mp4）走 [HttpDownloader]，那里具备真正的取消能力。
 *
 * 已核实的库限制（来自 2.0.2 源码/字节码，非猜测）：
 * 1. [dev.ffmpegkit_maintained.ytdlp.YtDlpResponse] 的 output/errorOutput 在库内部
 *    恒以空串构造，取不到 stdout/stderr → 必须用 executeDebug + 日志回调获取结果与错误；
 * 2. 运行器把 CLI 参数转成 YoutubeDL opts 字典，只会转发自定义 logger 的日志，
 *    **不转发 stdout** → 因此 `--print`/`-J` 这类走 stdout 的取值方式不可用，
 *    下载前无法获取标题/时长/清晰度列表（MVP 按 PRD 采用默认最优格式）；
 * 3. 库未暴露取消 API，Future.cancel 无法真正中断内嵌 Python 进程 → 取消为“放弃等待”，
 *    实际后台仍可能跑完（PRD 8.4 的取消要求在此候选上不满足）；
 * 4. yt-dlp 无法应用内更新，X 接口变化只能靠重新打包发版。
 *
 * 因此本类定位为验证载体；最终实现应把“解析”与“HTTP 下载”分离（下载用 OkHttp 可获得
 * 真正的进度与取消能力），解析器通过接口替换。
 */
class YtDlpDownloader(private val context: Context) {

    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var currentFuture: Future<*>? = null

    @Volatile
    private var cancelled = false

    private fun ensureInit() {
        YtDlp.init(context) // 内部幂等（initialized 标志）
    }

    /**
     * 下载到应用私有缓存目录。
     * @param cookiePath 可选的 Netscape cookies.txt 路径（用于需要登录态的站点）
     */
    fun download(url: String, cookiePath: String?, onEvent: (DownloadEvent) -> Unit) {
        cancelled = false
        logs.clear()

        val outDir = File(context.cacheDir, "ytdlp-downloads").apply { mkdirs() }
        // 清空旧产物，便于用“最新文件”识别本次结果
        outDir.listFiles()?.forEach { runCatching { it.delete() } }

        try {
            onEvent(DownloadEvent.Stage("初始化 Python 运行时…"))
            ensureInit()
        } catch (e: YtDlpException) {
            onEvent(DownloadEvent.Failure("Python 运行时初始化失败", e.stackTraceToString()))
            return
        }

        onEvent(DownloadEvent.Stage("下载中…"))
        val request = YtDlpRequest(url)
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            // 取单文件最佳格式（X 通常为单一 mp4）；避免依赖外部 ffmpeg 做音视频合并
            .addOption("-f", "b")
            .setOutputTemplate(File(outDir, "%(id)s.%(ext)s").absolutePath)

        // 需要登录态的站点（小红书、微博等）使用用户自己导入的 Cookie
        cookiePath?.takeIf { it.isNotBlank() }?.let { path ->
            request.addOption("--cookies", path)
        }

        try {
            val future = YtDlp.executeDebug(
                request,
                LogCallback { _, message -> collect(message, onEvent) },
                DownloadProgressCallback { fraction, _, line ->
                    onEvent(DownloadEvent.Progress(fraction.coerceIn(0f, 1f), line ?: ""))
                },
            )
            currentFuture = future
            val response = future.get()

            if (cancelled) {
                onEvent(DownloadEvent.Cancelled)
                return
            }
            if (!response.isSuccess) {
                val diag = diagnostics()
                onEvent(
                    DownloadEvent.Failure(
                        friendlyMessage(diag, response.exitCode),
                        diag,
                    )
                )
                return
            }
            val file = outDir.listFiles()
                ?.filter { it.isFile && it.length() > 0L }
                ?.maxByOrNull { it.lastModified() }
            if (file == null) {
                onEvent(DownloadEvent.Failure("下载完成但未找到输出文件", diagnostics()))
                return
            }
            onEvent(DownloadEvent.Downloaded(file))
        } catch (e: ExecutionException) {
            // 失败可能以 Python 异常形式抛出（而非退出码），此处同样走中文映射，
            // 避免把 yt-dlp 的英文原始报错（常含超长 URL）直接展示给用户
            val diag = diagnostics()
            val raw = e.cause?.message ?: e.message.orEmpty()
            onEvent(DownloadEvent.Failure(friendlyMessage("$raw\n${diag.orEmpty()}", -1), diag))
        } catch (e: Exception) {
            val diag = diagnostics()
            onEvent(
                DownloadEvent.Failure(
                    friendlyMessage("${e.message.orEmpty()}\n${diag.orEmpty()}", -1),
                    diag,
                )
            )
        }
    }

    /**
     * 取消：标记放弃 + 尝试取消 Future。
     * 注意：内嵌 Python 不会被真正中断，后台任务可能继续跑完（库无取消 API）。
     */
    fun cancel() {
        cancelled = true
        currentFuture?.cancel(true)
    }

    private fun collect(line: String?, onEvent: (DownloadEvent) -> Unit) {
        val text = line?.trim().orEmpty()
        if (text.isEmpty()) return
        synchronized(logs) {
            if (logs.size > 400) logs.removeAt(0)
            logs.add(text)
        }
        // 同步到 logcat 便于开发期取证；按 PRD 7 过滤认证类敏感信息
        if (!SENSITIVE_PATTERN.containsMatchIn(text)) {
            android.util.Log.d("YtDlp", text)
        }
        onEvent(DownloadEvent.Log(text))
    }

    /**
     * 把 yt-dlp 的英文报错翻译成面向用户的中文提示（PRD 3.1：不直接暴露内部报错）。
     * 依据 yt-dlp 的已知报错文本归类；无匹配时保留退出码与原始信息，不臆测原因。
     */
    private fun friendlyMessage(diagnostics: String?, exitCode: Int): String {
        val text = (diagnostics ?: "").lowercase()
        return when {
            "no video could be found" in text ->
                "该帖子中没有找到视频。可能只有图片/文字，或视频位于被引用的帖子中。"

            "impersonation" in text || "fingerprint" in text ->
                "该站点要求 TLS 指纹伪装，当前免费版解析库不支持。"

            // 需要登录态的站点（小红书、微博等）常表现为跳转到登录页后报 Unsupported URL
            "/login" in text || "login" in text || "sign in" in text ||
                "authentication" in text || "401" in text || "403" in text ->
                "该内容需要登录才能访问（链接被跳转到了登录页）。" +
                    "可在本页导入浏览器的 cookies.txt 后重试。"

            "404" in text || "not found" in text || "unavailable" in text ||
                "deleted" in text || "suspended" in text ->
                "帖子不存在、已被删除或不可访问。"

            "rate limit" in text || "429" in text || "too many requests" in text ->
                "请求过于频繁（被限流），请稍后再试。"

            "unsupported url" in text ->
                "该链接不受支持或已失效，请确认链接可直接打开且包含视频。"

            "unable to download webpage" in text || "timed out" in text ||
                "connection" in text || "network" in text ->
                "网络异常，请检查网络后重试。"

            "unable to extract" in text || "unable to complete" in text ->
                "解析失败，站点结构可能已变化（yt-dlp 需随库版本更新）。"

            // 兜底：不外泄可能含超长 URL 的原始文本，完整内容仅留在诊断信息里
            else -> "下载失败（yt-dlp 退出码 $exitCode）。详细原因见下方「诊断信息」。"
        }
    }

    /** 失败诊断：优先 ERROR 行，其次日志尾部 */
    private fun diagnostics(): String? = synchronized(logs) {
        if (logs.isEmpty()) return null
        val errors = logs.filter { it.contains("ERROR", ignoreCase = true) }
        (if (errors.isNotEmpty()) errors else logs.takeLast(25)).joinToString("\n")
    }

    private companion object {
        /** 认证类敏感关键字：命中则不写入 logcat（PRD 7） */
        val SENSITIVE_PATTERN = Regex(
            "cookie|authorization|token=|bearer ",
            RegexOption.IGNORE_CASE,
        )
    }
}
