package com.az.notes.data.storage

import com.az.notes.domain.markdown.NoteAlias
import com.az.notes.domain.markdown.RawLinkRef

/** 文件指纹（大小 + mtime）：内存快照与持久化缓存共用同一口径。 */
data class CachedFingerprint(val size: Long, val mtime: Long)

/** 单篇笔记的索引产物（写缓存用）。 */
data class CachedFile(
    val path: String,
    val relativePath: String,
    val fingerprint: CachedFingerprint,
    val refs: List<RawLinkRef>,
    /** 该文件 frontmatter 声明的别名（按声明顺序）；0 条也代表"已解析过" */
    val aliases: List<String>
)

/** 需要从缓存中移除的文件（相对路径用于清理别名行）。 */
data class RemovedFile(val absolutePath: String, val relativePath: String)

/**
 * 持久化索引快照：只存「原始引用 + 文件指纹 + 别名声明」。
 * 正向 / 反向表仍由内存重算（纯 CPU），因此名字集合变化时不需要重读任何正文。
 */
data class LinkCacheSnapshot(
    val fingerprints: Map<String, CachedFingerprint>,
    val refs: Map<String, List<RawLinkRef>>,
    val aliases: Map<String, List<NoteAlias>>
)

/**
 * 双链索引的持久化缓存。
 *
 * 索引原本是纯内存态：每次进程启动都要把全库正文读一遍才能建出反向表。
 * 持久化后，冷启动只做目录 stat 比对（[LinkCacheSnapshot.fingerprints]），
 * 正文只在文件真的变化时才读，"打开笔记/改名/删除"等操作也不再反复冷扫。
 *
 * [scope] 为规范化后的仓库根绝对路径：多仓库天然隔离，且不依赖设置流的加载时序。
 */
interface LinkCacheStore {

    /** 读取某仓库的缓存；无缓存（首装 / 升级后首次）返回 null，调用方走全量提取。 */
    suspend fun load(scope: String): LinkCacheSnapshot?

    /** 覆盖写入一批文件：先清该文件旧行再插入，指纹与引用不会出现半截状态。 */
    suspend fun saveFiles(scope: String, files: List<CachedFile>)

    suspend fun removeFiles(scope: String, removed: List<RemovedFile>)

    suspend fun clear(scope: String)
}

/** 不持久化的实现：单测与"关闭缓存"场景用，行为等同改造前的纯内存索引。 */
object NoLinkCacheStore : LinkCacheStore {
    override suspend fun load(scope: String): LinkCacheSnapshot? = null
    override suspend fun saveFiles(scope: String, files: List<CachedFile>) = Unit
    override suspend fun removeFiles(scope: String, removed: List<RemovedFile>) = Unit
    override suspend fun clear(scope: String) = Unit
}
