package com.jhh.myapptools.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/** 压缩模式（三选一） */
enum class CompressMode {
    /** 有损：按质量 q 单次编码 */
    LOSSY,

    /** 无损：像素不变，只重新编码（PNG / WebP 无损） */
    LOSSLESS,

    /** 目标大小：二分搜索质量 + 尺寸阶梯，达标即停 */
    TARGET_SIZE,
}

data class ImageCompressOptions(
    val mode: CompressMode = CompressMode.LOSSY,
    /** 有损质量 0-100 */
    val quality: Int = 80,
    /** 目标字节数，仅 [CompressMode.TARGET_SIZE] 使用 */
    val targetBytes: Long = 0L,
    /** 输出长边像素上限；null = 保持原始（只缩不放） */
    val maxEdge: Int? = null,
    /** 相对原图缩小百分比（50 = 缩到一半）；与 [maxEdge] 二选一，本字段优先 */
    val scalePercent: Int? = null,
    /** 输出格式；null = 保持原格式 */
    val outputFormat: ImageFormat? = null,
)

data class ImageCompressResult(
    val file: File,
    val originalBytes: Long,
    val outputBytes: Long,
    val srcWidth: Int,
    val srcHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
    val format: ImageFormat,
    /** null = 无损（质量参数无意义） */
    val quality: Int?,
    val lossless: Boolean,
    val finalLabel: String,
    /** false = 走完阶梯仍未达标，已保留体积最小的结果 */
    val metTarget: Boolean,
    val elapsedMs: Long,
) {
    /** 降幅百分比；体积反增时为负数，界面按实际情况展示 */
    val savedPercent: Int
        get() = if (originalBytes <= 0L) {
            0
        } else {
            (((originalBytes - outputBytes) * 100.0) / originalBytes).roundToInt()
        }
}

data class ImageBatchSummary(
    val total: Int,
    val success: Int,
    val failed: Int,
    val originalBytes: Long,
    val outputBytes: Long,
    val elapsedMs: Long,
) {
    val savedBytes: Long get() = (originalBytes - outputBytes).coerceAtLeast(0L)

    val savedPercent: Int
        get() = if (originalBytes <= 0L) {
            0
        } else {
            (savedBytes * 100.0 / originalBytes).roundToInt()
        }
}

sealed interface ImageCompressEvent {
    data class ItemStart(val index: Int, val total: Int, val name: String) : ImageCompressEvent

    /** 当前张所处阶段（解码 / 缩放 / 合成白底 / 编码 / 校验） */
    data class ItemStage(val index: Int, val total: Int, val stage: String) : ImageCompressEvent

    data class ItemDone(
        val index: Int,
        val total: Int,
        val result: ImageCompressResult,
    ) : ImageCompressEvent

    data class ItemFailed(
        val index: Int,
        val total: Int,
        val name: String,
        val reason: String,
    ) : ImageCompressEvent

    data class BatchDone(val summary: ImageBatchSummary) : ImageCompressEvent

    data object Cancelled : ImageCompressEvent
}

/**
 * 图片压缩引擎。
 *
 * 设计要点（详细依据见 docs/安卓本地工具箱_图片压缩模块需求与技术方案.md）：
 *
 * 1. **不使用 FFmpeg**：APK 内的 libavcodec 只有 webp 解码器、没有编码器，
 *    ffmpeg-kit-min 也无法输出调色板 PNG；原生 `Bitmap.compress()` 更快且无临时文件；
 * 2. **批量严格串行**：并行解码 3 张 12MP 图约 144MB，低端机必 OOM；
 * 3. **只缩不放**：目标尺寸大于原图时保持原尺寸，放大只会增大体积；
 * 4. **EXIF 先转正再缩放**：否则手机竖拍图输出会横躺；
 * 5. **alpha → JPEG 合成白底**：不处理会得到黑底，这是最典型的转格式 bug；
 * 6. **失败隔离**：单张失败（含 [OutOfMemoryError]）只标记该张，不中断整批；
 * 7. **绝不覆盖原图**：所有产物写入 `outputRoot/batch_<时间戳>/`。
 *
 * `compress()` 是阻塞调用，必须在 `Dispatchers.IO` 上执行。
 */
