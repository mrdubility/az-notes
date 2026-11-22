package com.az.notes.domain.markdown

/**
 * 单条原始双链引用（未解析）：只描述"原文里写了什么"，不含"指向哪里"。
 * [snippet] 为该行去掉双链标记后的可读上下文，可直接作为反向链接列表的副标题。
 */
data class RawLinkRef(
    /** 所在行号（0-based，按 frontmatter 剥离后的正文计） */
    val line: Int,
    /** 原文切片（如 `[[A#H|别名]]`） */
    val raw: String,
    /** 目标（未含 `#` 段）；空串表示 `[[#标题]]` 页内自锚点 */
    val target: String,
    val anchor: String?,
    val alias: String?,
    /** `![[...]]` 嵌入标记 */
    val isEmbed: Boolean,
    /** 该行可读上下文（已折叠空白并截断） */
    val snippet: String
)

/**
 * 双链语法的**提取侧**（与 [WikiLinkTransformer] 的转换侧共用同一份正则与保护规则）。
 *
 * 存在理由：预览正文是转换后的产物（`[[X]]` 已变成 `[X](az-note:...)`），
 * 反向索引与出链列表必须回到磁盘原文提取，否则一条都取不到。
 *
 * 保护规则与转换器逐条对齐：围栏代码块 / 行内代码 / `\[[` 转义 / 未闭合均不提取；
 * **标题行照常提取**（标题内链接可点击，与标签的"标题内不识别"规则相反）。
 *
 * 纯 Kotlin、零 IO，可在 JVM 单元测试运行。
 */
object WikiLinkExtractor {

    /** 单篇引用数上限（安全阀）：超长文件过滤链式噪声，避免索引被单篇撑爆。 */
    const val MAX_REFS_PER_NOTE = 500

    /** 上下文片段最大字符数。 */
    const val SNIPPET_MAX_CHARS = 160

    /** `[[#标题]]` 页内自锚点：出链与反向链接都不计入（已由大纲承担）。 */
    fun isSelfAnchor(ref: RawLinkRef): Boolean = ref.target.isEmpty()

    /** 逐行提取（围栏内原样跳过）。 */
    fun extract(body: String): List<RawLinkRef> {
        if (body.isEmpty() || !body.contains("[[")) return emptyList()
        val out = ArrayList<RawLinkRef>()
        var inFence = false
        var line = 0
        for (text in body.split('\n')) {
            if (out.size >= MAX_REFS_PER_NOTE) break
            if (WikiLinkTransformer.isFenceToggle(text)) inFence = !inFence
            if (!inFence && text.contains("[[")) collect(line, text, out)
            line++
        }
        return out
    }

    /**
     * 把一行里的双链标记替换为可读文本（`[[A|B]]` → `B`、`[[A#H]]` → `A > H`），
     * 折叠连续空白并按 [maxChars] 截断，供反向链接列表展示上下文。
     */
    fun plainLine(line: String, maxChars: Int = SNIPPET_MAX_CHARS): String {
        val readable = if (!line.contains("[[")) {
            line
        } else {
            WikiLinkTransformer.WIKI.replace(line) { m ->
                val target = m.groupValues[2].trim()
                val anchor = m.groupValues[3].trim()
                val alias = m.groupValues[4].trim()
                when {
                    alias.isNotEmpty() -> alias
                    target.isEmpty() -> anchor
                    anchor.isNotEmpty() -> "$target > $anchor"
                    else -> target
                }
            }
        }
        val collapsed = WHITESPACE_RUN.replace(readable, " ").trim()
        return if (collapsed.length <= maxChars) collapsed else collapsed.take(maxChars) + "…"
    }

    // ---------------------------------------------------------------- 内部

    private val WHITESPACE_RUN = Regex("\\s+")

    private fun collect(line: Int, text: String, out: MutableList<RawLinkRef>) {
        if (!text.contains("]]")) return
        val codeSpans = WikiLinkTransformer.INLINE_CODE.findAll(text).map { it.range }.toList()
        for (m in WikiLinkTransformer.WIKI.findAll(text)) {
            if (out.size >= MAX_REFS_PER_NOTE) return
            val start = m.range.first
            // `\[[` 转义不提取
            if (start > 0 && text[start - 1] == '\\') continue
            // 行内代码内不提取
            if (codeSpans.any { start in it }) continue
            val target = m.groupValues[2].trim()
            val anchor = m.groupValues[3].trim().ifEmpty { null }
            if (target.isEmpty() && anchor == null) continue // `[[#]]` 空目标无意义
            out += RawLinkRef(
                line = line,
                raw = m.value,
                target = target,
                anchor = anchor,
                alias = m.groupValues[4].trim().ifEmpty { null },
                isEmbed = m.groupValues[1] == "!",
                snippet = plainLine(text)
            )
        }
    }
}
