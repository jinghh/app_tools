package com.jhh.myapptools.download

/**
 * 从分享文本中提取第一条链接。
 *
 * 背景：部分站点（如小红书）复制分享出来的文本形如
 * `节奏感很强烈 https://xhslink.cn/o/3OsPOYWaqSm 来【小红书】发现这篇笔记的精彩~`，
 * 直接粘贴给 yt-dlp 会解析失败，因此需要先抽出其中的 URL。
 *
 * 规则：
 * - 只匹配 http/https 且字符集限定为 URL 合法字符（中文字符天然被排除）；
 * - 去掉尾部可能属于句子标点的字符（如 `。`、`，`、`)`、`"` 等）；
 * - 无匹配或匹配结果过短时返回 null，由调用方回退为原文。
 */
internal object UrlExtractor {

    private val URL_REGEX = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")

    /** 可能出现在链接末尾的句子标点（中英文） */
    private const val TRAILING_PUNCTUATION = ".,;:!?'\")]}，。！？、；：）】》”’…"

    fun extract(text: String): String? {
        val matched = URL_REGEX.find(text)?.value ?: return null
        var url = matched
        while (url.isNotEmpty() && url.last() in TRAILING_PUNCTUATION) {
            url = url.dropLast(1)
        }
        // 至少要有 "https://a.b" 这样的长度，避免把残缺片段当成链接
        return url.takeIf { it.length > "https://".length + 2 }
    }
}
