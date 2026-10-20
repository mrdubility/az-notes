package com.az.notes.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.az.notes.R
import com.az.notes.domain.markdown.FrontmatterAttribute

/** 属性值为单个 true / false 时返回布尔（忽略大小写与引号），否则 null。 */
internal fun booleanAttrValue(values: List<String>): Boolean? {
    val single = values.map { it.trim().trim('"', '\'') }
        .filter { it.isNotEmpty() }
        .singleOrNull() ?: return null
    return when (single.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** 历史联想锚定的输入框。 */
private enum class AttrInput { KEY, VALUE }

/**
 * 属性编辑弹窗。列表行展示属性；点击行原地切换为键值编辑行；「点击添加属性」
 * 在末尾追加一行空白编辑行；编辑行失去焦点即保存（键为空则丢弃，键改名合并为
 * 一次写盘）；新属性追加在最下面。键值输入框各自弹出历史联想，条目紧凑、限高
 * 可滚动，下方放不下时改在编辑行上方弹出；弹窗限高并随输入法上移，属性较多时
 * 列表内部滚动，编辑行始终在键盘上方。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttributeEditorDialog(
    attributes: List<FrontmatterAttribute>,
    history: Map<String, List<String>>?,
    onSetValues: (String, List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onReplace: (String, String, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    // null = 无编辑行；"" = 新增行；其他 = 正在编辑该键的属性
    var editTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var draftKey by rememberSaveable { mutableStateOf("") }
    var draftValue by rememberSaveable { mutableStateOf("") }
    var keyFocused by remember { mutableStateOf(false) }
    var valueFocused by remember { mutableStateOf(false) }
    var rowTouched by remember { mutableStateOf(false) }
    var activeInput by remember { mutableStateOf<AttrInput?>(null) }
    var focusTick by remember { mutableStateOf(0) }
    val keyFocus = remember { FocusRequester() }
    val valueFocus = remember { FocusRequester() }

    val editing = editTarget != null

    val keyHistory = remember(history, draftKey) {
        val keys = history?.keys?.toList().orEmpty()
        if (draftKey.isBlank()) keys else keys.filter { it.contains(draftKey.trim(), ignoreCase = true) }
    }
    val valueHistory = remember(history, draftKey) {
        history?.entries
            ?.firstOrNull { it.key.equals(draftKey.trim(), ignoreCase = true) }
            ?.value
            .orEmpty()
    }

    fun clearEdit() {
        editTarget = null
        draftKey = ""
        draftValue = ""
        keyFocused = false
        valueFocused = false
        rowTouched = false
        activeInput = null
    }

    fun submitRow() {
        if (!editing) return
        val key = draftKey.trim()
        val target = editTarget
        val values = draftValue.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        clearEdit()
        if (key.isEmpty()) return
        if (target != null && target.isNotEmpty() && target != key) {
            onReplace(target, key, values)
        } else {
            onSetValues(key, values)
        }
    }

    fun startEdit(target: String, key: String, value: String) {
        submitRow()
        editTarget = target
        draftKey = key
        draftValue = value
        focusTick++
    }

    // 编辑行曾获得过焦点且键值两框均失焦时保存
    LaunchedEffect(keyFocused, valueFocused) {
        if (rowTouched && !keyFocused && !valueFocused) submitRow()
    }

    // 编辑行组合完成后聚焦键输入框（两帧后布局稳定）
    LaunchedEffect(focusTick) {
        if (focusTick == 0 || !editing) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { keyFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = {
            submitRow()
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp)) {
                Text(
                    text = stringResource(R.string.reader_attr_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (attributes.isEmpty() && !editing) {
                        Text(
                            text = stringResource(R.string.reader_attr_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        attributes.forEach { attr ->
                            if (attr.key == editTarget) {
                                AttrEditRow(
                                    draftKey = draftKey,
                                    onKeyChange = {
                                        draftKey = it
                                        activeInput = AttrInput.KEY
                                    },
                                    draftValue = draftValue,
                                    onValueChange = {
                                        draftValue = it
                                        activeInput = AttrInput.VALUE
                                    },
                                    onKeyFocus = { focused ->
                                        keyFocused = focused
                                        if (focused) {
                                            rowTouched = true
                                            activeInput = AttrInput.KEY
                                        }
                                    },
                                    onValueFocus = { focused ->
                                        valueFocused = focused
                                        if (focused) {
                                            rowTouched = true
                                            activeInput = AttrInput.VALUE
                                        }
                                    },
                                    keyFocusRequester = keyFocus,
                                    valueFocusRequester = valueFocus,
                                    keyHistory = keyHistory,
                                    valueHistory = valueHistory,
                                    activeInput = activeInput,
                                    onPickKey = {
                                        draftKey = it
                                        activeInput = AttrInput.VALUE
                                        runCatching { valueFocus.requestFocus() }
                                    },
                                    onPickValue = {
                                        draftValue = if (draftValue.isBlank()) {
                                            it
                                        } else {
                                            draftValue.trimEnd('\n') + "\n" + it
                                        }
                                        activeInput = null
                                    },
                                    onDismissMenu = { activeInput = null },
                                    onCancel = { clearEdit() }
                                )
                            } else {
                                AttrRow(
                                    attr = attr,
                                    onClick = { startEdit(attr.key, attr.key, attr.values.joinToString("\n")) },
                                    onSetValues = onSetValues,
                                    onRemove = onRemove
                                )
                            }
                        }
                        if (editTarget == "") {
                            HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 4.dp, end = 8.dp))
                            AttrEditRow(
                                draftKey = draftKey,
                                onKeyChange = {
                                    draftKey = it
                                    activeInput = AttrInput.KEY
                                },
                                draftValue = draftValue,
                                onValueChange = {
                                    draftValue = it
                                    activeInput = AttrInput.VALUE
                                },
                                onKeyFocus = { focused ->
                                    keyFocused = focused
                                    if (focused) {
                                        rowTouched = true
                                        activeInput = AttrInput.KEY
                                    }
                                },
                                onValueFocus = { focused ->
                                    valueFocused = focused
                                    if (focused) {
                                        rowTouched = true
                                        activeInput = AttrInput.VALUE
                                    }
                                },
                                keyFocusRequester = keyFocus,
                                valueFocusRequester = valueFocus,
                                keyHistory = keyHistory,
                                valueHistory = valueHistory,
                                activeInput = activeInput,
                                onPickKey = {
                                    draftKey = it
                                    activeInput = AttrInput.VALUE
                                    runCatching { valueFocus.requestFocus() }
                                },
                                onPickValue = {
                                    draftValue = if (draftValue.isBlank()) {
                                        it
                                    } else {
                                        draftValue.trimEnd('\n') + "\n" + it
                                    }
                                    activeInput = null
                                },
                                onDismissMenu = { activeInput = null },
                                onCancel = { clearEdit() }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { startEdit("", "", "") }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.reader_attr_add_action))
                    }
                }
            }
        }
    }
}

/**
 * 键值编辑行：键 / 值左右排列，行尾取消（不保存）。键值历史分别锚定各自输入框，
 * 条目紧凑、限高 200dp 可滚动；编辑行下方放不下时在行上方弹出。
 */
@Composable
private fun AttrEditRow(
    draftKey: String,
    onKeyChange: (String) -> Unit,
    draftValue: String,
    onValueChange: (String) -> Unit,
    onKeyFocus: (Boolean) -> Unit,
    onValueFocus: (Boolean) -> Unit,
    keyFocusRequester: FocusRequester,
    valueFocusRequester: FocusRequester,
    keyHistory: List<String>,
    valueHistory: List<String>,
    activeInput: AttrInput?,
    onPickKey: (String) -> Unit,
    onPickValue: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onCancel: () -> Unit
) {
    var rowBounds by remember { mutableStateOf<Rect?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowBounds = it.boundsInWindow() }
            .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Box(Modifier.weight(0.4f)) {
            OutlinedTextField(
                value = draftKey,
                onValueChange = onKeyChange,
                singleLine = true,
                label = { Text(stringResource(R.string.reader_attr_key)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(keyFocusRequester)
                    .onFocusChanged { onKeyFocus(it.isFocused) }
            )
            AttrHistoryMenu(
                expanded = activeInput == AttrInput.KEY && keyHistory.isNotEmpty(),
                suggestions = keyHistory,
                anchorBounds = rowBounds,
                onDismiss = onDismissMenu,
                onPick = onPickKey
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(0.6f)) {
            OutlinedTextField(
                value = draftValue,
                onValueChange = onValueChange,
                minLines = 1,
                maxLines = 3,
                label = { Text(stringResource(R.string.reader_attr_value)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(valueFocusRequester)
                    .onFocusChanged { onValueFocus(it.isFocused) }
            )
            AttrHistoryMenu(
                expanded = activeInput == AttrInput.VALUE && valueHistory.isNotEmpty(),
                suggestions = valueHistory,
                anchorBounds = rowBounds,
                onDismiss = onDismissMenu,
                onPick = onPickValue
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                stringResource(R.string.reader_attr_cancel),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 历史联想弹层：锚定在编辑行（键 / 值框）下方，条目紧凑、限高 200dp 可滚动；
 * 行下方空间不足（放不下菜单或与键盘重叠）时改在行上方弹出，不遮挡编辑框。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttrHistoryMenu(
    expanded: Boolean,
    suggestions: List<String>,
    anchorBounds: Rect?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    val offset = remember(anchorBounds, suggestions.size, imeBottomPx) {
        val bounds = anchorBounds ?: return@remember DpOffset(0.dp, 4.dp)
        val gapPx = with(density) { 8.dp.toPx() }
        val menuPx = with(density) { minOf(200.dp, (suggestions.size * 36).dp).toPx() }
        val usableBottom = screenHeightPx - imeBottomPx
        if (usableBottom - bounds.bottom >= menuPx + gapPx) {
            DpOffset(0.dp, 4.dp)
        } else {
            with(density) { DpOffset(0.dp, (-(bounds.height + menuPx + gapPx)).toDp()) }
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        modifier = Modifier.heightIn(max = 200.dp),
        scrollState = rememberScrollState(),
        properties = PopupProperties(focusable = false)
    ) {
        suggestions.take(12).forEach { item ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(item) }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 属性显示行：键 + 值（布尔复选框 / 空值 / 单值 / 多值标签）+ 删除；点击行进入编辑。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttrRow(
    attr: FrontmatterAttribute,
    onClick: () -> Unit,
    onSetValues: (String, List<String>) -> Unit,
    onRemove: (String) -> Unit
) {
    val values = remember(attr.values) {
        attr.values.map { it.trim() }.filter { it.isNotEmpty() }
    }
    val bool = booleanAttrValue(values)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = attr.key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.4f).padding(end = 8.dp)
        )
        Box(Modifier.weight(0.6f)) {
            when {
                bool != null -> Checkbox(
                    checked = bool,
                    onCheckedChange = { checked ->
                        onSetValues(attr.key, listOf(if (checked) "true" else "false"))
                    },
                    modifier = Modifier.size(32.dp)
                )

                values.isEmpty() -> Text(
                    text = "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                values.size == 1 -> Text(
                    text = values.first(),
                    style = MaterialTheme.typography.bodyMedium
                )

                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    values.forEach { value ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                                )
                                IconButton(
                                    onClick = { onSetValues(attr.key, values - value) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        stringResource(R.string.reader_attr_remove_value),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        IconButton(onClick = { onRemove(attr.key) }, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                stringResource(R.string.reader_attr_remove),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
