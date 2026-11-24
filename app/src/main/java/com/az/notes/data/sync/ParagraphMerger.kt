package com.az.notes.data.sync

import com.az.notes.domain.markdown.FrontmatterParser

/**
 * 段落级三方文本合并（纯逻辑、零 IO、可在 JVM 单测运行）。
 *
 * 粒度：以空行分隔的**段落块**为单位（代码围栏整体成块，绝不在其中切分）。笔记的编辑
 * 单元是段落，段落级能直接得出"这段谁改过"的结论，避免行级 diff 在长段内碎成一堆冲突。
 *
 * 对齐方式：**公共前后缀裁剪 + 锚点递归**。
 *  - 三个版本开头/结尾相同的块直接输出；
 *  - 中间区域找"在 base / 本地 / 远端都出现"的块作为锚点，把区域再切小；
 *  - 找不到锚点时按 diff3 的常规规则判定（只有一侧改 → 取改的一侧；两侧改成同一结果 →
 *    取其一；两侧改成不同结果 → 计一处冲突）。
 *
 * 只要 [Result.conflicts] > 0，[Result.text] 里就会带内联标记（只保留本地的那份写法会丢内容）；
 * 无祖先（[Result.usedBase] = false）时才退回本地原文。frontmatter 不参与段落合并：
 * 两侧都改了属性块且结果不同时整篇回退为冲突（避免拼出非法 YAML）。
 */
object ParagraphMerger {

    /**
     * 冲突块标记（全角括号包裹）。
     *
     * 刻意不用 Git 风格的 `<<<<<<<` / `>>>>>>>`：行首的 `>` 会被 Markdown 当成引用块，
     * 预览里会渲染成竖线（`>>>>>>> 云端` 显示成 `||||||| 云端`）。全角括号是普通正文，
     * 不会被任何 Markdown 语法误识。
     */
    const val MARK_LOCAL = "〔同步冲突·本地〕"

    /** 冲突块分隔标记 */
    const val MARK_SEPARATOR = "〔同步冲突·分隔〕"

    /** 冲突块云端开始标记 */
    const val MARK_REMOTE = "〔同步冲突·云端〕"

    /** 冲突块结束标记 */
    const val MARK_END = "〔同步冲突·结束〕"

    /**
     * 合并结果：[text] 为合并文本（有冲突块时带内联标记；无祖先时等于 [local]）、
     * [conflicts] 为冲突块数、[usedBase] 表示是否真的用了祖先。
     */
    data class Result(val text: String, val conflicts: Int, val usedBase: Boolean)

    /** 三方合并；[base] 为 null 时直接返回冲突（调用方据此退回无祖先分支）。 */
    fun merge(base: String?, local: String, remote: String): Result {
        if (local == remote) return Result(local, 0, usedBase = base != null)
        if (base == null) return Result(local, 1, usedBase = false)

        val baseSplit = FrontmatterParser.split(base)
        val localSplit = FrontmatterParser.split(local)
        val remoteSplit = FrontmatterParser.split(remote)
        val localFmChanged = localSplit.prefix != baseSplit.prefix
        val remoteFmChanged = remoteSplit.prefix != baseSplit.prefix
        // 属性块两侧都改且改得不一样：拼合会产出非法 YAML，整篇回退为冲突
        if (localFmChanged && remoteFmChanged && localSplit.prefix != remoteSplit.prefix) {
            return Result(local, 1, usedBase = true)
        }
        val prefix = when {
            localFmChanged -> localSplit.prefix
            remoteFmChanged -> remoteSplit.prefix
            else -> localSplit.prefix
        }

        val out = StringBuilder(maxOf(local.length, remote.length))
        val counter = Counter()
        mergeBlockLevel(baseSplit.body, localSplit.body, remoteSplit.body, out, counter)
        return Result(prefix + out.toString(), counter.value, usedBase = true)
    }

    /** 正文段落合并（frontmatter 已在外层剥离）。 */
    private fun mergeBlockLevel(
        base: String,
        local: String,
        remote: String,
        out: StringBuilder,
        counter: Counter
    ) {
        val baseChunks = split(base)
        val localChunks = split(local)
        val remoteChunks = split(remote)
        mergeRange(
            baseChunks, 0, baseChunks.size,
            localChunks, 0, localChunks.size,
            remoteChunks, 0, remoteChunks.size,
            out, counter
        )
    }

    // ---------------------------------------------------------------- 合并

    private class Counter(var value: Int = 0)

    private fun mergeRange(
        base: List<Chunk>, bStart: Int, bEnd: Int,
        local: List<Chunk>, lStart: Int, lEnd: Int,
        remote: List<Chunk>, rStart: Int, rEnd: Int,
        out: StringBuilder,
        conflicts: Counter
    ) {
        var b0 = bStart
        var l0 = lStart
        var r0 = rStart
        var b1 = bEnd
        var l1 = lEnd
        var r1 = rEnd

        // 公共前缀：三边一致的块直接输出
        while (b0 < b1 && l0 < l1 && r0 < r1 &&
            base[b0].key == local[l0].key && base[b0].key == remote[r0].key
        ) {
            out.append(base[b0].raw)
            b0++
            l0++
            r0++
        }
        // 公共后缀：先裁掉，等中间处理完再补上
        while (b1 > b0 && l1 > l0 && r1 > r0 &&
            base[b1 - 1].key == local[l1 - 1].key && base[b1 - 1].key == remote[r1 - 1].key
        ) {
            b1--
            l1--
            r1--
        }

        mergeMiddle(base, b0, b1, local, l0, l1, remote, r0, r1, out, conflicts)

        for (i in b1 until bEnd) out.append(base[i].raw)
    }

