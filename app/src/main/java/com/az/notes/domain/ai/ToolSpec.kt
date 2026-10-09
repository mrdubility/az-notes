package com.az.notes.domain.ai

/** 工具定义（§5.1）：三协议 adapter 把 [parametersJson] 嵌入各自工具格式。 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 字符串（object 类型）。 */
    val parametersJson: String
)

/** 内置只读工具集（限定当前仓库；描述面向模型，中文）。 */
object ToolSpecs {
    const val LIST_NOTES = "list_notes"
    const val READ_NOTE = "read_note"
    const val SEARCH_NOTES = "search_notes"

    val ALL: List<ToolSpec> = listOf(
        ToolSpec(
            name = LIST_NOTES,
            description = "列出当前仓库中某个目录下的子目录与笔记（含相对路径、大小、修改时间），" +
                "最多向下展开 3 层。dir 为仓库内相对路径（如 folder/sub），省略或传空串表示从仓库根开始。",
            parametersJson =
                """{"type":"object","properties":{"dir":{"type":"string","description":"仓库内相对目录路径，省略表示仓库根"}},"required":[]}"""
        ),
        ToolSpec(
            name = READ_NOTE,
            description = "读取当前仓库中某篇笔记的正文。path 为仓库内相对路径（如 folder/note.md）。" +
                "单次最多返回 32768 字符，超出时可用 offset（起始行号，从 1 开始）与 limit（行数）分段继续读取。",
            parametersJson =
                """{"type":"object","properties":{"path":{"type":"string","description":"仓库内相对路径"},"offset":{"type":"integer","description":"起始行号（从 1 开始），可选"},"limit":{"type":"integer","description":"读取行数，可选"}},"required":["path"]}"""
        ),
        ToolSpec(
            name = SEARCH_NOTES,
            description = "在当前仓库的全部笔记中搜索关键词（匹配文件名与正文），返回命中的笔记相对路径与上下文片段。",
            parametersJson =
                """{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"}},"required":["query"]}"""
        )
    )
}
