package com.az.notes.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.markdown.Heading
import com.az.notes.ui.theme.LocalReadingStyle
import com.mikepenz.markdown.m3.Markdown
import kotlin.math.roundToInt

/**
 * 预览页（§5.2 / §5.4 / §5.5）：Markdown 渲染 + 大纲底部弹窗 + 进度恢复。
 *
 * 渲染用 mikepenz `Markdown`（Material 3）。Demo 用 verticalScroll 包裹 Column；
 * 正式版换 `LazyMarkdownSuccess` 以获得项级虚拟化。滚动定位以“每块近似像素高度”
 * 估算锚点（§5.4 允许的按块序号近似对齐）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onEdit: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readingStyle = LocalReadingStyle.current
    val scrollState = rememberScrollState()
    var showOutline by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 每块近似像素高度（字号 × 行高 × 3 行/块 估算），用于块序号 <-> 像素换算
    val density = LocalDensity.current
    val pxPerBlock = with(density) {
        (readingStyle.fontSizeSp * readingStyle.lineHeightRatio * 3f).dp.toPx().roundToInt()
    }.coerceAtLeast(1)

    // 恢复上次滚动位置
    LaunchedEffect(state.loading, state.initialProgress) {
        if (!state.loading && state.initialProgress != null) {
            val target = state.initialProgress.scrollIndex * pxPerBlock
            scrollState.scrollTo(target.coerceIn(0, scrollState.maxValue))
        }
    }

    // 监听滚动，转成块序号交给 ViewModel（内部 500ms 去抖落库）
    LaunchedEffect(scrollState, state.content) {
        snapshotFlow { scrollState.value }.collect { value ->
            viewModel.onScrollPosition(value / pxPerBlock, value % pxPerBlock)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.path.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showOutline = true }) {
                        Icon(Icons.Outlined.List, stringResource(R.string.action_outline))
                    }
                    IconButton(onClick = { onEdit(state.path) }) {
                        Icon(Icons.Filled.Edit, stringResource(R.string.action_edit))
                    }
                }
            )
        }
    ) { inner ->
        when {
            state.loading -> CenterBox { CircularProgressIndicator() }
            state.error != null -> CenterBox {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
            }
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Markdown(
                    state.content,
                    Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showOutline) {
        ModalBottomSheet(onDismissRequest = { showOutline = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.action_outline),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                if (state.headings.isEmpty()) {
                    Text(
                        "本文无标题层级",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                } else {
                    state.headings.forEach { h ->
                        OutlineItem(h = h, onClick = {
                            showOutline = false
                            scrollState.animateScrollTo(
                                (h.blockIndex * pxPerBlock).coerceIn(0, scrollState.maxValue)
                            )
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineItem(h: Heading, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (h.level * 16).dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "H${h.level}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            h.text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
}
