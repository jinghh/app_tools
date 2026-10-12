package com.jhh.myapptools.gif

import java.io.File
import java.util.Locale

/**
 * 视频转 GIF 引擎：FFmpegKit 社区维护分支（dev.ffmpegkit-maintained）。
 * 调色板链路：fps 降帧 -> scale 缩放 -> split -> palettegen -> paletteuse。
 * 该链路是 GIF 画质的事实标准，且支持 max_colors 逐级降色，满足压缩模块的参数级联策略。
 *
 * 纯 Java 备选（square/gifencoder）已实测弃用：同参数慢 29 倍且无法减色，已从工程移除。
 */
class FfmpegGifEngine : GifEngine {

    override fun cancel() {
        FfmpegRunner.cancel()
    }

    override fun encode(
        input: File,
        output: File,
        params: GifEncodeParams,
        onEvent: (GifEncodeEvent) -> Unit,
    ) {
        val totalSec = params.durationMs / 1000.0
        val startSec = params.startMs / 1000.0

        // 使用 String[] 变体，避免路径含空格时被命令字符串拆分破坏
        val args = buildArgs(input, output, params, startSec, totalSec)

        val outcome = FfmpegRunner.run(
            args = args,
            totalSec = totalSec,
            onProgress = { fraction ->
                onEvent(GifEncodeEvent.Progress(fraction, "FFmpeg 编码中"))
            },
        )

        when (outcome) {
            is FfmpegRunner.Outcome.Success -> {
                val info = GifInspector.inspect(output)
                if (info == null || output.length() == 0L) {
                    cleanup(output)
                    onEvent(
                        GifEncodeEvent.Failure("FFmpeg 报告成功但输出文件无法解析")
                    )
                } else {
                    onEvent(
                        GifEncodeEvent.Success(
                            GifEncodeResult(
                                file = output,
                                width = info.width,
                                height = info.height,
                                frameCount = info.frameCount,
                                bytes = output.length(),
                                elapsedMs = outcome.elapsedMs,
                            )
                        )
                    )
                }
            }

            is FfmpegRunner.Outcome.Cancelled -> {
                cleanup(output)
                onEvent(GifEncodeEvent.Cancelled)
            }

            is FfmpegRunner.Outcome.Error -> {
                cleanup(output)
                onEvent(
                    GifEncodeEvent.Failure(
                        "FFmpeg 执行失败（退出码 ${outcome.exitCode}）",
                        outcome.logs,
                    )
                )
            }
        }
    }

    private fun cleanup(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    companion object {

        /** 构建 ffmpeg 参数数组；UI 展示与执行共用同一实现，避免展示与实跑不一致 */
        fun buildArgs(
            input: File,
            output: File,
            params: GifEncodeParams,
            startSec: Double,
            totalSec: Double,
        ): Array<String> {
            val filter = buildString {
                append("fps=").append(params.fps)
                append(",scale=").append(params.width).append(":-1:flags=lanczos")
                append(",split[s0][s1];")
                append("[s0]palettegen=max_colors=").append(params.maxColors)
                append(":stats_mode=diff[p];")
                append("[s1][p]paletteuse=")
                if (params.dither) {
                    append("dither=bayer:bayer_scale=4:diff_mode=rectangle")
                } else {
                    append("dither=none")
                }
            }
            return arrayOf(
                "-hide_banner",
                "-nostdin",
                "-y",
                "-ss", String.format(Locale.US, "%.3f", startSec),
                "-i", input.absolutePath,
                "-t", String.format(Locale.US, "%.3f", totalSec),
                "-vf", filter,
                "-loop", "0",
                output.absolutePath,
            )
        }
    }
}
