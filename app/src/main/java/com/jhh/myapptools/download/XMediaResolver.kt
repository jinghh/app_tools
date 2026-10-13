package com.jhh.myapptools.download

/**
 * 媒体选项（PRD 5.3）：可下载资源、格式/清晰度信息。
 * 媒体 URL 有时效性，解析后应尽快下载，不得长期保存复用。
 */
data class MediaOption(
    val formatId: String,
    val label: String,
    val directUrl: String,
    val ext: String,
    val width: Int?,
    val height: Int?,
    val fps: Double?,
    val filesize: Long?,
    /** true = 可直接 HTTP 下载（mp4 等单文件）；false = 需分片下载（m3u8 等） */
    val httpDirect: Boolean,
)

/** PRD 3.1 建议的错误类型（本模块用到的子集） */
enum class ResolveError {
    INVALID_URL,
    UNSUPPORTED_URL,
    POST_UNAVAILABLE,
    AUTH_REQUIRED,
    RATE_LIMITED,
    RESOLVER_UNAVAILABLE,
    NETWORK_ERROR,
    UNKNOWN,
}

sealed interface ResolveResult {
    data class Success(
        val title: String?,
        val uploader: String?,
        val durationSec: Double?,
        val options: List<MediaOption>,
    ) : ResolveResult

    data class Failure(
        val error: ResolveError,
        val message: String,
        val diagnostics: String? = null,
    ) : ResolveResult
}

/**
 * X（或通用站点）媒体解析器接口（PRD 5.3）。
 * 业务 UI 只依赖此接口，不依赖具体解析实现，便于后续替换。
 * 实现约定：[resolve] 为阻塞调用，调用方负责在 IO 线程执行。
 *
 * ## 当前状态：暂无可用实现（有意留空）
 *
 * yt-dlp-android 2.0.2 无法承担解析职责，已由字节码核实其 CLI 参数**白名单**仅包含：
 * `--format --output --cookies --proxy --add-header --user-agent --restrict-filenames
 *  --newline --no-playlist --extract-audio --audio-format --audio-quality
 *  --write-sub --sub-lang --merge-output-format`
 * 且库**不转发 stdout**（只转发自定义 logger）。
 * 因此 `--print` / `-J` / `--write-info-json` / `--skip-download` 全部不可用
 * ⇒ 下载前拿不到标题、时长与可下载直链，也就无法把直链交给自研 HTTP 下载器
 * （意味着 yt-dlp 路径的“取消”能力缺失，PRD 8.4 暂不满足）。
 *
 * 后续若需要「下载前显示清晰度 / 真正可取消」的能力，应实现一个自研解析器
 * （直接解析公开帖子页面取得 media URL），届时配合 OkHttp 下载器即可满足；
 * 本接口即为该实现预留的替换点。
 */
interface XMediaResolver {
    fun resolve(url: String): ResolveResult
    fun cancel()
}
