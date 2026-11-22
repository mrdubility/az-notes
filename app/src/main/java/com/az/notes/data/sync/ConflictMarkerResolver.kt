package com.az.notes.data.sync

/**
 * 同步冲突内联标记的识别与消解（纯逻辑、零 IO、可在 JVM 单测运行）。
 *
 * 标记由 [ParagraphMerger] 在合并时写入，形如：
 * ```
 * <<<<<<< 本地
 * 本地这一版的内容
 * =======
 * 云端这一版的内容
 * >>>>>>> 云端
 * ```
 *
 * 消解规则：整篇按块替换——保留本地时输出本地段、保留云端时输出云端段，标记行一律删除；
 * 任何未闭合的标记（例如用户手动删掉了结束行）按原文保留，避免把正文误删。
 */
object ConflictMarkerResolver {

    private const val MARK_LOCAL = "<<<<<<<"
    private const val MARK_SEPARATOR = "======="
    private const val MARK_REMOTE = ">>>>>>>"

    /** 是否含冲突标记（用于编辑页决定是否显示"处理同步冲突"入口）。 */
    fun hasMarkers(text: String): Boolean =
        text.contains(MARK_LOCAL) && text.contains(MARK_REMOTE)

    /** 未闭合的冲突块数（不完整的标记不计入，避免误报）。 */
    fun countMarkers(text: String): Int {
        var count = 0
        var state = 0
        for (line in text.lineSequence()) {
            when {
                line.startsWith(MARK_LOCAL) -> state = 1
                state == 1 && line.startsWith(MARK_SEPARATOR) -> state = 2
                state == 2 && line.startsWith(MARK_REMOTE) -> {
                    count++
                    state = 0
                }
                state != 0 && line.startsWith(MARK_LOCAL) -> state = 1
            }
        }
        return count
    }

    /** 全部保留本地侧内容。 */
    fun keepLocal(text: String): String = resolve(text, keepLocal = true)

    /** 全部保留云端侧内容。 */
    fun keepRemote(text: String): String = resolve(text, keepLocal = false)

    private fun resolve(text: String, keepLocal: Boolean): String {
        if (!hasMarkers(text)) return text
        val out = StringBuilder(text.length)
        val local = StringBuilder()
        val remote = StringBuilder()
        var state = 0
        for (line in text.lineSequence()) {
            when {
                line.startsWith(MARK_LOCAL) -> {
                    // 上一个块未闭合：原样吐回，保证不吞正文
                    if (state != 0) out.append(local).append(remote)
                    local.setLength(0)
                    remote.setLength(0)
                    state = 1
                }
                state == 1 && line.startsWith(MARK_SEPARATOR) -> state = 2
                state == 2 && line.startsWith(MARK_REMOTE) -> {
                    if (keepLocal) out.append(local) else out.append(remote)
                    local.setLength(0)
                    remote.setLength(0)
                    state = 0
                }
                state == 1 -> local.append(line).append('\n')
                state == 2 -> remote.append(line).append('\n')
                else -> out.append(line).append('\n')
            }
        }
        if (state != 0) out.append(local).append(remote)
        // 去掉上面统一补的末尾换行，保持与原文结尾一致
        return if (text.endsWith("\n") || out.isEmpty()) out.toString() else out.dropLast(1).toString()
    }
}
