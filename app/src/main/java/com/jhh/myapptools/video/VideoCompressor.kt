package com.jhh.myapptools.video

import com.jhh.myapptools.gif.FfmpegRunner
import com.jhh.myapptools.media.VideoMetadata
import java.io.File

/**
 * 视频压缩（目标大小）—— 把 [com.jhh.myapptools.gif.GifCompressor] 的
 * 「参数级联 + 真实字节校验、达标即停」平移到 mp4。
 *
 * ## 编码器约束（决定实现方式，已实测确认）
 * 项目用的是 `dev.ffmpegkit-maintained:ffmpeg-kit-min`，**不含 libx264**（GPL，刻意排除）。
 * 从 APK 内的 `libavcodec.so` 实测到的可用编码器：
 * - ✅ `h264_mediacodec`（硬件 H.264，Android 自带的 MediaCodec）
 * - ✅ `hevc_mediacodec`（硬件 H.265）
 * - ✅ `mpeg4`（内置软件 MPEG-4 Part 2）
 * - ✅ `aac`（内置 AAC 音频编码）
 *
 * 因此：
 * 1. 画质维度**不能用 crf**（MediaCodec 不支持），改用**目标码率**控制体积；
 * 2. 主编码器用硬件 H.264（快、且产出微信可播的标准 MP4），**失败时自动回落 mpeg4**；
 * 3. 级联顺序：先只降码率（保持分辨率/帧率）→ 再降分辨率 → 最后降帧率。
 *
 * 每轮都用真实文件字节数校验，达标即停；走完阶梯仍超限则保留最后一轮并置 metTarget=false。
 * 输入文件只读，绝不覆盖。
 */
class VideoCompressor {

    fun cancel() {
        FfmpegRunner.cancel()
    }

