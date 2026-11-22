package com.az.notes.data.storage

import com.az.notes.domain.markdown.FrontmatterParser
import com.az.notes.domain.markdown.ImageReference
import com.az.notes.domain.markdown.NoteAlias
import com.az.notes.domain.markdown.RawLinkRef
import com.az.notes.domain.markdown.WikiLinkExtractor
import com.az.notes.domain.markdown.WikiLinkResolver
import com.az.notes.domain.model.FileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 出链条目：一条双链在解析后的去向。
 * [targetPath] 为 null 表示未解析（正文里点击会走「新建笔记」兜底）。
 */
data class LinkOutItem(
    val display: String,
    val targetPath: String?,
    val relativePath: String?,
    val anchor: String?,
    val count: Int
)

/** 反向链接条目：某篇笔记在 [line] 行提到了当前笔记（[snippet] 为该行可读上下文）。 */
data class BacklinkItem(
    val sourcePath: String,
    val relativePath: String,
    val line: Int,
    val display: String,
    val snippet: String
)

/** 单条引用的解析结果（三态：命中 / 普通链接未解析 / 不计入出链）。 */
sealed interface LinkResolution {
    data class Resolved(val file: File) : LinkResolution

    /** 普通链接未解析：计入出链（无目标），点击时走「新建笔记」兜底 */
    data object Unresolved : LinkResolution

    /** 不计入出链：图片嵌入，或未解析的嵌入（正文里保持原文不可点） */
    data object Ignored : LinkResolution
}

/**
 * 全库双链索引（内存态）：`原始引用 → 解析结果` 的两层结构与派生反向表。
 *
 * 分层理由（与内容寻址无关的简化口径）：
 *  - [Snapshot.raw] 只依赖文件内容，与"解析到哪"解耦；
 *  - [Snapshot.forward] / [Snapshot.reverse] 由 raw 派生，文件集合或内容一变就**整体重算**
 *    （纯 CPU，不重读盘）——改名、新增同名文件都会改变既有链接的解析结果，必须重算。
 *
 * 刷新口径与 [TagIndex] 一致：size + mtime 指纹增量、只重读变化文件、分批 [yield] 让步、
 * 局部副本构建完成后原子发布。反向表在构建期顺带生成，面板读取为 O(1)，不做全库扫描。
 *
 * 解析一律走 [WikiLinkResolver]（含同目录优先、层级与字典序消歧、aliases 回退），
 * 与正文点击共用同一套规则——解析规则只有一处。
 *
 * 已知上限：正文读取走 [VaultRepository.INDEX_MAX_BYTES]（4MB，与首页搜索的 256KB 上限
 * 刻意分开）——超过这一预算的笔记始终不参与索引（与是否 dirty 无关），因此大笔记的双链
 * 不会出现"刚编辑时有、刷新后消失"的忽有忽无。单篇引用数仍受
 * [WikiLinkExtractor.MAX_REFS_PER_NOTE] 限制。
 */
