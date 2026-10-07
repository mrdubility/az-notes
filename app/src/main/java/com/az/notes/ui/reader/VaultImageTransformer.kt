package com.az.notes.ui.reader

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size as CoilSize
import com.az.notes.R
import com.az.notes.domain.markdown.ImageReference
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import java.io.File

/**
 * 预览页图片加载器：实现渲染库 [ImageTransformer]，经 Markdown 的 `imageTransformer`
 * 参数注入（见 `ReaderScreen`）。
 *
 * 选择该机制的原因（对照 multiplatform-markdown-renderer 0.35.0 源码的结论）：
 * 段落里的 `![alt](path)` 位于 PARAGRAPH 节点内，渲染库把整个段落构建为 AnnotatedString，
 * 图片以 `MARKDOWN_TAG_IMAGE_URL` 内联内容（InlineTextContent）渲染，取图唯一路径是
 * [transform]；而 `markdownComponents(image = ...)` 只覆盖「IMAGE 作为独立块被
 * MarkdownElement 直接派发」的路径，对普通图片永不触发（曾因此导致所有图片不显示，
 * 勿回退到 components.image）。
 *
 * 三类来源：
 * 1. 本地图片：经 [ImageReference.resolveExisting] 定位（与附件索引同一套解析规则，避免漂移），
 *    Coil 加载文件，点击全屏预览（[onImageClick] 仅本地图片）。
 * 2. 网络图片（http/https/data）：先过 [NetworkImageGuard.check]（不发起请求的纯校验），
 *    通过后由受控客户端加载（见 [com.az.notes.AzNotesApp] 的 ImageLoader）。
 * 3. 缺失 / 被拒 / 加载失败：以文字占位画笔 [ReasonPainter] 给出可读原因；
 *    网络加载失败可点击重试（计数驱动请求重建；护栏拒绝为确定性结果，不提供重试）。
 */
