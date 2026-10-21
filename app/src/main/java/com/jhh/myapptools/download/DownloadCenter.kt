package com.jhh.myapptools.download

import android.content.Context
import com.jhh.myapptools.media.VideoMetadata
import com.jhh.myapptools.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 下载阶段 */
enum class DownloadPhase { IDLE, RUNNING, SUCCESS, FAILED, CANCELLED }

/** 下载界面状态（进程级单例持有，界面只做观察） */
data class DownloadUiState(
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val stage: String = "",
    val fraction: Float = 0f,
    val file: File? = null,
    val meta: VideoMetadata.Info? = null,
    val error: String? = null,
    val diagnostics: String? = null,
    val logs: List<String> = emptyList(),
    /** 中断残留的半成品大小（>0 时可「继续下载」） */
    val resumableBytes: Long = 0L,
) {
    val running: Boolean get() = phase == DownloadPhase.RUNNING
}

/**
 * 下载中心：把下载从 Compose 界面作用域提升到**进程作用域**，并配合 [DownloadService]
 * 前台服务，使下载在 App 退到后台时不被系统中断。
 *
 * 为什么必须这样做（而不是在界面里 launch 一个协程）：
 * 1. Android 11+ 的 **Cached Apps Freezer** 会在 App 进入后台（cached 状态）时对整个进程
 *    SIGSTOP —— 所有线程停摆，不再读 socket，连接随即超时判失败；
 * 2. 后台进程还可能被系统直接回收（本 App 内嵌 CPython 运行时，内存占用大，是优先目标）；
 * 3. 界面作用域的协程绑定在 Compose 上，Activity 被销毁就一起取消，结果也会丢。
 *
 * 前台服务能让进程保持"不被冻结、不被回收"；状态放在进程级单例里，
 * 则界面重建/切后台再回来仍能看到进度与结果。
 *
 * 未处理（当前实现的固有限制，非本类缺陷）：进程若被系统杀死，状态不持久化、也无法续传
 * —— 因为 yt-dlp 这条路径本身没有断点续传能力。
 */
object DownloadCenter {

    /** 进程级作用域：随进程存活，不绑定任何 Activity / Compose 生命周期 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    @Volatile
    private var downloader: YtDlpDownloader? = null

    /** 日志上限（与界面显示一致） */
    private const val MAX_LOGS = 60

    /**
     * 启动下载。必须在 App 处于前台时调用（Android 12+ 禁止从后台启动前台服务）。
     * @param cookiePath 按目标站点匹配到的 cookies.txt 路径（未匹配/已关闭则为 null）
     * @param proxy      可选代理地址；null 表示不显式指定
     * @param resume     true = 续传（保留上次的 .part 接着下）；false = 重新下载（先清掉残留）
     */
    fun start(
        context: Context,
        url: String,
        cookiePath: String?,
        proxy: String? = null,
        resume: Boolean = false,
    ) {
        if (_state.value.phase == DownloadPhase.RUNNING) return
        if (url.isBlank()) return

        // 必须用 applicationContext：单例持有引用不能泄漏 Activity
        val appContext = context.applicationContext
        val engine = YtDlpDownloader(appContext)
        downloader = engine

        _state.value = DownloadUiState(
            phase = DownloadPhase.RUNNING,
            stage = "下载中…（首次需初始化 Python 运行时，可能较慢）",
            // 明确记录本次实际用了什么凭证/代理，便于出问题时从日志判断
            logs = buildList {
                add(if (resume) "继续下载（续传）：$url" else "开始下载：$url")
                add(
                    cookiePath?.let { "Cookie：使用 ${File(it).name.removeSuffix(".txt")}" }
                        ?: "Cookie：未使用"
                )
                proxy?.takeIf { it.isNotBlank() }?.let { add("代理：$it") }
            },
        )

        // 关键一步：先把进程提升为前台服务，避免退到后台被冻结/回收
        val serviceOk = runCatching { DownloadService.start(appContext) }.isSuccess
        if (!serviceOk) {
            // 不阻断下载，但必须如实告知用户降级后的风险，不静默
            appendLog("警告：前台服务启动失败（系统限制），退到后台可能被中断")
        }

        scope.launch {
            try {
                engine.download(url, cookiePath, proxy, resume) { handleEvent(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 原生/Python 运行时异常不得冒泡到未捕获处理器
                handleEvent(
                    DownloadEvent.Failure(
                        "下载异常：${e.message ?: e.javaClass.simpleName}",
                        e.stackTraceToString(),
                    )
                )
            }

            // 本次尝试结束：同步"还剩多少没下完"。
            // 失败/取消时 .part 会留下，供「继续下载」使用；成功时 yt-dlp 已把 .part
            // 重命名为成品，这里自然归零。
            val partial = YtDlpDownloader.partialBytes(appContext)
            _state.update { it.copy(resumableBytes = partial) }

            // yt-dlp 无法在下载前提供元信息（参数白名单限制），只能下载完成后本地读取
            val file = _state.value.file
            if (_state.value.phase == DownloadPhase.SUCCESS && file != null) {
                val meta = runCatching { VideoMetadata.read(file) }.getOrNull()
                _state.update { it.copy(meta = meta) }
            }
        }
        }

        /** 取消：标记放弃 + 尝试取消 Future（内嵌 Python 不会被真正中断，库无取消 API） */
        fun cancel() {
        downloader?.cancel()
        }

        /**
        * 重新读取断点残留大小。界面在 ON_RESUME 时调用，
        * 覆盖"进程被杀后重启"也能看到可续传残留的场景。
        */
        fun refreshResumable(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val bytes = YtDlpDownloader.partialBytes(appContext)
            _state.update { it.copy(resumableBytes = bytes) }
        }
        }

    /**
     * 界面侧补充日志（Cookie 导入、保存结果等）。
     * 与下载日志共用同一列表，保证显示顺序与「一键复制」内容一致。
     */
    fun appendLog(line: String) {
        if (line.isBlank()) return
        _state.update { it.copy(logs = (it.logs + line).takeLast(MAX_LOGS)) }
    }

    private fun handleEvent(event: DownloadEvent) {
        _state.update { s ->
            when (event) {
                is DownloadEvent.Stage -> s.copy(stage = event.text)

                is DownloadEvent.Log ->
                    s.copy(logs = (s.logs + event.line).takeLast(MAX_LOGS))

                is DownloadEvent.Progress -> s.copy(
                    fraction = event.fraction.coerceIn(0f, 1f),
                    stage = if (event.line.isBlank()) s.stage else "下载中… ${event.line}",
                )

                is DownloadEvent.Downloaded -> s.copy(
                    phase = DownloadPhase.SUCCESS,
                    fraction = 1f,
                    stage = "下载完成",
                    file = event.file,
                    logs = (
                        s.logs + "已下载：${event.file.name}" +
                            "（${formatBytes(event.file.length())}）"
                        ).takeLast(MAX_LOGS),
                )

                is DownloadEvent.Failure -> s.copy(
                    phase = DownloadPhase.FAILED,
                    stage = "失败",
                    error = event.message,
                    diagnostics = event.diagnostics,
                    logs = (s.logs + "失败：${event.message}").takeLast(MAX_LOGS),
                )

                DownloadEvent.Cancelled -> s.copy(
                    phase = DownloadPhase.CANCELLED,
                    stage = "已取消（注意：后台任务可能仍在运行）",
                    logs = (s.logs + "已取消").takeLast(MAX_LOGS),
                )
            }
        }
    }
}
