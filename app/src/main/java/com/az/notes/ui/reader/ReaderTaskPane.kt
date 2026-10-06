package com.az.notes.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.az.notes.R
import com.az.notes.domain.markdown.FrontmatterAttribute
import com.az.notes.domain.markdown.TaskItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 预览页待办相关面板：文件名标题 / 属性面板 / 待办清单页。
 * 自 ReaderScreen 拆分独立文件（纯 UI，逻辑不变）。
 */

/**
 * 文件名标题（预览页 / 待办页共用）：与正文之间用分隔线区分，避免整片连在一起。
 */
@Composable
internal fun FileNameHeader(fileName: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * 笔记属性面板：frontmatter 中除 `note_type` 外的属性，圆角卡片显示、可折叠；
 * 多值属性（YAML 列表写法）以流式标签展示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteAttributesPanel(
    attributes: List<FrontmatterAttribute>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.reader_attrs_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                    else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.reader_attrs_collapse else R.string.reader_attrs_expand
                    ),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    attributes.forEach { attr ->
                        val values = attr.values.filter { it.isNotBlank() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = attr.key,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .width(80.dp)
                                    .padding(top = 2.dp)
                            )
                            if (values.isEmpty()) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    values.forEach { value ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                text = value,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
    }
}

/**
 * 待办清单页（frontmatter `note_type: task`）：仅渲染待办条目，
 * 点击勾选完成（写回 `- [x]`），底部输入栏追加新条目（写回 `- [ ]`）。
 * 支持「仅未完成」过滤；长按条目拖动排序（过滤态下只在可见子集内重排）；
 * 添加待办后列表自动滚动到新条目。
 */
@Composable
internal fun TaskListPane(
    fileName: String,
    tasks: List<TaskItem>,
    attributes: List<FrontmatterAttribute>,
    onToggle: (Int, Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onReorder: (List<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by rememberSaveable { mutableStateOf("") }
    var incompleteOnly by rememberSaveable { mutableStateOf(false) }
    var attrsExpanded by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    // 非定制属性（note_type 之外的普通属性）
    val attrs = remember(attributes) { attributes.filter { it.key != "note_type" } }

    // 显示条目：勾选「仅未完成」时过滤已完成项；拖动排序仅作用于可见子集
    val visibleTasks = remember(tasks, incompleteOnly) {
        if (incompleteOnly) tasks.filter { !it.checked } else tasks
    }

    // 拖动排序：长按拖起后本地顺序实时交换，松手写回；数据刷新后收敛回 tasks
    var dragOrder by remember { mutableStateOf<List<TaskItem>?>(null) }
    var draggedLine by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<Int, Float>() }
    val displayTasks = dragOrder ?: visibleTasks
    val latestTasks by rememberUpdatedState(visibleTasks)

    // 列表前导项数：文件名 + （属性面板）+ 过滤行
    val leadingCount = 2 + if (attrs.isNotEmpty()) 1 else 0

    // 添加后跟随滚动：等待列表数据刷新后再滚到末尾
    var pendingScrollToEnd by remember { mutableStateOf(false) }

    // 写回完成后本地顺序收敛（内容对齐即视为已落入磁盘）
    LaunchedEffect(visibleTasks, dragOrder) {
        val order = dragOrder ?: return@LaunchedEffect
        if (draggedLine != null) return@LaunchedEffect
        val converged = order.size == visibleTasks.size &&
            order.indices.all { i ->
                visibleTasks[i].text == order[i].text && visibleTasks[i].checked == order[i].checked
            }
        if (converged) dragOrder = null
    }

    LaunchedEffect(tasks.size) {
        if (!pendingScrollToEnd) return@LaunchedEffect
        pendingScrollToEnd = false
        if (visibleTasks.isNotEmpty()) {
            listState.animateScrollToItem(leadingCount + visibleTasks.size - 1, scrollOffset = 10_000)
        }
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        onAdd(text)
        input = ""
        pendingScrollToEnd = true
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            item(key = "file_name_header") {
                FileNameHeader(fileName)
            }
            if (attrs.isNotEmpty()) {
                item(key = "note_attrs") {
                    NoteAttributesPanel(
                        attributes = attrs,
                        expanded = attrsExpanded,
                        onToggleExpanded = { attrsExpanded = !attrsExpanded },
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }
            item(key = "task_filter") {
                FilterChip(
                    selected = incompleteOnly,
                    onClick = { incompleteOnly = !incompleteOnly },
                    label = { Text(stringResource(R.string.task_only_incomplete)) },
                    leadingIcon = if (incompleteOnly) {
                        { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            if (displayTasks.isEmpty()) {
                item(key = "task_empty") {
                    Text(
                        text = stringResource(
                            if (tasks.isEmpty()) R.string.task_empty else R.string.task_incomplete_empty
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                itemsIndexed(items = displayTasks, key = { _, task -> task.lineIndex }) { index, task ->
                    val isDragging = draggedLine != null && task.lineIndex == draggedLine
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            // graphicsLayer 必须位于底框 / 阴影之前（更外层）：拖动时整行
                            // （含背景框）一起位移，否则只有内层文字滑动、框留在原地
                            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                            .then(
                                when {
                                    isDragging -> Modifier
                                        .shadow(6.dp, RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                    // 拖动期间项位交换带动画；写回重载时 key 置换，关掉动画避免无意义滑动
                                    dragOrder != null -> Modifier.animateItem()
                                    else -> Modifier
                                }
                            )
                            .onGloballyPositioned { itemHeights[index] = it.size.height.toFloat() }
                            .pointerInput(task.lineIndex) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggedLine = task.lineIndex
                                        dragOrder = latestTasks
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggedLine = null
                                        dragOffsetY = 0f
                                        dragOrder = null
                                    },
                                    onDragEnd = {
                                        val order = dragOrder
                                        draggedLine = null
                                        dragOffsetY = 0f
                                        if (order != null) {
                                            val newOrder = order.map { it.lineIndex }
                                            if (newOrder != latestTasks.map { it.lineIndex }) {
                                                onReorder(newOrder)
                                                // 兜底：写回失败时避免本地顺序悬挂（正常由数据收敛清空）
                                                scope.launch {
                                                    delay(800)
                                                    if (dragOrder === order) dragOrder = null
                                                }
                                            } else {
                                                dragOrder = null
                                            }
                                        }
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        dragOffsetY += drag.y
                                        val line = draggedLine ?: return@detectDragGesturesAfterLongPress
                                        var order = dragOrder ?: return@detectDragGesturesAfterLongPress
                                        // 越过相邻项半高即交换；一次事件可连续交换多格
                                        while (true) {
                                            val from = order.indexOfFirst { it.lineIndex == line }
                                            if (from < 0) break
                                            val hDrag = itemHeights[from] ?: break
                                            if (dragOffsetY > 0f && from + 1 < order.size) {
                                                val hNext = itemHeights[from + 1] ?: break
                                                if (dragOffsetY < (hDrag + hNext) / 2f) break
                                                order = order.toMutableList().apply { add(from + 1, removeAt(from)) }
                                                dragOffsetY -= hNext
                                            } else if (dragOffsetY < 0f && from > 0) {
                                                val hPrev = itemHeights[from - 1] ?: break
                                                if (-dragOffsetY < (hPrev + hDrag) / 2f) break
                                                order = order.toMutableList().apply { add(from - 1, removeAt(from)) }
                                                dragOffsetY += hPrev
                                            } else {
                                                break
                                            }
                                            dragOrder = order
                                        }
                                    }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = task.checked,
                            onCheckedChange = { checked -> onToggle(task.lineIndex, checked) }
                        )
                        Text(
                            text = task.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (task.checked) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (task.checked) TextDecoration.LineThrough else null,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onToggle(task.lineIndex, !task.checked) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.task_add_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { submit() }, enabled = input.isNotBlank()) {
                Icon(Icons.Filled.Add, stringResource(R.string.task_add_action))
            }
        }
    }
}
