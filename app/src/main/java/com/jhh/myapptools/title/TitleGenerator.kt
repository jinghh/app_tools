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
internal class TitleGenerator(private val client: AiChatClient) {

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
    ): String = buildString {
        append("你是短视频爆款标题策划。写出来的标题必须像真人随手发的，不能像 AI 或公众号写的。\n\n")
        append("【素材】\n")
        // 描述框已默认预填文件名，此时不再重复传一份，避免模型当成两条不同信息
        val baseName = sourceName.substringBeforeLast('.', sourceName)
        val alreadyInDesc = baseName.length >= 3 &&
            params.description.contains(baseName, ignoreCase = true)
        if (!alreadyInDesc) append("原视频标题/文件名：").append(sourceName).append('\n')
        append("用户补充描述：").append(params.description.ifBlank { "无" }).append('\n')
        append("关键帧共 ").append(frameFiles.size).append(" 张，按时间顺序排列。\n\n")
        append("【什么样的标题合格】\n")
        append("1. 有具体的东西：数字、物件、动作、场景、结果，至少出现一个；不要通篇抽象情绪词。\n")
        append("2. 有真实的反应：像人对着手机随口说的，短句、口语、可以带语气词。\n")
        append("3. 看完标题能知道视频大概讲什么、为什么值得点开。\n\n")
        append("【AI 味禁用清单（违反任意一条就不合格）】\n")
        append("- 不要对仗排比：不是…而是…、与其…不如…、既…又…\n")
        append("- 不要抽象升华：藏着…的秘密、生活的真相、成年人最…、这才是…\n")
        append("- 不要万能结尾：我直接沉默了、看完只想…、谁懂啊、破防了\n")
        append("- 不要精致文艺：绝美、治愈、震撼、一眼万年\n")
        append("- 不要每条都套「第N个…」模板，不要通篇破折号和引号\n")
        append("- 不要 10 条一个腔调，要像同一个真人在不同时刻随口发的\n\n")
        append("【反面例子（AI 味，绝不能这样写）】\n")
        append("× 海边这场日落藏着成年人最舍不得说出口的秘密，看完只想立刻给家里打个电话\n")
        append("× 开箱3款网红产品，前两款我都想退货，直到第3个打开那一刻，我直接沉默了整整十秒\n")
        append("× 小狗在草地上奔跑，治愈了我一整周的班味\n")
        append("以上看似热闹，其实全是套话，一律不合格。\n\n")
        append("【正面例子（真人味，照这个粗糙程度写，不要照抄内容）】\n")
        append("✓ 随手拍的海边日落，评论区全在问我在哪，我把定位放在最后一条了，别再私信问了\n")
        append("✓ 3个网红产品开箱，第2个我拆开就扔了，纯纯浪费钱，剩下两个倒是真香，链接放评论区了\n")
        append("✓ 我家狗跑步同手同脚，邻居以为它喝多了，我笑了整整一天，发朋友圈又被笑了三天\n\n")
        append("【硬性规则】\n")
        append("1. 每条去掉标点和空格后的字符数必须在 ")
            .append(params.minChars).append("~").append(params.maxChars)
            .append(" 之间；汉字、字母、数字、emoji 各算 1 个字符。\n")
        append("2. 这是长标题，但要用多个短促口语分句连起来，不要写成精致长句；宁可粗糙，不要文艺。\n")
        append("3. 10 条不要重复句式和套路，但不要为了凑角度而写得不自然；优先写不同的具体细节。\n")
        append("4. 不编造素材没有的事实；不用「震惊」「必看」「家人们」等烂词；不以「视频展示了」「画面中」「这是一个」开头。\n")
        if (fixItems.isNotEmpty()) {
            append("\n【上一轮字数不达标，必须重写】\n")
            fixItems.forEachIndexed { i, item ->
                append("${i + 1}. [当前 ${item.charCount} 字] ${item.text}\n")
            }
            append("请把它们重写成每条 ").append(params.minChars).append("~").append(params.maxChars)
                .append(" 字的标题，按上面正面例子的粗糙口语风格写，不要 AI 味。\n")
            append("只输出重写后的 JSON 字符串数组，条数和顺序与上面列出的条目一致。\n")
        } else {
            append("\n只输出一个 JSON 字符串数组，不要 markdown、不要解释，共 10 条。\n")
        }
        if (round > 0) append("这是第 ${round + 1} 次生成，请勿重复上一轮的措辞和套路。\n")
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
