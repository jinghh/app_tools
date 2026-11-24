package com.jhh.myapptools.ai

import java.io.File

/**
 * 大模型服务商配置。
 *
 * 当前只内置 DeepSeek 预设，但字段刻意保持通用（OpenAI 兼容 Chat Completions）：
 * 后续接入豆包/通义/智谱等只需新增一个 preset，[chatUrl] 会自动把
 * `https://xxx/v1` 与 `https://xxx` 两种写法都收敛到 `/chat/completions`。
 */
data class ProviderConfig(
    val id: String = ID_DEEPSEEK,
    val name: String = "DeepSeek",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    /** 用户自填，永不由 App 内置；以密文形式落盘，见 SecureKeyStore */
    val apiKey: String = "",
    /**
     * 关闭深度思考。DeepSeek V4 系列默认开启思考且思考 token 计入 max_tokens，
     * 不关会出现 HTTP 200 但 content 为空。后续新增其他厂商预设时按需设为 false。
     */
    val disableThinking: Boolean = true,
) {
    val chatUrl: String
        get() = baseUrl.trim().trimEnd('/') + "/chat/completions"

    companion object {
        const val ID_DEEPSEEK = "deepseek"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        // DeepSeek 官方文档：deepseek-flash 为当前视觉模型，旧名 deepseek-v4-flash-vision-exp 已退役
        const val DEFAULT_MODEL = "deepseek-flash"

        fun deepSeek() = ProviderConfig()

        /** 每个预设返回一份全新实例，避免配置页改写后污染其他入口 */
        val presets: List<ProviderConfig> = listOf(deepSeek())
    }
}

/** 一次通用聊天请求：文本 + 若干张本地 JPEG 关键帧（不上传原始视频/GIF） */
data class ChatRequest(
    val prompt: String,
    val images: List<File> = emptyList(),
    val temperature: Double = 0.85,
    val maxTokens: Int = 2000,
)

/** 统一的调用结果。业务层只依赖本类型，不依赖具体实现（便于后续换 SDK） */
sealed interface ChatOutcome {
    data class Success(val content: String) : ChatOutcome

    data class Failure(val message: String, val kind: Kind) : ChatOutcome {
        enum class Kind {
            INVALID_CONFIG, AUTH, RATE_LIMIT, NETWORK, SERVER, PARSE, UNKNOWN,
        }
    }

    data object Cancelled : ChatOutcome
}
