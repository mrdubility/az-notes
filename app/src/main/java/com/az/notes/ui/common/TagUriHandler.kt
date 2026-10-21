package com.az.notes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import com.az.notes.domain.markdown.TagMatcher
import com.az.notes.domain.markdown.WikiLinkTransformer

/**
 * 标签点击拦截：渲染库对链接点击统一走 `LocalUriHandler`，此处把 `az-tag:`
 * 目标交 [onOpenTag] 回调（原文已解码），其余协议回交 [fallback]——wikilink
 * 与外链等行为与上层完全一致。注入方式：`CompositionLocalProvider(LocalUriHandler
 * provides ...)` 包裹 Markdown 组合（渲染库与组件均无需定制）。
 */
class TagUriHandler(
    private val fallback: UriHandler,
    private val onOpenTag: (String) -> Unit
) : UriHandler {

    override fun openUri(uri: String) {
        if (!uri.startsWith(TagMatcher.LINK_SCHEME)) {
            fallback.openUri(uri)
            return
        }
        val tag = WikiLinkTransformer.decodeTarget(
            uri.removePrefix(TagMatcher.LINK_SCHEME)
        )
        if (tag.isNotBlank()) onOpenTag(tag)
    }
}

/**
 * 构造标签点击处理器：`az-tag:` 目标经 [onOpenTag] 跳标签结果页，
 * 其余协议回交组合链上的既有 UriHandler（如 wikilink 处理器 / 平台默认）。
 * 在 `LocalUriHandler provides wikiHandler` 的内层调用本函数即可自然串接两级拦截。
 */
@Composable
fun rememberTagUriHandler(onOpenTag: (String) -> Unit): TagUriHandler {
    val fallback = LocalUriHandler.current
    return remember(fallback, onOpenTag) { TagUriHandler(fallback, onOpenTag) }
}
