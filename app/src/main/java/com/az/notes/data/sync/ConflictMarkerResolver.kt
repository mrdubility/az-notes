package com.az.notes.data.sync

/**
 * 同步冲突内联标记的识别与消解（纯逻辑、零 IO、可在 JVM 单测运行）。
 *
 * 标记由 [ParagraphMerger] 在合并时写入，形如：
 * ```
 * 〔同步冲突·本地〕
 * 本地这一版的内容
 * 〔同步冲突·分隔〕
 * 〔同步冲突·云端〕
 * 云端这一版的内容
 * 〔同步冲突·结束〕
 * ```
 * 用全角括号而非 Git 风格的 `<` / `>` 作为标记：行首的 `>` 会被 Markdown 当成引用块，
 * 预览里就变成了一排竖线。
 *
 * 兼容早期写进文件的旧格式（`<<<<<<< 本地` / `=======` / `>>>>>>> 云端`）：
 * 那类文件仍可一键消解，新合并只产出新格式。
 *
 * 消解规则：保留本地时输出本地段、保留云端时输出云端段，标记行一律删除；
 * 未闭合的标记只去掉标记行、内容按原样保留，避免把正文误删。
 */
object ConflictMarkerResolver {

    private const val LOCAL = "〔同步冲突·本地〕"
    private const val SEPARATOR = "〔同步冲突·分隔〕"
    private const val REMOTE = "〔同步冲突·云端〕"
    private const val END = "〔同步冲突·结束〕"

    /** 旧格式前缀（仅用于识别历史文件） */
    private const val LEGACY_LOCAL = "<<<<<<<"
    private const val LEGACY_SEPARATOR = "======="
    private const val LEGACY_REMOTE = ">>>>>>>"

    /** 是否含冲突标记（编辑页据此决定是否显示"处理同步冲突"）。 */
    fun hasMarkers(text: String): Boolean =
        text.contains(LOCAL) || (text.contains(LEGACY_LOCAL) && text.contains(LEGACY_REMOTE))

    /** 已闭合的冲突块数（不完整的标记不计入，避免误报）。 */
    fun countMarkers(text: String): Int {
        var count = 0
        var state = 0
        var legacy = false
        for (line in text.lineSequence()) {
            val isLocalStart = line.startsWith(LOCAL)
            val isLegacyStart = line.startsWith(LEGACY_LOCAL)
            when {
                isLocalStart || isLegacyStart -> {
                    legacy = isLegacyStart
                    state = 1
                }
                state == 1 && isSeparatorLine(line, legacy) -> state = 2
                !legacy && state == 2 && line.startsWith(REMOTE) -> state = 3
                !legacy && state == 3 && line.startsWith(END) -> {
                    count++
                    state = 0
                }
                legacy && state == 2 && line.startsWith(LEGACY_REMOTE) -> {
                    count++
                    state = 0
                }
            }
        }
        return count
    }

    /** 全部保留本地侧内容。 */
    fun keepLocal(text: String): String = resolve(text, keepLocal = true)

    /** 全部保留云端侧内容。 */
    fun keepRemote(text: String): String = resolve(text, keepLocal = false)

    private fun isSeparatorLine(line: String, legacy: Boolean): Boolean =
        line.startsWith(SEPARATOR) || (legacy && line.startsWith(LEGACY_SEPARATOR))

    private fun resolve(text: String, keepLocal: Boolean): String {
        if (!hasMarkers(text)) return text
        val out = StringBuilder(text.length)
        val local = StringBuilder()
        val remote = StringBuilder()
        // 0 = 普通正文；1 = 本地段；2 = 分隔之后（旧格式即为云端段）；3 = 新格式云端段
        var state = 0
        var legacy = false

        fun emitChosen() {
            out.append(if (keepLocal) local else remote)
            local.setLength(0)
            remote.setLength(0)
            state = 0
        }

        for (line in text.lineSequence()) {
            val isLocalStart = line.startsWith(LOCAL)
            val isLegacyStart = line.startsWith(LEGACY_LOCAL)
            when {
                isLocalStart || isLegacyStart -> {
                    // 上一个块未闭合：原样吐回已收集内容，保证不吞正文
                    if (state != 0) out.append(local).append(remote)
                    local.setLength(0)
                    remote.setLength(0)
                    legacy = isLegacyStart
                    state = 1
                }
                state == 1 && isSeparatorLine(line, legacy) -> state = 2
                !legacy && state == 2 && line.startsWith(REMOTE) -> state = 3
                !legacy && state == 3 && line.startsWith(END) -> emitChosen()
                legacy && state == 2 && line.startsWith(LEGACY_REMOTE) -> emitChosen()
                state == 2 || state == 3 -> remote.append(line).append('\n')
                state == 1 -> local.append(line).append('\n')
                else -> out.append(line).append('\n')
            }
        }
        if (state != 0) out.append(local).append(remote)
        // 上面统一补了行尾换行，这里还原原文结尾形态
        return if (text.endsWith("\n") || out.isEmpty()) out.toString() else out.dropLast(1).toString()
    }
}
