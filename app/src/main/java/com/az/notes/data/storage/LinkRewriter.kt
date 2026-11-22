package com.az.notes.data.storage

import com.az.notes.domain.markdown.FrontmatterParser
import com.az.notes.domain.markdown.LinkRewritePlanner
import com.az.notes.domain.markdown.NoteAlias
import com.az.notes.domain.markdown.PlannedEdit
import com.az.notes.domain.markdown.RawLinkRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 改名 / 移动后的双链批量改写器（IO 层，两阶段）。
 *
 * 阶段划分的原因：判定口径是"改前解析到被移动文件、且改后同一段文本不再指向新位置"。
 *  - [plan] 在**移动之前**运行：只负责把每条引用的"改前解析结果"钉下来；
 *  - [apply] 在**移动之后**运行：用当前（已移动的）文件系统与索引重算"改后解析结果"，
 *    只有真的不再指向新位置的引用才改写。
 *
 * 这条"改前 / 改后"对比是保住用户写法的关键：`[[别名]]`、`[[Note]]` 这类改名后仍能
 * 解析到同一目标的写法一律不动。
 *
 * 其他安全口径：
 *  - 偏移漂移（文件在计划后被改过）→ 该条跳过并计数，不做文本级兜底替换；
 *  - 批量移动部分失败 → 用 [succeeded]（文件级旧绝对路径）过滤，失败项的引用不动；
 *  - 源笔记自身被移动时，改写目标是它的**新路径**，改后解析也按新目录计算；
 *  - 单次最多改写 [MAX_FILES] 个源文件，超出部分计入 [Outcome.truncated] 告知用户。
 */
