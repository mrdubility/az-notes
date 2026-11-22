package com.az.notes.domain.markdown

import java.io.File

/** 单条待写回替换：偏移相对**正文**（frontmatter 之后），由调用方叠加前缀长度。 */
data class PlannedEdit(
    val startInBody: Int,
    val endInBody: Int,
    /** 期望的原文切片（写前校验，防偏移漂移覆盖错内容） */
    val expectedRaw: String,
    /** 新链接文本 */
    val replacement: String,
    /** 触发本次改写的旧绝对路径：批量移动部分失败时用它过滤 */
    val movedFrom: String
)

/** 一个源文件的改写计划。 */
data class NoteRewritePlan(val sourcePath: String, val edits: List<PlannedEdit>)

/**
 * 改名 / 移动后的链接改写计划器（纯 Kotlin，零 IO，可在 JVM 单测运行）。
 *
 * 判定口径不再是"文本是否长得像旧名字"，而是**改名前的解析结果**：只有
 * "改名前解析到被移动文件"的引用才进入计划。因此
 *  - 通过别名命中的 `[[别名]]`（别名本身未变）不会解析结果变化，不会被误改；
 *  - 同名文件的消歧翻转（原本解析到 A，A 改名后改为解析到 B）同样只会命中 A 的引用；
 *  - 图片嵌入 `![[a.png]]` 与笔记嵌入 `![[Note]]` 走同一套判断。
 *
 * 新文本只替换目标段，保留 `!` 前缀、`#锚点`、`|别名`；若别名与被改名文件的旧文件名
 * 完全相同（`[[旧名|旧名]]` 这类写法），别名同步换成新名。
 */
object LinkRewritePlanner {

    /**
     * @param refs 源笔记正文的原始引用（[RawLinkRef.startOffset] 相对正文）
     * @param moveByOldPath 旧绝对路径 → 新绝对路径（目录移动时由调用方展开为逐篇映射）
     * @param resolve 改名前状态的解析函数（返回绝对路径；未命中 null）
     */
    fun planForSource(
        sourcePath: String,
        refs: List<RawLinkRef>,
        moveByOldPath: Map<String, String>,
        vaultRoot: File,
        resolve: (RawLinkRef) -> String?
    ): NoteRewritePlan? {
        if (refs.isEmpty() || moveByOldPath.isEmpty()) return null
        val root = vaultRoot.normalize()
        val edits = ArrayList<PlannedEdit>()
        for (ref in refs) {
            if (WikiLinkExtractor.isSelfAnchor(ref)) continue
            val before = resolve(ref) ?: continue
            val after = moveByOldPath[before] ?: continue
            val replacement = rebuild(ref, before, after, root)
            if (replacement == ref.raw) continue
            edits += PlannedEdit(
                startInBody = ref.startOffset,
                endInBody = ref.endOffset,
                expectedRaw = ref.raw,
                replacement = replacement,
                movedFrom = before
            )
        }
        return if (edits.isEmpty()) null else NoteRewritePlan(sourcePath, edits)
    }

    /**
     * 按原写法重建链接：目标段换成新名（原写法含 `/` 时换成新仓库相对路径，保留原前导 `/`），
     * 锚点与别名保持不变（别名等于旧文件名时同步换新）。
     */
    fun rebuild(ref: RawLinkRef, oldAbsPath: String, newAbsPath: String, vaultRoot: File): String {
        val newRel = relativeOf(vaultRoot, File(newAbsPath))
        val oldName = File(oldAbsPath).name
        val newName = File(newAbsPath).name
        val targetPart = when {
            ref.target.startsWith("/") -> "/" + dropMd(newRel)
            ref.target.contains('/') -> dropMd(newRel)
            else -> dropMd(newName)
        }
        val anchorPart = ref.anchor?.let { "#$it" }.orEmpty()
        val alias = ref.alias?.let { original ->
            if (original.trim() == dropMd(oldName)) dropMd(newName) else original
        }
        val aliasPart = alias?.let { "|$it" }.orEmpty()
        val embed = if (ref.isEmbed) "!" else ""
        return "$embed[[$targetPart$anchorPart$aliasPart]]"
    }

    private fun relativeOf(root: File, file: File): String =
        file.normalize().absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')

    private fun dropMd(name: String): String =
        if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name
}
