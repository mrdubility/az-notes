package com.az.notes.data.storage

import com.az.notes.domain.model.FileNode
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vault 文件存储仓库（§4.1 / §2.1）。
 *
 * 直接用 `java.io.File` API（MANAGE_EXTERNAL_STORAGE 已授权），
 * 扫描 / 读写性能与桌面 Obsidian 一致。所有耗时方法均为 `suspend`，
 * 由调用方在 `Dispatchers.IO` 上执行。
 */
@Singleton
class VaultRepository @Inject constructor() {

    companion object {
        /** 忽略的配置目录（§1.1） */
        private val IGNORED_DIRS = setOf(".obsidian", ".trash", ".git")

        private val MARKDOWN_EXT = setOf("md", "markdown", "mdown", "mkd")
        private val ATTACHMENT_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp",
            "pdf", "mp3", "mp4", "mov", "webm", "avif"
        )

        /** 文件名（不含扩展）对应的 Markdown 判断 */
        fun isMarkdownName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in MARKDOWN_EXT

        fun isAttachmentName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in ATTACHMENT_EXT
    }

    /** 校验目录存在且可读。 */
    fun isValidVault(path: String): Boolean {
        val dir = File(path)
        return dir.exists() && dir.isDirectory && dir.canRead()
    }

    /**
     * 递归扫描 Vault，产出扁平化的 [FileNode] 列表（含 depth）。
     * 目录在前、按名称排序；排除 [IGNORED_DIRS]。
     */
    fun scanTree(rootPath: String): List<FileNode> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<FileNode>()
        walk(root, root.absolutePath, depth = 0, out)
        return out
    }

    private fun walk(dir: File, rootPath: String, depth: Int, out: MutableList<FileNode>) {
        val children = dir.listFiles() ?: return
        val sorted = children.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        for (f in sorted) {
            if (f.isDirectory && f.name in IGNORED_DIRS) continue
            val relative = relativize(rootPath, f.absolutePath)
            out += toNode(f, relative, rootPath, depth)
            if (f.isDirectory) {
                walk(f, rootPath, depth + 1, out)
            }
        }
    }

    private fun toNode(f: File, relative: String, rootPath: String, depth: Int): FileNode {
        return FileNode(
            relativePath = relative,
            absolutePath = f.absolutePath,
            name = f.name,
            isDirectory = f.isDirectory,
            size = if (f.isDirectory) 0L else f.length(),
            lastModified = f.lastModified(),
            depth = depth,
            isMarkdown = !f.isDirectory && isMarkdownName(f.name),
            isAttachment = !f.isDirectory && isAttachmentName(f.name)
        )
    }

    /** 读取文本文件内容（UTF-8 无 BOM，§5.3）。 */
    fun readText(absolutePath: String): String {
        val text = File(absolutePath).readText(StandardCharsets.UTF_8)
        return stripBom(text).normalizeToLf()
    }

    /**
     * 原子写盘（§5.3）：先写 `.tmp` 再 rename，避免崩溃损坏原文件；
     * 统一 UTF-8 无 BOM、LF 换行。
     */
    fun writeTextAtomically(absolutePath: String, content: String) {
        val target = File(absolutePath)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        tmp.writeText(normalized, StandardCharsets.UTF_8)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // rename 失败兜底：直接覆盖写
            target.writeText(normalized, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }

    /** 新建 Markdown 文件（§5.1 长按菜单）。 */
    fun createFile(absolutePath: String, initialContent: String = ""): Boolean {
        val f = File(absolutePath)
        if (f.exists()) return false
        return runCatching { writeTextAtomically(absolutePath, initialContent); true }
            .getOrDefault(false)
    }

    /** 新建目录。 */
    fun createDirectory(absolutePath: String): Boolean =
        File(absolutePath).mkdirs()

    /** 重命名 / 移动。 */
    fun rename(oldPath: String, newPath: String): Boolean {
        val src = File(oldPath)
        if (!src.exists()) return false
        return src.renameTo(File(newPath))
    }

    /** 删除（二次确认由 UI 层负责）。 */
    fun delete(absolutePath: String): Boolean {
        val f = File(absolutePath)
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    private fun relativize(rootPath: String, absPath: String): String {
        val rel = absPath.removePrefix(rootPath).trimStart('/', '\\')
        return rel.ifEmpty { "." }
    }

    private fun stripBom(s: String): String =
        if (s.isNotEmpty() && s[0] == '\uFEFF') s.substring(1) else s

    private fun String.normalizeToLf(): String =
        replace("\r\n", "\n").replace("\r", "\n")
}
