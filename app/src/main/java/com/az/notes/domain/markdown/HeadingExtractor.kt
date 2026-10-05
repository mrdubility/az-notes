package com.az.notes.domain.markdown

/**
 * 从 Markdown 源文本提取标题大纲（§5.4）。
 *
 * 纯 Kotlin、无 Android 依赖，便于 JVM 单元测试。
 * 简化处理：忽略代码围栏(``` / ~~~)内的伪标题行。正式版本会改用
 * JetBrains Markdown parser 的 AST `Heading` 节点，接口保持不变。
 */
object HeadingExtractor {

    private val atxHeading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")

    fun extract(markdown: String): List<Heading> {
        val result = ArrayList<Heading>()
        var insideFence = false
        var blockIndex = 0
        markdown.lineSequence().forEachIndexed { lineNo, rawLine ->
            val line = rawLine
            val trimmed = line.trim()
            // 处理代码围栏开关
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                insideFence = !insideFence
            }
            if (!insideFence) {
                atxHeading.find(line)?.let { m ->
                    val level = m.groupValues[1].length
                    val text = m.groupValues[2].ifBlank { "(无标题)" }
                    result += Heading(
                        level = level,
                        text = text,
                        line = lineNo,
                        blockIndex = blockIndex
                    )
                }
            }
            // 粗略块计数：非空行视为一个块，用于近似锚点
            if (trimmed.isNotEmpty()) blockIndex++
        }
        return result
    }
}
