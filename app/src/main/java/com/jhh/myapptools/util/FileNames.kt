package com.jhh.myapptools.util

/**
 * 从素材文件名生成安全的产物基名：去掉扩展名、消毒非法字符、截断长度。
 *
 * 转 GIF / GIF 压缩 / 视频压缩共用，保证产物带上原素材名，
 * 供「爆款标题」页把文件名作为内容上下文。
 */
internal fun safeBaseName(name: String): String {
    val base = name.substringBeforeLast('.', name)
    val cleaned = base.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), "_").trim()
    return (cleaned.ifEmpty { "input_${System.currentTimeMillis()}" }).take(80)
}
