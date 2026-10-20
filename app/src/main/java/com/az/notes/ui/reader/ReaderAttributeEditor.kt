package com.az.notes.ui.reader

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * 属性编辑浮窗：列出当前 frontmatter 属性（布尔值渲染为可切换复选框、多值为可删标签），
 * 每行可删除整条属性；底部输入键 / 值新增或覆盖属性。
 * 键输入弹出全库属性键历史，值输入只弹出当前键历史输入过的值（键值联动）；
 * 值换行分隔即写成多值。
 */
@Composable
internal fun AttributeEditorDialog(
    attributes: List<FrontmatterAttribute>,
    history: Map<String, List<String>>?,
    onSetValues: (String, List<String>) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyInput by rememberSaveable { mutableStateOf("") }
    var valueInput by rememberSaveable { mutableStateOf("") }
    var keyMenu by remember { mutableStateOf(false) }
    var valueMenu by remember { mutableStateOf(false) }

    val keyHistory = remember(history, keyInput) {
        val keys = history?.keys?.toList().orEmpty()
        if (keyInput.isBlank()) keys else keys.filter { it.contains(keyInput.trim(), ignoreCase = true) }
    }
    val valueHistory = remember(history, keyInput) {
        history?.entries
            ?.firstOrNull { it.key.equals(keyInput.trim(), ignoreCase = true) }
            ?.value
            .orEmpty()
    }

    fun add() {
        val key = keyInput.trim()
        if (key.isEmpty()) return
        val values = valueInput.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        onSetValues(key, values)
        keyInput = ""
        valueInput = ""
        keyMenu = false
        valueMenu = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_attr_edit_title)) },
        text = {
            Column {
                if (attributes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.reader_attr_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        attributes.forEach { attr ->
                            AttributeEditRow(
                                attr = attr,
                                onSetValues = onSetValues,
                                onRemove = onRemove
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Box {
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = {
                            keyInput = it
                            keyMenu = true
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.reader_attr_key)) },
                        trailingIcon = {
                            IconButton(onClick = { keyMenu = !keyMenu }) {
                                Icon(Icons.Filled.ArrowDropDown, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = keyMenu && keyHistory.isNotEmpty(),
                        onDismissRequest = { keyMenu = false }
                    ) {
                        keyHistory.take(8).forEach { key ->
                            DropdownMenuItem(
                                text = { Text(key, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    keyInput = key
                                    keyMenu = false
                                    valueMenu = true
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box {
                    OutlinedTextField(
                        value = valueInput,
                        onValueChange = {
                            valueInput = it
                            valueMenu = true
                        },
                        minLines = 1,
                        maxLines = 4,
                        label = { Text(stringResource(R.string.reader_attr_value)) },
                        supportingText = { Text(stringResource(R.string.reader_attr_value_hint)) },
                        trailingIcon = {
                            IconButton(onClick = { valueMenu = !valueMenu }) {
                                Icon(Icons.Filled.ArrowDropDown, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(
                        expanded = valueMenu && valueHistory.isNotEmpty(),
                        onDismissRequest = { valueMenu = false }
                    ) {
                        valueHistory.take(8).forEach { value ->
                            DropdownMenuItem(
                                text = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    valueInput = if (valueInput.isBlank()) {
                                        value
                                    } else {
                                        valueInput.trimEnd('\n') + "\n" + value
                                    }
                                    valueMenu = false
                                }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(enabled = keyInput.isNotBlank(), onClick = { add() }) {
                        Text(stringResource(R.string.reader_attr_add))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) }
        }
    )
}

/** 单条属性行：键 + 值（布尔复选框 / 单值 / 多值标签）+ 删除整条属性。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttributeEditRow(
    attr: FrontmatterAttribute,
    onSetValues: (String, List<String>) -> Unit,
    onRemove: (String) -> Unit
) {
    val values = remember(attr.values) {
        attr.values.map { it.trim() }.filter { it.isNotEmpty() }
    }
    val bool = booleanAttrValue(values)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = attr.key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(84.dp)
        )
        Box(Modifier.weight(1f)) {
            when {
                bool != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = bool,
                        onCheckedChange = { checked ->
                            onSetValues(attr.key, listOf(if (checked) "true" else "false"))
                        }
                    )
                    Text(
                        text = if (bool) "true" else "false",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                values.isEmpty() -> Text(
                    text = "—",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                values.size == 1 -> Text(
                    text = values.first(),
                    style = MaterialTheme.typography.labelMedium
                )

                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
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
