package com.az.notes.domain.markdown

/**
 * `#标签` 识别与渲染前转换：把正文中的合法标签改写为标准 Markdown 链接
 * `[#tag](az-tag:tag)`，交由既有渲染链消费（渲染库不识别 `#tag` 语法，会当普通文本），
 * 点击由 UI 层拦截 `az-tag:` scheme 跳标签结果页。
 *
 * 识别口径（对齐 Obsidian）：
 *  - 字符集为字母、数字、`_`、`-`、`/`（嵌套标签），至少一个非数字字符（`#1984` 不算），
 *    最长 64 字符，超长整段不识别；
 *  - `#` 前不能是 ASCII 字母数字（`abc#tag` 不算），也不能是 URL 常见前导字符
 *    （`/` `:` `?` `=` `&`，挡链接锚点误伤）；中文等非 ASCII 字符紧贴（「中文#标签」）
 *    仍可识别；
 *  - 代码围栏（``` / ~~~）与行内代码内、Markdown 链接目标括号与尖括号自动链接内、
 *    标题行（ATX / Setext，保护大纲取同源文本）内不识别；
 *  - `\#` 转义不识别。
 *
 * 纯 Kotlin、无 Android 依赖，可在 JVM 单元测试运行；不做任何文件 IO。
 * [extract]（索引聚合）与 [transform]（渲染）共用同一套保护口径，保证
 * 计数与可点击范围一致。
 */
object TagMatcher {

    /** 标签链接的私有 scheme：转换产物经它携带标签名，点击时由 UI 层拦截。 */
    const val LINK_SCHEME = "az-tag:"

    /** 标签名（不含 `#`）：字符集 + 长度上限。 */
    private val TAG_NAME = Regex("""[\p{L}\p{N}_\-/]{1,64}""")

    /** 行内标签：前导约束（仅 ASCII 字母数字与 URL 常见字符阻断）+ 尾随合法字符整体截断（超长不吞半截）。 */
    private val INLINE_TAG =
        Regex("""(?<![A-Za-z0-9/:?=&])#([\p{L}\p{N}_\-/]{1,64})(?![\p{L}\p{N}_\-/])""")

    /** 行内代码片段（反引号成对包裹，支持多反引号）。 */
    private val INLINE_CODE = Regex("`+[^`\n]*`+")

    /** ATX 标题行（`#` 后跟空白或行尾）。 */
    private val ATX_HEADING = Regex("""^ {0,3}#{1,6}([ \t]|$)""")

    /** Setext 标题下划线行。 */
    private val SETEXT_UNDERLINE = Regex("""^ {0,3}(=+|-+)[ \t]*$""")

    /** 标签名是否合法（供 frontmatter 值与补全候选复用）。 */
    fun isValid(tag: String): Boolean =
        TAG_NAME.matches(tag) && tag.any { it.isLetter() || it == '_' || it == '-' || it == '/' }

    /** 大小写归一化（Obsidian 标签大小写不敏感，`#Tag` 与 `#tag` 合并）。 */
    fun keyOf(tag: String): String = tag.lowercase()

    /** 命中片段：[tag] 不含 `#`；[start]/[end] 为原文偏移（含 `#`，end 不包含）。 */
    data class TagMatch(val tag: String, val start: Int, val end: Int)

    /** 提取全文中的全部合法标签（与 [transform] 同一套保护口径）。 */
    fun extract(content: String): List<TagMatch> {
        if (content.isEmpty() || content.indexOf('#') < 0) return emptyList()
        val out = ArrayList<TagMatch>()
        val lines = content.split('\n')
        var lineStart = 0
        var inFence = false
        for (idx in lines.indices) {
            val line = lines[idx]
            if (isFenceToggle(line)) inFence = !inFence
            if (!inFence && line.indexOf('#') >= 0 && !isHeadingLine(lines, idx)) {
                for (m in INLINE_TAG.findAll(line)) {
                    val start = m.range.first
                    if (start > 0 && line[start - 1] == '\\') continue
                    val tag = m.groupValues[1]
                    if (!isValid(tag)) continue
                    if (inProtectedSpan(line, start)) continue
                    out += TagMatch(tag, lineStart + start, lineStart + m.range.last + 1)
                }
            }
            lineStart += line.length + 1
        }
        return out
    }

    /** 渲染前转换：正文合法标签 → `[#tag](az-tag:tag)`；不含 `#` 原样返回。 */
    fun transform(markdown: String): String {
        if (markdown.isEmpty() || markdown.indexOf('#') < 0) return markdown
        val lines = markdown.split('\n')
        val out = StringBuilder(markdown.length + 24)
        var inFence = false
        for (idx in lines.indices) {
            val line = lines[idx]
            if (idx > 0) out.append('\n')
            if (isFenceToggle(line)) inFence = !inFence
            if (inFence || line.indexOf('#') < 0 || isHeadingLine(lines, idx)) {
                out.append(line)
            } else {
                out.append(transformLine(line))
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- 内部

    private fun transformLine(line: String): String {
        val codeSpans = INLINE_CODE.findAll(line).map { it.range }.toList()
        val protected = protectedSpans(line)
        var cursor = 0
        var out: StringBuilder? = null
        for (m in INLINE_TAG.findAll(line)) {
            val start = m.range.first
            if (start > 0 && line[start - 1] == '\\') continue
            val tag = m.groupValues[1]
            if (!isValid(tag)) continue
            if (codeSpans.any { start in it } || protected.any { start in it }) continue
            val sb = out ?: StringBuilder(line.length + 16).also { out = it }
            sb.append(line, cursor, start)
                .append("[#").append(tag).append("](").append(LINK_SCHEME)
                .append(ImageReference.encodeTarget(tag, escapePercent = true)).append(')')
            cursor = m.range.last + 1
        }
        return (out ?: return line).append(line, cursor, line.length).toString()
    }

    /** 命中位置是否处于行内代码 / 链接目标括号 / 尖括号自动链接内。 */
    private fun inProtectedSpan(line: String, position: Int): Boolean {
        if (INLINE_CODE.containsMatchIn(line) && INLINE_CODE.findAll(line).any { position in it.range }) {
            return true
        }
        return protectedSpans(line).any { position in it }
    }

    /** 本行的保护区间：Markdown 链接目标 `](...)` 与尖括号自动链接 `<...>`。 */
    private fun protectedSpans(line: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (true) {
            val open = line.indexOf("](", i)
            if (open < 0) break
            val close = line.indexOf(')', open + 2)
            if (close < 0) break
            out += open..close
            i = close + 1
        }
        i = 0
        while (true) {
            val open = line.indexOf('<', i)
            if (open < 0) break
            val close = line.indexOf('>', open + 1)
            if (close < 0) break
            out += open..close
            i = close + 1
        }
        return out
    }

    /** 当前行是否为标题行：ATX（`# 标题`）或 Setext（下一行是 `===` / `---`）。 */
    private fun isHeadingLine(lines: List<String>, idx: Int): Boolean {
        if (ATX_HEADING.containsMatchIn(lines[idx])) return true
        if (lines[idx].isBlank()) return false
        val next = lines.getOrNull(idx + 1) ?: return false
        return SETEXT_UNDERLINE.matches(next)
    }

    private fun isFenceToggle(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("```") || t.startsWith("~~~")
    }
}
