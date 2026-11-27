package com.jhh.myapptools.title

import com.jhh.myapptools.ai.AiChatClient
import com.jhh.myapptools.ai.ChatOutcome
import com.jhh.myapptools.ai.ChatRequest
import com.jhh.myapptools.ai.ProviderConfig
import org.json.JSONArray
import java.io.File

internal data class TitleParams(
    val minChars: Int,
    val maxChars: Int,
    /** 用户对资源的补充描述；可为空 */
    val description: String,
)

internal data class TitleItem(
    val text: String,
    /** 去除标点与空白后的字符数（汉字/字母/数字/emoji 各算 1） */
    val charCount: Int,
    val valid: Boolean,
)

internal data class TitleBatch(
    val items: List<TitleItem>,
    val round: Int,
)

internal sealed interface GenerateOutcome {
    data class Success(val batch: TitleBatch) : GenerateOutcome
    data class Failure(val message: String) : GenerateOutcome
    data object Cancelled : GenerateOutcome
}

/** 字数统计：标点（中英文）与空白不计，emoji 按 1 个 codePoint 计 */
internal object TitleTextCount {
    private val PUNCT_TYPES: Set<Int> = setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
    )

    fun count(text: String): Int = text.codePoints()
        .filter { cp -> !Character.isWhitespace(cp) && Character.getType(cp) !in PUNCT_TYPES }
        .count()
        .toInt()
}

/**
 * 爆款标题生成：prompt 组装 → API 调用 → JSON 解析 → 字数校验。
 *
 * 本类为阻塞调用，调用方必须放到 IO 线程（与 GifEngine 的约定一致）。
 * 只在「全部不达标」时追加一次修正请求，避免为凑字数反复付费。
 */
internal class TitleGenerator(
    private val client: AiChatClient,
    /** 提示词模板提供者：默认实现返回内置模板，可接入 PromptStore 的用户自定义模板 */
    private val templateProvider: () -> String = { PromptDefaults.TEMPLATE },
) {

    fun generate(
        config: ProviderConfig,
        frameFiles: List<File>,
        sourceName: String,
        params: TitleParams,
        round: Int,
    ): GenerateOutcome {
        val first = request(config, frameFiles, sourceName, params, round, fixItems = emptyList())
        val texts = when (first) {
            is ChatOutcome.Success -> parseTitles(first.content)
            is ChatOutcome.Cancelled -> return GenerateOutcome.Cancelled
            is ChatOutcome.Failure -> return GenerateOutcome.Failure(first.message)
        }
        if (texts.isEmpty()) return GenerateOutcome.Failure("模型未返回可解析的标题，请重试或换一批")

        var items = texts.take(10).map { it.toItem(params) }

        // 只要有任意一条字数不达标就批量重写（最多 2 次），不能把一堆红字直接交给用户
        repeat(2) {
            val badIndices = items.indices.filter { !items[it].valid }
            if (badIndices.isEmpty()) return@repeat
            val badItems = badIndices.map { items[it] }
            val fix = request(config, frameFiles, sourceName, params, round, fixItems = badItems)
            val fixedTexts = when (fix) {
                is ChatOutcome.Success -> parseTitles(fix.content)
                is ChatOutcome.Cancelled -> return GenerateOutcome.Cancelled
                is ChatOutcome.Failure -> return@repeat
            }
            if (fixedTexts.isEmpty()) return@repeat
            items = if (fixedTexts.size >= badItems.size) {
                items.toMutableList().apply {
                    badIndices.forEachIndexed { k, idx -> this[idx] = fixedTexts[k].toItem(params) }
                }
            } else {
                items
            }
        }
        return GenerateOutcome.Success(TitleBatch(items, round))
    }

    fun cancel() = client.cancel()

    private fun request(
        config: ProviderConfig,
        frameFiles: List<File>,
        sourceName: String,
        params: TitleParams,
        round: Int,
        fixItems: List<TitleItem>,
    ): ChatOutcome = client.chat(
        config,
        ChatRequest(
            prompt = buildPrompt(frameFiles, sourceName, params, round, fixItems),
            images = frameFiles,
            temperature = 0.9,
            // 10 条 × 最多 100 字，中文 token 较密，留足输出额度（关思考后不会浪费在推理上）
            maxTokens = 4000,
        ),
    )

    private fun buildPrompt(
        frameFiles: List<File>,
        sourceName: String,
        params: TitleParams,
        round: Int,
        fixItems: List<TitleItem>,
    ): String {
        // 描述框已默认预填文件名，此时不再重复传一份，避免模型当成两条不同信息
        val baseName = sourceName.substringBeforeLast('.', sourceName)
        val fileLine = if (baseName.length >= 3 &&
            params.description.contains(baseName, ignoreCase = true)
        ) {
            ""
        } else {
            "原视频标题/文件名：$sourceName\n"
        }

        val fixSection = if (fixItems.isEmpty()) {
            ""
        } else {
            buildString {
                append("\n【上一轮字数不达标，必须重写】\n")
                fixItems.forEachIndexed { i, item ->
                    append("${i + 1}. [当前 ${item.charCount} 字] ${item.text}\n")
                }
                append("请把它们重写成每条 ${params.minChars}~${params.maxChars} 字的标题，")
                append("按上面正面例子的粗糙口语风格写，不要 AI 味。\n")
            }
        }
        val outputLine = if (fixItems.isEmpty()) {
            "\n只输出一个 JSON 字符串数组，不要 markdown、不要解释，共 10 条。\n"
        } else {
            "只输出重写后的 JSON 字符串数组，条数和顺序与上面列出的条目一致。\n"
        }
        val roundLine = if (round > 0) {
            "这是第 ${round + 1} 次生成，请勿重复上一轮的措辞和套路。\n"
        } else {
            ""
        }

        return templateProvider()
            .replace("{{FILE_LINE}}", fileLine)
            .replace("{{DESCRIPTION}}", params.description.ifBlank { "无" })
            .replace("{{FRAME_COUNT}}", frameFiles.size.toString())
            .replace("{{MIN_CHARS}}", params.minChars.toString())
            .replace("{{MAX_CHARS}}", params.maxChars.toString())
            .replace("{{FIX_SECTION}}", fixSection)
            .replace("{{OUTPUT_LINE}}", outputLine)
            .replace("{{ROUND_LINE}}", roundLine)
    }

    private fun String.toItem(params: TitleParams): TitleItem {
        val n = TitleTextCount.count(this)
        return TitleItem(text = this, charCount = n, valid = n in params.minChars..params.maxChars)
    }

    private fun parseTitles(raw: String): List<String> {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start >= 0 && end > start) {
            runCatching {
                val arr = JSONArray(cleaned.substring(start, end + 1))
                return (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, null)?.trim()?.takeIf { it.isNotBlank() }
                }
            }
        }
        // 降级：模型没按 JSON 输出时，按行解析并剥掉序号
        return cleaned.lines()
            .map { it.replace(Regex("^\\s*\\d+\\s*[.、)]\\s*"), "").trim() }
            .filter { it.isNotBlank() }
    }
}
