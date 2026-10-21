package com.az.notes.data.storage

import com.az.notes.domain.markdown.FrontmatterParser
import com.az.notes.domain.markdown.TagMatcher
import com.az.notes.domain.model.FileNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/** 标签行（标签页展示）：[name] 为全库首次出现的写法，[count] 为含该标签的文档数。 */
data class TagRow(val name: String, val count: Int)

/**
 * 全库标签倒排索引（内存态）：`标签小写 → 含该标签的文档`。
 *
 * 进入标签页时经 [buildOrRefresh] 后台构建 / 增量刷新：首次或仓库切换时全量
 * 冷扫（正文读取复用 [VaultRepository.contentFor] 的 mtime 缓存，与搜索共享），
 * 之后以文件大小 + mtime 指纹比对，仅重读变化文件、剔除已删除文件。
 * 标签大小写不敏感合并（[TagMatcher.keyOf]），展示名保留全库首次出现的写法。
 * 构建在局部副本上进行、完成后原子发布，读取方始终看到完整索引。
 */
@Singleton
class TagIndex @Inject constructor(
    private val vaultRepository: VaultRepository
) {

    private data class Entry(
        val displayName: String,
        val docs: LinkedHashMap<String, FileNode>
    )

    private data class Fingerprint(val size: Long, val mtime: Long)

    @Volatile
    private var rootPath: String? = null

    @Volatile
    private var index: LinkedHashMap<String, Entry> = LinkedHashMap()

    private var fingerprints = HashMap<String, Fingerprint>()

    /** 全部现存标签（计数降序、同计数按名称字典序）。 */
    fun all(): List<TagRow> = index.values
        .map { TagRow(it.displayName, it.docs.size) }
        .sortedWith(compareByDescending<TagRow> { it.count }.thenBy { it.name.lowercase() })

    /** 含该标签的全部文档（大小写不敏感，按修改时间倒序）。 */
    fun docsOf(tag: String): List<FileNode> =
        index[TagMatcher.keyOf(tag.trim().removePrefix("#"))]
            ?.docs?.values?.sortedByDescending { it.lastModified }
            .orEmpty()

    /** 清空索引（仓库切换时由 [buildOrRefresh] 自动重建，无需调用）。 */
    fun invalidate() {
        rootPath = null
        index = LinkedHashMap()
        fingerprints = HashMap()
    }

    /**
     * 构建 / 增量刷新索引。全程在 IO 线程执行，分批让渡避免占死线程；
     * [onProgress] 每处理一篇回调一次（当前 / 总数），增量无变化时总数可为 0。
     */
    suspend fun buildOrRefresh(
        vaultPath: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) {
        withContext(Dispatchers.IO) {
            val notes = vaultRepository.listAllNotes(vaultPath)
            val current = HashMap<String, Fingerprint>(notes.size)
            notes.forEach { current[it.absolutePath] = Fingerprint(it.size, it.lastModified) }
            val fullRebuild = rootPath != vaultPath
            rootPath = vaultPath
            val working = LinkedHashMap<String, Entry>()
            if (!fullRebuild) {
                index.forEach { (k, v) -> working[k] = Entry(v.displayName, LinkedHashMap(v.docs)) }
            }
            if (fullRebuild) {
                scanBatch(notes, working, onProgress)
            } else {
                // 已删除的文件：从全部标签的文档集移除并清理空标签
                val removed = fingerprints.keys - current.keys
                if (removed.isNotEmpty()) {
                    working.values.forEach { entry -> entry.docs.keys.removeAll(removed) }
                    working.entries.removeIf { it.value.docs.isEmpty() }
                }
                // 新增 / 变化的文件：清掉旧标签再重扫
                val changed = notes.filter { fingerprints[it.absolutePath] != current[it.absolutePath] }
                scanBatch(changed, working, onProgress)
            }
            index = working
            fingerprints = current
        }
    }

    /** 分批扫描 [notes] 的标签并入 [index]，每批让渡协程并回调进度。 */
    private suspend fun scanBatch(
        notes: List<FileNode>,
        index: LinkedHashMap<String, Entry>,
        onProgress: (Int, Int) -> Unit
    ) {
        var done = 0
        for (note in notes) {
            index.values.forEach { it.docs.remove(note.absolutePath) }
            index.entries.removeIf { it.value.docs.isEmpty() }
            scan(note, index)
            done++
            if (done and 15 == 0) yield()
            onProgress(done, notes.size)
        }
    }

    /** 扫描单篇笔记的标签（正文 + frontmatter tags 属性）并入 [index]。 */
    private fun scan(node: FileNode, index: LinkedHashMap<String, Entry>) {
        val text = vaultRepository.contentFor(node) ?: return
        val split = FrontmatterParser.split(text)
        split.tags().forEach { add(it, node, index) }
        TagMatcher.extract(split.body).forEach { add(it.tag, node, index) }
    }

    /** 并入单个标签：大小写归一化合并，展示名保留首次出现的写法。 */
    private fun add(tag: String, node: FileNode, index: LinkedHashMap<String, Entry>) {
        val entry = index.getOrPut(TagMatcher.keyOf(tag)) { Entry(tag, LinkedHashMap()) }
        entry.docs[node.absolutePath] = node
    }
}
