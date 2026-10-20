package com.az.notes.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
 * 整条属性），点击任意属性行把键值带入下方编辑行修改（键改名 = 删旧键 + 建新键）。
 * 右下角「点击添加属性」展开 / 聚焦编辑行；键值左右排列，值换行分隔即多值。
 *
 * 键输入弹出全库属性键历史，值输入只弹出当前键历史输入过的值（键值联动）；
 * 历史分别锚定在键 / 值输入框下方，以非聚焦弹层展示（[PopupProperties.focusable]
 * = false），不遮挡输入框、不抢输入焦点、不收起输入法。
 * 浮窗整体限高并随输入法上移（[DialogProperties.decorFitsSystemWindows] = false +
 * imePadding），属性很多时列表内部滚动，编辑行始终在键盘上方。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttributeEditorDialog(
    attributes: List<FrontmatterAttribute>,
    history: Map<String, List<String>>?,
    onSetValues: (String, List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var valueDraft by rememberSaveable { mutableStateOf("") }
    var activeField by remember { mutableStateOf<AttrField?>(null) }
    var focusTick by remember { mutableStateOf(0) }
    val keyFocus = remember { FocusRequester() }
    val valueFocus = remember { FocusRequester() }

    val keyHistory = remember(history, keyDraft) {
        val keys = history?.keys?.toList().orEmpty()
        if (keyDraft.isBlank()) keys else keys.filter { it.contains(keyDraft.trim(), ignoreCase = true) }
    }
    val valueHistory = remember(history, keyDraft) {
        history?.entries
            ?.firstOrNull { it.key.equals(keyDraft.trim(), ignoreCase = true) }
            ?.value
            .orEmpty()
    }

    fun clearDraft() {
        keyDraft = ""
        valueDraft = ""
    }

    fun submit() {
        val key = keyDraft.trim()
        if (key.isEmpty()) return
        val values = valueDraft.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val target = editingKey
        if (target != null && target != key) onRemove(target)
        onSetValues(key, values)
        editingKey = null
        clearDraft()
        focusTick++
    }

    fun startEdit(attr: FrontmatterAttribute) {
        editingKey = attr.key
        keyDraft = attr.key
        valueDraft = attr.values.joinToString("\n")
        adding = true
        activeField = AttrField.KEY
        focusTick++
    }

    // 输入行组合完成后再请求焦点，重复提交 / 新增也能持续弹出键历史
    LaunchedEffect(adding, focusTick) {
        if (!adding) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { keyFocus.requestFocus() }
        activeField = AttrField.KEY
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                    if (attributes.isEmpty()) {
                        Text(
                            text = stringResource(R.string.reader_attr_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        attributes.forEach { attr ->
                            AttributeEditRow(
                                attr = attr,
                                onClick = { startEdit(attr) },
                                onSetValues = onSetValues,
                                onRemove = onRemove
                            )
                        }
                    }
                }
                if (adding) {
                    HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 8.dp, end = 8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Box(Modifier.weight(0.42f)) {
                            OutlinedTextField(
                                value = keyDraft,
                                onValueChange = {
                                    keyDraft = it
                                    activeField = AttrField.KEY
                                },
                                singleLine = true,
                                label = { Text(stringResource(R.string.reader_attr_key)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(keyFocus)
                                    .onFocusChanged { if (it.isFocused) activeField = AttrField.KEY }
                            )
                            DropdownMenu(
                                expanded = activeField == AttrField.KEY && keyHistory.isNotEmpty(),
                                onDismissRequest = { activeField = null },
                                properties = PopupProperties(focusable = false)
                            ) {
                                keyHistory.take(8).forEach { item ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        },
                                        onClick = {
                                            keyDraft = item
                                            activeField = AttrField.VALUE
                                            runCatching { valueFocus.requestFocus() }
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(0.58f)) {
                            OutlinedTextField(
                                value = valueDraft,
                                onValueChange = {
                                    valueDraft = it
                                    activeField = AttrField.VALUE
                                },
                                minLines = 1,
                                maxLines = 4,
                                label = { Text(stringResource(R.string.reader_attr_value)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(valueFocus)
                                    .onFocusChanged { if (it.isFocused) activeField = AttrField.VALUE }
                            )
                            // 值历史锚定在值输入框下方；非聚焦弹层不抢焦点、不收起输入法
                            DropdownMenu(
                                expanded = activeField == AttrField.VALUE && valueHistory.isNotEmpty(),
                                onDismissRequest = { activeField = null },
                                properties = PopupProperties(focusable = false)
                            ) {
                                valueHistory.take(8).forEach { item ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        },
                                        onClick = {
                                            valueDraft = if (valueDraft.isBlank()) {
                                                item
                                            } else {
                                                valueDraft.trimEnd('\n') + "\n" + item
                                            }
                                            activeField = null
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { submit() },
                            enabled = keyDraft.isNotBlank()
                        ) {
                            Icon(Icons.Filled.Check, stringResource(R.string.reader_attr_add))
                        }
                        IconButton(onClick = {
                            adding = false
                            editingKey = null
                            clearDraft()
                            activeField = null
                        }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.reader_attr_cancel))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        adding = true
                        editingKey = null
                        clearDraft()
                        focusTick++
                    }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.reader_attr_add_action))
                    }
                }
            }
        }
    }
}

/** 单条属性行：键 + 值（布尔复选框 / 单值 / 多值标签）+ 删除整条属性；点击行进入编辑。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttributeEditRow(
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
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                stringResource(R.string.reader_attr_remove),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
