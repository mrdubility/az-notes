package com.az.notes.data.storage

import com.az.notes.domain.markdown.FrontmatterParser
import com.az.notes.domain.markdown.LinkRewritePlanner
import com.az.notes.domain.markdown.NoteRewritePlan
import com.az.notes.domain.markdown.PlannedEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 改名 / 移动后的双链批量改写器（IO 层，两阶段）。
 *
 * 为什么分两阶段：
 *  1. [plan] 必须在**改名之前**运行——它要用"改名前"的解析结果判定哪些引用真的指向
 *     被移动的文件（文本匹配会把同名不同文件、别名命中的链接一起误改）；
 *  2. [apply] 在改名之后运行，逐文件重读、按偏移倒序拼接、写前校验原文切片。
 *
 * 安全口径：
 *  - 偏移漂移（文件在计划后被改过）→ 该条跳过并计数，不做文本级兜底替换；
 *  - 批量移动部分失败 → 由 [apply] 的 `succeeded` 过滤，失败项的引用一律不动；
 *  - 单次最多改写 [MAX_FILES] 个文件，超出部分留待用户手工处理（并经由返回值告知）。
 */
@Singleton
class LinkRewriter @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val linkIndex: VaultLinkIndex,
    private val vaultNameIndex: VaultNameIndex
) {

    /** 改写结果：[files] 实际写入的文件数、[links] 替换的链接数、[skipped] 因漂移/失败跳过的条数。 */
    data class Outcome(
        val files: Int,
        val links: Int,
        val skipped: Int,
        /** 被写入的源文件绝对路径（供同步强制上传使用） */
        val paths: List<String>
    ) {
        fun relativePaths(vaultPath: String): List<String> {
            val root = File(vaultPath).normalize().absolutePath
            return paths.map { it.removePrefix(root).trimStart('/', '\\') }
        }
    }

    /** 生成改写计划（须在改名 / 移动**之前**调用）。 */
    suspend fun plan(vaultPath: String, moves: Map<String, String>): List<NoteRewritePlan> {
        if (moves.isEmpty()) return emptyList()
        // 用现有索引（TTL 内复用）；未构建过则冷扫一次，只为拿到 raw + 解析上下文
        linkIndex.buildOrRefresh(vaultPath)
        val raw = linkIndex.rawSnapshot()
        if (raw.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val root = File(vaultPath).normalize()
            val names = vaultNameIndex.names(root)
            val aliases = vaultNameIndex.aliases(root)
            val plans = ArrayList<NoteRewritePlan>()
            for ((source, refs) in raw) {
                if (refs.isEmpty()) continue
                val noteDir = File(source).parentFile
                val plan = LinkRewritePlanner.planForSource(
                    sourcePath = source,
                    refs = refs,
                    moveByOldPath = moves,
                    vaultRoot = root,
                    resolve = { ref ->
                        when (val r = VaultLinkIndex.resolveLink(ref, root, noteDir, names, aliases)) {
                            is LinkResolution.Resolved -> r.file.absolutePath
                            else -> null
                        }
                    }
                )
                if (plan != null) plans += plan
            }
            plans.sortedBy { it.sourcePath }.take(MAX_FILES)
        }
    }

    /** 执行改写（在改名 / 移动**之后**调用）；[succeeded] 为真正成功的旧绝对路径集合。 */
    suspend fun apply(plans: List<NoteRewritePlan>, succeeded: Set<String>): Outcome =
        withContext(Dispatchers.IO) {
            if (plans.isEmpty() || succeeded.isEmpty()) return@withContext Outcome(0, 0, 0, emptyList())
            var files = 0
            var links = 0
            var skipped = 0
            val paths = ArrayList<String>()
            for (plan in plans) {
                val result = runCatching { rewriteOne(plan, succeeded) }.getOrNull()
                if (result == null) {
                    skipped += plan.edits.size
                    continue
                }
                skipped += result.skipped
                if (result.links > 0) {
                    files++
                    links += result.links
                    paths += plan.sourcePath
                }
            }
            Outcome(files, links, skipped, paths)
        }

    // ---------------------------------------------------------------- 内部

    private class FileResult(val links: Int, val skipped: Int)

    private fun rewriteOne(plan: NoteRewritePlan, succeeded: Set<String>): FileResult {
        val content = vaultRepository.readText(plan.sourcePath)
        val split = FrontmatterParser.split(content)
        val offset = split.prefix.length
        val body = split.body
        // 前缀 + 正文必须正好等于原文：否则偏移口径不可信，整篇跳过（不做猜测性修补）
        if (offset + body.length != content.length) return FileResult(0, plan.edits.size)

        val usable = ArrayList<PlannedEdit>(plan.edits.size)
        var skipped = 0
        for (edit in plan.edits.sortedByDescending { it.startInBody }) {
            if (edit.movedFrom !in succeeded) {
                skipped++
                continue
            }
            val inRange = edit.startInBody >= 0 && edit.endInBody <= body.length &&
                edit.startInBody <= edit.endInBody
            val matches = inRange && body.substring(edit.startInBody, edit.endInBody) == edit.expectedRaw
            if (matches) usable += edit else skipped++
        }
        if (usable.isEmpty()) return FileResult(0, skipped)

        val sb = StringBuilder(body)
        for (edit in usable) sb.replace(edit.startInBody, edit.endInBody, edit.replacement)
        vaultRepository.writeTextAtomically(plan.sourcePath, split.prefix + sb.toString())
        // 同秒写盘时 size + mtime 可能都不变：必须显式标脏，否则索引会读到旧引用
        linkIndex.markChanged(plan.sourcePath)
        return FileResult(usable.size, skipped)
    }

    private companion object {
        /** 单次改名 / 移动最多改写的源文件数（对齐移动端可接受的同步上传量）。 */
        const val MAX_FILES = 200
    }
}
