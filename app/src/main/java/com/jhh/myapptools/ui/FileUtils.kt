package com.jhh.myapptools.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/** SAF 导入导出、MediaStore 保存与版本号等界面通用工具（体积格式化见 util/Format.kt） */

/** 读取应用版本名（如 0.1.1）；失败时返回"未知"，不抛异常 */
internal fun appVersionName(context: Context): String = runCatching {
    val ifo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.PackageInfoFlags.of(0),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    ifo.versionName
}.getOrNull() ?: "未知"

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

/**
 * 一键保存到系统「下载」目录（MediaStore.Downloads）。
 * API 29+ 起 App 自己创建的媒体**无需任何存储权限**；SAF（CreateDocument）保留为「另存为」。
 * 成功返回写入的 Uri；失败（存储不可用/重名冲突等）返回 null，由调用方回退提示。
 * 阻塞 IO，调用方须在 Dispatchers.IO 上执行。
 */
internal fun saveToPublicDownloads(context: Context, file: File, mime: String): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    return runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore 插入失败")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: error("openOutputStream 返回 null")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            // 写入失败：清掉 pending 记录，不在 Downloads 里留 0 字节占位
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }.getOrNull()
}

/** MediaStore 需要的 MIME 类型；未知扩展名按通用二进制处理（仅影响系统归类，不影响内容） */
internal fun mimeForFile(file: File): String = when (file.extension.lowercase(Locale.US)) {
    "mp4", "mov", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}
