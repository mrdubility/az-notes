package com.az.notes.ui.reader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.az.notes.R
import com.az.notes.data.media.GallerySaver
import com.az.notes.data.media.RemoteImageDownloader
import com.az.notes.data.storage.BacklinkItem
import com.az.notes.data.storage.LinkOutItem
import com.az.notes.di.CoilHolderEntryPoint
import com.az.notes.domain.markdown.Heading
import com.az.notes.ui.common.formatDateTimeSeconds
import com.az.notes.ui.common.formatSize
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 预览页浮层与对话框：笔记面板（大纲 / 反向链接 / 出链） / 全屏图片预览 / 文档信息。
 * 自 ReaderScreen 拆分独立文件（纯 UI，逻辑不变）。
 */

/** 面板页签下标（与 TabRow 顺序一致）。 */
internal const val NOTE_PANEL_OUTLINE = 0
internal const val NOTE_PANEL_BACKLINKS = 1
internal const val NOTE_PANEL_OUTLINKS = 2

/**
 * 笔记面板浮层（全屏 Dialog）：正文右侧滑出面板 + 半透明遮罩；
 * 点击遮罩 / 系统返回收起。三个页签共用同一面板：
 * 大纲回传标题由调用方滚动到对应块；反向链接与出链的条目经 [onOpenLink]
 * 回传跳转目标（未解析的出链置灰不可点——面板在 Dialog 里拿不到正文的 Snackbar）。
 */
@Composable
internal fun NotePanelDialog(
    headings: List<Heading>,
    listState: LazyListState,
    blockOffset: Int,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    links: ReaderLinksUiState,
    onSelectHeading: (Heading) -> Unit,
    onOpenLink: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // 当前章节：第一个可见块所属的最近标题
        val activeIndex by remember(headings, blockOffset) {
            derivedStateOf {
                // 去掉文件名（与属性面板）占位，还原为真实块序号
                headings.indexOfLast { it.blockIndex <= listState.firstVisibleItemIndex - blockOffset }
            }
        }
        // 打开后触发一次性进入动画（面板自右滑入、遮罩淡入）
        var entered by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { entered = true }
        // 三个页签各自保留滚动位置
        val outlineState = rememberLazyListState()
        val backlinkState = rememberLazyListState()
        val outlinkState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        )
                )
            }
            AnimatedVisibility(
                visible = entered,
                enter = slideInHorizontally(initialOffsetX = { it }),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.86f)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    Column(Modifier.fillMaxHeight()) {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(
                                selected = selectedTab == NOTE_PANEL_OUTLINE,
                                onClick = { onTabSelected(NOTE_PANEL_OUTLINE) },
                                text = { Text(stringResource(R.string.action_outline)) }
                            )
                            Tab(
                                selected = selectedTab == NOTE_PANEL_BACKLINKS,
                                onClick = { onTabSelected(NOTE_PANEL_BACKLINKS) },
                                text = {
                                    Text(
                                        tabLabel(
                                            R.string.reader_panel_backlinks,
                                            links.backLinks.size
                                        )
                                    )
                                }
                            )
                            Tab(
                                selected = selectedTab == NOTE_PANEL_OUTLINKS,
                                onClick = { onTabSelected(NOTE_PANEL_OUTLINKS) },
                                text = {
                                    Text(
                                        tabLabel(
                                            R.string.reader_panel_outlinks,
                                            links.outLinks.size
                                        )
                                    )
                                }
                            )
                        }
                        when (selectedTab) {
                            NOTE_PANEL_BACKLINKS -> BacklinksTab(links, backlinkState, onOpenLink)
                            NOTE_PANEL_OUTLINKS -> OutLinksTab(links, outlinkState, onOpenLink)
                            else -> OutlineTab(headings, activeIndex, outlineState, onSelectHeading)
                        }
                    }
                }
            }
        }
    }
}

/** 页签标题：有计数时追加数字（大纲不追加）。 */
@Composable
private fun tabLabel(textRes: Int, count: Int): String {
    val label = stringResource(textRes)
    return if (count > 0) "$label $count" else label
}

/** 大纲页签：按级别缩进 + 当前章节高亮（真实块锚点匹配）。 */
@Composable
private fun OutlineTab(
    headings: List<Heading>,
    activeIndex: Int,
    listState: LazyListState,
    onSelect: (Heading) -> Unit
) {
    if (headings.isEmpty()) {
        EmptyHint(R.string.reader_outline_empty)
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(headings) { index, h ->
            OutlineItem(h = h, active = index == activeIndex, onClick = { onSelect(h) })
        }
    }
}