    fun compress(
        input: File,
        targetBytes: Long,
        onEvent: (VideoCompressEvent) -> Unit,
    ) {
        val startedAt = System.currentTimeMillis()
        val originalBytes = input.length()
        val info = VideoMetadata.read(input)
        if (info == null || info.durationMs <= 0L) {
            onEvent(VideoCompressEvent.Failure("无法解析视频信息（时长/分辨率）"))
            return
        }
        val durationSec = info.durationMs / 1000.0

        val ladder = buildLadder(targetBytes, durationSec, info.width)
        val outDir = File(input.parentFile, "video-compress-out").apply { mkdirs() }

        var lastValidFile: File? = null
        var lastValidLabel = ""
        var lastValidBytes = 0L
        var lastValidWidth = 0
        var roundsUsed = 0

        for ((index, attempt) in ladder.withIndex()) {
            val round = index + 1
            val roundFile = File(outDir, "round_$round.mp4")

            onEvent(VideoCompressEvent.RoundStart(round, ladder.size, attempt.label))

            val outcome = runRound(input, roundFile, attempt, durationSec) { fraction ->
                onEvent(
                    VideoCompressEvent.RoundProgress(
                        round, ladder.size, attempt.label, fraction,
                    )
                )
            }

            when (outcome) {
                is RoundOutcome.Cancelled -> {
                    deleteAllRoundFiles(outDir)
                    onEvent(VideoCompressEvent.Cancelled)
                    return
                }

                is RoundOutcome.Failed -> {
                    // 单轮失败不终止：还有后续阶梯可试；最后一轮也失败才报错
                    if (round == ladder.size) {
                        deleteAllRoundFiles(outDir)
                        onEvent(
                            VideoCompressEvent.Failure(
                                "压缩失败：${outcome.reason}",
                                outcome.logs,
                            )
                        )
                        return
                    }
                    continue
                }

                is RoundOutcome.Done -> Unit
            }

            val bytes = roundFile.length()
            if (bytes <= 0L) {
                cleanup(roundFile)
                continue
            }

            // 新有效轮次诞生：删掉上一轮保留的中间产物，最多同时存在 2 个文件
            lastValidFile?.let { cleanup(it) }
            lastValidFile = roundFile
            lastValidLabel = attempt.label
            lastValidBytes = bytes
            lastValidWidth = attempt.outWidth(info.width)
            roundsUsed = round

            val met = bytes <= targetBytes
            onEvent(VideoCompressEvent.RoundDone(round, attempt.label, bytes, met))
            if (met) {
                onEvent(
                    VideoCompressEvent.Success(
                        VideoCompressResult(
                            file = roundFile,
                            originalBytes = originalBytes,
                            compressedBytes = bytes,
                            width = attempt.outWidth(info.width),
                            durationMs = info.durationMs,
                            finalLabel = attempt.label,
                            roundsUsed = round,
                            metTarget = true,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                        )
                    )
                )
                return
            }
        }

        val finalFile = lastValidFile
        if (finalFile == null) {
            deleteAllRoundFiles(outDir)
            onEvent(VideoCompressEvent.Failure("所有压缩轮次均未产生有效输出"))
            return
        }
        onEvent(
            VideoCompressEvent.Success(
                VideoCompressResult(
                    file = finalFile,
                    originalBytes = originalBytes,
                    compressedBytes = lastValidBytes,
                    width = lastValidWidth,
                    durationMs = info.durationMs,
                    finalLabel = "$lastValidLabel（未达标）",
                    roundsUsed = roundsUsed,
                    metTarget = false,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            )
        )
    }

    /** 单轮执行：先用硬件 H.264，失败则同参数回落 mpeg4 软件编码 */
    private fun runRound(
        input: File,
        output: File,
        attempt: Attempt,
        totalSec: Double,
        onProgress: (Float) -> Unit,
    ): RoundOutcome {
        var lastLogs: String? = null
        for (encoder in ENCODERS) {
            cleanup(output)
            val outcome = FfmpegRunner.run(
                args = buildArgs(input, output, attempt, encoder),
                totalSec = totalSec,
                onProgress = onProgress,
            )
            when (outcome) {
                is FfmpegRunner.Outcome.Cancelled -> return RoundOutcome.Cancelled
                is FfmpegRunner.Outcome.Success -> return RoundOutcome.Done
                is FfmpegRunner.Outcome.Error -> {
                    lastLogs = outcome.logs
                    // 硬件编码器在某些机型/分辨率上不可用：换软件编码器再试同一档
                }
            }
        }
        return RoundOutcome.Failed(
            reason = "编码器均不可用（h264_mediacodec / mpeg4）",
            logs = lastLogs,
        )
    }

    private fun buildArgs(
        input: File,
        output: File,
        attempt: Attempt,
        encoder: Encoder,
    ): Array<String> {
        val filters = buildList {
            attempt.maxWidth?.let { add("scale=min(iw\\,$it):-2") }
            attempt.fps?.let { add("fps=$it") }
        }.joinToString(",")

        return buildList {
            add("-hide_banner")
            add("-nostdin")
            add("-y")
            add("-i"); add(input.absolutePath)
            if (filters.isNotEmpty()) {
                add("-vf"); add(filters)
            }
            add("-c:v"); add(encoder.ffmpegName)
            add("-b:v"); add("${attempt.videoKbps}k")
            // 视频容器统一 mp4，音频统一 AAC（min 变体内置 aac 编码器）
            add("-c:a"); add("aac")
            add("-b:a"); add("${attempt.audioKbps}k")
            // 便于边下边播 / 微信预览
            add("-movflags"); add("+faststart")
            add(output.absolutePath)
        }.toTypedArray()
    }

    /**
     * 构建码率阶梯。体积 ≈ 码率 × 时长，所以**码率是决定体积的主旋钮**；
     * 降分辨率/帧率的作用是"在同样码率下让观感更好"，因此排在后面。
     */
    private fun buildLadder(
        targetBytes: Long,
        durationSec: Double,
        srcWidth: Int,
    ): List<Attempt> {
        // 目标总码率（kbps）；留 5% 给容器/封装开销
        val totalKbps = (targetBytes * 8.0 * 0.95 / durationSec / 1000.0).toInt()

        // 注意括号：先除再夹取值区间。写成 `totalKbps / 8.coerceIn(...)` 会被解析成
        // `totalKbps / (8.coerceIn(48,128))` = `totalKbps / 48`，音频码率会算错
        val audioFull = (totalKbps / 8).coerceIn(48, 128)
        val videoFull = (totalKbps - audioFull).coerceAtLeast(MIN_VIDEO_KBPS)

        val ladder = mutableListOf<Attempt>()
        ladder += Attempt(videoFull, audioFull, null, null, "原分辨率 · ${videoFull}kbps")
        ladder += Attempt(
            (videoFull * 0.8).toInt().coerceAtLeast(MIN_VIDEO_KBPS),
            audioFull, null, null, "原分辨率 · 码率 -20%",
        )
        // 只在源更宽时才降分辨率（不放大）
        for (w in listOf(1280, 854, 640)) {
            if (w >= srcWidth) continue
            val fps = if (w <= 854) 24 else null
            ladder += Attempt(
                (videoFull * 0.8).toInt().coerceAtLeast(MIN_VIDEO_KBPS),
                if (w <= 854) 64 else audioFull,
                w, fps, "${w}px" + (fps?.let { " · ${it}fps" } ?: ""),
            )
        }
        // 兜底档：更低的码率 + 640px + 15fps
        if (srcWidth > 640) {
            ladder += Attempt(
                (videoFull * 0.6).toInt().coerceAtLeast(MIN_VIDEO_KBPS),
                48, 640, 15, "640px · 15fps · 最低码率",
            )
        }
        return ladder
    }

    private data class Attempt(
        val videoKbps: Int,
        val audioKbps: Int,
        /** null = 保持源分辨率 */
        val maxWidth: Int?,
        /** null = 保持源帧率 */
        val fps: Int?,
        val label: String,
    ) {
        /** 估算输出宽度（用于结果展示；实际由 ffmpeg 按 -2 对齐） */
        fun outWidth(srcWidth: Int): Int = maxWidth?.takeIf { it < srcWidth } ?: srcWidth
    }

    private enum class Encoder(val ffmpegName: String) {
        /** 硬件 H.264（MediaCodec）：快，产出微信可播的标准 MP4 */
        H264_MEDIACODEC("h264_mediacodec"),

        /** 内置软件 MPEG-4 Part 2：兼容性最好的兜底 */
        MPEG4("mpeg4"),
    }

    private sealed interface RoundOutcome {
        data object Done : RoundOutcome
        data object Cancelled : RoundOutcome
        data class Failed(val reason: String, val logs: String?) : RoundOutcome
    }

    private fun deleteAllRoundFiles(dir: File) {
        dir.listFiles()?.forEach { cleanup(it) }
    }

    private fun cleanup(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    private companion object {
        /** 码率低于此值画面基本不可用，作为下限 */
        const val MIN_VIDEO_KBPS = 100

        val ENCODERS = listOf(Encoder.H264_MEDIACODEC, Encoder.MPEG4)
    }
}

data class VideoCompressResult(
    val file: File,
    val originalBytes: Long,
    val compressedBytes: Long,
    val width: Int,
    val durationMs: Long,
    val finalLabel: String,
    val roundsUsed: Int,
    /** false = 走完阶梯仍未达标，已保留最后可用结果 */
    val metTarget: Boolean,
    val elapsedMs: Long,
)

sealed interface VideoCompressEvent {
    data class RoundStart(val round: Int, val totalRounds: Int, val label: String) : VideoCompressEvent

    data class RoundProgress(
        val round: Int,
        val totalRounds: Int,
        val label: String,
        val fraction: Float,
    ) : VideoCompressEvent

    data class RoundDone(
        val round: Int,
        val label: String,
        val bytes: Long,
        val metTarget: Boolean,
    ) : VideoCompressEvent

    data class Success(val result: VideoCompressResult) : VideoCompressEvent
    data class Failure(val error: String, val diagnostics: String? = null) : VideoCompressEvent
    data object Cancelled : VideoCompressEvent
}
