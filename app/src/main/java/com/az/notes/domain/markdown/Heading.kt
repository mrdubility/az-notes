package com.az.notes.domain.markdown

/**
 * 大纲节点（§5.4）。
 * [blockIndex] 为 Markdown AST 顶层块序号，与预览页 LazyColumn 的块下标一一对应
 * （与渲染库同一 GFM 解析链），大纲跳转与高亮均按真实锚点工作。
 */
data class Heading(
    val level: Int,          // 1..6
    val text: String,
    val line: Int,           // 源文本行号（0-based）
    val blockIndex: Int      // 大纲跳转锚点
)
