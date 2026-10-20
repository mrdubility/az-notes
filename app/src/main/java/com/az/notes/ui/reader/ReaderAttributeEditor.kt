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

/**
 * 属性值 → 布尔：仅单个 true / false（忽略大小写，兼容引号包裹）时返回，否则 null。
 */
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

/** 编辑行当前聚焦的输入（历史联想据此切换键 / 值列表）。 */
private enum class AttrField { KEY, VALUE }

/**
 * 属性编辑浮窗：列出当前 frontmatter 属性（无图标，键列 40% / 值列 60%，行尾 x 删除
 * 整条属性）。点击属性行**原地**变成键值编辑行，点击「点击添加属性」在列表末尾追加
 * 一行空编辑行；编辑行失去焦点即保存（键为空则丢弃），键改名 = 删旧键 + 建新键，
 * 新属性追加在最下面。
 *
 * 键输入弹出全库属性键历史，值输入只弹出当前键历史输入过的值（键值联动）；历史
 * 分别锚定在键 / 值输入框下方，条目紧凑、限高可滚动；下方放不下时改在编辑框上方
 * 弹出，不遮挡编辑框、不抢焦点、不收起输入法（非聚焦弹层）。
 * 浮窗整体限高并随输入法上移（decorFitsSystemWindows = false + imePadding），
 * 属性很多时列表内部滚动，编辑行始终在键盘上方。
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
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var newRow by rememberSaveable { mutableStateOf(false) }
    var draftKey by rememberSaveable { mutableStateOf("") }
    var draftValue by rememberSaveable { mutableStateOf("") }
    var rowTouched by remember { mutableStateOf(false) }
    var keyFocused by remember { mutableStateOf(false) }
    var valueFocused by remember { mutableStateOf(false) }
    var activeField by remember { mutableStateOf<AttrField?>(null) }
    var focusTick by remember { mutableStateOf(0) }
    val keyFocus = remember { FocusRequester() }
    val valueFocus = remember { FocusRequester() }

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

    fun resetRow() {
        editingKey = null
        newRow = false
        draftKey = ""
        draftValue = ""
        rowTouched = false
        keyFocused = false
        valueFocused = false
        activeField = null
    }

    fun commitRow() {
        if (editingKey == null && !newRow) return
        val key = draftKey.trim()
        val target = editingKey
        resetRow()
        if (key.isEmpty()) return
        val values = draftValue.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (target != null && target != key) {
            onReplace(target, key, values)
        } else {
            onSetValues(key, values)
        }
    }

    fun beginEdit(attr: FrontmatterAttribute) {
        commitRow()
        editingKey = attr.key
        newRow = false
        draftKey = attr.key
        draftValue = attr.values.joinToString("\n")
        focusTick++
    }

    fun beginNew() {
        commitRow()
        editingKey = null
        newRow = true
        draftKey = ""
        draftValue = ""
        focusTick++
    }

    // 编辑行失去焦点即保存（曾获得过焦点且键值两框都不再聚焦时提交）
    LaunchedEffect(keyFocused, valueFocused) {
        if (rowTouched && !keyFocused && !valueFocused) commitRow()
    }

    // 新行 / 编辑行组合完成后请求焦点（两帧后布局稳定）
    LaunchedEffect(focusTick) {
        if (focusTick == 0 || (editingKey == null && !newRow)) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { keyFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = {
            commitRow()
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
                    if (attributes.isEmpty() && !newRow) {
                        Text(
                            text = stringResource(R.string.reader_attr_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        attributes.forEach { attr ->
                            if (attr.key == editingKey) {
                                AttributeEditRow(
                                    draftKey = draftKey,
                                    onKeyChange = {
                                        draftKey = it
                                        activeField = AttrField.KEY
                                    },
                                    draftValue = draftValue,
                                    onValueChange = {
                                        draftValue = it
                                        activeField = AttrField.VALUE
                                    },
                                    onKeyFocus = { focused ->
                                        keyFocused = focused
                                        if (focused) {
                                            rowTouched = true
                                            activeField = AttrField.KEY
                                        }
                                    },
                                    onValueFocus = { focused ->
                                        valueFocused = focused
                                        if (focused) {
                                            rowTouched = true
                                            activeField = AttrField.VALUE
                                        }
                                    },
                                    keyFocusRequester = keyFocus,
                                    valueFocusRequester = valueFocus,
                                    keyHistory = keyHistory,
                                    valueHistory = valueHistory,
                                    activeField = activeField,
                                    onPickKey = {
                                        draftKey = it
                                        activeField = AttrField.VALUE
                                        runCatching { valueFocus.requestFocus() }
                                    },
                                    onPickValue = {
                                        draftValue = if (draftValue.isBlank()) {
                                            it
                                        } else {
                                            draftValue.trimEnd('\n') + "\n" + it
                                        }
                                        activeField = null
                                    },
                                    onDismissMenu = { activeField = null },
                                    onCancel = { resetRow() }
                                )
                            } else {
                                AttributeDisplayRow(
                                    attr = attr,
                                    onClick = { beginEdit(attr) },
                                    onSetValues = onSetValues,
                                    onRemove = onRemove
                                )
                            }
                        }
                    }
                    if (newRow) {
                        HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 4.dp, end = 8.dp))
                        AttributeEditRow(
                            draftKey = draftKey,
                            onKeyChange = {
                                draftKey = it
                                activeField = AttrField.KEY
                            },
                            draftValue = draftValue,
                            onValueChange = {
                                draftValue = it
                                activeField = AttrField.VALUE
                            },
                            onKeyFocus = { focused ->
                                keyFocused = focused
                                if (focused) {
                                    rowTouched = true
                                    activeField = AttrField.KEY
                                }
                            },
                            onValueFocus = { focused ->
                                valueFocused = focused
                                if (focused) {
                                    rowTouched = true
                                    activeField = AttrField.VALUE
                                }
                            },
                            keyFocusRequester = keyFocus,
                            valueFocusRequester = valueFocus,
                            keyHistory = keyHistory,
                            valueHistory = valueHistory,
                            activeField = activeField,
                            onPickKey = {
                                draftKey = it
                                activeField = AttrField.VALUE
                                runCatching { valueFocus.requestFocus() }
                            },
                            onPickValue = {
                                draftValue = if (draftValue.isBlank()) {
                                    it
                                } else {
                                    draftValue.trimEnd('\n') + "\n" + it
                                }
                                activeField = null
                            },
                            onDismissMenu = { activeField = null },
                            onCancel = { resetRow() }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { beginNew() }) {
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
 * 编辑行（原地编辑 / 新增共用的键值输入行）：键值左右排列，行尾 x 取消（不保存）。
 * 键 / 值历史分别锚定各自输入框下方，条目紧凑、限高 200dp 可滚动；
 * 下方空间不足（编辑行底到键盘顶装不下菜单）时改在编辑行上方弹出。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttributeEditRow(
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
    activeField: AttrField?,
    onPickKey: (String) -> Unit,
    onPickValue: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onCancel: () -> Unit
) {
    var rowBounds by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    fun menuOffset(suggestions: List<String>): DpOffset {
        val bounds = rowBounds ?: return DpOffset(0.dp, 4.dp)
        val gapPx = with(density) { 8.dp.toPx() }
        // 条目高近似 36dp：菜单高度按条目数估算并封顶
        val menuPx = with(density) { minOf(200.dp, (suggestions.size * 36).dp).toPx() }
        val usableBottom = screenHeightPx - imeBottomPx
        return if (usableBottom - bounds.bottom >= menuPx + gapPx) {
            DpOffset(0.dp, 4.dp)
        } else {
            with(density) { DpOffset(0.dp, (-(bounds.height + menuPx + gapPx)).toDp()) }
        }
    }

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
            DropdownMenu(
                expanded = activeField == AttrField.KEY && keyHistory.isNotEmpty(),
                onDismissRequest = onDismissMenu,
                offset = menuOffset(keyHistory),
                modifier = Modifier.heightIn(max = 200.dp),
                scrollState = rememberScrollState(),
                properties = PopupProperties(focusable = false)
            ) {
                keyHistory.take(12).forEach { item ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPickKey(item) }
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
            DropdownMenu(
                expanded = activeField == AttrField.VALUE && valueHistory.isNotEmpty(),
                onDismissRequest = onDismissMenu,
                offset = menuOffset(valueHistory),
                modifier = Modifier.heightIn(max = 200.dp),
                scrollState = rememberScrollState(),
                properties = PopupProperties(focusable = false)
            ) {
                valueHistory.take(12).forEach { item ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPickValue(item) }
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

/** 属性显示行：键 + 值（布尔复选框 / 单值 / 多值标签）+ 删除整条属性；点击行进入原地编辑。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttributeDisplayRow(
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
