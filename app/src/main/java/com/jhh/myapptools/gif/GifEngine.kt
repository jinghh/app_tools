package com.jhh.myapptools.gif

import java.io.File

/**
 * GIF 编码参数。
 * 注：纯 Java 引擎（square/gifencoder）已实测弃用——同参数下比 FFmpeg 慢 29 倍
 * （331s vs 11.5s，5s 视频 / 8fps / 320px），且无法减少调色板颜色数。
 */
data class GifEncodeParams(
    val startMs: Long,
    val durationMs: Long,
    val fps: Int = 12,
    val width: Int = 480,
    val maxColors: Int = 256,
    val dither: Boolean = true,
)

data class GifEncodeResult(
    val file: File,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val bytes: Long,
    val elapsedMs: Long,
)

sealed interface GifEncodeEvent {
    /** @param fraction 0f..1f 阶段内进度 */
    data class Progress(val fraction: Float, val stage: String) : GifEncodeEvent
    data class Success(val result: GifEncodeResult) : GifEncodeEvent
    /** @param diagnostics 面向开发的诊断信息（ffmpeg 日志/堆栈），不直接展示给用户 */
    data class Failure(val error: String, val diagnostics: String? = null) : GifEncodeEvent
    data object Cancelled : GifEncodeEvent
}

/**
 * GIF 编码引擎接口。实现方约定：
 * - [encode] 为阻塞调用，调用方负责放到 IO 线程；
 * - 事件回调可能在任意线程触发，UI 层需自行切换主线程；
 * - [cancel] 为协作式取消，实现方应在安全点停止并清理半成品文件；
 * - 实现可能被跨任务复用，[encode] 入口必须复位内部状态（如取消标志）。
 */
interface GifEngine {

    fun encode(
        input: File,
        output: File,
        params: GifEncodeParams,
        onEvent: (GifEncodeEvent) -> Unit,
    )

    fun cancel()
}
