package com.az.notes.domain.model

/**
 * 目录树节点（§5.1）。
 * 由 [com.az.notes.data.storage.VaultRepository] 扫描 `File` 树生成，
 * 以“扁平列表 + depth 缩进”的形式驱动 LazyColumn 渲染。
 */
data class FileNode(
    /** 相对 Vault 根的路径，使用 '/' 分隔；作为稳定 key */
    val relativePath: String,
    /** 绝对路径 */
    val absolutePath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    /** 层级深度，根为 0 */
    val depth: Int,
    /** 是否为受支持的 Markdown 文本文件（可点开预览/编辑） */
    val isMarkdown: Boolean,
    /** 是否为附件（图片/pdf 等，交给系统查看器） */
    val isAttachment: Boolean
) {
    val isNavigable: Boolean get() = isDirectory || isMarkdown
}