class ImageCompressor(private val outputRoot: File) {

    private val cancelled = AtomicBoolean(false)

    /** 取消：仅在「张与张之间」生效，当前张处理完才停止（单张编码通常 <1s） */
    fun cancel() {
        cancelled.set(true)
    }

    fun compress(
        inputs: List<File>,
        options: ImageCompressOptions,
        onEvent: (ImageCompressEvent) -> Unit,
    ) {
        if (inputs.isEmpty()) return
        cancelled.set(false)
        val startedAt = System.currentTimeMillis()
        val batchDir = prepareBatchDir()

        var success = 0
        var failed = 0
        var originalTotal = 0L
        var outputTotal = 0L

        for ((offset, input) in inputs.withIndex()) {
            val index = offset + 1
            if (cancelled.get()) {
                // 保留已完成的产物（用户可能只想要前面几张），仅停止后续处理
                onEvent(ImageCompressEvent.Cancelled)
                return
            }
            onEvent(ImageCompressEvent.ItemStart(index, inputs.size, input.name))
            val originalBytes = input.length()
            originalTotal += originalBytes

            try {
                val result = processOne(
                    input = input,
                    index = index,
                    total = inputs.size,
                    options = options,
                    batchDir = batchDir,
                    originalBytes = originalBytes,
                    onEvent = onEvent,
                )
                success++
                outputTotal += result.outputBytes
                onEvent(ImageCompressEvent.ItemDone(index, inputs.size, result))
            } catch (e: ItemFailure) {
                failed++
                onEvent(
                    ImageCompressEvent.ItemFailed(index, inputs.size, input.name, e.message ?: "处理失败")
                )
            } catch (e: OutOfMemoryError) {
                // OOM 是 Error 不是 Exception，漏掉这一层会让整批崩溃
                failed++
                onEvent(
                    ImageCompressEvent.ItemFailed(
                        index, inputs.size, input.name, "内存不足，建议降低输出尺寸后重试"
                    )
                )
            } catch (e: Exception) {
                failed++
                onEvent(
                    ImageCompressEvent.ItemFailed(
                        index, inputs.size, input.name, e.message ?: e.javaClass.simpleName
                    )
                )
            }
        }

        onEvent(
            ImageCompressEvent.BatchDone(
                ImageBatchSummary(
                    total = inputs.size,
                    success = success,
                    failed = failed,
                    originalBytes = originalTotal,
                    outputBytes = outputTotal,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                )
            )
        )
    }

    // ---------------------------------------------------------------- 单张处理

