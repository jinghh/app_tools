package com.jhh.myapptools.title

import android.content.Context

/**
 * 爆款标题提示词模板。
 *
 * 占位符（会被实际值替换，用户编辑时请保留）：
 * - {{FILE_LINE}}  原视频标题/文件名那一行（无文件名时为空）
 * - {{DESCRIPTION}} 用户补充描述
 * - {{FRAME_COUNT}} 关键帧张数
 * - {{MIN_CHARS}} / {{MAX_CHARS}} 字数范围
 * - {{FIX_SECTION}} 字数不达标时的重写要求（首次生成时为空）
 * - {{OUTPUT_LINE}} 输出格式要求（含首次/重写的差异）
 * - {{ROUND_LINE}} 换一批时的去重提示（首次为空）
 */
internal object PromptDefaults {
    const val TEMPLATE: String = """你是短视频爆款标题策划。写出来的标题必须像真人随手发的，不能像 AI 或公众号写的。

【素材】
{{FILE_LINE}}用户补充描述：{{DESCRIPTION}}
关键帧共 {{FRAME_COUNT}} 张，按时间顺序排列。

【什么样的标题合格】
1. 有具体的东西：数字、物件、动作、场景、结果，至少出现一个；不要通篇抽象情绪词。
2. 有真实的反应：像人对着手机随口说的，短句、口语、可以带语气词。
3. 看完标题能知道视频大概讲什么、为什么值得点开。

【AI 味禁用清单（违反任意一条就不合格）】
- 不要对仗排比：不是…而是…、与其…不如…、既…又…
- 不要抽象升华：藏着…的秘密、生活的真相、成年人最…、这才是…
- 不要万能结尾：我直接沉默了、看完只想…、谁懂啊、破防了
- 不要精致文艺：绝美、治愈、震撼、一眼万年
- 不要每条都套「第N个…」模板，不要通篇破折号和引号
- 不要 10 条一个腔调，要像同一个真人在不同时刻随口发的

【反面例子（AI 味，绝不能这样写）】
× 海边这场日落藏着成年人最舍不得说出口的秘密，看完只想立刻给家里打个电话
× 开箱3款网红产品，前两款我都想退货，直到第3个打开那一刻，我直接沉默了整整十秒
× 小狗在草地上奔跑，治愈了我一整周的班味
以上看似热闹，其实全是套话，一律不合格。

【正面例子（真人味，照这个粗糙程度写，不要照抄内容）】
✓ 随手拍的海边日落，评论区全在问我在哪，我把定位放在最后一条了，别再私信问了
✓ 3个网红产品开箱，第2个我拆开就扔了，纯纯浪费钱，剩下两个倒是真香，链接放评论区了
✓ 我家狗跑步同手同脚，邻居以为它喝多了，我笑了整整一天，发朋友圈又被笑了三天

【硬性规则】
1. 每条去掉标点和空格后的字符数必须在 {{MIN_CHARS}}~{{MAX_CHARS}} 之间；汉字、字母、数字、emoji 各算 1 个字符。
2. 这是长标题，但要用多个短促口语分句连起来，不要写成精致长句；宁可粗糙，不要文艺。
3. 10 条不要重复句式和套路，但不要为了凑角度而写得不自然；优先写不同的具体细节。
4. 不编造素材没有的事实；不用「震惊」「必看」「家人们」等烂词；不以「视频展示了」「画面中」「这是一个」开头。
{{FIX_SECTION}}{{OUTPUT_LINE}}{{ROUND_LINE}}"""

    val PLACEHOLDERS: List<String> = listOf(
        "{{FILE_LINE}}", "{{DESCRIPTION}}", "{{FRAME_COUNT}}",
        "{{MIN_CHARS}}", "{{MAX_CHARS}}", "{{FIX_SECTION}}",
        "{{OUTPUT_LINE}}", "{{ROUND_LINE}}",
    )
}

/** 提示词模板持久化：没自定义过就用内置默认 */
internal class PromptStore(context: Context) {
    private val prefs = context.getSharedPreferences("title_prompt", Context.MODE_PRIVATE)

    fun load(): String = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: PromptDefaults.TEMPLATE

    fun save(template: String) {
        prefs.edit().putString(KEY, template).apply()
    }

    /** 恢复默认：删除自定义值，回到内置模板 */
    fun reset() {
        prefs.edit().remove(KEY).apply()
    }

    fun isCustomized(): Boolean = prefs.getString(KEY, null)?.isNotBlank() == true

    private companion object {
        const val KEY = "prompt_template"
    }
}
