package com.az.notes.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 可本地化的一次性文本（Snackbar / Toast / 错误信息）。
 * ViewModel 层只携带资源 id 与参数，由 UI 层按当前语言解析，
 * 避免在 VM 中持有已格式化的单一语言文本。
 */
data class UiText(
    val resId: Int? = null,
    val args: List<Any> = emptyList(),
    /** 动态文本兜底（如异常消息）；仅当 [resId] 为 null 时使用。 */
    val raw: String? = null
) {
    companion object {
        fun of(resId: Int, vararg args: Any): UiText = UiText(resId, args.toList())
        fun ofRaw(text: String): UiText = UiText(raw = text)
    }
}

/** 组合环境内解析为当前语言文本。 */
@Composable
fun UiText.resolve(): String =
    resId?.let { stringResource(it, *args.toTypedArray()) } ?: raw.orEmpty()

/** 非组合环境（Toast 等）按 Context 当前语言解析。 */
fun UiText.resolve(context: Context): String =
    resId?.let { context.getString(it, *args.toTypedArray()) } ?: raw.orEmpty()

/** 异常 → UiText：优先展示原始消息，无消息时回退到资源文案。 */
fun Throwable.toUiText(fallbackRes: Int): UiText =
    message?.let { UiText.ofRaw(it) } ?: UiText.of(fallbackRes)
