package com.az.notes.domain.model

/**
 * 编辑器工具栏的可用工具（§5.6）：id 持久化到 DataStore，顺序与启用状态
 * 由设置页配置；读取时忽略未知 id，新版本新增工具默认追加到末尾并启用。
 */
enum class EditorTool(val id: String) {
    UNDO("undo"),
    REDO("redo"),
    BOLD("bold"),
    ITALIC("italic"),
    STRIKETHROUGH("strike"),
    INLINE_CODE("code"),
    HEADING("heading"),
    QUOTE("quote"),
    LIST_BULLET("ul"),
    LIST_NUMBER("ol"),
    TASK("task"),
    CODE_BLOCK("code_block"),
    HORIZONTAL_RULE("hr"),
    INDENT("indent"),
    UNINDENT("unindent"),
    LINK("link"),
    IMAGE("image"),
    PROPERTY("property");

    companion object {
        /** 默认顺序（启用全部）。 */
        val defaultOrder: List<String> = entries.map { it.id }

        private val byId = entries.associateBy { it.id }

        /** 解析持久化的工具 id；未知返回 null。 */
        fun fromId(id: String): EditorTool? = byId[id]

        /**
         * 依据持久化配置解析工具栏工具列表：按 [order] 排序（缺失的旧工具按声明序
         * 补齐到末尾，默认启用），剔除 [disabled] 中的工具。
         */
        fun resolve(order: List<String>, disabled: Set<String>): List<EditorTool> {
            val complete = order + defaultOrder.filter { it !in order }
            return complete.mapNotNull { fromId(it) }.filter { it.id !in disabled }
        }
    }
}
