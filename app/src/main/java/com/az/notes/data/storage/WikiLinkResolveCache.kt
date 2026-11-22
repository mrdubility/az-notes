package com.az.notes.data.storage

import com.az.notes.domain.markdown.NoteAlias
import com.az.notes.domain.markdown.WikiLinkResolver
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双链解析结果 memo（LRU）：`(索引版本, 源笔记目录, 目标原文) → 解析到的绝对路径`。
 *
 * 存在理由：[WikiLinkResolver.resolve] 一次调用要做多条 `File.isFile` 存在性判定，
 * 而「点击链接 / 补全 / 出链列表」会在同一份索引上重复解析同一批目标。memo 只缓存
 * **结果**，不缓存索引本身。
 *
 * 失效口径：key 带 [VaultNameIndex.version]——索引一旦重建或显式失效，旧 key 自然
 * 淘汰，无需在每个写盘点手动清缓存（清理点漏一处就是陈旧解析）。
 */
@Singleton
class WikiLinkResolveCache @Inject constructor(
    private val vaultNameIndex: VaultNameIndex
) {

    private val cache = object : LinkedHashMap<String, String?>(CACHE_INITIAL, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean =
            size > CACHE_MAX
    }

    /** 解析并缓存；未命中返回 null（同样缓存，避免反复对同一不存在目标做 stat）。 */
    @Synchronized
    fun resolve(
        target: String,
        root: File,
        noteDir: File?,
        names: Map<String, List<String>>,
        aliases: Map<String, List<NoteAlias>>
    ): File? {
        val key = "${vaultNameIndex.version}|${noteDir?.absolutePath.orEmpty()}|$target"
        if (cache.containsKey(key)) return cache[key]?.let(::File)
        val file = WikiLinkResolver.resolve(target, root, noteDir, names, aliases)
        cache[key] = file?.absolutePath
        return file
    }

    /** 显式清空（测试与仓库切换兜底；正常失效靠版本号）。 */
    @Synchronized
    fun invalidate() {
        cache.clear()
    }

    private companion object {
        const val CACHE_INITIAL = 256
        const val CACHE_MAX = 1024
    }
}
