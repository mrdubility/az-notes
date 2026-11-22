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
 * 判定口径不是"文本是否长得像旧名字"，而是**改名前后解析结果是否一致**：
 *  - 引用必须改前解析到被移动的文件（否则与本次移动无关，不动）；
 *  - 且改名**后**同一段文本不再指向新位置（否则明文仍能正常解析，不动）。
 *
 * 第二条专门保护别名写法：`[[别名]]` 指向的笔记改名后，别名不变，同一文本仍解析到
 * 新路径 → 不需要也不应该改写（否则用户写的别名会被换成新文件名）。
 * 它同时覆盖同名消歧翻转（原本解析到 A，A 改名后改为解析到 B → 需要改）与相对路径
 * 写法（改名后旧路径不存在 → 需要改）。
 */
object LinkRewritePlanner {

    /**
     * @param refs 源笔记正文的原始引用（[RawLinkRef.startOffset] 相对正文）
     * @param moveByOldPath 旧绝对路径 → 新绝对路径（目录移动时由调用方展开为逐篇映射）
     * @param resolveBefore 改名前解析（返回绝对路径；未命中 null）
     * @param resolveAfter 改名**后**的同一文本解析结果：等于新路径时视为无需改写
     */
    fun planForSource(
        sourcePath: String,
        refs: List<RawLinkRef>,
        moveByOldPath: Map<String, String>,
        vaultRoot: File,
        resolveBefore: (RawLinkRef) -> String?,
        resolveAfter: (RawLinkRef) -> String?
    ): NoteRewritePlan? {
        if (refs.isEmpty() || moveByOldPath.isEmpty()) return null
        val root = vaultRoot.normalize()
        val edits = ArrayList<PlannedEdit>()
        for (ref in refs) {
            if (WikiLinkExtractor.isSelfAnchor(ref)) continue
            val before = resolveBefore(ref) ?: continue
            val newPath = moveByOldPath[before] ?: continue
            // 改后同一段文本仍能解析到新位置（典型：通过别名指向）→ 保留用户写法
            if (resolveAfter(ref) == newPath) continue
            val replacement = rebuild(ref, before, newPath, root)
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
