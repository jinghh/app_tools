package com.jhh.myapptools.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/** SAF 导入文件的公共处理：复制到私有目录 + 文件名消毒 + 体积格式化 */

internal fun copyUriToCache(context: Context, uri: Uri, subDir: String, fallbackExt: String): File? {
    val dir = File(context.cacheDir, subDir).apply { mkdirs() }
    // DISPLAY_NAME 可能含路径分隔符等非法字符，必须消毒后再构造 File
    val safeName = sanitizeFileName(
        queryDisplayName(context, uri) ?: "input_${System.currentTimeMillis()}.$fallbackExt"
    )
    val out = File(dir, safeName)
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        out
    }.onFailure {
        // 失败时清理半成品，不在缓存目录堆积
        runCatching { if (out.exists()) out.delete() }
    }.getOrNull()
}

/** 过滤文件系统非法字符，防止 DISPLAY_NAME 携带路径穿越字符 */
internal fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim()
    return cleaned.ifEmpty { "input_${System.currentTimeMillis()}" }
}

internal fun queryDisplayName(context: Context, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index)
        }
    }
    return null
}

/** 文档 3.3 约定：UI 标注 MB，内部按十进制 MB（1 MB = 1,000,000 bytes）固定换算 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1000 -> "$bytes B"
    bytes < 1_000_000 -> String.format(Locale.US, "%.1f KB", bytes / 1000.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / 1_000_000.0)
}
