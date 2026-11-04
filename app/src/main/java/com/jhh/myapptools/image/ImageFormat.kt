package com.jhh.myapptools.image

import android.graphics.Bitmap
import android.os.Build
import java.util.Locale

/**
 * 图片格式。
 *
 * 输入侧需要识别 HEIC / GIF（不可编码，但必须能识别并给出恰当提示）；
 * 输出侧只允许 [JPEG] / [PNG] / [WEBP] 三种。
 *
 * 平台编码能力（与 minSdk 24 强相关，见 docs/安卓本地工具箱_图片压缩模块需求与技术方案.md 5.4）：
 * - JPEG：有损，无 alpha
 * - PNG：始终无损，`quality` 参数被 Skia 忽略
 * - WebP 有损：API 24+（`WEBP`） / API 30+（`WEBP_LOSSY`）
 * - WebP 无损：**仅 API 30+**（`WEBP_LOSSLESS`），低版本只能退化为 PNG
 */
enum class ImageFormat(
    val displayName: String,
    val extension: String,
    val mime: String,
    /** 能否作为输出格式（HEIC/GIF 只能识别、不能编码） */
    val encodable: Boolean,
) {
    JPEG("JPEG", "jpg", "image/jpeg", true),
    PNG("PNG", "png", "image/png", true),
    WEBP("WebP", "webp", "image/webp", true),

    /** 输入可解码（API 28+），平台无编码 API，输出需转 JPEG/PNG/WebP */
    HEIC("HEIC", "heic", "image/heic", false),

    /** 动图，本模块不处理，引导到「GIF 压缩」页 */
    GIF("GIF", "gif", "image/gif", false),

    OTHER("未知", "img", "application/octet-stream", false),
    ;

    /** 该格式能否无损编码 */
    val supportsLossless: Boolean
        get() = when (this) {
            PNG -> true
            WEBP -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            else -> false
        }

    /**
     * 无损请求的实际落点：低版本系统没有 WebP 无损编码器时退化为 PNG。
     * PNG 本身已经是无损，原样返回。
     */
    fun losslessTarget(): ImageFormat =
        if (supportsLossless) this else PNG

    /** 映射到平台编码格式；[lossless] 只对 WebP 有意义 */
    fun compressFormat(lossless: Boolean): Bitmap.CompressFormat = when (this) {
        PNG -> Bitmap.CompressFormat.PNG
        WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (lossless) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP_LOSSY
            }
        } else {
            // API 24-29 只有有损 WEBP；无损请求已在 losslessTarget() 里改为 PNG
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        else -> Bitmap.CompressFormat.JPEG
    }

    companion object {

        /** 可选的输出格式（界面只列这三种） */
        val outputOptions: List<ImageFormat> = listOf(JPEG, PNG, WEBP)

        fun fromMimeType(mime: String?): ImageFormat =
            when (mime?.lowercase(Locale.US)) {
                "image/jpeg", "image/jpg" -> JPEG
                "image/png" -> PNG
                "image/webp" -> WEBP
                "image/heic", "image/heif" -> HEIC
                "image/gif" -> GIF
                else -> OTHER
            }

        fun fromExtension(name: String): ImageFormat =
            when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
                "jpg", "jpeg" -> JPEG
                "png" -> PNG
                "webp" -> WEBP
                "heic", "heif" -> HEIC
                "gif" -> GIF
                else -> OTHER
            }
    }
}
