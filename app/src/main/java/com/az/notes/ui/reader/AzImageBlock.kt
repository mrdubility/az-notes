package com.az.notes.ui.reader

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import com.az.notes.R
import com.az.notes.domain.markdown.ImageReference
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import java.io.File

/**
 * 独立图片段落自渲染块（[MarkdownImageLifter] 产物的渲染路径，仅限本地图片）。
 *
 * 存在理由（对齐 multiplatform-markdown-renderer 0.35.0 的占位时序）：
 * 库对段落内图片的占位尺寸走 `placeholderConfig(density, containerSize, intrinsicImageSize)`
 * 两帧链——帧 0 组合时 containerSize / intrinsicImageSize 均未知，占位盒落入库兜底
 * 180×180sp；帧 1（LaunchedEffect 上报尺寸后）才切到真实尺寸。每个图片 item（含滚动
 * 回收后的重建）首次布局都必然经历这一跳变：向下滚动时 item 从视口下缘进入、跳变藏在
 * 视口之外不易察觉；向上回滚时新进入的 item 立即成为滚动锚点，一帧内高度突变会把下方
 * 内容整体位移（用户反馈的「向上滑动跳动」根因）。该函数非 @Composable 且拿不到 link，
 * 外部无法在帧 0 注入尺寸——独立图片段落只能自渲染。
 *
 * 实现要点：首帧即定盒——组合期同步预读像素尺寸（[ImageSizePreload]，只读文件头），
 * 盒子取「min(图宽, 容器宽) × 按比例高」，加载中 / 成功 / 失败在同一尺寸盒内切换，
 * 无布局突变；加载中顺带呈现「图片加载中…」占位（问题 2 的自渲染路径实现）。
 * 尺寸口径与库默认 placeholderConfig 对齐：像素数值直接承载为 dp（fontScale=1 时即
 * 库的 sp 口径）——宽图 clamp 到容器宽、小图按原值、竖图全宽按比例。
 * 预读失败（null）时退化为解码完成后采纳尺寸（仍优于库路径：少一帧兜底跳变）。
 *
 * 适用范围：仅「本地图片 + 独立段落」；图文混排 / 列表 / 引用内图片与远程图仍走
 * 渲染库 inline 路径（见 [VaultImageTransformer]），护栏 / 重试 / 日志逻辑集中在
 * 那一侧，避免两条渲染路径行为漂移。
 */
@Composable
internal fun AzImageBlock(
    link: String,
    vaultRoot: String,
    baseDir: File,
    onImageClick: (File) -> Unit
) {
    val root = remember(vaultRoot) { File(vaultRoot) }
    val file = remember(link, root, baseDir) {
        // 与 [VaultImageTransformer] 同一套解析规则（含越界防护），保证两条路径取图一致
        ImageReference.resolveExisting(link, root, baseDir)
    }

    if (file == null) {
        // 与库路径缺失分支同款提示；固定一条横幅高，避免占位比图片本身更抢眼
        ImageNoteBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            text = stringResource(R.string.image_missing_placeholder),
            error = true
        )
        return
    }

    val platformContext = LocalPlatformContext.current
    val request = remember(file, platformContext) {
        ImageRequest.Builder(platformContext)
            .data(Uri.fromFile(file))
            // 与库路径 / 官方实现一致：按原始尺寸解码，上报的 intrinsicSize 即原图像素
            // 尺寸，与预读尺寸同口径（占位盒与解码结果零偏差）
            .size(CoilSize.ORIGINAL)
            .build()
    }
    val painter = rememberAsyncImagePainter(model = request)
    val state by painter.state.collectAsState()

    // 首帧定盒：预读尺寸（组合期同步可得）优先；预读失败时才退化为解码完成后采纳
    val preSize = remember(file) { ImageSizePreload.sizeOf(file) }
    val decodedSize = (state as? AsyncImagePainter.State.Success)
        ?.painter?.intrinsicSize?.takeIf { it.isSpecified }
    val imgSize: Size? = preSize ?: decodedSize

    val boxModifier = if (imgSize != null && imgSize.width > 0f && imgSize.height > 0f) {
        // 盒宽 = min(图宽, 容器宽)：widthIn 先把内层 maxWidth 收到图宽（不放大父约束），
        // fillMaxWidth 再取满；aspectRatio 以宽定高。首帧即最终尺寸 → 滚动锚点零位移。
        Modifier
            .widthIn(max = imgSize.width.dp)
            .fillMaxWidth()
            .aspectRatio(imgSize.width / imgSize.height)
    } else {
        // 尺寸完全未知（预读失败且解码未完成）：沿用库兜底盒高度，解码后收敛
        Modifier
            .fillMaxWidth()
            .height(180.dp)
    }

    when (state) {
        is AsyncImagePainter.State.Success -> Image(
            painter = painter,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = boxModifier.clickable { onImageClick(file) }
        )

        is AsyncImagePainter.State.Error -> ImageNoteBox(
            // 文件存在但解码失败（损坏等）：与库路径错误分支同款文案
            modifier = boxModifier,
            text = stringResource(R.string.reader_image_load_failed),
            error = true
        )

        else -> ImageNoteBox(
            // 加载中 / 未开始：显示「加载中」占位（盒尺寸即最终尺寸，加载完成无重排）；
            // 与库路径 loading 分支一致可点击进入全屏预览
            modifier = boxModifier.clickable { onImageClick(file) },
            text = stringResource(R.string.reader_image_loading)
        )
    }
}

/**
 * 文字占位盒（加载中 / 失败 / 缺失共用）：圆角浅色底 + 顶部居中说明文字。
 * 顶部对齐的理由：盒高可能远超视口（竖图全宽时高近千 dp），居中文字会落到视口之外。
 */
@Composable
private fun ImageNoteBox(
    modifier: Modifier,
    text: String,
    error: Boolean = false
) {
    val background = if (error) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (error) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier.background(background, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = text,
            color = foreground,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/** 独立图片段落检测：内容恰为单张图片 `![alt](link)`（与 [MarkdownImageLifter] 同口径）。 */
private val standaloneImage = Regex("""^!\[[^\]]*]\(([^)]*)\)$""")

/**
 * 若 [node] 是「内容恰为单张本地图片」的段落（[MarkdownImageLifter] 把纯图片行 / 混排行
 * 按图片切段后的产物形态），返回图片链接原文；否则返回 null（继续走渲染库路径）。
 * 远程图返回 null：尺寸不可预知，首帧定盒收益有限，且护栏 / 重试集中在
 * [VaultImageTransformer]，避免两条路径行为漂移。
 */
internal fun standaloneImageLink(content: String, node: ASTNode): String? {
    if (node.type != MarkdownElementTypes.PARAGRAPH) return null
    if (node.endOffset > content.length) return null
    val text = content.substring(node.startOffset, node.endOffset).trim()
    val link = standaloneImage.matchEntire(text)?.groupValues?.get(1)?.trim()
    if (link.isNullOrEmpty() || ImageReference.isRemote(link)) return null
    return link
}