/** 反向链接页签：来源笔记名 + 相对目录 + 命中行上下文；末尾追加「未链接提及」分组。 */
@Composable
private fun BacklinksTab(
    links: ReaderLinksUiState,
    listState: LazyListState,
    onOpenLink: (String, String?) -> Unit
) {
    when {
        links.loading -> LinksLoadingHint(links.progress)
        links.backLinks.isEmpty() && links.mentions.isEmpty() ->
            EmptyHint(R.string.reader_backlinks_empty)
        else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(links.backLinks, key = { "bl-${it.sourcePath}:${it.line}" }) { item ->
                BacklinkRow(item) { onOpenLink(item.sourcePath, null) }
            }
            if (links.mentions.isNotEmpty()) {
                item(key = "mentions_header") {
                    Text(
                        text = stringResource(R.string.reader_mentions_header, links.mentions.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                items(links.mentions, key = { "mn-${it.sourcePath}:${it.line}" }) { item ->
                    BacklinkRow(item) { onOpenLink(item.sourcePath, null) }
                }
            }
        }
    }
}

/** 出链页签：已解析可点（带锚点），未解析置灰不可点。 */
@Composable
private fun OutLinksTab(
    links: ReaderLinksUiState,
    listState: LazyListState,
    onOpenLink: (String, String?) -> Unit
) {
    if (links.outLinks.isEmpty()) {
        EmptyHint(R.string.reader_outlinks_empty)
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(links.outLinks) { item ->
            val target = item.targetPath
            val onClick: (() -> Unit)? = if (target != null) {
                { onOpenLink(target, item.anchor) }
            } else {
                null
            }
            OutLinkRow(item = item, onClick = onClick)
        }
    }
}

/** 单条反向引用：文件名 + 相对目录 + 上下文片段。 */
@Composable
private fun BacklinkRow(item: BacklinkItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text = item.sourcePath.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val dir = item.relativePath.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) {
            Text(
                text = dir,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = item.snippet,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 单条出链：显示名 + 次数 + 目标相对路径；未解析附「未创建」标记。 */
@Composable
private fun OutLinkRow(item: LinkOutItem, onClick: (() -> Unit)?) {
    val resolved = item.targetPath != null
    val base = Modifier.fillMaxWidth()
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.display,
                style = MaterialTheme.typography.bodyLarge,
                color = if (resolved) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.count > 1) {
                Text(
                    text = "×${item.count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!resolved) {
                Text(
                    text = stringResource(R.string.reader_link_unresolved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        val sub = item.relativePath?.let { path ->
            item.anchor?.let { "$path#$it" } ?: path
        } ?: item.anchor
        if (sub != null) {
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 页签空态提示。 */
@Composable
private fun EmptyHint(textRes: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
    }
}

/** 反向链接索引构建中的提示（带进度）。 */
@Composable
private fun LinksLoadingHint(progress: Pair<Int, Int>?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (progress != null && progress.second > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.reader_links_scanning,
                        progress.first,
                        progress.second
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 大纲条目：按级别缩进；[active] 高亮当前所在章节（真实块锚点匹配）。 */
@Composable
private fun OutlineItem(h: Heading, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else Color.Transparent
            )
            .padding(start = (h.level * 16).dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "H${h.level}",
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = h.text.ifBlank { stringResource(R.string.reader_outline_untitled) },
            style = MaterialTheme.typography.bodyLarge,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 预览浮层的保存来源：本地文件直读；网络图经受控客户端拉取原图字节。 */
internal sealed interface PreviewSaveSource {
    data class Local(val file: File) : PreviewSaveSource
    data class Remote(val url: String) : PreviewSaveSource
}

/** 全屏图片预览（本地文件入口）：黑底 + 点按关闭 + 捏合缩放（1–5 倍，放大后可拖动）。
 *  图库 / 阅读器本地图共用；网络图请走 request 重载（需复用行内解码尺寸）。 */
@Composable
internal fun ImagePreviewDialog(file: File, onDismiss: () -> Unit) {
    val platformContext = LocalPlatformContext.current
    val request = remember(file) {
        ImageRequest.Builder(platformContext)
            .data(Uri.fromFile(file))
            .crossfade(true)
            .build()
    }
    ImagePreviewDialog(
        request = request,
        contentDescription = file.name,
        onDismiss = onDismiss,
        saveSource = PreviewSaveSource.Local(file)
    )
}

/** 全屏图片预览（通用入口）：[request] 决定来源与解码策略。网络图必须与行内渲染使用
 *  相同 URL + 相同 size（Coil 内存缓存按请求参数做 key），预览才能复用行内已解码位图、
 *  零下载零解码；本地文件不落盘缓存，可直接由目标视图尺寸决定解码大小。 */
@Composable
internal fun ImagePreviewDialog(
    request: ImageRequest,
    contentDescription: String?,
    onDismiss: () -> Unit,
    /** 保存来源（非 null 时显示「保存到相册」按钮）：本地文件或网络图 URL。 */
    saveSource: PreviewSaveSource? = null
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .transformable(transformableState)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
            saveSource?.let { source ->
                SaveToGalleryButton(
                    source = source,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 32.dp)
                )
            }
        }
    }
}

/** 相册保存按钮状态（终态短暂展示后自动复位）。 */
private enum class GallerySaveUiState { IDLE, SAVING, SAVED, FAILED, DENIED }

/**
 * 「保存到相册」按钮（黑底预览浮层专用，胶囊形）：写入 Pictures/AzNotes。
 * Android 8.0–9.0 写公共相册需 WRITE 权限（运行时请求）；Android 10+ 零权限。
 * 本地文件直读；网络图经受控客户端拉取原图字节（与行内加载同一护栏）。
 */
@Composable
private fun SaveToGalleryButton(source: PreviewSaveSource, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(GallerySaveUiState.IDLE) }

    // 结果状态展示 2 秒后复位，恢复可保存形态
    LaunchedEffect(state) {
        when (state) {
            GallerySaveUiState.SAVED, GallerySaveUiState.FAILED, GallerySaveUiState.DENIED -> {
                delay(2000)
                state = GallerySaveUiState.IDLE
            }
            else -> Unit
        }
    }

    val doSave: () -> Unit = {
        if (state != GallerySaveUiState.SAVING) {
            state = GallerySaveUiState.SAVING
            scope.launch {
                val uri = when (source) {
                    is PreviewSaveSource.Local -> GallerySaver.save(context, source.file)
                    is PreviewSaveSource.Remote -> RemoteImageDownloader.saveToGallery(
                        context = context,
                        client = coilClient(context),
                        url = source.url,
                        defaultBase = context.getString(R.string.image_default_base_name)
                    )
                }
                state = if (uri != null) GallerySaveUiState.SAVED else GallerySaveUiState.FAILED
            }
        }
    }

    // Android 8.0–9.0 写公共相册需 WRITE 权限（Manifest maxSdkVersion=29 已声明）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doSave() else state = GallerySaveUiState.DENIED
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable {
                if (state == GallerySaveUiState.SAVING) return@clickable
                val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsPermission) {
                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    doSave()
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            GallerySaveUiState.SAVING -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Color.White
            )
            GallerySaveUiState.SAVED -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            else -> Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(
                when (state) {
                    GallerySaveUiState.IDLE -> R.string.image_save_gallery
                    GallerySaveUiState.SAVING -> R.string.image_save_saving
                    GallerySaveUiState.SAVED -> R.string.image_save_saved
                    GallerySaveUiState.FAILED -> R.string.image_save_failed
                    GallerySaveUiState.DENIED -> R.string.image_save_denied
                }
            ),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White
        )
    }
}

/** 取受控 OkHttp 客户端（Hilt EntryPoint；仅网络图保存路径使用，与行内加载同一护栏）。 */
private fun coilClient(context: Context): OkHttpClient =
    EntryPointAccessors
        .fromApplication(context.applicationContext, CoilHolderEntryPoint::class.java)
        .coilHolder()
        .client()

/** 文档信息对话框：文件名 / 位置 / 大小 / 创建与修改时间 / 字数统计。 */
@Composable
internal fun DocInfoDialog(info: DocInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DocInfoRow(stringResource(R.string.reader_info_name), info.name)
                DocInfoRow(stringResource(R.string.reader_info_path), info.path)
                DocInfoRow(stringResource(R.string.reader_info_size), formatSize(info.sizeBytes))
                info.createdAt?.let {
                    DocInfoRow(stringResource(R.string.reader_info_created), formatDateTimeSeconds(it))
                }
                DocInfoRow(
                    stringResource(R.string.reader_info_modified),
                    formatDateTimeSeconds(info.lastModified)
                )
                DocInfoRow(stringResource(R.string.reader_info_chars), info.charCount.toString())
                DocInfoRow(stringResource(R.string.reader_info_lines), info.lineCount.toString())
                DocInfoRow(stringResource(R.string.reader_info_headings), info.headingCount.toString())
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

/** 信息行：左侧固定宽度标签 + 自适应值。 */
@Composable
private fun DocInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
