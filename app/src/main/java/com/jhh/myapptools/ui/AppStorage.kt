package com.jhh.myapptools.ui

import android.content.Context
import com.jhh.myapptools.download.YtDlpDownloader
import java.io.File

/**
 * 工具产物的存储统计与一键清理。
 *
 * 管理范围：下载工作目录（含 .part 残留）、下载成品长期目录、转 GIF 产物、
 * SAF 导入的临时副本（含其下的 compress-out 压缩中间产物）。
 *
 * 「保留最近结果」的语义：每个目录只保留最新修改的一个文件——即最近一次下载/转换的产物，
 * 其余全部删除。**下载进行中不可调用**（会把正在写入的 .part 当普通文件清掉），
 * 由调用方保证非 RUNNING。
 */
internal object AppStorage {

    private fun managedDirs(context: Context): List<File> = listOf(
        YtDlpDownloader.outDir(context),      // 下载工作目录（.part 残留 + 尚未转移的成品）
        YtDlpDownloader.finalDir(context),    // 下载成品长期目录
        File(context.cacheDir, "gif-output"), // 转 GIF 产物
        File(context.cacheDir, "video-input"),// SAF 导入的视频副本
        File(context.cacheDir, "gif-input"),  // SAF 导入的 GIF 副本（含 compress-out）
    )

    fun totalBytes(context: Context): Long =
        managedDirs(context).sumOf { dir ->
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }

    /** 一键清理，返回释放的字节数 */
    fun clean(context: Context): Long {
        var freed = 0L
        managedDirs(context).forEach { dir ->
            val files = dir.walkBottomUp().filter { it.isFile }.toList()
            val keep = files.maxByOrNull { it.lastModified() }
            files.forEach { f ->
                if (f != keep) {
                    val size = f.length()
                    if (runCatching { f.delete() }.getOrDefault(false)) freed += size
                }
            }
            // 清空的子目录（如 gif-input/compress-out）一并移除，walkBottomUp 已保证自底向上
            dir.walkBottomUp().filter { it.isDirectory && it != dir }
                .forEach { runCatching { it.delete() } }
        }
        return freed
    }
}