    private fun processOne(
        input: File,
        index: Int,
        total: Int,
        options: ImageCompressOptions,
        batchDir: File,
        originalBytes: Long,
        onEvent: (ImageCompressEvent) -> Unit,
    ): ImageCompressResult {
        val startedAt = System.currentTimeMillis()

        onEvent(ImageCompressEvent.ItemStage(index, total, "解析"))
        val info = ImageInspector.read(input) ?: throw ItemFailure("无法识别的图片")
        // GIF 与动图 WebP 都只解得到首帧，压缩成静态图不符合预期，直接引导到已有模块
        if (info.isAnimated || info.format == ImageFormat.GIF) {
            throw ItemFailure("动图请使用「GIF 压缩」页")
        }
        if (info.tooLarge) throw ItemFailure("图片过大（超过 1 亿像素），暂不支持")

        val requested = resolveOutputFormat(info.format, options.outputFormat)
        val lossless = options.mode == CompressMode.LOSSLESS
        // 无损请求在低版本系统上无法用 WebP 实现，退化为 PNG
        val format = if (lossless) requested.losslessTarget() else requested
        val (targetW, targetH) = resolveTargetSize(info, options)

        /** 用户既没换格式也没换尺寸：此时「压缩」只可能靠重编码，绝不该让体积变大 */
        val identityRequested = options.outputFormat == null &&
            targetW == info.displayWidth && targetH == info.displayHeight

        // 无损 + 原格式 + 原尺寸 = 原图本身就是最优的无损表示。
        // Android 平台没有 oxipng/zopfli 级的无损再优化能力；重编码尤其会暴涨：
        //   JPEG →（losslessTarget 改成 PNG）十几 MB、
        //   调色板 PNG → 解成 ARGB_8888 再编码膨胀数倍。
        // 因此直接复制原文件，零 CPU、零膨胀，且严格满足「像素不变」。
        if (lossless && identityRequested) {
            onEvent(ImageCompressEvent.ItemStage(index, total, "保留原图"))
            val kept = copyOriginal(
                input = input,
                batchDir = batchDir,
                info = info,
                width = targetW,
                height = targetH,
                label = "无损 · 原样保留（未重编码）",
            )
            return toResult(kept, info, originalBytes, startedAt)
        }

        onEvent(ImageCompressEvent.ItemStage(index, total, "解码"))
        var bitmap = decodeWithOrientation(input, info, max(targetW, targetH))

        try {
            onEvent(ImageCompressEvent.ItemStage(index, total, "缩放"))
            bitmap = scaleTo(bitmap, targetW, targetH)

            if (format == ImageFormat.JPEG && bitmap.hasAlpha()) {
                onEvent(ImageCompressEvent.ItemStage(index, total, "合成白底"))
                bitmap = flattenOnWhite(bitmap)
            }

            onEvent(ImageCompressEvent.ItemStage(index, total, "编码"))
            var encoded = when {
                lossless -> encodeOnce(
                    bitmap = bitmap,
                    input = input,
                    format = format,
                    lossless = true,
                    quality = 100,
                    batchDir = batchDir,
                    tag = "lossless",
                )

                options.mode == CompressMode.TARGET_SIZE -> encodeToTarget(
                    prepared = bitmap,
                    input = input,
                    format = format,
                    targetBytes = options.targetBytes,
                    batchDir = batchDir,
                    index = index,
                    total = total,
                    onEvent = onEvent,
                )

                else -> {
                    val q = options.quality.coerceIn(1, 100)
                    encodeOnce(
                        bitmap = bitmap,
                        input = input,
                        format = format,
                        lossless = false,
                        quality = q,
                        batchDir = batchDir,
                        tag = "q$q",
                    )
                }
            }

            // 通用兜底：有损 q100、目标大小给得很宽时也可能比原图大。
            // 只要用户没要求改尺寸/格式，就回退到原图副本，不允许「越压越大」。
            if (identityRequested && encoded.bytes >= originalBytes) {
                runCatching { encoded.file.delete() }
                encoded = copyOriginal(
                    input = input,
                    batchDir = batchDir,
                    info = info,
                    width = encoded.width,
                    height = encoded.height,
                    label = "已保留原图（重编码未减小体积）",
                )
            }

            onEvent(ImageCompressEvent.ItemStage(index, total, "校验"))
            return toResult(encoded, info, originalBytes, startedAt)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // ---------------------------------------------------------------- 编码

    private fun encodeOnce(
        bitmap: Bitmap,
        input: File,
        format: ImageFormat,
        lossless: Boolean,
        quality: Int,
        batchDir: File,
        tag: String,
    ): Encoded {
        val out = File(batchDir, buildOutputName(input, bitmap.width, bitmap.height, format, tag))
        if (!encode(bitmap, out, format, lossless, quality)) throw ItemFailure("编码失败")
        return Encoded(
            file = out,
            bytes = out.length(),
            width = bitmap.width,
            height = bitmap.height,
            format = format,
            label = "${bitmap.width}x${bitmap.height} · " + if (lossless) "无损" else "q$quality",
            quality = if (lossless) null else quality,
            lossless = lossless,
            metTarget = true,
        )
    }

    /**
     * 原样复制：一个字节都不改，是「无损」能得到的最优结果。
     *
     * 触发场景：
     * - 无损 + 原尺寸 + 保持原格式：Android 平台没有 oxipng/zopfli 级的无损再优化能力，
     *   重编码（尤其 JPEG→PNG、调色板 PNG→ARGB_8888）只会让体积暴涨；
     * - 任何模式下重编码后反而比原图大、且用户没要求改尺寸/格式：不让用户「越压越大」。
     */
    private fun copyOriginal(
        input: File,
        batchDir: File,
        info: ImageInspector.Info,
        width: Int,
        height: Int,
        label: String,
    ): Encoded {
        val out = File(
            batchDir,
            buildOutputName(input, width, height, info.format, "keep"),
        )
        runCatching { input.copyTo(out, overwrite = true) }
            .getOrElse { throw ItemFailure("复制原文件失败：${it.message ?: "IO 异常"}") }
        return Encoded(
            file = out,
            bytes = out.length(),
            width = width,
            height = height,
            format = info.format,
            label = label,
            quality = null,
            lossless = true,
            metTarget = true,
        )
    }

    private fun toResult(
        encoded: Encoded,
        info: ImageInspector.Info,
        originalBytes: Long,
        startedAt: Long,
    ): ImageCompressResult = ImageCompressResult(
        file = encoded.file,
        originalBytes = originalBytes,
        outputBytes = encoded.bytes,
        srcWidth = info.displayWidth,
        srcHeight = info.displayHeight,
        outWidth = encoded.width,
        outHeight = encoded.height,
        format = encoded.format,
        quality = encoded.quality,
        lossless = encoded.lossless,
        finalLabel = encoded.label,
        metTarget = encoded.metTarget,
        elapsedMs = System.currentTimeMillis() - startedAt,
    )

    /**
     * 目标大小搜索：
     * 1. 尺寸档位从 1.0 开始，每档内对质量做二分（图片编码快，6 轮即可收敛到 ±3）；
     * 2. 某档达标立即返回（不再继续缩小尺寸，避免无谓的画质损失）；
     * 3. 无损 PNG 无法用质量换体积，只能整档测量，靠尺寸阶梯；
     * 4. 全部走完仍超限 → 保留体积最小者并标记 metTarget=false。
     */
    private fun encodeToTarget(
        prepared: Bitmap,
        input: File,
        format: ImageFormat,
        targetBytes: Long,
        batchDir: File,
        index: Int,
        total: Int,
        onEvent: (ImageCompressEvent) -> Unit,
    ): Encoded {
        if (targetBytes <= 0L) throw ItemFailure("目标大小无效")

        val candidates = mutableListOf<Encoded>()
        var rounds = 0

        for (step in SIZE_STEPS) {
            if (cancelled.get()) throw ItemFailure("已取消")

            val w = (prepared.width * step).roundToInt().coerceAtLeast(1)
            val h = (prepared.height * step).roundToInt().coerceAtLeast(1)
            val base = if (w == prepared.width && h == prepared.height) {
                prepared
            } else {
                // 每档都从 prepared 重新缩放（而不是在上一档结果上继续缩），避免累积模糊
                Bitmap.createScaledBitmap(prepared, w, h, true)
            }

            try {
                onEvent(
                    ImageCompressEvent.ItemStage(index, total, "编码 ${w}x${h}")
                )

                if (format == ImageFormat.PNG) {
                    // PNG 无损：只有一次机会，体积由像素数决定
                    val encoded = encodeOnce(
                        bitmap = base, input = input, format = format, lossless = true,
                        quality = 100, batchDir = batchDir, tag = "lossless_${w}x$h",
                    )
                    candidates += encoded
                    rounds++
                    if (encoded.bytes <= targetBytes) {
                        deleteOtherCandidates(candidates, encoded)
                        return encoded
                    }
                } else {
                    var lo = MIN_QUALITY
                    var hi = MAX_QUALITY
                    var best: Encoded? = null
                    while (lo <= hi && rounds < MAX_ENCODE_ROUNDS) {
                        val q = (lo + hi) / 2
                        val encoded = encodeOnce(
                            bitmap = base, input = input, format = format, lossless = false,
                            quality = q, batchDir = batchDir, tag = "${w}x${h}_q$q",
                        )
                        rounds++
                        candidates += encoded
                        if (encoded.bytes <= targetBytes) {
                            best = encoded
                            lo = q + 1
                        } else {
                            hi = q - 1
                        }
                    }
                    if (best != null) {
                        deleteOtherCandidates(candidates, best)
                        return best
                    }
                }
            } finally {
                if (base !== prepared) base.recycle()
            }

            if (rounds >= MAX_ENCODE_ROUNDS) break
        }

        val chosen = candidates.minByOrNull { it.bytes }
            ?: throw ItemFailure("编码失败（未产生有效输出）")
        deleteOtherCandidates(candidates, chosen)
        return chosen.copy(
            label = chosen.label + "（未达标）",
            metTarget = chosen.bytes <= targetBytes,
        )
    }

    private fun deleteOtherCandidates(candidates: List<Encoded>, keep: Encoded) {
        candidates.forEach { candidate ->
            if (candidate.file != keep.file) runCatching { candidate.file.delete() }
        }
    }

    private fun encode(
        bitmap: Bitmap,
        out: File,
        format: ImageFormat,
        lossless: Boolean,
        quality: Int,
    ): Boolean = runCatching {
        FileOutputStream(out).use { stream ->
            bitmap.compress(format.compressFormat(lossless), quality, stream)
        }
        out.length() > 0L
    }.getOrElse {
        runCatching { out.delete() }
        false
    }

    // ---------------------------------------------------------------- 解码与变换

    private fun decodeWithOrientation(
        input: File,
        info: ImageInspector.Info,
        targetLongEdge: Int,
    ): Bitmap {
        val sample = safeSample(info, targetLongEdge)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val raw = BitmapFactory.decodeFile(input.absolutePath, options)
            ?: throw ItemFailure("解码失败（文件可能已损坏）")

        val matrix = orientationMatrix(info.exifOrientation) ?: return raw
        val rotated = runCatching {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        }.getOrNull()
        if (rotated != null && rotated !== raw) raw.recycle()
        // 转正失败（极少见的 OOM）时退回未转正的图，比直接失败更有用
        return rotated ?: raw
    }

    private fun scaleTo(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (src.width == targetW && src.height == targetH) return src
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    /** alpha → JPEG 前必须合成到白底，否则透明区变黑 */
    private fun flattenOnWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    private fun orientationMatrix(orientation: Int): Matrix? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return null
        }
        return matrix
    }

    /** 采样率取 2 的幂：解码器对非 2 的幂处理不一致，且 2 的幂内存收益最直接 */
    private fun computeSample(srcEdge: Int, targetEdge: Int): Int {
        if (srcEdge <= 0 || targetEdge <= 0) return 1
        var sample = 1
        while (srcEdge / (sample * 2) >= targetEdge) sample *= 2
        return sample
    }

    /**
     * 采样率：先按目标长边算，再用内存预算兜底。
     *
     * 只按尺寸算是不够的——用户选「保持原始尺寸」时 targetLongEdge 就是原图长边，
     * computeSample 会返回 1，等于整图解码。50MP 图的 ARGB_8888 需要 200MB，
     * 在 maxMemory 较小的机型上直接 OOM。这里用 maxMemory/4 作为单张解码预算，
     * 超预算就继续加倍采样率，宁可降清晰度也不让整批崩掉。
     */
    private fun safeSample(info: ImageInspector.Info, targetLongEdge: Int): Int {
        var sample = computeSample(info.longEdge, max(targetLongEdge, MIN_DECODE_EDGE))
        val budget = Runtime.getRuntime().maxMemory() / 4
        while (sample < MAX_SAMPLE) {
            val w = (info.width / sample).coerceAtLeast(1)
            val h = (info.height / sample).coerceAtLeast(1)
            if (w.toLong() * h * BYTES_PER_PIXEL <= budget) break
            sample *= 2
        }
        return sample
    }

    // ---------------------------------------------------------------- 目标尺寸与命名

    private fun resolveTargetSize(
        info: ImageInspector.Info,
        options: ImageCompressOptions,
    ): Pair<Int, Int> {
        val dispW = info.displayWidth
        val dispH = info.displayHeight

        val percent = options.scalePercent
        if (percent != null && percent in 1..100) {
            val ratio = percent / 100.0
            return scale(dispW, dispH, ratio)
        }

        val maxEdge = options.maxEdge
        if (maxEdge == null || maxEdge <= 0) return dispW to dispH

        val longEdge = max(dispW, dispH)
        // 只缩不放：目标长边不小于原图时保持原尺寸
        if (maxEdge >= longEdge) return dispW to dispH
        return scale(dispW, dispH, maxEdge.toDouble() / longEdge)
    }

    private fun scale(width: Int, height: Int, ratio: Double): Pair<Int, Int> =
        max(1, (width * ratio).roundToInt()) to max(1, (height * ratio).roundToInt())

    private fun resolveOutputFormat(source: ImageFormat, requested: ImageFormat?): ImageFormat {
        if (requested != null && requested.encodable) return requested
        return when (source) {
            ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP -> source
            // HEIC / GIF / 未知格式无法原格式输出，统一转 JPEG（兼容性最好）
            else -> ImageFormat.JPEG
        }
    }

    private fun buildOutputName(
        input: File,
        width: Int,
        height: Int,
        format: ImageFormat,
        tag: String,
    ): String {
        val base = input.nameWithoutExtension
            .take(40)
            .replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_")
            .ifBlank { "image" }
        return "${base}_${width}x${height}_$tag.${format.extension}"
    }

    // ---------------------------------------------------------------- 批次目录

    private fun prepareBatchDir(): File {
        outputRoot.mkdirs()
        // 每次开始新批次先清掉旧批次：旧产物要么已保存到相册，要么已经放弃
        outputRoot.listFiles()?.forEach { deleteRecursively(it) }
        val dir = File(outputRoot, "batch_${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }

    private fun deleteRecursively(file: File) {
        runCatching {
            if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
            file.delete()
        }
    }

    // ---------------------------------------------------------------- 内部模型

    private data class Encoded(
        val file: File,
        val bytes: Long,
        val width: Int,
        val height: Int,
        /** 文件真实格式（可能与用户请求的不同，例如无损 JPEG 被保留为原图） */
        val format: ImageFormat,
        val label: String,
        val quality: Int?,
        val lossless: Boolean,
        val metTarget: Boolean,
    )

    /** 单张失败（预期内的问题，不打印堆栈） */
    private class ItemFailure(message: String) : Exception(message)

    private companion object {
        /** 目标大小搜索的质量区间：下限留 5，超小目标也只能到这里 */
        const val MIN_QUALITY = 5
        const val MAX_QUALITY = 95

        /** 单张最多编码次数：三档尺寸 × 每档最多 6 轮二分，12 次足够且不会无限耗时 */
        const val MAX_ENCODE_ROUNDS = 12

        /** 尺寸阶梯：先原尺寸，再 80%、65%（从 prepared 重新缩放，不累积模糊） */
        val SIZE_STEPS = listOf(1.0, 0.8, 0.65)

        /** 解码下采样下限：目标再小也别把图采到看不清 */
        const val MIN_DECODE_EDGE = 256

        /** 内存兜底时采样率的上限（再大就没有实用价值了） */
        const val MAX_SAMPLE = 64

        /** ARGB_8888 = 4 字节/像素，用于估算解码内存 */
        const val BYTES_PER_PIXEL = 4L
    }
}
