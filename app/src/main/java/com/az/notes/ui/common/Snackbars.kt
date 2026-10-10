package com.az.notes.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.withTimeoutOrNull

/** 横幅（Snackbar）统一显示时长（毫秒）：普通提示与撤销横幅一律 5 秒后自动消失。 */
const val SNACKBAR_DURATION_MS = 5_000L

/**
 * 显示统一时长（[SNACKBAR_DURATION_MS]）的横幅：5 秒后自动消失（划走 / 超时均视为关闭）。
 * 带 [actionLabel] 时返回用户是否点了动作（[SnackbarResult.ActionPerformed]），
 * 超时 / 划走返回 [SnackbarResult.Dismissed]（调用方据此决定是否执行撤销等动作）。
 */
suspend fun SnackbarHostState.showTimedSnackbar(
    message: String,
    actionLabel: String? = null,
    withDismissAction: Boolean = false
): SnackbarResult = withTimeoutOrNull(SNACKBAR_DURATION_MS) {
    showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = withDismissAction,
        duration = SnackbarDuration.Indefinite
    )
} ?: SnackbarResult.Dismissed
