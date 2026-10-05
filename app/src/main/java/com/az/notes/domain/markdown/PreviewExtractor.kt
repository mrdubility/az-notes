package com.az.notes.domain.markdown

/**
 * 列表预览提取（主页笔记条目）：剥离常见 Markdown 标记，
 * 将多行正文压缩为单行纯文本，并按字符数截断（超出追加省略号）。
 * 仅用于列表速览，不追求完整语法的精确还原。
 */
object PreviewExtractor {

    /** ``` 或 ~~~ 围栏行 */
    private val CODE_FENCE = Regex("^\\s*(?:```|~~~).*$")

    /** 无序 / 有序列表行首标记 */
    private val LIST_MARKER = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+")

    /** 一级到六级标题行首标记 */
    private val HEADING_MARKER = Regex("^#{1,6}\\s+")

    /** 引用行首标记 */
    private val BLOCKQUOTE_MARKER = Regex("^\\s*>+\\s?")

    /** 分隔线（--- / *** / ___） */
    private val HORIZONTAL_RULE = Regex("^\\s*(?:[-*_]\\s*){3,}$")

    /** 图片：保留 alt 文本 */
    private val IMAGE = Regex("!\\[([^]]*)]\\([^)]*\\)")

    /** 链接：保留链接文字 */
    private val LINK = Regex("\\[([^]]*)]\\([^)]*\\)")

    /** 行内代码 / 粗体 / 斜体 / 删除线标记 */
    private val INLINE_MARKERS = Regex("(\\*\\*|__|~~|`+|\\*|_)")

    /** HTML 标签（简单剥离） */
    private val HTML_TAG = Regex("<[^>]+>")

    /** 连续空白（含换行）折叠为单个空格 */
    private val WHITESPACE = Regex("\\s+")

    /**
     * 提取 [markdown] 的预览文本，最多 [maxChars] 个字符。
     * 返回空字符串表示无可展示内容（如仅含空白）。
     */
    fun extract(markdown: String, maxChars: Int): String {
        if (markdown.isBlank() || maxChars <= 0) return ""

        val singleLine = markdown.lineSequence()
            .map { cleanLine(it) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        val plain = singleLine
            .replace(INLINE_MARKERS, "")
            .replace(WHITESPACE, " ")
            .trim()

        if (plain.isEmpty()) return ""
        return if (plain.length <= maxChars) plain
        else plain.take(maxChars).trimEnd() + "…"
    }

    /** 清洗单行：去掉行首的结构标记；返回空串表示该行不参与预览。 */
    private fun cleanLine(raw: String): String {
        val line = raw.trimEnd()
        if (line.isBlank()) return ""
        if (CODE_FENCE.matches(line)) return ""
        var cleaned = HORIZONTAL_RULE.replace(line, "").trim()
        if (cleaned.isEmpty()) return ""
        cleaned = HEADING_MARKER.replaceFirst(cleaned, "")
        cleaned = BLOCKQUOTE_MARKER.replaceFirst(cleaned, "")
        cleaned = LIST_MARKER.replaceFirst(cleaned, "")
        cleaned = IMAGE.replace(cleaned) { it.groupValues[1] }
        cleaned = LINK.replace(cleaned) { it.groupValues[1] }
        cleaned = HTML_TAG.replace(cleaned, "")
        return cleaned.trim()
    }
}
