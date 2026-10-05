package com.az.notes.domain.markdown

/**
 * 大纲节点（§5.4）。
 * [blockIndex] 为近似锚点：Demo 阶段以“可见块序号”近似定位，
 * 后续接 mikepenz renderer 的 AST 块可无缝替换为真实块序号。
 */
data class Heading(
    val level: Int,          // 1..6
    val text: String,
    val line: Int,           // 源文本行号（0-based）
    val blockIndex: Int      // 大纲跳转锚点
)
