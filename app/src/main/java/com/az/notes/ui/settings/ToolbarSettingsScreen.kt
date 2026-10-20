package com.az.notes.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.EditorTool
import com.az.notes.ui.common.label
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 编辑器工具栏设置页：逐项开关 + 长按拖动排序 + 恢复默认。
 * 从设置页入口进入（内容较长，独立成页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 持久化顺序可能缺少新版本追加的工具：补齐到末尾（默认启用）
    val persistedOrder = remember(settings.editorToolOrder) {
        settings.editorToolOrder + EditorTool.defaultOrder.filter { it !in settings.editorToolOrder }
    }
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var scrollAccum by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<String, Float>() }
    val displayOrder = dragOrder ?: persistedOrder
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // 写回完成后本地顺序收敛
    LaunchedEffect(persistedOrder, dragOrder) {
        val order = dragOrder ?: return@LaunchedEffect
        if (draggedId != null) return@LaunchedEffect
        if (order == persistedOrder) dragOrder = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_editor_toolbar)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item(key = "subtitle") {
                Text(
                    text = stringResource(R.string.settings_editor_toolbar_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
                )
            }
            itemsIndexed(items = displayOrder, key = { _, id -> "tool_$id" }) { index, id ->
                val tool = EditorTool.fromId(id) ?: return@itemsIndexed
                val dragging = draggedId == id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffsetY else 0f }
                        .then(
                            when {
                                dragging -> Modifier
                                    .shadow(6.dp, RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                // 拖动期间不做位移动画：动画与手动 translation 叠加会产生闪动
                                else -> Modifier
                            }
                        )
                        .onGloballyPositioned { itemHeights[id] = it.size.height.toFloat() }
                        .pointerInput(id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggedId = id
                                    dragOrder = persistedOrder
                                    dragOffsetY = 0f
                                    scrollAccum = 0f
                                },
                                onDragCancel = {
                                    draggedId = null
                                    dragOffsetY = 0f
                                    scrollAccum = 0f
                                    dragOrder = null
                                },
                                onDragEnd = {
                                    val order = dragOrder
                                    draggedId = null
                                    dragOffsetY = 0f
                                    scrollAccum = 0f
                                    if (order != null && order != persistedOrder) {
                                        viewModel.setEditorToolOrder(order)
                                        // 兜底：写回失败时避免本地顺序悬挂（正常由数据收敛清空）
                                        scope.launch {
                                            delay(800)
                                            if (dragOrder === order) dragOrder = null
                                        }
                                    } else {
                                        dragOrder = null
                                    }
                                },
                                onDrag = { change, drag ->
                                    change.consume()
                                    dragOffsetY += drag.y
                                    val current = draggedId ?: return@detectDragGesturesAfterLongPress
                                    var order = dragOrder ?: return@detectDragGesturesAfterLongPress
                                    // 越过相邻项高度即交换（官方 drag-to-reorder 同口径）；一次事件可连续交换多格
                                    while (true) {
                                        val from = order.indexOf(current)
                                        if (from < 0) break
                                        val hDrag = itemHeights[order[from]] ?: break
                                        if (dragOffsetY > hDrag && from + 1 < order.size) {
                                            val hNext = itemHeights[order[from + 1]] ?: break
                                            order = order.toMutableList().apply { add(from + 1, removeAt(from)) }
                                            dragOffsetY -= hNext
                                        } else if (dragOffsetY < 0f && from > 0) {
                                            val hPrev = itemHeights[order[from - 1]] ?: break
                                            order = order.toMutableList().apply { add(from - 1, removeAt(from)) }
                                            dragOffsetY += hPrev
                                        } else {
                                            break
                                        }
                                        dragOrder = order
                                    }
                                    // 拖到列表顶 / 底之外自动滚动；累计一定距离再滚，避免每帧派发
                                    val from = order.indexOf(current)
                                    val h = itemHeights[current] ?: 0f
                                    val y = change.position.y
                                    val atTop = y < 0f && listState.canScrollBackward
                                    val atBottom = y > h && listState.canScrollForward
                                    if (atTop || atBottom) {
                                        scrollAccum += drag.y
                                        if (abs(scrollAccum) >= 12f) {
                                            val delta = scrollAccum
                                            scrollAccum = 0f
                                            // 滚动使项在列表中的布局位置上移，同步补偿位移保持跟手
                                            scope.launch {
                                                listState.scrollBy(delta)
                                                dragOffsetY += delta
                                            }
                                        }
                                    } else {
                                        scrollAccum = 0f
                                    }
                                }
                            )
                        }
                        .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ViewList,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = tool.label(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (id !in settings.editorToolDisabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                    )
                    Switch(
                        checked = id !in settings.editorToolDisabled,
                        onCheckedChange = { enabled ->
                            val disabled = settings.editorToolDisabled.toMutableSet()
                            if (enabled) disabled.remove(id) else disabled.add(id)
                            viewModel.setEditorToolDisabled(disabled)
                        }
                    )
                }
            }
            item(key = "reset") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = {
                        viewModel.setEditorToolOrder(EditorTool.defaultOrder)
                        viewModel.setEditorToolDisabled(emptySet())
                    }) {
                        Text(stringResource(R.string.settings_editor_toolbar_reset))
                    }
                }
            }
        }
    }
}
