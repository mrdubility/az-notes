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

    /** 解析结果：标题列表 + 顶层块总数（阅读进度百分比用）。 */
    data class Result(
        val headings: List<Heading>,
        val blockCount: Int
    )

    /** 解析：标题（含 ATX 与 Setext 两种写法）+ 顶层块数量。 */
    fun parse(markdown: String): Result {
        val parser = MarkdownParser(GFMFlavourDescriptor())
        val blocks = parser.buildMarkdownTreeFromString(markdown).children
        val headings = blocks.mapIndexedNotNull { index, node ->
            val level = node.headingLevel() ?: return@mapIndexedNotNull null
            Heading(
                level = level,
                text = headingText(markdown, node),
                line = lineOf(markdown, node.startOffset),
                blockIndex = index
            )
        }
        return Result(headings = headings, blockCount = blocks.size)
    }

    /** 兼容旧入口：仅取标题列表。 */
    fun extract(markdown: String): List<Heading> = parse(markdown).headings

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
        return raw.trim()
    }

    /** 字符偏移 → 行号（0 基）。 */
    private fun lineOf(markdown: String, offset: Int): Int {
        var line = 0
        var i = 0
        val end = offset.coerceAtMost(markdown.length)
        while (i < end) {
            if (markdown[i] == '\n') line++
            i++
        }
        return line
    }
}
