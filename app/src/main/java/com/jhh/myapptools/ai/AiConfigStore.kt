package com.jhh.myapptools.ai

import android.content.Context

/**
 * 服务商配置的持久化：非敏感字段（预设/baseUrl/model）明文，API Key 走 Keystore 加密。
 *
 * 读写分家的目的：任何业务模块都只能拿到完整配置用于发请求，
 * 而设置页可以单独控制「是否覆盖已有 Key」——用户不改 Key 时不会被空输入抹掉。
 */
internal class AiConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)
    private val secure = SecureKeyStore(context)

    fun load(): ProviderConfig = ProviderConfig(
        id = prefs.getString(KEY_ID, ProviderConfig.ID_DEEPSEEK) ?: ProviderConfig.ID_DEEPSEEK,
        name = prefs.getString(KEY_NAME, "DeepSeek") ?: "DeepSeek",
        baseUrl = prefs.getString(KEY_BASE_URL, ProviderConfig.DEFAULT_BASE_URL)
            ?: ProviderConfig.DEFAULT_BASE_URL,
        model = prefs.getString(KEY_MODEL, ProviderConfig.DEFAULT_MODEL)
            ?: ProviderConfig.DEFAULT_MODEL,
        apiKey = runCatching { secure.read() }.getOrDefault(""),
        disableThinking = prefs.getBoolean(KEY_DISABLE_THINKING, true),
    )

    /**
     * 先写 Key 再写配置：Keystore 不可用时抛异常，且不会留下「baseUrl/model 已改、Key 还是旧的」
     * 这种半截状态。
     */
    fun save(config: ProviderConfig) {
        secure.write(config.apiKey)
        prefs.edit()
            .putString(KEY_ID, config.id)
            .putString(KEY_NAME, config.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
            .putBoolean(KEY_DISABLE_THINKING, config.disableThinking)
            .apply()
    }

    fun hasKey(): Boolean = load().apiKey.isNotBlank()

    private companion object {
        const val KEY_ID = "provider_id"
        const val KEY_NAME = "provider_name"
        const val KEY_BASE_URL = "provider_base_url"
        const val KEY_MODEL = "provider_model"
        const val KEY_DISABLE_THINKING = "disable_thinking"
    }
}
