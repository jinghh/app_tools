package com.jhh.myapptools.gif

import java.io.File

/**
 * GIF 压缩模块（文档 3.3 策略实现）。
 *
 * 级联顺序：原参数重编码 -> 减色(256/128/64/32) -> 降帧率(8/6fps) -> 降宽度(480/360/240)。
 * 每轮都用 GifInspector/真实字节数校验，达标即停；达到最低参数仍超限时保留最后可用
 * 结果并置 metTarget=false，由 UI 明确提示。输入文件只读，绝不覆盖。
 */
class GifCompressor {

    fun cancel() {
        FfmpegRunner.cancel()
    }

    fun compress(
        input: File,
        targetBytes: Long,
        onEvent: (GifCompressEvent) -> Unit,
    ) {
        val startedAt = System.currentTimeMillis()
        val originalBytes = input.length()
        val srcInfo = GifInspector.inspect(input)
        if (srcInfo == null || originalBytes <= 0L) {
            onEvent(GifCompressEvent.Failure("无法解析输入 GIF 文件"))
            return
        }

        val ladder = buildLadder(srcInfo.width)
        val outDir = File(input.parentFile, "compress-out").apply { mkdirs() }
        // GIF 无全局时长字段；按 12fps 估算，仅用于进度显示
        val estimatedSec = maxOf(1.0, srcInfo.frameCount / 12.0)

        var lastValidFile: File? = null
        var lastValidLabel = ""
        var lastValidBytes = 0L
        var roundsUsed = 0

        for ((index, attempt) in ladder.withIndex()) {
            val round = index + 1
            // 每轮独立产物文件，便于清理中间结果且不误用上一轮内容
            val roundFile = File(outDir, "round_$round.gif")

            onEvent(GifCompressEvent.RoundStart(round, ladder.size, attempt.label))

            val outcome = FfmpegRunner.run(
                args = buildArgs(input, roundFile, attempt),
                totalSec = estimatedSec,
                onProgress = { fraction ->
                    onEvent(
                        GifCompressEvent.RoundProgress(round, ladder.size, attempt.label, fraction)
                    )
                },
            )

            if (outcome is FfmpegRunner.Outcome.Cancelled) {
                deleteAllRoundFiles(outDir)
                onEvent(GifCompressEvent.Cancelled)
                return
            }

            if (outcome is FfmpegRunner.Outcome.Error) {
                // 单轮失败不终止整体流程；最后一轮仍失败才报错
                if (round == ladder.size) {
                    deleteAllRoundFiles(outDir)
                    onEvent(
                        GifCompressEvent.Failure(
                            "压缩失败（退出码 ${outcome.exitCode}）",
                            outcome.logs,
                        )
                    )
                    return
                }
                continue
            }

            val success = outcome as FfmpegRunner.Outcome.Success
            val bytes = roundFile.length()
            val roundInfo = GifInspector.inspect(roundFile)
            if (bytes <= 0L || roundInfo == null) {
                // 本轮产物无效：删除后继续，避免把损坏文件当结果
                cleanup(roundFile)
                continue
            }

            // 新有效轮次诞生：删除上一轮保留的中间文件，最多同时存在 2 个产物
            lastValidFile?.let { cleanup(it) }
            lastValidFile = roundFile
            lastValidLabel = attempt.label
            lastValidBytes = bytes
            roundsUsed = round

            val met = bytes <= targetBytes
            onEvent(GifCompressEvent.RoundDone(round, attempt.label, bytes, met))

            if (met) {
                val info = roundInfo
                onEvent(
                    GifCompressEvent.Success(
                        GifCompressResult(
                            file = roundFile,
                            originalBytes = originalBytes,
                            compressedBytes = bytes,
                            width = info.width,
                            height = info.height,
                            frameCount = info.frameCount,
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

        // 级联走完仍未达标：保留最后可用结果，明确未达标（文档 8.3）
        val finalFile = lastValidFile
        if (finalFile == null) {
            deleteAllRoundFiles(outDir)
            onEvent(GifCompressEvent.Failure("所有压缩轮次均未产生有效输出"))
            return
        }
        val finalInfo = GifInspector.inspect(finalFile)
        if (finalInfo == null) {
            deleteAllRoundFiles(outDir)
            onEvent(GifCompressEvent.Failure("最终产物无法解析"))
            return
        }
        onEvent(
            GifCompressEvent.Success(
                GifCompressResult(
                    file = finalFile,
                    originalBytes = originalBytes,
                    compressedBytes = lastValidBytes,
                    width = finalInfo.width,
                    height = finalInfo.height,
                    frameCount = finalInfo.frameCount,
                    finalLabel = "$lastValidLabel（未达标）",
                    roundsUsed = roundsUsed,
                    metTarget = false,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            )
        )
    }

    /** 一级降参阶梯：颜色数优先，其次帧率，最后分辨率；总轮次有界，防止跑飞 */
    private fun buildLadder(srcWidth: Int): List<Attempt> {
        val ladder = mutableListOf<Attempt>()
        ladder += Attempt(256, null, null, "原参数重编码（256 色）")
        ladder += Attempt(128, null, null, "128 色")
        ladder += Attempt(64, null, null, "64 色")
        ladder += Attempt(64, 8, null, "64 色 / 8fps")
        // 宽度档位只取小于源宽度的（不放大）
        for (w in listOf(480, 360, 240)) {
            if (w < srcWidth) {
                ladder += Attempt(64, 8, w, "64 色 / 8fps / ${w}px")
            }
        }
        val floorWidth = if (240 < srcWidth) 240 else null
        ladder += Attempt(
            32,
            6,
            floorWidth,
            if (floorWidth != null) {
                "32 色 / 6fps / ${floorWidth}px（最低参数）"
            } else {
                "32 色 / 6fps（最低参数）"
            },
        )
        return ladder
    }

    private data class Attempt(
        val colors: Int,
        /** null = 不设 fps 滤镜，保留全部帧 */
        val fps: Int?,
        /** null = 不缩放，保留源尺寸 */
        val scaleWidth: Int?,
        val label: String,
    )

    private fun buildArgs(input: File, output: File, attempt: Attempt): Array<String> {
        val filter = buildString {
            attempt.fps?.let { append("fps=").append(it).append(",") }
            attempt.scaleWidth?.let { append("scale=").append(it).append(":-1:flags=lanczos,") }
            append("split[s0][s1];")
            append("[s0]palettegen=max_colors=").append(attempt.colors)
            append(":stats_mode=diff[p];")
            append("[s1][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle")
        }
        return arrayOf(
            "-hide_banner",
            "-nostdin",
            "-y",
            "-i", input.absolutePath,
            "-vf", filter,
            "-loop", "0",
            output.absolutePath,
        )
    }

    private fun deleteAllRoundFiles(dir: File) {
        dir.listFiles()?.forEach { cleanup(it) }
    }

    private fun cleanup(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }
}

data class GifCompressResult(
    val file: File,
    val originalBytes: Long,
    val compressedBytes: Long,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    /** 最终使用的参数描述 */
    val finalLabel: String,
    val roundsUsed: Int,
    /** false = 达到最低参数仍未达标，已保留最后可用结果 */
    val metTarget: Boolean,
    val elapsedMs: Long,
)

sealed interface GifCompressEvent {
    data class RoundStart(val round: Int, val totalRounds: Int, val label: String) : GifCompressEvent
    data class RoundProgress(
        val round: Int,
        val totalRounds: Int,
        val label: String,
        val fraction: Float,
    ) : GifCompressEvent

    data class RoundDone(
        val round: Int,
        val label: String,
        val bytes: Long,
        val metTarget: Boolean,
    ) : GifCompressEvent

    data class Success(val result: GifCompressResult) : GifCompressEvent
    data class Failure(val error: String, val diagnostics: String? = null) : GifCompressEvent
    data object Cancelled : GifCompressEvent
}
