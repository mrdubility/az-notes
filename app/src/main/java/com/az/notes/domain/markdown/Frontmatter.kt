package com.az.notes.domain.markdown

/**
 * frontmatter 拆分结果：[attributes] 属性表、[prefix] 原文前缀（含结束分隔行与换行）、[body] 正文。
 * 无合法属性块时 [prefix] 为空串、[body] 为原文。
 */
data class FrontmatterSplit(
    val attributes: Map<String, String>,
    val prefix: String,
    val body: String
)

/**
 * 笔记头部属性（frontmatter）解析。
 *
 * 识别笔记开头形如：
 * ```
 * ---
 * tag: log
 * note_type: task
 * ---
 * ```
 * 的属性块：首行 `---` 起始、`---` 闭合、块内按 `key: value` 解析。
 * 首行不是 `---` 或未在 [MAX_SCAN_LINES] 行内闭合时，整篇按普通正文处理。
 */
object FrontmatterParser {

    /** 结束分隔行扫描上限：防止把正文中的水平分隔线误当属性块。 */
    private const val MAX_SCAN_LINES = 60

    private const val DELIMITER = "---"

    /** 拆分 [content]；写回时用 `prefix + body` 可无损还原原文。 */
    fun split(content: String): FrontmatterSplit {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val end = endDelimiterIndex(lines)
            ?: return FrontmatterSplit(emptyMap(), "", normalized)
        val attributes = LinkedHashMap<String, String>()
        for (i in 1 until end) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim()
            if (key.isEmpty()) continue
            attributes[key] = line.substring(colon + 1).trim()
        }
        val prefix = lines.take(end + 1).joinToString("\n") + "\n"
        val body = lines.drop(end + 1).joinToString("\n")
        return FrontmatterSplit(attributes, prefix, body)
    }

    /** 解析属性表；无合法属性块时返回空表。 */
    fun parseAttributes(content: String): Map<String, String> = split(content).attributes

    /** 剥离 frontmatter 返回正文；无属性块时返回原文（换行已规范化为 LF）。 */
    fun strip(content: String): String = split(content).body

    /** 定位结束分隔行下标（0 基）；首行不是 `---` 或未闭合时返回 null。 */
    private fun endDelimiterIndex(lines: List<String>): Int? {
        if (lines.isEmpty() || lines[0].trimEnd() != DELIMITER) return null
        val limit = minOf(lines.size, MAX_SCAN_LINES + 1)
        for (i in 1 until limit) {
            if (lines[i].trimEnd() == DELIMITER) return i
        }
        return null
    }
}
