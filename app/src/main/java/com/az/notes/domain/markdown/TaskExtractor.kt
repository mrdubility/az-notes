package com.az.notes.domain.markdown

/** 单条待办：行号（正文 0 基）+ 勾选状态 + 文本内容。 */
data class TaskItem(
    val lineIndex: Int,
    val checked: Boolean,
    val text: String
)

/**
 * 待办笔记（frontmatter `note_type: task`）的行级读写：
 * 从正文提取 `- [ ] / - [x]` 条目、切换勾选状态、追加新条目。
 * 全部操作只改动目标行，其余内容（含 frontmatter）原样保留。
 */
object TaskExtractor {

    /** 待办行：`- [ ] 文本` / `- [x] 文本` / `- [] 文本`（兼容 `*` `+` 与缩进）。 */
    private val TASK_LINE = Regex("^(\\s*)[-*+]\\s+\\[([ xX]*)]\\s*(.*)$")

    /** 行内勾选框 `[...]`（切换时定位替换范围）。 */
    private val CHECKBOX = Regex("\\[[ xX]*]")

    /** 提取全部待办条目（行号升序）。 */
    fun extract(body: String): List<TaskItem> =
        body.split('\n').mapIndexedNotNull { index, line ->
            val match = TASK_LINE.matchEntire(line) ?: return@mapIndexedNotNull null
            TaskItem(
                lineIndex = index,
                checked = match.groupValues[2].trim().equals("x", ignoreCase = true),
                text = match.groupValues[3].trim()
            )
        }

    /** 将 [lineIndex] 行的勾选状态置为 [checked]；目标行不是待办行时返回 null。 */
    fun toggle(body: String, lineIndex: Int, checked: Boolean): String? {
        val lines = body.split('\n').toMutableList()
        if (lineIndex !in lines.indices) return null
        val line = lines[lineIndex]
        if (!TASK_LINE.matches(line)) return null
        val box = CHECKBOX.find(line) ?: return null
        lines[lineIndex] = line.replaceRange(box.range, if (checked) "[x]" else "[ ]")
        return lines.joinToString("\n")
    }

    /**
     * 重排：把 [orderedLineIndexes] 指定的任务行按给定顺序重新填充回它们原有的行槽位。
     * 仅调整传入行之间的相对顺序（支持只传子集，如“仅未完成”过滤视图下拖动），
     * 未传入的行（含其它已完成待办）保持在原位不动。数据不一致时返回 null。
     */
    fun reorder(body: String, orderedLineIndexes: List<Int>): String? {
        if (orderedLineIndexes.size < 2) return null
        val lines = body.split('\n').toMutableList()
        val slots = orderedLineIndexes.sorted()
        if (slots.any { it !in lines.indices || !TASK_LINE.matches(lines[it]) }) return null
        val contents = orderedLineIndexes.map { lines[it] }
        slots.forEachIndexed { index, slot -> lines[slot] = contents[index] }
        return lines.joinToString("\n")
    }

    /** 追加一条待办：插到最后一个待办行之后；无待办行时追加到正文末尾。空白文本返回 null。 */
    fun append(body: String, text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val taskLine = "- [ ] $trimmed"
        val lines = body.split('\n').toMutableList()
        val lastTask = lines.indexOfLast { TASK_LINE.matches(it) }
        return when {
            lastTask >= 0 -> {
                lines.add(lastTask + 1, taskLine)
                lines.joinToString("\n")
            }
            body.isBlank() -> taskLine
            else -> body.trimEnd('\n') + "\n\n" + taskLine
        }
    }
}
