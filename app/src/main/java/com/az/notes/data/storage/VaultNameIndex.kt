package com.az.notes.data.storage

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全库文件名索引（小写文件名 → 仓库内相对路径列表）：供「只写文件名」的引用
 * （`[[笔记]]` / `![[图.png]]`）做全库命中（见 [com.az.notes.domain.markdown.WikiLinkResolver]
 * 与 [com.az.notes.domain.markdown.ImageReference]）。
 *
 * 单次全库遍历，只登记文件名不做内容读取；隐藏项（含 `.obsidian` / `.trash` / `.git`）
 * 不参与。缓存条目带 TTL，写操作后可 [invalidate] 立即失效。
 */
@Singleton
class VaultNameIndex @Inject constructor() {

    private data class Snapshot(
        val root: String,
        val builtAt: Long,
        val names: Map<String, List<String>>
    )

    private var snapshot: Snapshot? = null

    /** 获取 [root] 的文件名索引（TTL 内复用；过期或换根时重建）。 */
    @Synchronized
    fun names(root: File): Map<String, List<String>> {
        val key = root.absolutePath
        val now = System.currentTimeMillis()
        snapshot?.takeIf { it.root == key && now - it.builtAt < TTL_MS }?.let { return it.names }
        val built = build(root, key)
        snapshot = Snapshot(key, now, built)
        return built
    }

    /** 失效缓存（新建 / 重命名 / 删除后调用，避免 TTL 窗口内读到旧索引）。 */
    @Synchronized
    fun invalidate() {
        snapshot = null
    }

    // ---------------------------------------------------------------- 内部

    private fun build(root: File, rootPath: String): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        collect(root, rootPath, out)
        return out
    }

    private fun collect(dir: File, rootPath: String, out: MutableMap<String, MutableList<String>>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                collect(f, rootPath, out)
            } else {
                val relative = f.absolutePath.removePrefix(rootPath).trimStart('/', '\\')
                out.getOrPut(f.name.lowercase()) { ArrayList() }.add(relative)
            }
        }
    }

    private companion object {
        /** 缓存有效期：覆盖同一页面内的多次链接解析；写操作经 [invalidate] 立即失效。 */
        const val TTL_MS = 10_000L
    }
}
