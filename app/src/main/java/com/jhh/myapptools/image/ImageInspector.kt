package com.jhh.myapptools.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File
import kotlin.math.max

/**
 * 图片元信息探测（只读）。
 *
 * 与 `gif/GifInspector.kt` 的定位一致：任何压缩/转换前先拿到真实信息，
 * 后续所有决策（是否拒绝、算目标尺寸、是否合成白底）都基于这里的结果，
 * 而不是靠文件扩展名猜。
 *
 * 注意：`hasAlpha()` 必须真正解码一次才能确定，这里用极小采样（长边 64px）探测，
 * 避免为了判断透明通道把 12MP 大图完整解码进内存。
 */
object ImageInspector {

    /** 超过 1 亿像素直接拒绝：解码后的 ARGB_8888 需要 400MB，必然 OOM */
    const val MAX_PIXELS: Long = 100_000_000L

    /** 透明通道探测的采样长边 */
    private const val ALPHA_PROBE_EDGE = 64

    data class Info(
        val file: File,
        val bytes: Long,
        val width: Int,
        val height: Int,
        val format: ImageFormat,
        val hasAlpha: Boolean,
        val exifOrientation: Int,
        val isAnimated: Boolean,
    ) {
        val megapixels: Double get() = width.toLong() * height / 1_000_000.0

        val longEdge: Int get() = max(width, height)

        val tooLarge: Boolean get() = width.toLong() * height > MAX_PIXELS

        /** EXIF 转正后（即用户实际看到的）宽高：90/270 度旋转与转置时宽高互换 */
        val displayWidth: Int get() = if (isQuarterTurn) height else width

        val displayHeight: Int get() = if (isQuarterTurn) width else height

        val displayLongEdge: Int get() = max(displayWidth, displayHeight)

        private val isQuarterTurn: Boolean
            get() = exifOrientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                exifOrientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                exifOrientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                exifOrientation == ExifInterface.ORIENTATION_TRANSVERSE
    }

    /** 探测失败（文件损坏 / 非图片）返回 null，由调用方给出「无法识别的图片」 */
    fun read(file: File): Info? {
        if (!file.exists() || file.length() <= 0L) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        // outMimeType 最可靠；个别系统对未知格式给 null，回落到扩展名
        val byMime = ImageFormat.fromMimeType(bounds.outMimeType)
        val format = if (byMime == ImageFormat.OTHER) ImageFormat.fromExtension(file.name) else byMime

        val animated = format == ImageFormat.GIF ||
            (format == ImageFormat.WEBP && isAnimatedWebp(file))

        return Info(
            file = file,
            bytes = file.length(),
            width = width,
            height = height,
            format = format,
            // JPEG 永远没有透明通道，短路省掉一次解码
            hasAlpha = format != ImageFormat.JPEG && probeAlpha(file, width, height),
            exifOrientation = readOrientation(file),
            isAnimated = animated,
        )
    }

    private fun probeAlpha(file: File, width: Int, height: Int): Boolean {
        val longEdge = max(width, height)
        var sample = 1
        while (longEdge / (sample * 2) >= ALPHA_PROBE_EDGE) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
            .getOrNull() ?: return false
        return try {
            bitmap.hasAlpha()
        } finally {
            bitmap.recycle()
        }
    }

    private fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * 动图 WebP 判定：RIFF 容器里首个 VP8X chunk 的 flags 第 2 位（0x02）为 ANIMATION。
     * 只读前 21 字节，不解码。
     */
    private fun isAnimatedWebp(file: File): Boolean = runCatching {
        file.inputStream().buffered().use { input ->
            val header = ByteArray(21)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) break
                read += n
            }
            if (read < header.size) return@use false
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return@use false
            if (String(header, 8, 4, Charsets.US_ASCII) != "WEBP") return@use false
            if (String(header, 12, 4, Charsets.US_ASCII) != "VP8X") return@use false
            (header[20].toInt() and 0xFF) and 0x02 != 0
        }
    }.getOrDefault(false)
}
