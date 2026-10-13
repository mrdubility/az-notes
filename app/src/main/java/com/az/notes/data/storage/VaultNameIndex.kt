package com.az.notes.data.storage

import com.az.notes.domain.markdown.FrontmatterParser
import com.az.notes.domain.markdown.NoteAlias
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全库名称索引：供「只写文件名 / 别名」的引用（`[[笔记]]` / `![[图.png]]`）做全库命中
 * （见 [com.az.notes.domain.markdown.WikiLinkResolver] 与
 * [com.az.notes.domain.markdown.ImageReference]）。
 *
 * - 文件名索引：[names]，单次全库遍历，只登记文件名不做内容读取；
 * - 别名索引：[aliases]，扫描笔记头部 frontmatter 的 aliases 声明（只读文件头有限
 *   字节，比全量读文低一个量级；TTL 更长）。
 *
 * 隐藏项（含 `.obsidian` / `.trash` / `.git`）不参与。缓存条目带 TTL，写操作后可
 * [invalidate] 立即失效。
 */
@Singleton
class VaultNameIndex @Inject constructor() {

    private data class Snapshot(
        val root: String,
        val builtAt: Long,
        val names: Map<String, List<String>>
    )

    private data class AliasSnapshot(
        val root: String,
        val builtAt: Long,
        val aliases: Map<String, List<NoteAlias>>
    )

    private var snapshot: Snapshot? = null
    private var aliasSnapshot: AliasSnapshot? = null

    /** 获取 [root] 的文件名索引（TTL 内复用；过期或换根时重建）。 */
    @Synchronized
    fun names(root: File): Map<String, List<String>> {
        val key = root.absolutePath
        val now = System.currentTimeMillis()
        snapshot?.takeIf { it.root == key && now - it.builtAt < TTL_MS }?.let { return it.names }
        val built = buildNames(root, key)
        snapshot = Snapshot(key, now, built)
        return built
    }

    /**
     * 获取 [root] 的别名索引（小写别名 → 声明条目；TTL 内复用）。
     * 仅扫描 Markdown 笔记头部 frontmatter，隐藏项与无声明笔记不参与。
     */
    @Synchronized
    fun aliases(root: File): Map<String, List<NoteAlias>> {
        val key = root.absolutePath
        val now = System.currentTimeMillis()
        aliasSnapshot?.takeIf { it.root == key && now - it.builtAt < ALIAS_TTL_MS }
            ?.let { return it.aliases }
        val built = buildAliases(root, key)
        aliasSnapshot = AliasSnapshot(key, now, built)
        return built
    }

    /** 失效缓存（新建 / 重命名 / 删除后调用，避免 TTL 窗口内读到旧索引）。 */
    @Synchronized
    fun invalidate() {
        snapshot = null
        aliasSnapshot = null
    }

    // ---------------------------------------------------------------- 内部

    private fun buildNames(root: File, rootPath: String): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        collectNames(root, rootPath, out)
        return out
    }

    private fun collectNames(dir: File, rootPath: String, out: MutableMap<String, MutableList<String>>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                collectNames(f, rootPath, out)
            } else {
                val relative = f.absolutePath.removePrefix(rootPath).trimStart('/', '\\')
                out.getOrPut(f.name.lowercase()) { ArrayList() }.add(relative)
            }
        }
    }

    private fun buildAliases(root: File, rootPath: String): Map<String, List<NoteAlias>> {
        val out = HashMap<String, MutableList<NoteAlias>>()
        collectAliases(root, rootPath, out)
        return out
    }

    private fun collectAliases(dir: File, rootPath: String, out: MutableMap<String, MutableList<NoteAlias>>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                collectAliases(f, rootPath, out)
                continue
            }
            if (!VaultRepository.isMarkdownName(f.name)) continue
            val declared = runCatching { FrontmatterParser.split(readHead(f)).aliases() }
                .getOrDefault(emptyList())
            if (declared.isEmpty()) continue
            val relative = f.absolutePath.removePrefix(rootPath).trimStart('/', '\\')
            declared.forEach { alias ->
                out.getOrPut(alias.lowercase()) { ArrayList() }.add(NoteAlias(alias, relative))
            }
        }
    }

    /** 读取文件头部有限字节（frontmatter 位于文件开头；UTF-8 截断残字剔除）。 */
    private fun readHead(file: File, maxBytes: Int = HEAD_BYTES): String {
        val limit = file.length().coerceAtMost(maxBytes.toLong()).coerceAtLeast(1L).toInt()
        val buffer = ByteArray(limit)
        val read = file.inputStream().use { input ->
            var off = 0
            while (off < buffer.size) {
                val n = input.read(buffer, off, buffer.size - off)
                if (n < 0) break
                off += n
            }
            off
        }
        if (read <= 0) return ""
        return String(buffer, 0, read, StandardCharsets.UTF_8).trimEnd('\uFFFD')
    }

    private companion object {
        /** 文件名索引有效期：覆盖同一页面内的多次链接解析；写操作经 [invalidate] 立即失效。 */
        const val TTL_MS = 10_000L

        /** 别名索引有效期：构建需读取每篇笔记头部（更重），延长缓存窗口。 */
        const val ALIAS_TTL_MS = 60_000L

        /** 头部读取上限：frontmatter 位于文件开头，8KB 覆盖扫描上限行数且有富余。 */
        const val HEAD_BYTES = 8 * 1024
    }
}