@Singleton
class LinkRewriter @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val linkIndex: VaultLinkIndex,
    private val vaultNameIndex: VaultNameIndex
) {

    /**
     * 计划阶段捕获的快照：[entries] 为"源笔记 → (引用, 改前解析到的绝对路径)"。
     * [moves] 必须是展开到**文件级**的旧→新绝对路径映射。
     */
    data class Prepared(
        val vaultPath: String,
        val moves: Map<String, String>,
        val entries: Map<String, List<Pair<RawLinkRef, String?>>>
    )

    /**
     * 改写结果：[files] 写入文件数、[links] 替换链接数、[skipped] 因漂移/失败跳过的条数、
     * [truncated] 因超过单次上限而未处理的源文件数（>0 时需提示用户）。
     */
    data class Outcome(
        val files: Int = 0,
        val links: Int = 0,
        val skipped: Int = 0,
        val truncated: Int = 0,
        /** 被写入的源文件绝对路径（供同步强制上传使用） */
        val paths: List<String> = emptyList()
    ) {
        fun relativePaths(vaultPath: String): List<String> {
            val root = File(vaultPath).normalize().absolutePath
            return paths.map { it.removePrefix(root).trimStart('/', '\\') }
        }
    }

    /** 生成改写计划（须在改名 / 移动**之前**调用）。 */
    suspend fun plan(vaultPath: String, moves: Map<String, String>): Prepared? {
        if (moves.isEmpty()) return null
        // 用现有索引（TTL 内复用）；未构建过则冷扫一次，只为拿到 raw + 改前解析上下文
        linkIndex.buildOrRefresh(vaultPath)
        val raw = linkIndex.rawSnapshot()
        if (raw.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            val root = File(vaultPath).normalize()
            val names = vaultNameIndex.names(root)
            val aliases = vaultNameIndex.aliases(root)
            val entries = HashMap<String, List<Pair<RawLinkRef, String?>>>(raw.size)
            for ((source, refs) in raw) {
                if (refs.isEmpty()) continue
                val noteDir = File(source).parentFile
                entries[source] = refs.map { ref ->
                    ref to resolveTo(ref, root, noteDir, names, aliases)
                }
            }
            if (entries.isEmpty()) null else Prepared(root.absolutePath, moves, entries)
        }
    }

    /**
     * 执行改写（在改名 / 移动**之后**调用）；[succeeded] 为真正成功的**文件级**旧绝对路径集合。
     * 调用方应已在移动后失效 [VaultNameIndex]，使这里的解析反映移动后的状态。
     */
    suspend fun apply(prepared: Prepared?, succeeded: Set<String>): Outcome {
        if (prepared == null || succeeded.isEmpty() || prepared.entries.isEmpty()) return Outcome()
        return withContext(Dispatchers.IO) {
            val root = File(prepared.vaultPath).normalize()
            val names = vaultNameIndex.names(root)
            val aliases = vaultNameIndex.aliases(root)
            // 只考虑真正成功的移动，失败项的引用一律不动
            val activeMoves = prepared.moves.filterKeys { it in succeeded }
            var files = 0
            var links = 0
            var skipped = 0
            var truncated = 0
            var processed = 0
            val paths = ArrayList<String>()
            for ((source, pairs) in prepared.entries) {
                // 源笔记自身被移动时：读写的目标换成新路径，改后解析也按新目录算
                val movedSelf = activeMoves[source]
                val target = movedSelf ?: source
                val noteDirAfter = File(target).parentFile
                val beforeByOffset = HashMap<Int, String?>(pairs.size)
                pairs.forEach { (ref, before) -> beforeByOffset[ref.startOffset] = before }
                // 判定口径只在 LinkRewritePlanner 一处：改前指向被移动文件、且改后不再指向新位置
                val plan = LinkRewritePlanner.planForSource(
                    sourcePath = target,
                    refs = pairs.map { it.first },
                    moveByOldPath = activeMoves,
                    vaultRoot = root,
                    resolveBefore = { ref -> beforeByOffset[ref.startOffset] },
                    resolveAfter = { ref -> resolveTo(ref, root, noteDirAfter, names, aliases) }
                ) ?: continue
                if (processed >= MAX_FILES) {
                    truncated++
                    continue
                }
                processed++
                val result = runCatching { rewriteOne(target, plan.edits) }.getOrNull()
                if (result == null) {
                    skipped += plan.edits.size
                    continue
                }
                skipped += result.skipped
                if (result.links > 0) {
                    files++
                    links += result.links
                    paths += target
                }
            }
            Outcome(files, links, skipped, truncated, paths)
        }
    }

    // ---------------------------------------------------------------- 内部

    private class FileResult(val links: Int, val skipped: Int)

    private fun resolveTo(
        ref: RawLinkRef,
        root: File,
        noteDir: File?,
        names: Map<String, List<String>>,
        aliases: Map<String, List<NoteAlias>>
    ): String? = when (val r = VaultLinkIndex.resolveLink(ref, root, noteDir, names, aliases)) {
        is LinkResolution.Resolved -> r.file.absolutePath
        else -> null
    }

    private fun rewriteOne(sourcePath: String, edits: List<PlannedEdit>): FileResult {
        val content = vaultRepository.readText(sourcePath)
        val split = FrontmatterParser.split(content)
        val offset = split.prefix.length
        val body = split.body
        // 前缀 + 正文必须正好等于原文：否则偏移口径不可信，整篇跳过（不做猜测性修补）
        if (offset + body.length != content.length) return FileResult(0, edits.size)

        val usable = ArrayList<PlannedEdit>(edits.size)
        var skipped = 0
        for (edit in edits.sortedByDescending { it.startInBody }) {
            val inRange = edit.startInBody >= 0 && edit.endInBody <= body.length &&
                edit.startInBody <= edit.endInBody
            val matches = inRange && body.substring(edit.startInBody, edit.endInBody) == edit.expectedRaw
            if (matches) usable += edit else skipped++
        }
        if (usable.isEmpty()) return FileResult(0, skipped)

        val sb = StringBuilder(body)
        for (edit in usable) sb.replace(edit.startInBody, edit.endInBody, edit.replacement)
        vaultRepository.writeTextAtomically(sourcePath, split.prefix + sb.toString())
        // 同秒写盘时 size + mtime 可能都不变：必须显式标脏，否则索引会读到旧引用
        linkIndex.markChanged(sourcePath)
        return FileResult(usable.size, skipped)
    }

    private companion object {
        /** 单次改名 / 移动最多改写的源文件数（对齐移动端可接受的同步上传量）。 */
        const val MAX_FILES = 200
    }
}
