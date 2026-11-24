package com.az.notes.data.storage

import com.az.notes.domain.markdown.FrontmatterParser
import com.az.notes.domain.markdown.ImageReference
import com.az.notes.domain.markdown.NoteAlias
import com.az.notes.domain.markdown.RawLinkRef
import com.az.notes.domain.markdown.WikiLinkExtractor
import com.az.notes.domain.markdown.WikiLinkResolver
import com.az.notes.domain.markdown.WikiLinkTransformer
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

/** 反向链接条目：某篇笔记在 [line] 行提到了当前笔记（[snippet] 为该行可读上下文）。
 * [count] 为同一来源笔记指向本笔记的引用次数（同一篇引用多行时大于 1）。 */
data class BacklinkItem(
    val sourcePath: String,
    val relativePath: String,
    val line: Int,
    val display: String,
    val snippet: String,
    val count: Int = 1
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
 * 全库双链索引：`原始引用 → 解析结果` 的两层结构与派生反向表。
 *
 * 分层理由（与内容寻址无关的简化口径）：
 *  - [Snapshot.raw] 只依赖文件内容，与"解析到哪"解耦；
 *  - [Snapshot.forward] / [Snapshot.reverse] 由 raw 派生，文件集合或内容一变就**整体重算**
 *    （纯 CPU，不重读盘）——改名、新增同名文件都会改变既有链接的解析结果，必须重算。
 *
 * 持久化（[LinkCacheStore]）：raw / 指纹 / 别名声明落库，冷启动只做一次目录 stat 比对，
 * 正文只在文件真的变化时才读；[invalidate] 只丢内存快照，下次从缓存恢复后仍然增量。
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
    private val vaultNameIndex: VaultNameIndex,
    private val linkCacheStore: LinkCacheStore
) {

    private data class Snapshot(
        val rootPath: String,
        val builtAt: Long,
        val fingerprints: Map<String, CachedFingerprint>,
        /** 写盘后被显式标记为需要重读的路径（消除同秒 mtime 的边界） */
        val dirty: Set<String>,
        val raw: Map<String, List<RawLinkRef>>,
        val forward: Map<String, List<LinkOutItem>>,
        val reverse: Map<String, List<BacklinkItem>>,
        /** 别名声明（foldName → 条目）：随缓存持久化，冷启动不再重读全库文件头 */
        val aliases: Map<String, List<NoteAlias>>
    )

    private val buildMutex = Mutex()

    /** 是否正在构建索引（UI 可据此区分"没反链"与"正在扫"）。 */
    @Volatile
    private var building: Boolean = false

    val isBuilding: Boolean get() = building

    @Volatile
    private var snapshot: Snapshot? = null

    /** 当前笔记的出链（按首次出现顺序，同目标合并计数）。 */
    fun outLinksOf(path: String): List<LinkOutItem> = snapshot?.forward?.get(keyOf(path)).orEmpty()

    /** 指向当前笔记的反向引用（按来源相对路径 + 行号排序）。 */
    fun backlinksOf(path: String): List<BacklinkItem> = snapshot?.reverse?.get(keyOf(path)).orEmpty()

    /**
     * 当前快照的原始引用表（源绝对路径 → 正文引用，偏移相对正文）。
     * 改名后的链接改写（[LinkRewritePlanner]）需要"改名前"的解析上下文，故只读暴露。
     */
    fun rawSnapshot(): Map<String, List<RawLinkRef>> = snapshot?.raw.orEmpty()

    /** 该仓库的索引是否已建好：未建时 [LinkRewriter.plan] 会冷扫一次全库。 */
    fun isReadyFor(vaultPath: String): Boolean = snapshot?.rootPath == keyOf(vaultPath)

    /**
     * 外部改动检测结果（同步拉取 / 其他 App 或脚本修改）：只做 size + mtime 比对，不读正文。
     * [isEmpty] 为 true 表示索引快照仍可信。
     */
    data class ExternalDiff(
        val changed: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
        val added: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty() && added.isEmpty()
    }

    /**
     * 与快照指纹对比检测外部改动；索引未构建时返回空 diff（未建就没有可信度可言）。
     * 发现差异后调用方应 [invalidate]，让下一次 [buildOrRefresh] 全量重算。
     */
    suspend fun detectExternalChanges(vaultPath: String): ExternalDiff {
        val key = keyOf(vaultPath)
        val current = snapshot?.takeIf { it.rootPath == key } ?: return ExternalDiff()
        return withContext(Dispatchers.IO) {
            val notes = vaultRepository.listAllNotes(key)
            val now = HashMap<String, CachedFingerprint>(notes.size)
            notes.forEach { now[it.absolutePath] = CachedFingerprint(it.size, it.lastModified) }
            val changed = ArrayList<String>()
            val added = ArrayList<String>()
            for ((path, fingerprint) in now) {
                val prev = current.fingerprints[path]
                when {
                    prev == null -> added += path
                    prev != fingerprint -> changed += path
                }
            }
            val removed = current.fingerprints.keys.filter { it !in now }
            ExternalDiff(changed, removed, added)
        }
    }

    /**
     * 确保索引可用：已就绪直接返回 true；否则构建一次并返回是否成功。
     * 与 [buildOrRefresh] 同一构建路径（不另开扫描逻辑）。
     */
    suspend fun ensureReady(
        vaultPath: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean {
        if (isReadyFor(vaultPath)) return true
        return runCatching {
            buildOrRefresh(vaultPath) { done, total -> onProgress?.invoke(done, total) }
            isReadyFor(vaultPath)
        }.getOrDefault(false)
    }

    /** 未链接提及缓存：`vault|目标|名字索引版本 → 结果`（只保留最近一次）。 */
    private var mentionsCache: Pair<String, List<BacklinkItem>>? = null

    /**
     * 未链接提及：全库正文里出现了目标笔记名字 / 别名，但该行没有任何双链的行。
     *
     * 面板打开时按需扫描（结果按 vault + 目标 + 名字索引版本缓存），只扫前 [limit] 条；
     * 已链接的行通过引用行号直接排除，不再依赖文本比对（避免"既算反链又算提及"）。
     */
    suspend fun unlinkedMentionsOf(
        vaultPath: String,
        path: String,
        limit: Int = MENTIONS_LIMIT
    ): List<BacklinkItem> {
        val root = File(vaultPath).normalize()
        val target = keyOf(path)
        buildOrRefresh(vaultPath)
        val cacheKey = "${root.absolutePath}|$target|${vaultNameIndex.version}"
        mentionsCache?.let { (key, items) -> if (key == cacheKey) return items }
        val snap = snapshot ?: return emptyList()
        val names = mentionNamesOf(root, target)
        val out = ArrayList<BacklinkItem>()
        if (names.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                for (node in vaultRepository.listAllNotes(root.absolutePath)) {
                    if (out.size >= limit) break
                    if (keyOf(node.absolutePath) == target) continue
                    val text = vaultRepository.contentFor(node, VaultRepository.INDEX_MAX_BYTES)
                        ?: continue
                    collectMentions(
                        root = root,
                        node = node,
                        body = FrontmatterParser.split(text).body,
                        refs = snap.raw[node.absolutePath].orEmpty(),
                        names = names,
                        out = out,
                        limit = limit
                    )
                }
            }
        }
        val result = out.toList()
        mentionsCache = cacheKey to result
        return result
    }

    /**
     * 标记某篇笔记内容已变（写盘后调用）：下次刷新必定重读该文件，且绕过正文缓存。
     * 同秒写盘（mtime 未变且 size 相同）时，指纹比对与正文缓存都会失真——必须直读磁盘。
     * 快照缺失（冷启动 / 刚被同步失效）时先记入待重读集合，等下一次构建一并生效。
     */
    @Synchronized
    fun markChanged(path: String) {
        val key = keyOf(path)
        val current = snapshot
        if (current == null) {
            pendingDirty += key
            return
        }
        if (!current.fingerprints.containsKey(key)) return
        snapshot = current.copy(builtAt = 0L, dirty = current.dirty + key)
    }

    /** 快照缺失期间累积的待重读路径（随构建成功清空）。 */
    private val pendingDirty = HashSet<String>()

    @Synchronized
    private fun pendingDirtySnapshot(): Set<String> = pendingDirty.toSet()

    @Synchronized
    private fun clearPendingDirty(keys: Set<String>) {
        pendingDirty.removeAll(keys)
    }

    /**
     * 清空内存索引（仓库切换 / 同步后失效等场景）。持久化缓存**保留**：
     * 下一次 [buildOrRefresh] 会从缓存恢复，只重读真正变化的文件，不再全库扫。
     */
    @Synchronized
    fun invalidate() {
        snapshot = null
    }

    /** 丢弃某仓库的持久化缓存（仓库被移除时调用；下次重建会重新全量提取）。 */
    suspend fun forget(vaultPath: String) {
        val key = keyOf(vaultPath)
        if (snapshot?.rootPath == key) snapshot = null
        runCatching { linkCacheStore.clear(key) }
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
            building = true
            try {
                withContext(Dispatchers.IO) { rebuild(root, key, prev, onProgress) }
            } finally {
                building = false
            }
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
        val fingerprints = HashMap<String, CachedFingerprint>(notes.size)
        notes.forEach { fingerprints[it.absolutePath] = CachedFingerprint(it.size, it.lastModified) }

        val previous = prev?.takeIf { it.rootPath == key }
        // 内存快照缺失（冷启动 / 同步后失效）时从持久化缓存恢复：
        // 指纹一致的文件不读正文，只对真正变化的文件重新提取
        val cached = if (previous == null) runCatching { linkCacheStore.load(key) }.getOrNull() else null
        val baseFingerprints = previous?.fingerprints ?: cached?.fingerprints.orEmpty()
        val baseRaw = previous?.raw ?: cached?.refs.orEmpty()
        val baseAliases = previous?.aliases ?: cached?.aliases.orEmpty()

        val removed = baseFingerprints.keys - fingerprints.keys
        val added = fingerprints.keys - baseFingerprints.keys
        val dirty = previous?.dirty.orEmpty() + pendingDirtySnapshot()
        val changed = notes.filter { node ->
            node.absolutePath in dirty ||
                (baseFingerprints[node.absolutePath]?.let { it != fingerprints[node.absolutePath] } ?: true)
        }
        if (previous != null && removed.isEmpty() && added.isEmpty() && changed.isEmpty()) {
            snapshot = previous.copy(builtAt = System.currentTimeMillis())
            return
        }
        if (previous == null && cached != null && removed.isEmpty() && added.isEmpty() && changed.isEmpty()) {
            // 缓存命中且文件全部未变：零正文读取，只重算派生表
            val raw = HashMap<String, List<RawLinkRef>>(fingerprints.size)
            fingerprints.keys.forEach { raw[it] = baseRaw[it].orEmpty() }
            publish(key, fingerprints, emptySet(), raw, baseAliases, root, vaultNameIndex.names(root))
            return
        }
        // 文件集合变了：名字 / 别名索引可能是旧的（改名、新建同名文件），先失效再取
        if (previous == null || removed.isNotEmpty() || added.isNotEmpty()) {
            vaultNameIndex.invalidate()
        }
        val names = vaultNameIndex.names(root)

        val changedPaths = changed.mapTo(HashSet()) { it.absolutePath }
        // 删除或内容变更的文件：旧别名条目先剔除（变更文件可能改掉 aliases 声明）
        val staleRelative = (removed + changedPaths).mapTo(HashSet()) { relativePathOf(root, File(it)) }
        val aliases = HashMap<String, MutableList<NoteAlias>>(baseAliases.size)
        baseAliases.forEach { (folded, list) ->
            val kept = list.filter { it.relativePath !in staleRelative }
            if (kept.isNotEmpty()) aliases[folded] = kept.toMutableList()
        }

        val raw = HashMap<String, List<RawLinkRef>>(notes.size)
        val pending = ArrayList<CachedFile>()
        var done = 0
        for (node in notes) {
            val path = node.absolutePath
            if (path !in changedPaths) {
                raw[path] = baseRaw[path].orEmpty()
            } else {
                val refs = extractLinks(node, forceRead = path in dirty)
                raw[path] = refs
                // 变化文件顺带重读头部：别名声明可能与正文一起改了，缓存需要同步更新
                val declared = vaultNameIndex.aliasesOf(root, File(path))
                declared.forEach { alias ->
                    aliases.getOrPut(WikiLinkResolver.foldName(alias.name)) { ArrayList() }.add(alias)
                }
                pending += CachedFile(
                    path = path,
                    relativePath = relativePathOf(root, File(path)),
                    fingerprint = fingerprints.getValue(path),
                    refs = refs,
                    aliases = declared.map { it.name }
                )
            }
            done++
            if (done and 15 == 0) yield()
            onProgress(done, notes.size)
        }

        // 缓存写回：分批提交，避免一次性构造过多行
        pending.chunked(CACHE_WRITE_BATCH).forEach { chunk -> linkCacheStore.saveFiles(key, chunk) }
        if (removed.isNotEmpty()) {
            linkCacheStore.removeFiles(key, removed.map { RemovedFile(it, relativePathOf(root, File(it))) })
        }
        publish(key, fingerprints, emptySet(), raw, aliases.mapValues { it.value.toList() }, root, names)
        clearPendingDirty(dirty)
    }

    /** 由原始引用派生正向 / 反向表并原子发布（纯 CPU，不读盘）。 */
    private fun publish(
        key: String,
        fingerprints: Map<String, CachedFingerprint>,
        dirty: Set<String>,
        raw: Map<String, List<RawLinkRef>>,
        aliases: Map<String, List<NoteAlias>>,
        root: File,
        names: Map<String, List<String>>
    ) {
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
        // 同一来源的多行引用 → 回填"该来源指向本笔记的次数"，供面板显示 ×N
        val counted = reverse.mapValues { (_, list) ->
            val perSource = HashMap<String, Int>()
            list.forEach { perSource[it.sourcePath] = (perSource[it.sourcePath] ?: 0) + 1 }
            list.map { it.copy(count = perSource[it.sourcePath] ?: 1) }
        }
        snapshot = Snapshot(
            rootPath = key,
            builtAt = System.currentTimeMillis(),
            fingerprints = fingerprints,
            dirty = dirty,
            raw = raw,
            forward = forward,
            reverse = counted.mapValues { it.value.toList() },
            aliases = aliases
        )
        // 把别名表推给名字索引：点击解析 / 编辑补全 / 改名 / AI 引用不再重扫全库文件头
        vaultNameIndex.primeAliases(root, aliases)
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

    /** 目标笔记的可搜索名：去扩展名文件名 + 其声明的全部别名。 */
    private fun mentionNamesOf(root: File, targetAbs: String): List<String> {
        val names = LinkedHashSet<String>()
        val fileName = File(targetAbs).name
        names += if (fileName.endsWith(".md", ignoreCase = true)) fileName.dropLast(3) else fileName
        // 别名走索引快照（随缓存持久化），仅快照缺失时才回退到读文件头
        val aliasMap = snapshot?.aliases
            ?: runCatching { vaultNameIndex.aliases(root) }.getOrDefault(emptyMap())
        aliasMap.values.forEach { list ->
            list.forEach { alias ->
                if (File(root, alias.relativePath).normalize().absolutePath == targetAbs) {
                    names += alias.name
                }
            }
        }
        return names.filter { it.isNotBlank() }
    }

    /** 逐行找未链接提及：跳过已有链接的行，并把行内双链区段遮蔽后再匹配。 */
    private fun collectMentions(
        root: File,
        node: FileNode,
        body: String,
        refs: List<RawLinkRef>,
        names: List<String>,
        out: MutableList<BacklinkItem>,
        limit: Int
    ) {
        val lowered = names.map { it.lowercase() }.filter { it.isNotEmpty() }
        if (lowered.isEmpty()) return
        val linkedLines = refs.mapTo(HashSet()) { it.line }
        var line = 0
        for (raw in body.split('\n')) {
            if (out.size >= limit) return
            if (line !in linkedLines && raw.isNotBlank()) {
                val masked = maskWikiLinks(raw).lowercase()
                if (lowered.any { masked.contains(it) }) {
                    out += BacklinkItem(
                        sourcePath = node.absolutePath,
                        relativePath = relativePathOf(root, File(node.absolutePath)),
                        line = line,
                        display = WikiLinkExtractor.plainLine(raw),
                        snippet = WikiLinkExtractor.plainLine(raw)
                    )
                }
            }
            line++
        }
    }

    /** 把 `[[...]]` 替换为等长空白（保持字符偏移不变），避免把已链接内容重复算作提及。 */
    private fun maskWikiLinks(line: String): String {
        if (!line.contains("[[")) return line
        val sb = StringBuilder(line)
        for (m in WikiLinkTransformer.WIKI.findAll(line)) {
            for (i in m.range) sb.setCharAt(i, ' ')
        }
        return sb.toString()
    }

    companion object {
        /** 索引有效期：覆盖同一轮面板内的多次读取；写盘后经 [markChanged] 立即失效。 */
        private const val TTL_MS = 2_000L

        /** 未链接提及单次最多返回条数（面板展示上限）。 */
        private const val MENTIONS_LIMIT = 100

        /** 缓存写回分批大小：控制单次事务的行数。 */
        private const val CACHE_WRITE_BATCH = 200

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
