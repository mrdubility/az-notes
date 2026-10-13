package com.az.notes.ui.common

import androidx.compose.ui.platform.UriHandler
import com.az.notes.domain.markdown.WikiLinkTransformer

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
