package com.az.notes.ui.reader

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.az.notes.R
import com.az.notes.domain.markdown.ImageReference
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import java.io.File
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/**
 * 预览页图片渲染：把 Markdown 图片链接解析并渲染，覆盖三类来源——
 *
 * 1. 仓库内本地图片：经 [ImageReference.resolveExisting] 定位（与附件索引同一套解析规则，
 *    避免两处漂移），用 Coil 加载本地文件，点击全屏预览（[onImageClick]，仅本地支持）。
 * 2. 网络图片（http/https/data）：按「不可信来源」经 [NetworkImageGuard] 校验，
 *    通过则以受控客户端加载（见 [com.az.notes.AzNotesApp] 的 ImageLoader），不挂点击。
 * 3. 被护栏拒绝（超限 / 私网 / 重定向 / 不允许地址 / 已关闭）：不发起请求，直接给可读原因。
 *
 * 加载态与失败态：Loading 显示占位（避免大片空白）；失败按 [NetworkImageGuard.classify]
 * 映射可读文案，网络图片附「点击重试」（以新 key 重建请求）。
 */

/** 从图片 AST 节点取链接目标（LINK_DESTINATION），取不到返回 null。 */
private fun extractImageLink(node: ASTNode, content: String): String? {
    val dest = node.children.firstNotNullDown(MarkdownElementTypes.LINK_DESTINATION) ?: return null
    val raw = content.substring(dest.startOffset, dest.endOffset).trim()
    return raw.ifBlank { null }
}

private fun List<ASTNode>.firstNotNullDown(type: IElementType): ASTNode? {
    for (child in this) {
        if (child.type == type) return child
        child.children.firstNotNullDown(type)?.let { return it }
    }
    return null
}

/**
 * Markdown 图片组件（经 `markdownComponents(image = ...)` 接管渲染）。
 * [vaultRoot]/[baseDir]/[maxBytes] 由预览页注入；[onImageClick] 仅本地图片触发。
 */
@Composable
fun VaultMarkdownImage(
    model: MarkdownComponentModel,
    vaultRoot: String,
    baseDir: File,
    maxBytes: Long,
    onImageClick: (File) -> Unit
) {
    val link = extractImageLink(model.node, model.content) ?: return
    if (link.isBlank()) return

    val root = remember(vaultRoot) { File(vaultRoot) }
    if (ImageReference.isRemote(link)) {
        RemoteImage(link = link, maxBytes = maxBytes)
    } else {
        val file = remember(link, vaultRoot) {
            ImageReference.resolveExisting(link, root, baseDir)
        }
        if (file == null) {
            // 本地图片缺失：保留 alt 占位，不显示破损大块
            MissingImagePlaceholder()
        } else {
            LocalImage(file = file, onImageClick = onImageClick)
        }
    }
}

/** 本地图片：Coil 加载文件，点击全屏预览；加载中/失败用占位替代空白。 */
@Composable
private fun LocalImage(file: File, onImageClick: (File) -> Unit) {
    // Coil3 的 AsyncImage 无 loading/error 槽位：用 onState 回流状态，按状态叠加占位
    var state by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onImageClick(file) },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                .data(Uri.fromFile(file))
                .crossfade(true)
                .build(),
            contentDescription = file.name,
            modifier = Modifier.fillMaxWidth(),
            onState = { state = it }
        )
        when (state) {
            is AsyncImagePainter.State.Loading -> ImageLoadingIndicator()
            is AsyncImagePainter.State.Error -> MissingImagePlaceholder()
            else -> Unit
        }
    }
}

/** 网络图片：护栏校验通过后经受控客户端加载，失败给原因 + 重试。 */
@Composable
private fun RemoteImage(link: String, maxBytes: Long) {
    // 重试计数：改变 key 让 Coil 重新发起请求
    var retry by remember(link) { mutableIntStateOf(0) }
    val blocked = remember(link, maxBytes) { NetworkImageGuard.check(link, maxBytes) }
    if (blocked != null) {
        NetworkImageError(blocked, retryable = false, onRetry = {})
        return
    }
    // key(retry) 重建请求：网络图片不做缓存，唯有重建才能重新发起
    key(retry) {
        var state by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
                    .data(link)
                    .size(Size.ORIGINAL)
                    .crossfade(true)
                    .build(),
                contentDescription = link.substringAfterLast('/'),
                modifier = Modifier.fillMaxWidth(),
                onState = { state = it }
            )
            when (val s = state) {
                is AsyncImagePainter.State.Loading -> ImageLoadingIndicator()
                is AsyncImagePainter.State.Error -> {
                    val failure = NetworkImageGuard.classify(s.result.throwable)
                        ?: NetworkImageGuard.Failure.UNSUPPORTED
                    NetworkImageError(failure, retryable = true, onRetry = { retry++ })
                }
                else -> Unit
            }
        }
    }
}

/** 加载中占位：居中指示器，避免图片区域塌陷成空白。 */
@Composable
private fun ImageLoadingIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}

/** 本地图片缺失占位。 */
@Composable
private fun MissingImagePlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.image_missing_placeholder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 网络图片失败：展示可读原因；可重试时附「点击重试」。 */
@Composable
private fun NetworkImageError(failure: NetworkImageGuard.Failure, retryable: Boolean, onRetry: () -> Unit) {
    val reason = stringResource(
        when (failure) {
            NetworkImageGuard.Failure.DISABLED -> R.string.remote_image_disabled
            NetworkImageGuard.Failure.PRIVATE_ADDRESS -> R.string.remote_image_private
            NetworkImageGuard.Failure.REDIRECT -> R.string.remote_image_redirect
            NetworkImageGuard.Failure.TOO_LARGE -> R.string.remote_image_too_large
            NetworkImageGuard.Failure.TIMEOUT -> R.string.remote_image_timeout
            NetworkImageGuard.Failure.UNSUPPORTED -> R.string.remote_image_blocked
        }
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (retryable) {
            Text(
                text = stringResource(R.string.remote_image_retry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(onClick = onRetry)
            )
        }
    }
}
