package com.az.notes.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.az.notes.R
import com.az.notes.domain.markdown.WikiLinkTransformer
import kotlinx.coroutines.launch

/**
 * Wikilink 点击拦截：渲染库对链接点击统一走 `LocalUriHandler`，此处把 `az-note:`
 * 目标交 [onWikiTarget] 回调（原文已解码），其余协议回交 [fallback]——外链等行为
 * 与默认完全一致。注入方式：`CompositionLocalProvider(LocalUriHandler provides ...)`
 * 包裹 Markdown 组合（渲染库与组件均无需定制）。
 */
class WikiLinkUriHandler(
    private val fallback: UriHandler,
    private val onWikiTarget: (String) -> Unit
) : UriHandler {

    override fun openUri(uri: String) {
        if (!uri.startsWith(WikiLinkTransformer.LINK_SCHEME)) {
            fallback.openUri(uri)
            return
        }
        val target = WikiLinkTransformer.decodeTarget(
            uri.removePrefix(WikiLinkTransformer.LINK_SCHEME)
        )
        if (target.isNotBlank()) onWikiTarget(target)
    }
}

/**
 * 构造带兜底的 Wikilink 点击处理器（阅读器 / 对话页共用）：`az-note:` 目标经
 * [resolve] 解析 → [onOpen] 跳转；未命中 → Snackbar 提示 +「新建笔记」
 * （[onCreate] 成功后经 [onOpen] 打开）；其余协议回交平台 UriHandler。
 */
@Composable
fun rememberWikiLinkUriHandler(
    snackbarHostState: SnackbarHostState,
    resolve: suspend (String) -> String?,
    onOpen: (String) -> Unit,
    onCreate: suspend (String) -> String?
): WikiLinkUriHandler {
    val context = LocalContext.current
    val fallback = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    return remember(fallback, snackbarHostState, resolve, onOpen, onCreate) {
        WikiLinkUriHandler(fallback) { target ->
            scope.launch {
                val path = resolve(target)
                if (path != null) {
                    onOpen(path)
                    return@launch
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(
                        R.string.wiki_note_missing,
                        target.substringBefore('#').trim()
                    ),
                    actionLabel = context.getString(R.string.wiki_create_note)
                )
                if (result == SnackbarResult.ActionPerformed) {
                    onCreate(target)?.let(onOpen)
                }
            }
        }
    }
}
