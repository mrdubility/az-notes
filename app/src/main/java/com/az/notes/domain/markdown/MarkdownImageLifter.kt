package com.az.notes.domain.markdown

/**
 * 预览渲染预处理：把「嵌在段落里的图片」抽出成独立块。
 *
 * 存在理由（对齐 multiplatform-markdown-renderer 0.35.0 的机制）：
 * 库对 PARAGRAPH 内的 IMAGE 走 inline [androidx.compose.foundation.text.InlineTextContent]
 * 占位机制，占位高度参与所在行的行高但不撑高整段——竖图（intrinsicSize 高约 1500~2400 px
 * 折算数百 sp）时表现为「图片只显示上半部分、下方文字看不到、LazyColumn 内容不足以滚动」。
 * 而当图片自身就是完整段落（前后有空行），段落 item 高度=占位高度，图片可完整显示、
 * 下方文字与滚动均正常。
 *
 * 保守原则：仅处理**普通段落行**，跳过以下上下文，避免破坏语义结构：
 *  - 列表项（`- ` / `* ` / `+ ` / `1. `）
 *  - 引用块（`> `）
 *  - 标题（`# ` ~ `###### `）
 *  - 表格行（含 `|`）
 *  - 围栏代码块（``` / ~~~ 内部）
 *
 * 纯 Kotlin、无 Android 依赖，可在 JVM 单元测试运行。
 */
object MarkdownImageLifter {

    /** 行内图片 markdown：`![alt](url)`；url 内不允许出现右括号（CommonMark 允许转义但笔记场景罕见）。 */
    private val imageMd = Regex("""!\[[^\]]*]\([^)]*\)""")

    /** 特殊行前缀：命中则本行整体不动（列表/引用/标题/表格/围栏）。 */
    private val specialLine = Regex("""^\s*(?:([-*+]|\d+[.)])\s|>|#{1,6}\s|\||```|~~~)""")

    /**
     * 主入口：对整篇 markdown 做段落内图片抽离。
     * 若原文不含图片语法，直接返回原串（避免无谓拷贝）。
     */
    fun lift(markdown: String): String {
        if (markdown.isEmpty() || !imageMd.containsMatchIn(markdown)) return markdown

        val lines = markdown.lines()
        val out = StringBuilder(markdown.length + 32)
        var inFence = false

        for ((idx, raw) in lines.withIndex()) {
            // 代码围栏切换：进入/退出的这行本身也要原样保留
            if (isFenceToggle(raw)) inFence = !inFence
            val eligible = !inFence && imageMd.containsMatchIn(raw) && !specialLine.containsMatchIn(raw)

            if (!eligible) {
                out.append(raw)
                if (idx != lines.lastIndex) out.append('\n')
                continue
            }

            // 图文混排 → 按图片位置切段；纯图片行 → 保持内容不变，仅在前后补空行
            val pieces = splitAroundImages(raw)
            // 上一段末尾若非空行，补空行让图片独立成段
            if (out.isNotEmpty() && !endsWithBlankLine(out)) out.append("\n\n")
            out.append(pieces.joinToString("\n\n"))
            // 与下一段之间也留出一个空行（下一轮循环直接 append 下一 raw 时会紧跟其后）
            out.append("\n\n")
        }

        return collapseExtraBlankLines(out.toString(), markdown)
    }

    // ---------------------------------------------------------------- 内部

    private fun isFenceToggle(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("```") || t.startsWith("~~~")
    }

    /** 尾部是否存在至少一个空行（即末尾有连续两个 `\n`）。 */
    private fun endsWithBlankLine(sb: StringBuilder): Boolean {
        var i = sb.length - 1
        var count = 0
        while (i >= 0 && sb[i] == '\n') {
            count++
            i--
        }
        return count >= 2
    }

    /**
     * 按图片匹配位置切分：返回按原顺序排列的「文字段 / 图片 / 文字段 / 图片 ...」列表。
     * 每个元素都是一段独立 markdown（图片元素就是原图 markdown，文字元素是去空白后的行片段）。
     */
    private fun splitAroundImages(line: String): List<String> {
        var cursor = 0
        val parts = mutableListOf<String>()
        for (m in imageMd.findAll(line)) {
            if (m.range.first > cursor) {
                val before = line.substring(cursor, m.range.first).trim()
                if (before.isNotEmpty()) parts += before
            }
            parts += m.value
            cursor = m.range.last + 1
        }
        val tail = line.substring(cursor).trim()
        if (tail.isNotEmpty()) parts += tail
        return parts
    }

    /** 折叠 3+ 连续换行为 2，并按原文件末尾换行风格对齐（先裁末尾空白再按原风格补一个 \n）。 */
    private fun collapseExtraBlankLines(text: String, original: String): String {
        val collapsed = text.replace(Regex("""\n{3,}"""), "\n\n").trimEnd('\n')
        return if (original.endsWith("\n")) collapsed + "\n" else collapsed
    }
}
