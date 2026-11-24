package com.az.notes.data.media

import com.az.notes.data.storage.VaultRepository
import com.az.notes.domain.markdown.ImageReference
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 附件（图片）引用索引（设计理念：附件必须依托笔记存在）。
 *
 * 递归扫描仓库：收集全部附件文件，读取每篇笔记正文并用 [ImageReference] 解析引用，
 * 建立「附件绝对路径 → 引用它的笔记集合」映射。由此可判定：
 * - 孤儿图片：未被任何笔记引用（可清理）；
 * - 仅本文引用的附件：删除 / 移动某篇笔记时应联动的对象。
 *
 * 与渲染层复用同一套解析规则（[ImageReference]），避免两处逻辑漂移；
 * 大笔记（超过 [VaultRepository.CONTENT_SEARCH_MAX_BYTES]）跳过正文解析并计入
 * [OrphanReport.unscannedNotes]，在页面提示，避免把在用附件误判为孤儿。
 */
@Singleton
class AttachmentRepository @Inject constructor(
    private val vaultRepository: VaultRepository
) {

    /** 忽略的配置目录（与 VaultRepository 保持一致）。 */
    private val ignoredDirs = setOf(".obsidian", ".trash", ".git")

    /** 孤儿图片扫描结果。 */
    data class OrphanReport(
        val orphans: List<File>,
        val totalBytes: Long,
        /** 因超过正文扫描上限而未纳入引用解析的笔记数（可能低估引用，页面需提示）。 */
        val unscannedNotes: Int
    )

    /** 某篇笔记引用附件的统计（供删除 / 移动联动）。 */
    data class ReferencedAttachments(
        /** 本文引用的附件总数。 */
        val total: Int,
        /** 仅被本文引用的附件数（删除本文后将成为孤儿）。 */
        val exclusiveCount: Int,
        /** 仅被本文引用的附件文件（联动移入回收站的对象）。 */
        val exclusiveFiles: List<File>
    ) {
        val hasExclusive: Boolean get() = exclusiveCount > 0
    }

    /** 递归列出仓库内全部图片附件（忽略隐藏项 / 配置目录）。 */
    fun listAttachments(vaultRoot: File): List<File> {
        if (!vaultRoot.isDirectory) return emptyList()
        val out = ArrayList<File>()
        collectAttachments(vaultRoot, out)
        return out
    }

    private fun collectAttachments(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".") || f.name in ignoredDirs) continue
            if (f.isDirectory) {
                collectAttachments(f, out)
            } else if (VaultRepository.isImageName(f.name)) {
                out += f
            }
        }
    }

    /**
     * 建立「附件绝对路径 → 引用它的笔记绝对路径集合」索引。
     *
     * @param onProgress 逐篇笔记回调（已处理数, 总数），供页面展示扫描进度
     * @return unscanned 计数随索引一并返回（超大笔记跳过正文）
     */
    fun buildReferenceIndex(
        vaultRoot: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): IndexResult {
        val root = vaultRoot.normalize()
        return buildReferenceIndex(root, listAttachments(root), onProgress)
    }

    /** 索引构建实现（attachments 由调用方预先列出，[orphans] 与其共用同一次全库扫描）。 */
    private fun buildReferenceIndex(
        root: File,
        attachments: List<File>,
        onProgress: (Int, Int) -> Unit
    ): IndexResult {
        val notes = ArrayList<File>()
        if (root.isDirectory) collectNotes(root, notes)
        val nameIndex = buildNameIndex(root, attachments)
        val index = HashMap<String, MutableSet<String>>()
        var unscanned = 0
        val total = notes.size
        notes.forEachIndexed { i, note ->
            onProgress(i + 1, total)
            val text = if (note.length() > VaultRepository.CONTENT_SEARCH_MAX_BYTES) {
                unscanned++
                null
            } else {
                runCatching { vaultRepository.readText(note.absolutePath) }.getOrNull()
            }
            if (text == null) return@forEachIndexed
            val noteDir = note.parentFile ?: return@forEachIndexed
            ImageReference.extract(text).forEach { ref ->
                if (ImageReference.isRemote(ref)) return@forEach
                val resolved = ImageReference.resolveExisting(ref, root, noteDir, nameIndex) ?: return@forEach
                // 笔记互链 `[文字](other.md)` 不是附件引用：剔除，避免删除/移动统计把笔记算成附件
                if (VaultRepository.isMarkdownName(resolved.name)) return@forEach
                index.getOrPut(resolved.absolutePath) { LinkedHashSet() }.add(note.absolutePath)
            }
        }
        return IndexResult(index, unscanned)
    }

    /** 索引构建产出。 */
    data class IndexResult(val index: Map<String, Set<String>>, val unscannedNotes: Int)

    /** 孤儿图片：附件存在但不在引用索引内。 */
    fun orphans(vaultRoot: File, onProgress: (Int, Int) -> Unit = { _, _ -> }): OrphanReport {
        val root = vaultRoot.normalize()
        val attachments = listAttachments(root)
        val result = buildReferenceIndex(root, attachments, onProgress)
        val orphanFiles = attachments.filter { it.absolutePath !in result.index }
        return OrphanReport(
            orphans = orphanFiles.sortedBy { it.absolutePath },
            totalBytes = orphanFiles.sumOf { it.length() },
            unscannedNotes = result.unscannedNotes
        )
    }

    /** 统计某篇笔记引用的附件（总数 / 仅本文引用数与文件），供删除 / 移动联动。 */
    fun referencedByNote(vaultRoot: File, notePath: String): ReferencedAttachments {
        val root = vaultRoot.normalize()
        val result = buildReferenceIndex(root)
        var total = 0
        val exclusive = ArrayList<File>()
        result.index.forEach { (attachmentAbs, notes) ->
            if (notePath in notes) {
                total++
                if (notes.size == 1) exclusive += File(attachmentAbs)
            }
        }
        return ReferencedAttachments(total, exclusive.size, exclusive.sortedBy { it.absolutePath })
    }

    // ---------------------------------------------------------------- 内部

    /** 「查看引用」命中的一篇笔记：首个引用所在行号与该行摘要。 */
    data class ReferenceHit(
        val notePath: String,
        val relativePath: String,
        val line: Int,
        val snippet: String
    )

    /**
     * 查找引用了 [imagePath] 的笔记（每篇只列首个引用行）。
     *
     * 逐行判断以便给出准确行号，代码围栏内的示例链接跳过；
     * 先用文件名做一次整篇包含判断，未提及的笔记直接跳过（绝大多数笔记不含该图）。
     */
    fun referencesOf(
        vaultRoot: File,
        imagePath: String,
        limit: Int = REFERENCE_LIMIT
    ): List<ReferenceHit> {
        val root = vaultRoot.normalize()
        val target = File(imagePath).normalize().absolutePath
        val imageName = File(target).name
        val attachments = listAttachments(root)
        val nameIndex = buildNameIndex(root, attachments)
        val notes = ArrayList<File>().also { collectNotes(root, it) }
        val out = ArrayList<ReferenceHit>()
        for (note in notes) {
            if (out.size >= limit) break
            if (note.length() > VaultRepository.CONTENT_SEARCH_MAX_BYTES) continue
            val text = runCatching { vaultRepository.readText(note.absolutePath) }.getOrNull() ?: continue
            if (!text.contains(imageName, ignoreCase = true)) continue
            val noteDir = note.parentFile ?: continue
            var hitLine = -1
            var hitSnippet = ""
            forEachBodyLine(text) { lineNo, line ->
                val matched = ImageReference.extract(line).any { ref ->
                    !ImageReference.isRemote(ref) &&
                        ImageReference.resolveExisting(ref, root, noteDir, nameIndex)
                            ?.absolutePath == target
                }
                if (matched) {
                    hitLine = lineNo
                    hitSnippet = line.trim().take(SNIPPET_MAX_CHARS)
                    false
                } else {
                    true
                }
            }
            if (hitLine >= 0) {
                out += ReferenceHit(
                    notePath = note.absolutePath,
                    relativePath = relativePathOf(root, note),
                    line = hitLine,
                    snippet = hitSnippet
                )
            }
        }
        return out
    }

    /** 逐行遍历正文并跳过代码围栏内的行（围栏内的示例链接不算引用）。 */
    private inline fun forEachBodyLine(text: String, action: (lineNo: Int, line: String) -> Boolean) {
        var fence = false
        var index = 0
        for (line in text.split('\n')) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fence = !fence
            } else if (!fence && !action(index, line)) {
                return
            }
            index++
        }
    }

    private fun relativePathOf(root: File, file: File): String =
        file.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')

    private companion object {
        /** 「查看引用」单次最多返回的笔记数（浮层展示上限）。 */
        const val REFERENCE_LIMIT = 200

        /** 引用行摘要的截断长度。 */
        const val SNIPPET_MAX_CHARS = 160
    }

    private fun collectNotes(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith(".") || f.name in ignoredDirs) continue
            if (f.isDirectory) {
                collectNotes(f, out)
            } else if (VaultRepository.isMarkdownName(f.name)) {
                out += f
            }
        }
    }

    /** 附件文件名（小写）→ 仓库内相对路径列表，供只写文件名的引用（`![[名字]]`）命中。 */
    private fun buildNameIndex(root: File, attachments: List<File>): Map<String, List<String>> {
        val rootAbs = root.absolutePath
        val map = HashMap<String, MutableList<String>>()
        attachments.forEach { file ->
            val relative = file.absolutePath.removePrefix(rootAbs).trimStart('/', '\\')
            map.getOrPut(file.name.lowercase(Locale.ROOT)) { ArrayList() }.add(relative)
        }
        return map
    }
}
