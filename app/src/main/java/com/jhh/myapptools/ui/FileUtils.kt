package com.jhh.myapptools.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File

/** SAF 导入与版本号等界面通用工具（体积格式化见 com.jhh.myapptools.util.formatBytes） */

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
