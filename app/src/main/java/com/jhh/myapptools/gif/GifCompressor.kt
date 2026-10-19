package com.jhh.myapptools.gif

import java.io.File

/**
 * GIF 压缩模块（文档 3.3 策略实现）。
 *
 * 两套级联策略：
 *
 * **默认（lossyPreferred = false）**
 * 原参数重编码 -> 减色(256/128/64/32) -> 降帧率(8/6fps) -> 降宽度(480/360/240)。
 *
 * **有损优先（lossyPreferred = true）**
 * 先只损画质、**保持帧率/尺寸/256 色不变**：
 * 抖动 4→2 -> 抖动 1 -> 无抖动 -> 无抖动+轻模糊 -> 无抖动+强模糊；
 * 仍超限才回落到减色/降帧率/降尺寸（并保持"无抖动"，因为它只会让结果更小）。
 *
 * 为什么"关抖动"能显著减小体积：GIF 用 LZW 压缩，靠"连续相同像素"建字典；
 * 抖动让相邻像素刻意不同，长串全部消失，压缩率急剧下降。因此抖动是"用体积换平滑"，
 * 关掉即"用平滑换体积"——且 256 色时色阶较细，色带相对可接受。
 *
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
        lossyPreferred: Boolean = false,
        onEvent: (GifCompressEvent) -> Unit,
    ) {
        val startedAt = System.currentTimeMillis()
        val originalBytes = input.length()
        val srcInfo = GifInspector.inspect(input)
        if (srcInfo == null || originalBytes <= 0L) {
            onEvent(GifCompressEvent.Failure("无法解析输入 GIF 文件"))
            return
        }

        val ladder = buildLadder(srcInfo.width, lossyPreferred)
        val outDir = File(input.parentFile, "compress-out").apply { mkdirs() }
        // GIF 无全局时长字段；按 12fps 估算，仅用于进度显示
        val estimatedSec = maxOf(1.0, srcInfo.frameCount / 12.0)

        var lastValidFile: File? = null
        var lastValidLabel = ""
        var lastValidBytes = 0L
        var lastValidWasLossy = false
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
            lastValidWasLossy = attempt.lossyStage
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
                            // 有损优先模式下若靠"降参数"才达标，必须让用户看得见
                            finalLabel = if (lossyPreferred && !attempt.lossyStage) {
                                "${attempt.label}（有损档位仍超限，已降参数）"
                            } else {
                                attempt.label
                            },
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
                    finalLabel = if (lossyPreferred && !lastValidWasLossy) {
                        "$lastValidLabel（未达标，已回落到降参数）"
                    } else {
                        "$lastValidLabel（未达标）"
                    },
                    roundsUsed = roundsUsed,
                    metTarget = false,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            )
        )
    }

    /**
     * 构建级联阶梯。
     *
     * 有损优先模式先用"只损画质、不动参数"的档位（抖动逐级减弱 → 无抖动 → 加轻微模糊），
     * 全部超限才回落到减色 / 降帧率 / 降尺寸。回落时保持"无抖动"，因为它只会让结果更小。
     */
    private fun buildLadder(srcWidth: Int, lossyPreferred: Boolean): List<Attempt> {
        val ladder = mutableListOf<Attempt>()

        if (lossyPreferred) {
            // ---- 阶段 1：保持帧率 / 尺寸 / 256 色，只损画质 ----
            ladder += Attempt(
                256, dither = Dither.BAYER_2, lossyStage = true,
                label = "有损优先 · 256 色 · 抖动 4→2",
            )
            ladder += Attempt(
                256, dither = Dither.BAYER_1, lossyStage = true,
                label = "有损优先 · 256 色 · 抖动 1",
            )
            ladder += Attempt(
                256, dither = Dither.NONE, lossyStage = true,
                label = "有损优先 · 256 色 · 无抖动",
            )
            ladder += Attempt(
                256, dither = Dither.NONE, blurSigma = 0.5f, lossyStage = true,
                label = "有损优先 · 256 色 · 无抖动 · 轻模糊",
            )
            ladder += Attempt(
                256, dither = Dither.NONE, blurSigma = 1.0f, lossyStage = true,
                label = "有损优先 · 256 色 · 无抖动 · 强模糊",
            )
        } else {
            ladder += Attempt(256, label = "原参数重编码（256 色）")
        }

        // ---- 阶段 2：仍超限，回落到降参数（颜色数优先，其次帧率，最后分辨率）----
        val dither = if (lossyPreferred) Dither.NONE else Dither.BAYER_4
        ladder += Attempt(128, dither = dither, label = "128 色")
        ladder += Attempt(64, dither = dither, label = "64 色")
        ladder += Attempt(64, 8, dither = dither, label = "64 色 / 8fps")
        // 宽度档位只取小于源宽度的（不放大）
        for (w in listOf(480, 360, 240)) {
            if (w < srcWidth) {
                ladder += Attempt(64, 8, w, dither = dither, label = "64 色 / 8fps / ${w}px")
            }
        }
        val floorWidth = if (240 < srcWidth) 240 else null
        ladder += Attempt(
            32,
            6,
            floorWidth,
            dither,
            label = if (floorWidth != null) {
                "32 色 / 6fps / ${floorWidth}px（最低参数）"
            } else {
                "32 色 / 6fps（最低参数）"
            },
        )
        return ladder
    }

    /** 抖动档位：bayer_scale 越大，噪点越多、LZW 越难压缩 */
    private enum class Dither(val value: String) {
        BAYER_4("bayer:bayer_scale=4"),
        BAYER_2("bayer:bayer_scale=2"),
        BAYER_1("bayer:bayer_scale=1"),
        NONE("none"),
    }

    private data class Attempt(
        val colors: Int,
        /** null = 不设 fps 滤镜，保留全部帧 */
        val fps: Int? = null,
        /** null = 不缩放，保留源尺寸 */
        val scaleWidth: Int? = null,
        /** 抖动强度；默认与历史行为一致（Bayer 4） */
        val dither: Dither = Dither.BAYER_4,
        /** null = 不做模糊；有值则加 gblur=sigma=N（压低噪点、提升压缩率，但画面变软） */
        val blurSigma: Float? = null,
        /** true = 属于"有损优先"阶段（帧率 / 尺寸 / 色数均未改动） */
        val lossyStage: Boolean = false,
        val label: String,
    )

    private fun buildArgs(input: File, output: File, attempt: Attempt): Array<String> {
        val filter = buildString {
            attempt.fps?.let { append("fps=").append(it).append(",") }
            attempt.scaleWidth?.let { append("scale=").append(it).append(":-1:flags=lanczos,") }
            // 模糊放在 split 之前：调色板生成与上色两个分支都会吃到
            attempt.blurSigma?.let { append("gblur=sigma=").append(it).append(",") }
            append("split[s0][s1];")
            append("[s0]palettegen=max_colors=").append(attempt.colors)
            append(":stats_mode=diff[p];")
            append("[s1][p]paletteuse=dither=").append(attempt.dither.value)
            append(":diff_mode=rectangle")
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
