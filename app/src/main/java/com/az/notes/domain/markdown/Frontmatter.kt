package com.az.notes.domain.markdown

/** 单条属性：key + 一个或多个值（多值来自 YAML 列表写法）。 */
data class FrontmatterAttribute(
    val key: String,
    val values: List<String>
)

/**
 * frontmatter 拆分结果：[entries] 属性列表（保序）、[prefix] 原文前缀（含结束分隔行与换行）、
 * [body] 正文。无合法属性块时 [prefix] 为空串、[body] 为原文。
 */
data class FrontmatterSplit(
    val entries: List<FrontmatterAttribute>,
    val prefix: String,
    val body: String
) {
    /** 查询属性的首个值（如 note_type）；无该属性时返回 null。 */
    fun value(key: String): String? =
        entries.firstOrNull { it.key == key }?.values?.firstOrNull()
}

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
 * 的属性块：首行 `---` 起始、`---` 闭合、块内按 `key: value` 解析；
 * 键后无值时可跟缩进的 `- 值` 行组成多值（YAML 列表）：
 * ```
 * tags:
 *   - tag
 *   - 生日快乐
 * ```
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
            ?: return FrontmatterSplit(emptyList(), "", normalized)
        val entries = ArrayList<FrontmatterAttribute>()
        var i = 1
        while (i < end) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) {
                i++
                continue
            }
            val key = line.substring(0, colon).trim()
            if (key.isEmpty()) {
                i++
                continue
            }
            val inline = line.substring(colon + 1).trim()
            if (inline.isNotEmpty()) {
                entries += FrontmatterAttribute(key, listOf(inline))
                i++
            } else {
                // 键后换行的 YAML 列表：收集后续缩进的 "- 值" 行
                val values = ArrayList<String>()
                var j = i + 1
                while (j < end) {
                    val item = lines[j].trim()
                    if (item == "-") {
                        values += ""
                        j++
                    } else if (item.startsWith("- ")) {
                        values += item.substring(2).trim()
                        j++
                    } else {
                        break
                    }
                }
                entries += FrontmatterAttribute(key, values)
                i = if (values.isEmpty()) i + 1 else j
            }
        }
        val prefix = lines.take(end + 1).joinToString("\n") + "\n"
        val body = lines.drop(end + 1).joinToString("\n")
        return FrontmatterSplit(entries, prefix, body)
    }

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
