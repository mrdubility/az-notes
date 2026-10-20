package com.az.notes.data.storage

import com.az.notes.domain.markdown.FrontmatterParser
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全库属性索引：扫描笔记头部 frontmatter，汇总「属性键 → 值」的输入历史，
 * 供预览页属性编辑器做键 / 值联想（值历史按所选键联动）。
 *
 * 只读文件头有限字节（与 [VaultNameIndex] 同口径），隐藏项不参与；
 * 缓存带 TTL，属性写回后调用 [invalidate] 立即失效。
 */
@Singleton
class VaultPropertyIndex @Inject constructor() {

    private data class Snapshot(
        val root: String,
        val builtAt: Long,
        val history: Map<String, List<String>>
    )

    private var snapshot: Snapshot? = null

    /** 获取 [root] 的属性历史（键按使用频次降序，值同）；TTL 内复用。 */
    @Synchronized
    fun history(root: File): Map<String, List<String>> {
        val key = root.absolutePath
        val now = System.currentTimeMillis()
        snapshot?.takeIf { it.root == key && now - it.builtAt < TTL_MS }?.let { return it.history }
        val built = build(root)
        snapshot = Snapshot(key, now, built)
        return built
    }

    /** 失效缓存（属性写回后调用，避免 TTL 窗口内读到旧历史）。 */
    @Synchronized
    fun invalidate() {
        snapshot = null
    }

    private fun build(root: File): Map<String, List<String>> {
        val keyCounts = HashMap<String, Int>()
        val valueCounts = HashMap<String, HashMap<String, Int>>()
        collect(root, keyCounts, valueCounts)
        return keyCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .associate { (key, _) ->
                key to valueCounts[key].orEmpty().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .map { it.key }
            }
    }

    private fun collect(
        dir: File,
        keyCounts: MutableMap<String, Int>,
        valueCounts: MutableMap<String, HashMap<String, Int>>
    ) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                collect(f, keyCounts, valueCounts)
                continue
            }
            if (!VaultRepository.isMarkdownName(f.name)) continue
            val entries = runCatching { FrontmatterParser.split(readHead(f)).entries }
                .getOrDefault(emptyList())
            entries.forEach { attr ->
                val key = attr.key.trim()
                if (key.isEmpty()) return@forEach
                keyCounts[key] = (keyCounts[key] ?: 0) + 1
                val values = valueCounts.getOrPut(key) { HashMap() }
                attr.values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { value ->
                    values[value] = (values[value] ?: 0) + 1
                }
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
        /** 属性历史有效期：覆盖属性编辑器的连续打开；写回经 [invalidate] 立即失效。 */
        const val TTL_MS = 60_000L

        /** 头部读取上限：frontmatter 位于文件开头，8KB 覆盖扫描上限行数且有富余。 */
        const val HEAD_BYTES = 8 * 1024
    }
}
