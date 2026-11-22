package com.az.notes.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * 从 Markdown 源文本提取标题大纲与块结构（§5.4），基于 JetBrains Markdown AST。
 *
 * 与渲染库（mikepenz multiplatform-markdown-renderer）使用完全一致的解析链
 * （GFMFlavourDescriptor + MarkdownParser，同版本 org.jetbrains:markdown），
 * 因此 [Heading.blockIndex] 与预览页 LazyColumn 的块下标严格一一对应，
 * 大纲跳转/高亮即为真实锚点而非像素估算。
 *
 * 纯 Kotlin、无 Android 依赖，可在 JVM 单元测试运行。
 */
object HeadingExtractor {

    /** 解析结果：标题列表 + 顶层块总数（阅读进度百分比用）+ 块引用表。 */
    data class Result(
        val headings: List<Heading>,
        val blockCount: Int,
        /** `^id` 块标记 → 顶层块下标（供 `[[笔记#^id]]` 跳转，见 [parse]） */
        val blocks: Map<String, Int> = emptyMap()
    )

    /** 解析：标题（含 ATX 与 Setext 两种写法）+ 顶层块数量。 */
    fun parse(markdown: String): Result {
        val parser = MarkdownParser(GFMFlavourDescriptor())
        val blocks = parser.buildMarkdownTreeFromString(markdown).children
        // 单趟推进行号游标：块节点按文档序排列、startOffset 单调递增，
        // 无需为每个标题从头扫描全文（避免 O(n·标题数) 的平方级扫描）
        var line = 0
        var scanned = 0
        val headings = blocks.mapIndexedNotNull { index, node ->
            val level = node.headingLevel() ?: return@mapIndexedNotNull null
            val offset = node.startOffset.coerceAtMost(markdown.length)
            while (scanned < offset) {
                if (markdown[scanned] == '\n') line++
                scanned++
            }
            Heading(
                level = level,
                text = headingText(markdown, node),
                line = line,
                blockIndex = index
            )
        }
        return Result(headings = headings, blockCount = blocks.size, blocks = parseBlockIds(markdown, blocks))
    }

    /**
     * 块引用 `^id`：按行扫描块末尾的标记，再映射到包含该行的顶层块下标。
     *
     * 按行扫描而不切 AST 节点文本：行偏移与块偏移都是同一份源码坐标，映射稳定；
     * 判定条件：`^id` 前为行首或空白、后仅剩空白；代码块内的行整体跳过。
     * 同一 id 重复时取首个。
     */
    private fun parseBlockIds(
        markdown: String,
        blocks: List<ASTNode>
    ): Map<String, Int> {
        if (markdown.indexOf('^') < 0 || blocks.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>()
        var offset = 0
        for (line in markdown.split('\n')) {
            val id = trailingBlockId(line)
            if (id != null && id !in out) {
                val index = blocks.indexOfFirst { offset >= it.startOffset && offset < it.endOffset }
                if (index >= 0 && !blocks[index].isCodeBlock()) out[id] = index
            }
            offset += line.length + 1
        }
        return out
    }

    /** 行尾的 `^id`（`^` 前为行首或空白、其后仅剩空白）；无则 null。 */
    private fun trailingBlockId(line: String): String? {
        for (m in BLOCK_ID.findAll(line)) {
            val at = m.range.first
            val before = if (at > 0) line[at - 1] else ' '
            if (!before.isWhitespace()) continue
            if (line.substring(m.range.last + 1).isNotBlank()) continue
            return m.groupValues[1]
        }
        return null
    }

    private fun ASTNode.isCodeBlock(): Boolean =
        type == MarkdownElementTypes.CODE_FENCE || type == MarkdownElementTypes.CODE_BLOCK

    /** `^` 后的块 id（字母数字与 `-` `_`，上限 64）。不用锚点写法，避免跨实现歧义。 */
    private val BLOCK_ID = Regex("""\^([A-Za-z0-9_-]{1,64})""")

    /** 兼容旧入口：仅取标题列表。 */
    fun extract(markdown: String): List<Heading> = parse(markdown).headings

    /**
     * 按锚点文本定位标题（精确 → 忽略大小写 → 前缀），返回首个命中的块下标；
     * 无命中返回 null（调用方静默停留在原位）。
     */
    fun findBlockByTitle(headings: List<Heading>, title: String): Int? {
        val t = title.trim()
        if (t.isEmpty()) return null
        headings.firstOrNull { it.text.trim() == t }?.let { return it.blockIndex }
        headings.firstOrNull { it.text.trim().equals(t, ignoreCase = true) }?.let { return it.blockIndex }
        return headings.firstOrNull { it.text.trim().startsWith(t, ignoreCase = true) }?.blockIndex
    }

    /** 标题级别：ATX_1..6 / SETEXT_1..2，非标题返回 null。 */
    private fun ASTNode.headingLevel(): Int? = when (type) {
        MarkdownElementTypes.ATX_1 -> 1
        MarkdownElementTypes.ATX_2 -> 2
        MarkdownElementTypes.ATX_3 -> 3
        MarkdownElementTypes.ATX_4 -> 4
        MarkdownElementTypes.ATX_5 -> 5
        MarkdownElementTypes.ATX_6 -> 6
        MarkdownElementTypes.SETEXT_1 -> 1
        MarkdownElementTypes.SETEXT_2 -> 2
        else -> null
    }

    /** 笔记链接转换产物（`[显示](az-note:...)`）：大纲只展示显示文本。 */
    private val NOTE_LINK = Regex("""\[([^\]]*)]\(az-note:[^)]*\)""")

    /** 标题纯文本：优先 ATX_CONTENT / SETEXT_CONTENT 子节点（自动剔除 # 号）。 */
    private fun headingText(markdown: String, node: ASTNode): String {
        val contentNode = node.children.firstOrNull {
            it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
        }
        val raw = if (contentNode != null) {
            markdown.substring(contentNode.startOffset, contentNode.endOffset)
        } else {
            // 兜底：取标题结构首行并去掉可能的 # 前缀
            markdown.substring(node.startOffset, node.endOffset)
                .lineSequence().first().trimStart('#', ' ', '\n')
        }
        return NOTE_LINK.replace(raw, "\$1").trim()
    }
}