    private fun mergeMiddle(
        base: List<Chunk>, b0: Int, b1: Int,
        local: List<Chunk>, l0: Int, l1: Int,
        remote: List<Chunk>, r0: Int, r1: Int,
        out: StringBuilder,
        conflicts: Counter
    ) {
        if (b0 == b1 && l0 == l1 && r0 == r1) return

        // 锚点：base 中间块在本地与远端中间区域里也出现（取最靠前者，区域必然收缩）
        val anchorB = (b0 until b1).firstOrNull { i ->
            val key = base[i].key
            (l0 until l1).any { local[it].key == key } && (r0 until r1).any { remote[it].key == key }
        }
        if (anchorB != null) {
            val key = base[anchorB].key
            val anchorL = (l0 until l1).first { local[it].key == key }
            val anchorR = (r0 until r1).first { remote[it].key == key }
            mergeRange(base, b0, anchorB, local, l0, anchorL, remote, r0, anchorR, out, conflicts)
            out.append(base[anchorB].raw)
            mergeRange(base, anchorB + 1, b1, local, anchorL + 1, l1, remote, anchorR + 1, r1, out, conflicts)
            return
        }

        when {
            // 本地没动这一段 → 取远端
            keysEqual(base, b0, b1, local, l0, l1) -> appendRange(remote, r0, r1, out)
            // 远端没动这一段 → 取本地
            keysEqual(base, b0, b1, remote, r0, r1) -> appendRange(local, l0, l1, out)
            // 两侧改成了同一结果 → 取其一
            keysEqual(local, l0, l1, remote, r0, r1) -> appendRange(local, l0, l1, out)
            // 一侧整段删除、另一侧保留原样 → 删除生效
            l0 == l1 && keysEqual(base, b0, b1, remote, r0, r1) -> Unit
            r0 == r1 && keysEqual(base, b0, b1, local, l0, l1) -> Unit
            else -> {
                // 两侧对同一段改成不同内容：该段内联标记，其余段落仍正常合并
                conflicts.value++
                out.append(MARK_LOCAL).append('\n')
                appendRange(local, l0, l1, out)
                ensureNewline(out)
                out.append(MARK_SEPARATOR).append('\n')
                out.append(MARK_REMOTE).append('\n')
                appendRange(remote, r0, r1, out)
                ensureNewline(out)
                out.append(MARK_END).append('\n')
            }
        }
    }

    private fun ensureNewline(out: StringBuilder) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }

    private fun appendRange(chunks: List<Chunk>, from: Int, to: Int, out: StringBuilder) {
        for (i in from until to) out.append(chunks[i].raw)
    }

    /** 两个切片的内容是否逐块相同（长度相等且 key 全等）。 */
    private fun keysEqual(
        a: List<Chunk>, aFrom: Int, aTo: Int,
        b: List<Chunk>, bFrom: Int, bTo: Int
    ): Boolean {
        if (aTo - aFrom != bTo - bFrom) return false
        for (i in 0 until (aTo - aFrom)) {
            if (a[aFrom + i].key != b[bFrom + i].key) return false
        }
        return true
    }

    // ---------------------------------------------------------------- 块切分

    /** 一个段落块：[raw] 保留原文（含尾随分隔空行），[key] 为比较用的去空白内容。 */
    private class Chunk(val raw: String, val key: String)

    private fun split(text: String): List<Chunk> {
        if (text.isEmpty()) return emptyList()
        val raw = ArrayList<StringBuilder>()
        val current = StringBuilder()
        val lines = text.split('\n')
        var inFence = false

        fun flush() {
            if (current.isEmpty()) return
            raw += StringBuilder(current)
            current.setLength(0)
        }

        lines.forEachIndexed { index, line ->
            if (isFenceLine(line)) {
                // 围栏起始：先收束之前的普通段落；围栏整体成块，内部空行不再切分
                if (!inFence) flush()
                inFence = !inFence
                current.append(line)
                if (index < lines.lastIndex) current.append('\n')
                if (!inFence) flush()
                return@forEachIndexed
            }
            if (!inFence && line.isBlank()) {
                current.append(line)
                if (index < lines.lastIndex) current.append('\n')
                flush()
                return@forEachIndexed
            }
            current.append(line)
            if (index < lines.lastIndex) current.append('\n')
        }
        flush()

        val out = ArrayList<Chunk>(raw.size)
        val pending = StringBuilder()
        for (builder in raw) {
            val text = builder.toString()
            val key = text.trim()
            if (key.isEmpty()) {
                // 纯空白块：有前一块就并入前一块，否则挂起到下一个内容块前面
                // （frontmatter 与正文之间的空行就属于这种前导空白，丢了会粘在一起）
                if (out.isNotEmpty()) {
                    val last = out.removeAt(out.lastIndex)
                    out += Chunk(last.raw + text, last.key)
                } else {
                    pending.append(text)
                }
                continue
            }
            out += Chunk(pending.toString() + text, key)
            pending.setLength(0)
        }
        return out
    }

    private fun isFenceLine(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("```") || trimmed.startsWith("~~~")
    }
}
