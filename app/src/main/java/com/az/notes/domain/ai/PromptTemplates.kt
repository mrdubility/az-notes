package com.az.notes.domain.ai

/**
 * 内置系统提示词（§9.4，中文、面向模型阅读）。
 * 工具指引段随 B3 工具循环引入；压缩摘要使用独立指令（§6.2，B4），不复用本提示词。
 */
object PromptTemplates {
    /** 对话基础提示词（随每次请求发送）。 */
    const val CHAT_SYSTEM: String =
        "你是「Az Notes」笔记应用内置的 AI 笔记助理。回答时基于用户提供的内容：" +
            "引用文档时注明其相对路径（如 folder/note.md）；信息不足或不确定时明确说明，不要编造。"
}
