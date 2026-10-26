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
 * yt-dlp 下载实现 —— **当前 App 里唯一的下载路径**。
 *
 * （历史说明：早期文档曾写"常规单文件直链走独立的 HttpDownloader、yt-dlp 只作兜底"，
 * 但该方案未落地，实际解析与下载都由本类承担。相关设想保留在 PRD 中，
 * 待自研解析器 + OkHttp 直链下载实现后再拆分。）
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
     * 下载到应用私有缓存目录（成品成功后转移到 [Companion.finalDir]）。
     * @param cookiePath 按目标站点匹配到的 Netscape cookies.txt 路径（未匹配则 null）
     * @param proxy      可选代理地址（如 http://10.0.0.2:7890）；null 表示不显式指定
     */
    fun download(
        url: String,
        cookiePath: String?,
        proxy: String?,
        resume: Boolean = false,
        onEvent: (DownloadEvent) -> Unit,
    ) {
        cancelled = false
        logs.clear()

        val outDir = outDir(context)
        val resumeMarker = File(context.filesDir, RESUME_URL_FILE)

        if (resume && partialBytes(context) > 0L) {
            // 续传：**保留 .part**。yt-dlp 默认就是断点续传（写 .part + HTTP Range），
            // 只要不删掉它，重跑同一 URL 就会从已有字节接着下。
            // （--continue 不在库的参数白名单内，无法显式传，靠的就是这个默认行为）
        } else {
            // 重新填写链接后点击下载：清掉上一次的半成品。
            // 否则旧残留可能被当成本次结果挑走，.part 也会越积越多。
            clear(context)
        }
        runCatching { resumeMarker.writeText(url) }

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
            // 文件名用视频标题（截 80 字符，缺标题回退 id；yt-dlp 会自动消毒非法字符）
            .setOutputTemplate(File(outDir, OUTPUT_TEMPLATE).absolutePath)

        // 需要登录态的站点（小红书、微博等）使用用户自己导入的 Cookie
        cookiePath?.takeIf { it.isNotBlank() }?.let { path ->
            request.addOption("--cookies", path)
        }

        // 内嵌 Python 读不到 Android 的系统代理设置，必须显式传 --proxy
        // （--proxy 在 yt-dlp 运行器的参数白名单内，已核实）
        proxy?.takeIf { it.isNotBlank() }?.let { value ->
            request.addOption("--proxy", value)
        }

        // 链路类失败（连接被拒/重置/超时/DNS/TLS）自动用同一 URL 续传重试——
        // .part 已保留，重试只下载剩余字节；限流/登录/链接本身的问题不重试。
        var attempt = 0
        while (true) {
            when (val terminal = runOnce(request, onEvent)) {
                is DownloadEvent.Downloaded -> {
                    // 成功：.part 已被 yt-dlp 重命名为成品，续传标记随之失效。
                    //
                    // 一条链接可能产出**多个视频**（yt-dlp 会用 "#1/#2" 后缀区分同名），
                    // 因此这里把**所有**成品都移出 cacheDir：
                    // 既避免被系统「清理缓存」清掉，也避免只移走一个、其余滞留在缓存里。
                    // 界面结果取第一个（与 runOnce 的选择保持一致）。
                    runCatching { resumeMarker.delete() }
                    val movedAll = moveAllToFinalDir(context)
                    val primary = movedAll.firstOrNull { it.name == terminal.file.name }
                        ?: movedAll.firstOrNull()
                        ?: terminal.file
                    when {
                        movedAll.size > 1 -> onEvent(
                            DownloadEvent.Log(
                                "本条链接共下载 ${movedAll.size} 个视频，" +
                                    "已全部保存到长期目录；界面显示第 1 个"
                            )
                        )

                        primary != terminal.file ->
                            onEvent(DownloadEvent.Log("成品已移至长期目录：${primary.name}"))
                    }
                    onEvent(DownloadEvent.Downloaded(primary))
                    return
                }

                is DownloadEvent.Failure -> {
                    attempt++
                    if (attempt <= MAX_AUTO_RETRIES && isTransientNetworkError(terminal)) {
                        onEvent(
                            DownloadEvent.Stage(
                                "网络异常，${RETRY_DELAY_MS / 1000} 秒后自动重试" +
                                    "（第 $attempt/$MAX_AUTO_RETRIES 次）…"
                            )
                        )
                        android.util.Log.w(
                            "YtDlp",
                            "transient failure, auto-retry $attempt: " +
                                (terminal.diagnostics?.lineSequence()?.lastOrNull()?.take(300) ?: ""),
                        )
                        // 分片休眠，让「取消」能及时生效（跑在 IO 线程，阻塞无妨）
                        var waited = 0L
                        while (waited < RETRY_DELAY_MS && !cancelled) {
                            Thread.sleep(100)
                            waited += 100
                        }
                        if (cancelled) {
                            onEvent(DownloadEvent.Cancelled)
                            return
                        }
                        onEvent(DownloadEvent.Stage("下载中…（自动重试）"))
                        continue
                    }
                    onEvent(terminal)
                    return
                }

                else -> {
                    onEvent(terminal)
                    return
                }
            }
        }
    }

    /**
     * 执行一次下载尝试。**不发射**终态事件，把它返回给调用方——
     * 这样调用方（[download]）可以先判断是否值得自动重试，再决定是否把失败呈现给用户。
     */
    private fun runOnce(request: YtDlpRequest, onEvent: (DownloadEvent) -> Unit): DownloadEvent {
        return try {
            val future = YtDlp.executeDebug(
                request,
                LogCallback { _, message -> collect(message, onEvent) },
                DownloadProgressCallback { fraction, _, line ->
                    onEvent(DownloadEvent.Progress(fraction.coerceIn(0f, 1f), line ?: ""))
                },
            )
            currentFuture = future
            val response = future.get()

            if (cancelled) return DownloadEvent.Cancelled
            if (!response.isSuccess) {
                val diag = diagnostics()
                return DownloadEvent.Failure(friendlyMessage(diag, response.exitCode), diag)
            }
            val file = outDir(context).listFiles()
                // .part 是没写完的半成品，绝不能当成本次结果挑走
                ?.filter { it.isFile && it.length() > 0L && !it.name.endsWith(PART_SUFFIX) }
                // 多个视频时取**第一个**（下载顺序 = mtime 升序），而不是最新那个。
                // 例如一条帖子含 2 个视频，yt-dlp 依次产出 "标题 #1.mp4"、"标题 #2.mp4"，
                // 用户默认期望拿到第一个；此前取 newest 会拿到 #2。
                ?.minByOrNull { it.lastModified() }
            if (file == null) {
                DownloadEvent.Failure("下载完成但未找到输出文件", diagnostics())
            } else {
                DownloadEvent.Downloaded(file)
            }
        } catch (e: ExecutionException) {
            // 失败可能以 Python 异常形式抛出（而非退出码），同样走中文映射；
            // 原始报错并入诊断信息（PRD 7：只进「诊断信息」面板，不进用户提示）
            failureWithRaw(e.cause?.message ?: e.message.orEmpty())
        } catch (e: Exception) {
            failureWithRaw(e.message.orEmpty())
        }
    }

    /** 异常路径的失败事件：友好提示 + 原始文本并入诊断信息（供自动重试判断与排查） */
    private fun failureWithRaw(raw: String): DownloadEvent.Failure {
        val diag = diagnostics()
        val merged = listOf(raw.trim(), diag.orEmpty().trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifEmpty { null }
        return DownloadEvent.Failure(friendlyMessage("$raw\n${diag.orEmpty()}", -1), merged)
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

            // ---- 网络层错误：属于"到站点的链路没建立起来"，与链接内容无关 ----
            // Errno 111 = ECONNREFUSED：连接被直接拒绝（代理未启动/端口不对，或链路被阻断）
            "errno 111" in text || "connection refused" in text ->
                "连接被拒绝，无法到达该站点。若下载 X 等站点，请确认代理已开启；" +
                    "若代理不是全局模式，请在上方「网络代理」里填写代理地址。"

            // TLS 握手中途被断开：链路被干扰或代理不稳定
            "unexpected_eof" in text || "sslerror" in text || "[ssl" in text ||
                "ssl: " in text || "certificate verify failed" in text ->
                "网络连接被异常中断（TLS 握手失败）。常见原因是网络链路受干扰或代理不稳定，" +
                    "请确认代理可用后重试。"

            // 域名解析失败
            "name or service not known" in text || "nodename nor servname" in text ||
                "getaddrinfo" in text || "temporary failure in name resolution" in text ->
                "域名解析失败，请检查网络或代理设置。"

            // Errno 104：连接被重置
            "errno 104" in text || "connection reset" in text ||
                "connection aborted" in text || "remote end closed" in text ->
                "连接被重置（网络链路中断），请确认代理可用后重试。"

            "unable to download webpage" in text || "timed out" in text ||
                "connection" in text || "network" in text ->
                "网络异常，请检查网络后重试。"

            "unable to extract" in text || "unable to complete" in text ->
                "解析失败，站点结构可能已变化（yt-dlp 需随库版本更新）。"

            // 兜底：不外泄可能含超长 URL 的原始文本，完整内容仅留在诊断信息里
            else -> "下载失败（yt-dlp 退出码 $exitCode）。详细原因见下方「诊断信息」。"
        }
    }

    /** 链路类（瞬态）网络错误：值得用 .part 续传自动重试；限流/登录/链接问题不在此列 */
    private fun isTransientNetworkError(failure: DownloadEvent.Failure): Boolean {
        val text = "${failure.message}\n${failure.diagnostics.orEmpty()}"
        return TRANSIENT_NETWORK_PATTERN.containsMatchIn(text)
    }

    /** 失败诊断：优先 ERROR 行，其次日志尾部 */
    private fun diagnostics(): String? = synchronized(logs) {
        if (logs.isEmpty()) return null
        val errors = logs.filter { it.contains("ERROR", ignoreCase = true) }
        (if (errors.isNotEmpty()) errors else logs.takeLast(25)).joinToString("\n")
    }

    companion object {
        const val OUT_DIR_NAME = "ytdlp-downloads"

        /** 成品长期目录名（位于外部应用专属目录下，见 [finalDir]） */
        private const val FINAL_DIR_NAME = "downloads"

        /**
         * 输出文件名模板：标题优先、缺标题回退 id（yt-dlp 的 `,` 备选语法，已对照官方文档），
         * `.80s` 在括号外对最终结果截断 80 字符。非法字符由 yt-dlp 自动消毒。
         */
        private const val OUTPUT_TEMPLATE = "%(title,id).80s.%(ext)s"

        /** 链路类瞬态错误（与 friendlyMessage 的网络层分组一致）：自动重试只针对这一组 */
        private val TRANSIENT_NETWORK_PATTERN = Regex(
            "errno 111|connection refused|unexpected_eof|sslerror|\\[ssl|ssl: |" +
                "certificate verify failed|name or service not known|nodename nor servname|" +
                "getaddrinfo|temporary failure in name resolution|errno 104|connection reset|" +
                "connection aborted|remote end closed|timed out|unable to download webpage",
            RegexOption.IGNORE_CASE,
        )

        /** 自动重试上限与间隔：仅链路类失败，用 .part 续传接着下 */
        private const val MAX_AUTO_RETRIES = 2
        private const val RETRY_DELAY_MS = 3_000L

        /** 认证类敏感关键字：命中则不写入 logcat（PRD 7） */
        val SENSITIVE_PATTERN = Regex(
            "cookie|authorization|token=|bearer ",
            RegexOption.IGNORE_CASE,
        )

        /** yt-dlp 未写完的半成品后缀；续传的依据就是它 */
        private const val PART_SUFFIX = ".part"

        /**
         * 记录"上次下载尝试的 URL"，供「继续下载」使用。
         * 放在 filesDir 而不是输出目录：避免与"用最新文件识别结果"的逻辑互相干扰。
         */
        private const val RESUME_URL_FILE = "ytdlp-resume-url.txt"

        fun outDir(context: Context): File =
            File(context.cacheDir, OUT_DIR_NAME).apply { mkdirs() }

        /**
         * 成品目录：外部应用专属存储（`Android/data/<pkg>/files/downloads`），
         * **不受系统「清理缓存」影响**——cacheDir 在磁盘紧张时会被整体清空，
         * 用户还没「保存到本地」的视频会无声丢失。外部存储不可用时退回内部 `filesDir`。
         * `.part` 半成品仍留在 [outDir]（丢了可重下，不必占长期空间）。
         */
        fun finalDir(context: Context): File {
            val ext = context.getExternalFilesDir(null)?.let { File(it, FINAL_DIR_NAME) }
            return (ext ?: File(context.filesDir, FINAL_DIR_NAME)).apply { mkdirs() }
        }

        /**
         * 把成品从 [outDir] 转移到 [finalDir]。两处可能不在同一卷（rename 失败），退回复制；
         * 抛异常时调用方应退回使用原文件，不影响本次结果。
         */
        fun moveToFinalDir(file: File, context: Context): File {
            val target = File(finalDir(context), file.name)
            if (file.canonicalFile == target.canonicalFile) return file
            runCatching { target.delete() }
            if (file.renameTo(target) && target.length() > 0L) return target
            file.copyTo(target, overwrite = true)
            check(target.length() > 0L) { "复制到成品目录失败：${target.absolutePath}" }
            runCatching { file.delete() }
            return target
        }

        /**
         * 把 outDir 里**所有**成品移到 [finalDir]，按下载顺序（mtime 升序）返回。
         *
         * 为什么不是只移一个：一条链接可能产出多个视频（yt-dlp 用 "#1/#2" 后缀区分），
         * 只移最新那个会让其余文件滞留在 cacheDir，等着被系统「清理缓存」无声清掉。
         * 单个文件失败的项会被跳过（不影响其余），返回值可能少于实际文件数。
         */
        fun moveAllToFinalDir(context: Context): List<File> =
            outDir(context).listFiles()
                ?.filter { it.isFile && it.length() > 0L && !it.name.endsWith(PART_SUFFIX) }
                ?.sortedBy { it.lastModified() }
                ?.mapNotNull { f -> runCatching { moveToFinalDir(f, context) }.getOrNull() }
                ?: emptyList()

        /**
         * 可续传的半成品大小（.part 合计字节）。
         * > 0 表示有中断残留，可以「继续下载」；下载成功后 yt-dlp 会把 .part
         * 重命名为成品，此时自然归零。
         */
        fun partialBytes(context: Context): Long =
            outDir(context).listFiles()
                ?.filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
                ?.sumOf { it.length() }
                ?: 0L

        /** 上次下载尝试的 URL；没有记录时返回 null */
        fun resumeUrl(context: Context): String? =
            File(context.filesDir, RESUME_URL_FILE)
                .takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        /** 清空下载产物（含半成品）；成品长期目录 [finalDir] 不在此列，由存储清理管理 */
        fun clear(context: Context) {
            outDir(context).listFiles()?.forEach { runCatching { it.delete() } }
        }
    }
}
