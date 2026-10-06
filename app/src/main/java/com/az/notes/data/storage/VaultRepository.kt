package com.az.notes.data.storage

import com.az.notes.domain.markdown.PreviewExtractor
import com.az.notes.domain.model.FileNode
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 全局搜索命中：snippet 为正文命中位置的上下文片段（null = 仅文件名命中）。 */
data class SearchHit(val node: FileNode, val snippet: String? = null)

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

        /**
         * 数据安全红线：本应用持有“所有文件访问”权限，
         * 破坏性操作（删除 / 重命名 / 新建）绝不允许触碰以下系统与存储根路径。
         */
        private val PROTECTED_PATHS = setOf(
            "/", "/sdcard", "/mnt", "/storage", "/storage/emulated",
            "/system", "/data", "/vendor", "/proc", "/sys"
        )

        private val MARKDOWN_EXT = setOf("md", "markdown", "mdown", "mkd")
        private val ATTACHMENT_EXT = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp",
            "pdf", "mp3", "mp4", "mov", "webm", "avif"
        )

        /** 日期变量：`$...$` 包裹的片段（内容按 SimpleDateFormat 语法解析）。 */
        private val DATE_VAR = Regex("\\$([^$]+)\\$")

        /** 正文搜索：超过该大小的文件跳过正文匹配（仅参与文件名匹配）。 */
        private const val CONTENT_SEARCH_MAX_BYTES = 262_144L

        /** 正文搜索缓存上限：超过后整体清空，控制内存占用。 */
        private const val CONTENT_CACHE_LIMIT = 512

        /** 片段压缩用的连续空白。 */
        private val WHITESPACE_RUN = Regex("\\s+")

        /** 文件名（不含扩展）对应的 Markdown 判断 */
        fun isMarkdownName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in MARKDOWN_EXT

        fun isAttachmentName(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in ATTACHMENT_EXT

        /** 清洗用户输入的文件 / 文件夹名：过滤路径分隔符与非法字符；空或 '.' 开头返回 null。 */
        fun sanitizeEntryName(input: String): String? = input.trim()
            .filterNot { it in "\\/:*?\"<>|" }
            .trim()
            .takeIf { it.isNotEmpty() && !it.startsWith(".") }

        /** 笔记名补全 .md 后缀（已带 Markdown 扩展则保留）。 */
        fun ensureMarkdownName(name: String): String =
            if (isMarkdownName(name)) name else "$name.md"

        /**
         * 解析新建笔记名：仅 `$...$` 包裹的片段按 Android/Java 的 SimpleDateFormat
         * 语法用当前时间格式化（如 `日记$yyyyMMdd$` → `日记20261005`）。
         * 其余字符（含普通英文字母）一律原样保留；片段模式非法时保留原样（含 `$`），
         * 便于用户在列表中直接看出写错了变量。
         */
        fun resolveDateName(pattern: String): String =
            DATE_VAR.replace(pattern) { match ->
                runCatching {
                    SimpleDateFormat(match.groupValues[1], Locale.getDefault()).format(Date())
                }.getOrDefault(match.value)
            }

        /**
         * 敏感路径护栏：delete / rename / createFile 等破坏性操作前的最后一道防线。
         * 命中系统目录、外部存储根或其祖先路径时返回 false（禁止操作）。
         */
        fun isSafeTarget(path: String): Boolean {
            val abs = File(path).absolutePath
            if (abs.isBlank() || abs == "/") return false
            if (abs in PROTECTED_PATHS) return false
            val segments = abs.split('/').filter { it.isNotEmpty() }
            if (segments.size < 3) return false
            val storageRoot = runCatching {
                android.os.Environment.getExternalStorageDirectory().absolutePath
            }.getOrNull()
            if (storageRoot != null && (abs == storageRoot || storageRoot.startsWith("$abs/"))) return false
            return true
        }
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

    /**
     * 单层列出目录内容（主页笔记列表）：仅返回子目录与 Markdown 笔记，
     * 过滤名称以 '.' 开头的隐藏文件/文件夹（含 [IGNORED_DIRS]）。
     * [rootPath] 用于计算相对路径；排序由调用方按用户偏好执行。
     */
    fun listChildren(dirPath: String, rootPath: String): List<FileNode> {
        val dir = File(dirPath)
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { it.isVisibleEntry() }
            .filter { it.isDirectory || isMarkdownName(it.name) }
            .map { toNode(it, relativize(rootPath, it.absolutePath), rootPath, depth = 0) }
    }

    /**
     * 递归搜索 Vault 中的 Markdown 笔记（主页全局搜索）：
     * 按修改时间从新到旧逐条匹配（文件名或正文命中即收集），结果严格倒序；
     * 正文读取走 mtime 增量缓存（见 [contentFor]），大文件跳过正文匹配。
     * 最多返回 [limit] 条；正文命中时附带 [SearchHit.snippet] 上下文片段。
     */
    fun searchNotes(rootPath: String, query: String, limit: Int = 100): List<SearchHit> {
        val root = File(rootPath)
        val trimmed = query.trim()
        if (!root.isDirectory || trimmed.isEmpty()) return emptyList()
        val notes = ArrayList<FileNode>()
        collectNotes(root, rootPath, notes)
        // 从新到旧单轮遍历：命中即收集，输出天然严格按修改时间倒序
        val ordered = notes.sortedByDescending { it.lastModified }
        val out = ArrayList<SearchHit>()
        for (node in ordered) {
            if (out.size >= limit) break
            if (node.name.contains(trimmed, ignoreCase = true)) {
                out += SearchHit(node)
                continue
            }
            val text = contentFor(node) ?: continue
            val index = text.indexOf(trimmed, ignoreCase = true)
            if (index >= 0) out += SearchHit(node, snippetAround(text, index, trimmed.length))
        }
        return out
    }

    /** 递归收集全部 Markdown 笔记（忽略隐藏项）。 */
    private fun collectNotes(dir: File, rootPath: String, out: MutableList<FileNode>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (!f.isVisibleEntry()) continue
            if (f.isDirectory) {
                collectNotes(f, rootPath, out)
            } else if (isMarkdownName(f.name)) {
                out += toNode(f, relativize(rootPath, f.absolutePath), rootPath, depth = 0)
            }
        }
    }

    /** 正文搜索缓存条目：文件 mtime 未变即可复用已读文本。 */
    private data class CachedContent(val lastModified: Long, val text: String)

    private val contentCache = ConcurrentHashMap<String, CachedContent>()

    /** 读取文件正文（搜索用，mtime 增量缓存）；超大文件返回 null 跳过正文匹配。 */
    private fun contentFor(node: FileNode): String? {
        if (node.size > CONTENT_SEARCH_MAX_BYTES) return null
        val cached = contentCache[node.absolutePath]
        if (cached != null && cached.lastModified == node.lastModified) return cached.text
        val text = runCatching { readText(node.absolutePath) }.getOrNull() ?: return null
        if (contentCache.size >= CONTENT_CACHE_LIMIT) contentCache.clear()
        contentCache[node.absolutePath] = CachedContent(node.lastModified, text)
        return text
    }

    /** 命中位置的上下文片段：前后各截一段并压缩连续空白。 */
    private fun snippetAround(text: String, index: Int, length: Int): String {
        val start = (index - 30).coerceAtLeast(0)
        val end = (index + length + 50).coerceAtMost(text.length)
        return text.substring(start, end).replace(WHITESPACE_RUN, " ").trim()
    }

    /** 递归收集全部子目录（忽略隐藏项，含 depth），供批量移动选择目标文件夹。 */
    fun listAllDirectories(rootPath: String): List<FileNode> {
        val root = File(rootPath)
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<FileNode>()
        walkDirs(root, rootPath, depth = 0, out)
        return out
    }

    private fun walkDirs(dir: File, rootPath: String, depth: Int, out: MutableList<FileNode>) {
        val children = dir.listFiles() ?: return
        val sorted = children.filter { it.isDirectory && it.isVisibleEntry() }
            .sortedBy { it.name.lowercase() }
        for (f in sorted) {
            out += toNode(f, relativize(rootPath, f.absolutePath), rootPath, depth)
            walkDirs(f, rootPath, depth + 1, out)
        }
    }

    /** 隐藏项判定：'.' 开头的名字与 [IGNORED_DIRS]。 */
    private fun File.isVisibleEntry(): Boolean =
        !name.startsWith(".") && name !in IGNORED_DIRS

    /** 读取文本文件内容（UTF-8 无 BOM，§5.3）。 */
    fun readText(absolutePath: String): String {
        val text = File(absolutePath).readText(StandardCharsets.UTF_8)
        return stripBom(text).normalizeToLf()
    }

    /**
     * 读取列表预览：剥离 Markdown 标记后截取前 [maxChars] 个字符。
     *
     * 性能：只读取文件头部有限字节（maxChars × 6 + 2KB 上限），
     * 避免为生成预览而整读大文件——笔记数量多 / 单文件大时显著省 IO。
     * 读取失败（编码异常等）时返回空串，不影响列表渲染。
     */
    fun readPreview(absolutePath: String, maxChars: Int): String {
        val text = runCatching { readPrefix(absolutePath, maxChars) }.getOrElse { return "" }
        return PreviewExtractor.extract(text, maxChars)
    }

    /** 读取文件头部有限字节并解码为 UTF-8 文本（剔除末尾被截断的多字节字符）。 */
    private fun readPrefix(absolutePath: String, maxChars: Int): String {
        val file = File(absolutePath)
        if (!file.isFile) return ""
        val byteLimit = (maxChars.toLong() * 6 + 2048)
            .coerceAtMost(file.length().coerceAtLeast(1L))
            .toInt()
        val buffer = ByteArray(byteLimit)
        val read = file.inputStream().use { input ->
            var offset = 0
            while (offset < buffer.size) {
                val n = input.read(buffer, offset, buffer.size - offset)
                if (n < 0) break
                offset += n
            }
            offset
        }
        if (read <= 0) return ""
        return String(buffer, 0, read, StandardCharsets.UTF_8).trimEnd('\uFFFD')
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

    /** 新建 Markdown 文件（§5.1 长按菜单）；敏感路径护栏兜底。 */
    fun createFile(absolutePath: String, initialContent: String = ""): Boolean {
        if (!isSafeTarget(absolutePath)) return false
        val f = File(absolutePath)
        if (f.exists()) return false
        return runCatching { writeTextAtomically(absolutePath, initialContent); true }
            .getOrDefault(false)
    }

    /**
     * 生成不与现有文件冲突的新笔记绝对路径：
     * `新建笔记.md` → `新建笔记 2.md` → `新建笔记 3.md` …
     */
    fun uniqueNotePath(dirPath: String, baseName: String = "新建笔记"): String {
        val safeBase = sanitizeEntryName(baseName) ?: "新建笔记"
        var candidate = File(dirPath, "$safeBase.md")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dirPath, "$safeBase $index.md")
            index++
        }
        return candidate.absolutePath
    }

    /** 新建目录。 */
    fun createDirectory(absolutePath: String): Boolean =
        File(absolutePath).mkdirs()

    /** 重命名 / 移动。破坏性操作，先经敏感路径护栏校验（数据安全红线）。 */
    fun rename(oldPath: String, newPath: String): Boolean {
        if (!isSafeTarget(oldPath) || !isSafeTarget(newPath)) return false
        val src = File(oldPath)
        if (!src.exists()) return false
        if (File(newPath).exists()) return false
        return src.renameTo(File(newPath))
    }

    /** 删除（二次确认由 UI 层负责；敏感路径护栏兜底，绝不触碰 Vault 之外的系统文件）。 */
    fun delete(absolutePath: String): Boolean {
        if (!isSafeTarget(absolutePath)) return false
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
