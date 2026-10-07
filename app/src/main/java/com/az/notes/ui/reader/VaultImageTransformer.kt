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
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size as CoilSize
import com.az.notes.R
import com.az.notes.data.debug.DebugLogLevel
import com.az.notes.domain.markdown.ImageReference
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.PlaceholderConfig
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    private val onImageClick: (File) -> Unit,
    /** 图片加载链路日志回调（写入调试日志 IMAGE 类型；未开启收集时静默丢弃）。 */
    private val log: (DebugLogLevel, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
    /** 预览页 LazyColumn 单侧水平内边距（与 ReaderScreen.contentPadding.horizontal 保持一致）。
     *  库从 parentLayoutCoordinates 拿到的 containerSize 为规整视口宽（包含 padding），
     *  直接参与占位计算会让竖图右侧溢出可见区域。placeholderConfig 中会从此基确扣减 2 倍。 */
    private val containerHorizontalPaddingDp: Dp = 0.dp
) : ImageTransformer {

    /** 网络图片失败后的重试计数（link → 次数）：点击错误占位累加，驱动请求重建 */
    private val retries = mutableStateMapOf<String, Int>()

    @Composable
    override fun transform(link: String): ImageData? {
        // 入口埋点（INFO）：闪退时能定位到“transform 进到了但后续某步没回来”。
        // 以 remember(link) 包裹，同 link 多次重组只记一次，避免日志刷屏。
        val remote = ImageReference.isRemote(link)
        remember(link) {
            log(
                DebugLogLevel.INFO,
                "transform-enter",
                mapOf("link" to link.take(200), "remote" to remote)
            )
            Unit
        }
        val root = remember(vaultRoot) { File(vaultRoot) }
        // 组合上下文内读取一次：remember 的 calculation lambda 非 @Composable，不可在其中访问
        val platformContext = LocalPlatformContext.current

        if (remote) {
            // 浏览器可直开的链接常含未编码字符（中文/空格等），OkHttp 会直接拒绝；
            // 先用 HttpUrl 规范化编码（解析失败保留原样，随后的失败原因会如实呈现）
            val safeLink = remember(link) { link.toHttpUrlOrNull()?.toString() ?: link }
            val blocked = remember(safeLink, maxBytes) { NetworkImageGuard.check(safeLink, maxBytes) }
            if (blocked != null) {
                // 护栏拒绝为确定性结果：给出原因，不提供重试。
                // 以 (safeLink, blocked) 为 key 的 remember：同链接同拒绝原因只记一条，避免重组刷屏。
                remember(safeLink, blocked) {
                    log(
                        DebugLogLevel.INFO,
                        "remote-image-blocked",
                        mapOf("link" to link.take(200), "failure" to blocked.name)
                    )
                    Unit
                }
                return ImageData(painter = reasonPainter(reasonText(blocked, retryable = false)))
            }
            val retry = retries[link] ?: 0
            val request = remember(safeLink, retry, platformContext) {
                ImageRequest.Builder(platformContext)
                    .data(safeLink)
                    // 远程图未经过导入压缩，常为数 MB 原图：限制解码尺寸（最长边 2048 px，
                    // 默认 Scale.FIT 等比缩放），避免 ORIGINAL 全尺寸解码 OOM 闪退；
                    // intrinsicSize 上报的是解码后尺寸，占位与显示不受影响
                    .size(CoilSize(2048, 2048))
                    .build()
            }
            val painter = rememberAsyncImagePainter(model = request)
            // painter 创建埋点：与 success/error 埋点形成夹阅区间——
            // 如果只到此一条而没有后续 success/error/blocked，就能定位到崩溃发生在 Coil 取图/解码阶段。
            // 以 (safeLink, retry) 为 key，同链接同重试次数只记一次。
            remember(safeLink, retry) {
                log(
                    DebugLogLevel.INFO,
                    "remote-painter-created",
                    mapOf("link" to link.take(200), "retry" to retry)
                )
                Unit
            }
            val state by painter.state.collectAsState()
            return when (val s = state) {
                is AsyncImagePainter.State.Error -> {
                    val cause = s.result.throwable
                    val failure = NetworkImageGuard.classify(cause)
                        ?: NetworkImageGuard.Failure.UNSUPPORTED
                    // 错误埋点（WARN）：除异常类型与完整异常链外，携带 HTTP 诊断信息
                    //（状态码、实际请求 URL、响应 Server/网关特征头、5xx 响应体摘要）——
                    // 用于辨识 403/504 等错误的真实来源（目标站 / 中间网关 / 本地代理）。
                    remember(s) {
                        val httpEx = NetworkImageGuard.httpStatusExceptionOf(cause)
                        log(
                            DebugLogLevel.WARN,
                            "remote-image-error",
                            buildMap<String, Any?> {
                                put("link", link.take(200))
                                put(
                                    "cause",
                                    cause?.let { "${it.javaClass.simpleName}: ${it.message}" }
                                        ?.take(200) ?: "null"
                                )
                                put(
                                    "chain",
                                    cause?.let { c ->
                                        generateSequence(c) { it.cause }.take(6)
                                            .joinToString("<-") { it.javaClass.simpleName }
                                    } ?: "null"
                                )
                                httpEx?.let { ex ->
                                    put("status", ex.code)
                                    ex.requestUrl?.let { put("reqUrl", it.take(200)) }
                                    ex.responseHeaders?.let { put("respHeaders", it.take(240)) }
                                    ex.bodyPreview?.let { put("body", it.take(160)) }
                                    ex.connInfo?.let { put("conn", it.take(360)) }
                                }
                            }
                        )
                        Unit
                    }
                    ImageData(
                        painter = reasonPainter(
                            reasonText(
                                failure,
                                retryable = true,
                                httpStatus = NetworkImageGuard.httpStatusOf(cause)
                            )
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { retries[link] = retry + 1 }
                    )
                }

                is AsyncImagePainter.State.Success -> {
                    val w = painter.intrinsicSize.width
                    val h = painter.intrinsicSize.height
                    remember(s) {
                        log(
                            DebugLogLevel.INFO,
                            "remote-image-success",
                            mapOf("link" to link.take(200), "w" to w, "h" to h)
                        )
                        Unit
                    }
                    ImageData(
                        painter = painter,
                        contentDescription = link.substringAfterLast('/')
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
            remember(link) {
                log(DebugLogLevel.INFO, "local-image-missing", mapOf("link" to link.take(200)))
                Unit
            }
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
            painterState.value.painter?.intrinsicSize?.also {
                if (it != size) {
                    // 占位尺寸收敛日志：仅尺寸变化时记录，定位"占位不更新/裁切"类问题
                    log(
                        DebugLogLevel.INFO,
                        "image-intrinsic-size",
                        mapOf("w" to it.width, "h" to it.height)
                    )
                    size = it
                }
            }
        }
        return size
    }

    /**
     * 占位对齐与宽基修正：
     * 1) 库默认 [PlaceholderVerticalAlign.Bottom]（占位底边对齐基线、向上延伸）——竖图时占位
     *    向上凸出数百 sp 把上方文字行整体盖住。改为 Top：占位从行顶向下延伸。
     * 2) 库拿到的 containerSize 为规整视口宽（包含 LazyColumn contentPadding）——竖图
     *    intrinsic.width ≥ 视口宽时，placeholder.width = containerSize.width = 视口宽，而
     *    实际可见区域 = 视口宽 - 2×contentPadding，因此右侧溢出（用户描述“图右边一小部分
     *    被撑出去”）。计算前从 containerSize.width 中扣减 2×容器内边距，与可见区域对齐。
     */
    override fun placeholderConfig(
        density: Density,
        containerSize: Size,
        intrinsicImageSize: Size
    ): PlaceholderConfig {
        val padPx = with(density) { containerHorizontalPaddingDp.toPx() } * 2f
        // Size 类无 isUnspecified / isNaN 属性；用 data class 的 equals 与
        // Size.Unspecified（NaN, NaN）对比。Unspecified 时 super 落入 180×180
        // 兑底分支，无需扣 padding。
        val effectiveContainer = if (containerSize == Size.Unspecified) containerSize
        else containerSize.copy(width = (containerSize.width - padPx).coerceAtLeast(1f))
        return super.placeholderConfig(density, effectiveContainer, intrinsicImageSize)
            .copy(verticalAlign = PlaceholderVerticalAlign.Top)
    }

    /**
     * 失败 / 拒绝原因文案；[retryable] 时追加「点击重试」提示行。
     * [httpStatus] 仅在 [NetworkImageGuard.Failure.HTTP_STATUS] 时用于携带具体状态码。
     */
    @Composable
    private fun reasonText(
        failure: NetworkImageGuard.Failure?,
        retryable: Boolean,
        httpStatus: Int? = null
    ): String {
        // 状态码文案含占位符，其余文案无占位（空格式参数数组合法）
        val formatArgs =
            if (failure == NetworkImageGuard.Failure.HTTP_STATUS) arrayOf<Any>(httpStatus ?: 0)
            else emptyArray<Any>()
        val base = stringResource(
            when (failure) {
                NetworkImageGuard.Failure.DISABLED -> R.string.remote_image_disabled
                NetworkImageGuard.Failure.PRIVATE_ADDRESS -> R.string.remote_image_private
                NetworkImageGuard.Failure.REDIRECT -> R.string.remote_image_redirect
                NetworkImageGuard.Failure.TOO_LARGE -> R.string.remote_image_too_large
                NetworkImageGuard.Failure.TIMEOUT -> R.string.remote_image_timeout
                NetworkImageGuard.Failure.HTTP_STATUS -> R.string.remote_image_http_status
                NetworkImageGuard.Failure.DNS_FAILED -> R.string.remote_image_dns
                NetworkImageGuard.Failure.CONNECT_FAILED -> R.string.remote_image_connect
                NetworkImageGuard.Failure.UNSUPPORTED -> R.string.remote_image_blocked
                null -> R.string.image_missing_placeholder
            },
            *formatArgs
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