@Singleton
class VaultLinkIndex @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val vaultNameIndex: VaultNameIndex
) {

    private data class Fingerprint(val size: Long, val mtime: Long)

    private data class Snapshot(
        val rootPath: String,
        val builtAt: Long,
        val fingerprints: Map<String, Fingerprint>,
        /** 写盘后被显式标记为需要重读的路径（消除同秒 mtime 的边界） */
        val dirty: Set<String>,
        val raw: Map<String, List<RawLinkRef>>,
        val forward: Map<String, List<LinkOutItem>>,
        val reverse: Map<String, List<BacklinkItem>>
    )

    private val buildMutex = Mutex()

    @Volatile
    private var snapshot: Snapshot? = null

    /** 当前笔记的出链（按首次出现顺序，同目标合并计数）。 */
    fun outLinksOf(path: String): List<LinkOutItem> = snapshot?.forward?.get(keyOf(path)).orEmpty()

    /** 指向当前笔记的反向引用（按来源相对路径 + 行号排序）。 */
    fun backlinksOf(path: String): List<BacklinkItem> = snapshot?.reverse?.get(keyOf(path)).orEmpty()

    /**
     * 标记某篇笔记内容已变（写盘后调用）：下次刷新必定重读该文件，且绕过正文缓存。
     * 同秒写盘（mtime 未变且 size 相同）时，正文缓存会命中旧文本——必须直读磁盘。
     * 笔记尚未进入索引时忽略（首次构建会自然读到最新内容）。
     */
    @Synchronized
    fun markChanged(path: String) {
        val current = snapshot ?: return
        val key = keyOf(path)
        if (!current.fingerprints.containsKey(key)) return
        snapshot = current.copy(builtAt = 0L, dirty = current.dirty + key)
    }

    /** 清空索引（仓库切换等场景兜底；[buildOrRefresh] 检测到换根时会自动冷扫）。 */
    @Synchronized
    fun invalidate() {
        snapshot = null
    }

    /**
     * 构建 / 增量刷新索引。全程 IO 线程执行，分批让步；[onProgress] 每处理一篇回调一次。
     * [force] 为 false 时，[TTL_MS] 内的重复调用直接返回（页签来回切换不重复扫描）；
     * 测试需要连续刷新时传 true。
     */
    suspend fun buildOrRefresh(
        vaultPath: String,
        force: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) {
        val root = File(vaultPath).normalize()
        val key = root.absolutePath
        if (!force && isFresh(key)) return
        buildMutex.withLock {
            if (!force && isFresh(key)) return
            val prev = snapshot
            withContext(Dispatchers.IO) { rebuild(root, key, prev, onProgress) }
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun isFresh(key: String): Boolean {
        val current = snapshot ?: return false
        return current.rootPath == key && System.currentTimeMillis() - current.builtAt < TTL_MS
    }

    private suspend fun rebuild(
        root: File,
        key: String,
        prev: Snapshot?,
        onProgress: (Int, Int) -> Unit
    ) {
        val notes = vaultRepository.listAllNotes(key)
        val fingerprints = HashMap<String, Fingerprint>(notes.size)
        notes.forEach { fingerprints[it.absolutePath] = Fingerprint(it.size, it.lastModified) }

        val previous = prev?.takeIf { it.rootPath == key }
        val prevFingerprints = previous?.fingerprints.orEmpty()
        val prevRaw = previous?.raw.orEmpty()
        val removed = prevFingerprints.keys - fingerprints.keys
        val added = fingerprints.keys - prevFingerprints.keys
        val dirty = previous?.dirty.orEmpty()
        val changed = notes.filter { node ->
            node.absolutePath in dirty ||
                prevFingerprints[node.absolutePath]?.let { it != fingerprints[node.absolutePath] } ?: true
        }
        if (previous != null && removed.isEmpty() && added.isEmpty() && changed.isEmpty()) {
            snapshot = previous.copy(builtAt = System.currentTimeMillis())
            return
        }
        // 文件集合变了：名字 / 别名索引可能是旧的（改名、新建同名文件），先失效再取
        if (previous == null || removed.isNotEmpty() || added.isNotEmpty()) {
            vaultNameIndex.invalidate()
        }
        val names = vaultNameIndex.names(root)
        val aliases = vaultNameIndex.aliases(root)

        val changedPaths = changed.mapTo(HashSet()) { it.absolutePath }
        val raw = HashMap<String, List<RawLinkRef>>(notes.size)
        var done = 0
        for (node in notes) {
            val cached = if (previous != null && node.absolutePath !in changedPaths) {
                prevRaw[node.absolutePath]
            } else {
                null
            }
            raw[node.absolutePath] = cached ?: extractLinks(node, forceRead = node.absolutePath in dirty)
            done++
            if (done and 15 == 0) yield()
            onProgress(done, notes.size)
        }

        val forward = HashMap<String, List<LinkOutItem>>()
        val reverse = HashMap<String, MutableList<BacklinkItem>>()
        for ((source, refs) in raw) {
            if (refs.isEmpty()) continue
            val noteDir = File(source).parentFile
            val merged = LinkedHashMap<String, LinkOutItem>()
            for (ref in refs) {
                if (WikiLinkExtractor.isSelfAnchor(ref)) continue
                val file = when (val resolution = resolveLink(ref, root, noteDir, names, aliases)) {
                    is LinkResolution.Resolved -> resolution.file
                    LinkResolution.Unresolved -> null
                    LinkResolution.Ignored -> continue
                }
                val display = displayOf(ref)
                val itemKey = file?.absolutePath ?: "?${ref.target}"
                val existing = merged[itemKey]
                merged[itemKey] = if (existing == null) {
                    LinkOutItem(
                        display = display,
                        targetPath = file?.absolutePath,
                        relativePath = file?.let { relativePathOf(root, it) },
                        anchor = ref.anchor,
                        count = 1
                    )
                } else {
                    existing.copy(count = existing.count + 1)
                }
                if (file != null && file.absolutePath != source) {
                    val mentions = reverse.getOrPut(file.absolutePath) { ArrayList() }
                    // 同一行重复的同一链接不重复列出（引用按"提及位置"计数）
                    val last = mentions.lastOrNull()
                    if (last == null || last.sourcePath != source || last.line != ref.line || last.display != display) {
                        mentions += BacklinkItem(
                            sourcePath = source,
                            relativePath = relativePathOf(root, File(source)),
                            line = ref.line,
                            display = display,
                            snippet = ref.snippet
                        )
                    }
                }
            }
            if (merged.isNotEmpty()) forward[source] = merged.values.toList()
        }
        reverse.values.forEach { list ->
            list.sortWith(compareBy({ it.relativePath.lowercase() }, { it.line }))
        }
        snapshot = Snapshot(
            rootPath = key,
            builtAt = System.currentTimeMillis(),
            fingerprints = fingerprints,
            dirty = emptySet(),
            raw = raw,
            forward = forward,
            reverse = reverse.mapValues { it.value.toList() }
        )
    }

    /**
     * 读取单篇正文并提取双链：统一走 [VaultRepository.contentFor] 的索引预算（4MB），
     * [forceRead] 仅表示"绕过 mtime 缓存重读"，不再改变大小上限——
     * 否则 dirty 与非 dirty 两条路径的可见范围不同，索引结果会随之漂移。
     */
    private fun extractLinks(node: FileNode, forceRead: Boolean): List<RawLinkRef> {
        val text = vaultRepository.contentFor(
            node,
            maxBytes = VaultRepository.INDEX_MAX_BYTES,
            forceRead = forceRead
        )
        val linked = text?.takeIf { it.contains("[[") } ?: return emptyList()
        return WikiLinkExtractor.extract(FrontmatterParser.split(linked).body)
    }

    private fun keyOf(path: String): String = File(path).normalize().absolutePath

    private fun relativePathOf(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')

    /** 显示文本规则与转换器一致：别名优先，其次「目标 > 锚点」，最后裸目标。 */
    private fun displayOf(ref: RawLinkRef): String =
        ref.alias ?: ref.anchor?.let { "${ref.target} > $it" } ?: ref.target

    companion object {
        /** 索引有效期：覆盖同一轮面板内的多次读取；写盘后经 [markChanged] 立即失效。 */
        private const val TTL_MS = 2_000L

        /**
         * 解析单条引用（索引与「当前笔记出链」共用，避免两处口径漂移）。
         *
         * 嵌入（`![[...]]`）的语义与渲染侧一致：解析到图片即图片嵌入（正文不可点，
         * 不计入链接）；解析到笔记则降级为链接（计入）；未解析保持原文（不计入）。
         */
        fun resolveLink(
            ref: RawLinkRef,
            root: File,
            noteDir: File?,
            names: Map<String, List<String>>,
            aliases: Map<String, List<NoteAlias>>
        ): LinkResolution {
            if (!ref.isEmbed) {
                val file = WikiLinkResolver.resolve(ref.target, root, noteDir, names, aliases)
                return if (file != null) LinkResolution.Resolved(file) else LinkResolution.Unresolved
            }
            val file = ImageReference.resolveExisting(ref.target, root, noteDir ?: root, names)
                ?: WikiLinkResolver.resolve(ref.target, root, noteDir, names, aliases)
                ?: return LinkResolution.Ignored
            if (VaultRepository.isImageName(file.name)) return LinkResolution.Ignored
            return LinkResolution.Resolved(file)
        }
    }
}