class VaultImageTransformer(
    private val vaultRoot: String,
    private val baseDir: File,
    private val maxBytes: Long,
    private val onImageClick: (File) -> Unit
) : ImageTransformer {

    /** 网络图片失败后的重试计数（link → 次数）：点击错误占位累加，驱动请求重建 */
    private val retries = mutableStateMapOf<String, Int>()

    @Composable
    override fun transform(link: String): ImageData? {
        val root = remember(vaultRoot) { File(vaultRoot) }
        // 组合上下文内读取一次：remember 的 calculation lambda 非 @Composable，不可在其中访问
        val platformContext = LocalPlatformContext.current

        if (ImageReference.isRemote(link)) {
            val blocked = remember(link, maxBytes) { NetworkImageGuard.check(link, maxBytes) }
            if (blocked != null) {
                // 护栏拒绝为确定性结果：给出原因，不提供重试
                return ImageData(painter = reasonPainter(reasonText(blocked, retryable = false)))
            }
            val retry = retries[link] ?: 0
            val request = remember(link, retry, platformContext) {
                ImageRequest.Builder(platformContext)
                    .data(link)
                    .size(CoilSize.ORIGINAL)
                    .crossfade(true)
                    .build()
            }
            val painter = rememberAsyncImagePainter(model = request)
            val state by painter.state.collectAsState()
            return when (val s = state) {
                is AsyncImagePainter.State.Error -> {
                    val failure = NetworkImageGuard.classify(s.result.throwable)
                        ?: NetworkImageGuard.Failure.UNSUPPORTED
                    ImageData(
                        painter = reasonPainter(reasonText(failure, retryable = true)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { retries[link] = retry + 1 }
                    )
                }

                else -> ImageData(
                    painter = painter,
                    contentDescription = link.substringAfterLast('/')
                )
            }
        }

        val file = remember(link, vaultRoot) {
            ImageReference.resolveExisting(link, root, baseDir)
        }
        if (file == null) {
            return ImageData(painter = reasonPainter(reasonText(null, retryable = false)))
        }
        val request = remember(file, platformContext) {
            ImageRequest.Builder(platformContext)
                .data(Uri.fromFile(file))
                // 与远程分支 / 官方实现一致：按原始尺寸解码，保证上报的 intrinsicSize 即原图像素尺寸
                .size(CoilSize.ORIGINAL)
                .crossfade(true)
                .build()
        }
        val painter = rememberAsyncImagePainter(model = request)
        return ImageData(
            painter = painter,
            contentDescription = file.name,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onImageClick(file) }
        )
    }

    /**
     * 内联图片尺寸上报：渲染库（0.35.0）在 `createImageInlineTextContent` 中以
     * `LaunchedEffect(intrinsicSize) { imageState.updateImageSize(...) }` 驱动段落占位重排。
     * 必须与官方 Coil3ImageTransformerImpl 一样订阅 painter 状态并记忆最后一次有效尺寸：
     * 只取一次当前值的话，图片异步加载完成后不触发重组，占位将永远停在库兜底尺寸
     * （全宽 × 180sp），表现为预览中图片被固定块裁切、同段落文字重排异常。
     */
    @Composable
    override fun intrinsicSize(painter: Painter): Size {
        var size by remember(painter) { mutableStateOf(painter.intrinsicSize) }
        if (painter is AsyncImagePainter) {
            val painterState = painter.state.collectAsState()
            painterState.value.painter?.intrinsicSize?.also { size = it }
        }
        return size
    }

    /** 失败 / 拒绝原因文案；[retryable] 时追加「点击重试」提示行。 */
    @Composable
    private fun reasonText(failure: NetworkImageGuard.Failure?, retryable: Boolean): String {
        val base = stringResource(
            when (failure) {
                NetworkImageGuard.Failure.DISABLED -> R.string.remote_image_disabled
                NetworkImageGuard.Failure.PRIVATE_ADDRESS -> R.string.remote_image_private
                NetworkImageGuard.Failure.REDIRECT -> R.string.remote_image_redirect
                NetworkImageGuard.Failure.TOO_LARGE -> R.string.remote_image_too_large
                NetworkImageGuard.Failure.TIMEOUT -> R.string.remote_image_timeout
                NetworkImageGuard.Failure.UNSUPPORTED -> R.string.remote_image_blocked
                null -> R.string.image_missing_placeholder
            }
        )
        return if (retryable) base + "\n" + stringResource(R.string.remote_image_retry) else base
    }

    /** 文字占位画笔：在图片位置绘制居中的原因文案（内联渲染机制下无法叠加其它 Composable）。 */
    @Composable
    private fun reasonPainter(text: String): Painter {
        val measurer = rememberTextMeasurer()
        val color = MaterialTheme.colorScheme.error
        return remember(text, measurer, color) { ReasonPainter(text, measurer, color) }
    }
}

/** 居中的单色文字画笔：仅用于图片加载失败 / 被拒 / 缺失时的原因占位。 */
private class ReasonPainter(
    private val text: String,
    private val textMeasurer: TextMeasurer,
    private val color: Color
) : Painter() {

    /**
     * 横幅尺寸（像素语义，渲染库经 toSp 折算后仍是该像素值）：宽 960 与容器宽取小、
     * 高随比例收敛，最终呈现为一条「接近满宽、约 160px 高」的横幅。
     * 返回 [Size.Unspecified]（旧实现）会落入渲染库兜底分支（容器宽 × 180sp 巨型块），
     * 且文案容易溢出到相邻正文行上（红字浮位）。
     */
    override val intrinsicSize: Size get() = Size(960f, 160f)

    override fun DrawScope.onDraw() {
        val layout = textMeasurer.measure(
            text = AnnotatedString(text),
            style = TextStyle(fontSize = 12.sp, color = color)
        )
        val topLeft = Offset(
            x = ((size.width - layout.size.width) / 2f).coerceAtLeast(0f),
            y = ((size.height - layout.size.height) / 2f).coerceAtLeast(0f)
        )
        drawText(textLayoutResult = layout, topLeft = topLeft)
    }
}
