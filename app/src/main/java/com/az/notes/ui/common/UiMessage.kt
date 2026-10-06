package com.az.notes.ui.common

/**
 * 一次性提示 + 可选撤销动作：UI 层以 Snackbar 展示 [text]；
 * [undo] 非空时横幅右侧显示「撤销」按钮，点击执行该动作（通常是反向操作）。
 * 供可撤销的业务操作使用（收藏 / 删除进回收站 / 回收站恢复等）。
 */
data class UiMessage(
    val text: UiText,
    val undo: (() -> Unit)? = null
)
