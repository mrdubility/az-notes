package com.az.notes.ui.reader

import android.net.Uri
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.az.notes.domain.markdown.Heading
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 预览页浮层与对话框：大纲面板 / 全屏图片预览 / 文档信息。
 * 自 ReaderScreen 拆分独立文件（纯 UI，逻辑不变）。
 */

/**
 * 大纲浮层（全屏 Dialog）：正文右侧滑出面板 + 半透明遮罩；
 * 点击遮罩 / 系统返回收起；点击标题回传由调用方滚动到对应块。
 */
@Composable
internal fun OutlineDialog(
    headings: List<Heading>,
    listState: LazyListState,
    blockOffset: Int,
    onSelect: (Heading) -> Unit,
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
                        .fillMaxWidth(0.78f)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    Column(Modifier.fillMaxHeight()) {
                        Text(
                            stringResource(R.string.action_outline),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                        if (headings.isEmpty()) {
                            Text(
                                stringResource(R.string.reader_outline_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(headings) { index, h ->
                                    OutlineItem(
                                        h = h,
                                        active = index == activeIndex,
                                        onClick = { onSelect(h) }
                                    )
                                }
                            }
                        }
                    }
                }
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

/** 全屏图片预览：黑底 + 点按关闭 + 捏合缩放（1–5 倍，放大后可拖动）。 */
@Composable
internal fun ImagePreviewDialog(file: File, onDismiss: () -> Unit) {
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
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(Uri.fromFile(file))
                    .crossfade(true)
                    .build(),
                contentDescription = file.name,
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
        }
    }
}

/** 文档信息对话框：文件名 / 位置 / 大小 / 修改时间 / 字数统计。 */
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
                DocInfoRow(
                    stringResource(R.string.reader_info_modified),
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(info.lastModified))
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

/** 文件大小：B / KB / MB（各档保留一位小数）。 */
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
