package com.jhh.myapptools.ai

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenAI 兼容 Chat Completions 的极简客户端（HttpURLConnection + org.json，零新增依赖）。
 *
 * 只做一件事：把「文本 + 本地 JPEG」发到 [ProviderConfig.chatUrl]，拿回 assistant 文本。
 * 不耦合任何业务 prompt；后续其他功能（文案改写、字幕总结等）可直接复用。
 *
 * 取消语义：本类为阻塞调用，调用方必须在 IO 线程执行；[cancel] 会断开当前连接，
 * 使阻塞的 read 立刻抛 IOException，再统一映射为 [ChatOutcome.Cancelled]。
 */
internal class AiChatClient {

    private val active = AtomicReference<HttpURLConnection?>(null)

    /**
     * 每次 chat 领取一个 token，cancel 让当前 token 失效。
     * 不用布尔标志：新请求若重置标志，仍在读图/序列化阶段的旧请求会「复活」并重复扣费。
     */
    private val generation = AtomicLong(0)

    fun cancel() {
        generation.incrementAndGet()
        active.getAndSet(null)?.disconnect()
    }

    fun chat(config: ProviderConfig, request: ChatRequest): ChatOutcome {
        val token = generation.incrementAndGet()
        fun isCancelled(): Boolean = generation.get() != token

        if (config.apiKey.isBlank()) {
            return ChatOutcome.Failure("尚未配置 API Key", ChatOutcome.Failure.Kind.INVALID_CONFIG)
        }
        val url = runCatching { URL(config.chatUrl) }.getOrElse {
            return ChatOutcome.Failure("接口地址无效：${config.chatUrl}", ChatOutcome.Failure.Kind.INVALID_CONFIG)
        }

        val body = buildBody(config.model, request, config.disableThinking)
        if (isCancelled()) return ChatOutcome.Cancelled
        var conn: HttpURLConnection? = null
        try {
            conn = url.openConnection() as HttpURLConnection
            if (isCancelled()) {
                conn.disconnect()
                return ChatOutcome.Cancelled
            }
            active.set(conn)
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (isCancelled()) return ChatOutcome.Cancelled

            val code = conn.responseCode
            val raw = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

            if (isCancelled()) return ChatOutcome.Cancelled
            if (code !in 200..299) return failureOf(code, raw)

            val content = parseContent(raw)
            return if (content.isNullOrBlank()) {
                ChatOutcome.Failure(
                    "模型返回内容为空（${emptyContentDiagnostic(raw)}）。响应片段：${raw.take(160)}",
                    ChatOutcome.Failure.Kind.PARSE,
                )
            } else {
                ChatOutcome.Success(content)
            }
        } catch (e: IOException) {
            return if (isCancelled()) {
                ChatOutcome.Cancelled
            } else {
                ChatOutcome.Failure(
                    "网络连接失败：${e.message ?: e.javaClass.simpleName}",
                    ChatOutcome.Failure.Kind.NETWORK,
                )
            }
        } catch (e: Exception) {
            return ChatOutcome.Failure(
                e.message ?: e.javaClass.simpleName,
                ChatOutcome.Failure.Kind.UNKNOWN,
            )
        } finally {
            active.compareAndSet(conn, null)
            conn?.disconnect()
        }
    }

    private fun buildBody(model: String, request: ChatRequest, disableThinking: Boolean): String {
        val imageParts = request.images.mapNotNull { file ->
            runCatching {
                val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            // detail=low 缩放到 512×512，更快更省；标题生成不需要高分辨率细节
                            .put("url", "data:image/jpeg;base64,$encoded")
                            .put("detail", "low"),
                    )
            }.getOrNull()
        }

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", request.prompt))
            .apply { imageParts.forEach { put(it) } }

        return JSONObject()
            .put("model", model)
            .put("temperature", request.temperature)
            .put("max_tokens", request.maxTokens)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .apply {
                if (disableThinking) {
                    // DeepSeek V4 默认开思考，思考 token 会先吃光 max_tokens 导致 content 为空
                    put("thinking", JSONObject().put("type", "disabled"))
                }
            }
            .toString()
    }

    private fun failureOf(code: Int, raw: String): ChatOutcome.Failure {
        val detail = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull().takeUnless { it.isNullOrBlank() } ?: raw.take(200)

        val kind = when (code) {
            401, 403 -> ChatOutcome.Failure.Kind.AUTH
            408, 429 -> ChatOutcome.Failure.Kind.RATE_LIMIT
            in 500..599 -> ChatOutcome.Failure.Kind.SERVER
            else -> ChatOutcome.Failure.Kind.UNKNOWN
        }
        val prefix = when (kind) {
            ChatOutcome.Failure.Kind.AUTH -> "API Key 无效或无权访问该模型"
            ChatOutcome.Failure.Kind.RATE_LIMIT -> "请求过于频繁或额度不足"
            ChatOutcome.Failure.Kind.SERVER -> "服务商暂时不可用"
            else -> "请求失败（HTTP $code）"
        }
        return ChatOutcome.Failure("$prefix：$detail", kind)
    }

    /** 诊断 HTTP 200 但 content 为空的原因（思考模式吃额度 / 被截断等） */
    private fun emptyContentDiagnostic(raw: String): String = runCatching {
        val choice = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
        val reason = choice?.optString("finish_reason") ?: "未知"
        val hasReasoning = choice?.optJSONObject("message")
            ?.optString("reasoning_content")
            ?.isNotBlank() == true
        buildString {
            append("finish_reason=$reason")
            if (hasReasoning) append("，检测到 reasoning_content（思考占用了输出额度）")
        }
    }.getOrDefault("解析响应失败")

    private fun parseContent(raw: String): String? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        return when (val c = message.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).mapNotNull { i ->
                c.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("\n").ifBlank { null }
            else -> null
        }
    }

}
