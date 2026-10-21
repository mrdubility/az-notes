package com.az.notes.ui.reader

import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
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
 * 一次写盘），行尾 √ 确定输入并回到展示态；新属性追加在最下面。键值输入框各自
 * 弹出历史联想（统一从编辑行上方弹出，上方空间不足时菜单变矮可滚动，不遮挡
 * 编辑框）；弹窗高度固定（420–520dp），键盘弹出仅上移一次不反复调整尺寸。
 */
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
    var draftKey by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var draftValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var keyFocused by remember { mutableStateOf(false) }
    var valueFocused by remember { mutableStateOf(false) }
    var rowTouched by remember { mutableStateOf(false) }
    var activeInput by remember { mutableStateOf<AttrInput?>(null) }
    var focusTick by remember { mutableStateOf(0) }
    val keyFocus = remember { FocusRequester() }
    val valueFocus = remember { FocusRequester() }

    val editing = editTarget != null

    val keyHistory = remember(history, draftKey.text) {
        val keys = history?.keys?.toList().orEmpty()
        val filter = draftKey.text.trim()
        if (filter.isEmpty()) keys else keys.filter { it.contains(filter, ignoreCase = true) }
    }
    val valueHistory = remember(history, draftKey.text) {
        history?.entries
            ?.firstOrNull { it.key.equals(draftKey.text.trim(), ignoreCase = true) }
            ?.value
            .orEmpty()
    }

    fun clearEdit() {
        editTarget = null
        draftKey = TextFieldValue("")
        draftValue = TextFieldValue("")
        keyFocused = false
        valueFocused = false
        rowTouched = false
        activeInput = null
    }

    fun submitRow() {
        if (!editing) return
        val key = draftKey.text.trim()
        val target = editTarget
        val values = draftValue.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
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
        draftKey = TextFieldValue(key, TextRange(key.length))
        draftValue = TextFieldValue(value, TextRange(value.length))
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
        // 键盘弹出时禁用 window resize，仅由 imePadding 上移一次，浮窗尺寸不再反复变化
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window
                ?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(min = 420.dp, max = 520.dp)
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
                            .weight(1f)
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
                                            draftKey = TextFieldValue(it, TextRange(it.length))
                                            activeInput = AttrInput.VALUE
                                            runCatching { valueFocus.requestFocus() }
                                        },
                                        onPickValue = {
                                            val joined = if (draftValue.text.isBlank()) {
                                                it
                                            } else {
                                                draftValue.text.trimEnd('\n') + "\n" + it
                                            }
                                            draftValue = TextFieldValue(joined, TextRange(joined.length))
                                            activeInput = null
                                        },
                                        onDismissMenu = { activeInput = null },
                                        onSubmit = { submitRow() }
                                    )
                                } else {
                                    AttrRow(
                                        attr = attr,
                                        onClick = {
                                            startEdit(
                                                attr.key,
                                                attr.key,
                                                attr.values.joinToString("\n")
                                            )
                                        },
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
                                        draftKey = TextFieldValue(it, TextRange(it.length))
                                        activeInput = AttrInput.VALUE
                                        runCatching { valueFocus.requestFocus() }
                                    },
                                    onPickValue = {
                                        val joined = if (draftValue.text.isBlank()) {
                                            it
                                        } else {
                                            draftValue.text.trimEnd('\n') + "\n" + it
                                        }
                                        draftValue = TextFieldValue(joined, TextRange(joined.length))
                                        activeInput = null
                                    },
                                    onDismissMenu = { activeInput = null },
                                    onSubmit = { submitRow() }
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
}

/**
 * 键值编辑行：键 / 值左右排列，行尾 √ 确定输入并回到展示态。键值历史分别锚定
 * 各自输入框，统一从编辑行上方弹出。
 */
@Composable
private fun AttrEditRow(
    draftKey: TextFieldValue,
    onKeyChange: (TextFieldValue) -> Unit,
    draftValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
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
    onSubmit: () -> Unit
) {
    var keyBounds by remember { mutableStateOf<Rect?>(null) }
    var valueBounds by remember { mutableStateOf<Rect?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Box(
            Modifier
                .weight(0.4f)
                .onGloballyPositioned { keyBounds = it.boundsInWindow() }
        ) {
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
                anchorBounds = keyBounds,
                onDismiss = onDismissMenu,
                onPick = onPickKey
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(0.6f)
                .onGloballyPositioned { valueBounds = it.boundsInWindow() }
        ) {
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
                anchorBounds = valueBounds,
                onDismiss = onDismissMenu,
                onPick = onPickValue
            )
        }
        IconButton(
            onClick = onSubmit,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Filled.Check,
                stringResource(R.string.reader_attr_confirm),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 历史联想弹层：等编辑行与菜单尺寸测量稳定后按上下方空间自适应定位——下方空间
 * （到键盘上沿）放得下则弹在编辑行下方，否则弹在上方；菜单限高（≤ 200dp 且两个
 * 方向都放得下），内部滚动。宽度随内容自适应（120–240dp）。非聚焦弹层：不抢
 * 焦点、不收起输入法。
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
    if (!expanded) return
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val gapPx = with(density) { 8.dp.toPx() }
    val maxMenuPx = with(density) { 200.dp.toPx() }
    val minMenuPx = with(density) { 48.dp.toPx() }

    // 菜单高度上限 = min(200dp, max(下方可用, 上方可用))，保证任一方向都放得下
    val menuMaxPx = remember(anchorBounds, windowSize.height, imeBottomPx) {
        val bounds = anchorBounds ?: return@remember maxMenuPx
        val below = (windowSize.height - imeBottomPx) - bounds.bottom
        val above = bounds.top
        minOf(maxMenuPx, maxOf(minMenuPx, maxOf(below, above) - gapPx))
    }
    val provider = remember(anchorBounds, windowSize.height, imeBottomPx) {
        val boundsRef = anchorBounds
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val bounds = boundsRef ?: return IntOffset(0, 0)
                val gap = with(density) { 8.dp.toPx().toInt() }
                val menuH = popupContentSize.height
                val below = (windowSize.height - imeBottomPx.toInt()) - bounds.bottom.toInt()
                return if (below >= menuH + gap) {
                    IntOffset(bounds.left.toInt(), bounds.bottom.toInt() + gap)
                } else {
                    IntOffset(bounds.left.toInt(), (bounds.top.toInt() - menuH - gap).coerceAtLeast(0))
                }
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 120.dp, max = 240.dp)
                    .width(IntrinsicSize.Max)
                    .heightIn(max = with(density) { menuMaxPx.toDp() })
                    .verticalScroll(rememberScrollState())
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
