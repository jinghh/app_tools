package com.jhh.myapptools.gif

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import java.util.Locale
import java.util.concurrent.CountDownLatch

/**
 * FFmpegKit 同步执行封装：阻塞至会话结束，统一取消/进度/日志语义。
 * 视频转 GIF 引擎与 GIF 压缩模块共用，避免执行样板重复。
 *
 * 注意：FFmpegKit.cancel() 是进程级静态调用，本 App 同时只运行一个会话。
 */
internal object FfmpegRunner {

    sealed interface Outcome {
        data class Success(val elapsedMs: Long) : Outcome
        data class Cancelled(val elapsedMs: Long) : Outcome
        data class Error(val exitCode: Int, val logs: String, val elapsedMs: Long) : Outcome
    }

    /**
     * @param totalSec 进度分母（秒）。ffmpeg 日志的 time= 字段格式无歧义，优先于
     *                 Statistics.getTime()（其单位在文档中未明确）。
     */
    fun run(
        args: Array<String>,
        totalSec: Double,
        onProgress: (Float) -> Unit,
        onStage: (String) -> Unit = {},
    ): Outcome {
        val startedAt = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        val outcomeRef = java.util.concurrent.atomic.AtomicReference<Outcome?>(null)

        val logCallback = LogCallback { log ->
            parseProgress(log.message, totalSec)?.let { fraction ->
                onProgress(fraction.coerceIn(0f, 0.99f))
            }
        }

        // 统计回调仅展示帧率，不改写进度值
        val statisticsCallback = StatisticsCallback { stats ->
            onStage(String.format(Locale.US, "%.1f fps", stats.videoFps))
        }

        FFmpegKit.executeWithArgumentsAsync(
            args,
            { session ->
                val rc = session.returnCode
                val elapsed = System.currentTimeMillis() - startedAt
                outcomeRef.set(
                    when {
                        rc == null -> Outcome.Error(
                            -1,
                            session.failStackTrace ?: "未知错误",
                            elapsed,
                        )

                        ReturnCode.isCancel(rc) -> Outcome.Cancelled(elapsed)

                        ReturnCode.isSuccess(rc) -> Outcome.Success(elapsed)

                        else -> Outcome.Error(
                            rc.value,
                            buildString {
                                append(session.allLogsAsString)
                                session.failStackTrace?.let { append('\n').append(it) }
                            },
                            elapsed,
                        )
                    }
                )
                latch.countDown()
            },
            logCallback,
            statisticsCallback,
        )

        // 阻塞等待完成，使调用方可用统一的协程 IO 调度语义
        latch.await()

        // 回调一定会在取消/完成时触发；此分支仅为防御（不应到达）
        return outcomeRef.get() ?: Outcome.Cancelled(System.currentTimeMillis() - startedAt)
    }

    fun cancel() {
        FFmpegKit.cancel()
    }

    private val PROGRESS_REGEX = Regex("""time=(\d+):(\d{2}):(\d{2}\.\d+)""")

    /** 解析 ffmpeg 进度行中的 time= 字段，返回 0..1 的进度；解析失败返回 null */
    private fun parseProgress(message: String, totalSec: Double): Float? {
        if (totalSec <= 0) return null
        val match = PROGRESS_REGEX.find(message) ?: return null
        val (h, m, s) = match.destructured
        val sec = h.toLong() * 3600 + m.toLong() * 60 + s.toDouble()
        if (sec <= 0) return null
        return (sec / totalSec).toFloat()
    }
}
