package com.jhh.myapptools.title

import android.content.Context
import android.media.MediaMetadataRetriever
import com.jhh.myapptools.gif.FfmpegRunner
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.Locale

/** 一批可用于 AI 分析的关键帧。目录为 cacheDir 下的批次目录，页面退出/清理工具会回收 */
internal data class ExtractedFrames(
    val files: List<File>,
    val dir: File,
    val sourceName: String,
)

/**
 * 把视频或 GIF 均匀抽成 N 张 JPEG 关键帧。
 *
 * 为什么不把原视频/GIF 直接上传：
 * - 主流 OpenAI 兼容视觉接口接收的是图片，不是视频文件；
 * - GIF 是 256 色二次压缩产物，再上传等于降质两次；
 * - 8 张 640px JPEG 远小于原片，上传快、成本低、失败率也低。
 *
 * 抽帧始终走 FFmpeg（与转 GIF 共用 FfmpegRunner），不依赖 MediaMetadataRetriever 解码，
 * 后者只用来读时长决定采样间隔。
 */
internal class FrameExtractor(private val context: Context) {

    fun extract(source: File, count: Int, onProgress: (Float) -> Unit): ExtractedFrames {
        require(count in 1..MAX_FRAMES) { "帧数必须在 1..$MAX_FRAMES" }
        val dir = File(context.cacheDir, "title-frames/batch_${System.currentTimeMillis()}")
        check(dir.mkdirs() || dir.isDirectory) { "无法创建抽帧缓存目录" }

        val durationMs = readDurationMs(source)
        val isGif = source.name.endsWith(".gif", ignoreCase = true)
        // GIF 通常 ≤30s 且 MediaMetadataRetriever 读不到时长，用固定 1fps 覆盖前 8 秒
        val fps = when {
            isGif -> 1.0
            durationMs > 0 -> count.toDouble() / (durationMs / 1000.0)
            else -> 2.0
        }.coerceIn(0.02, 8.0)

        val args = mutableListOf("-y", "-i", source.path)
        args += listOf(
            "-vf",
            "fps=${String.format(Locale.US, "%.5f", fps)}," +
                "scale=640:640:force_original_aspect_ratio=decrease",
            "-frames:v", count.toString(),
            "-q:v", "4",
            File(dir, "frame_%02d.jpg").path,
        )

        when (val outcome = FfmpegRunner.run(args.toTypedArray(), durationMs / 1000.0 + 1.0, onProgress)) {
            is FfmpegRunner.Outcome.Success -> Unit
            is FfmpegRunner.Outcome.Cancelled -> throw CancellationException("抽帧已取消")
            is FfmpegRunner.Outcome.Error ->
                throw IllegalStateException(outcome.logs.take(300).ifBlank { "FFmpeg 退出码 ${outcome.exitCode}" })
        }

        val files = dir.listFiles { f -> f.name.endsWith(".jpg", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) throw IllegalStateException("未能从素材中抽取到画面，请确认文件可正常播放")

        pruneOldBatches(keep = dir)
        return ExtractedFrames(files = files, dir = dir, sourceName = source.name)
    }

    fun cancel() = FfmpegRunner.cancel()

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    /** 只保留最近 3 批，防止反复「重新抽帧」把 cacheDir 撑满 */
    private fun pruneOldBatches(keep: File) {
        val parent = keep.parentFile ?: return
        parent.listFiles { f -> f.isDirectory && f.name.startsWith("batch_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { old ->
                old.walkBottomUp().forEach { runCatching { it.delete() } }
            }
    }

    private companion object {
        const val MAX_FRAMES = 12
    }
}
