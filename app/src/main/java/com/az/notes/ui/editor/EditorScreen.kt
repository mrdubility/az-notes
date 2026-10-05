package com.az.notes.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.ui.theme.LocalReadingStyle

/**
 * 编辑页（§5.3）。源码 `BasicTextField` + 工具栏插入常用标记；
 * 停止输入 1.5s 自动保存；返回时保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val readingStyle = LocalReadingStyle.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.path.substringAfterLast('/'),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.save(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).fillMaxWidth(0.04f),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (state.dirty) "未保存" else "已保存",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state.dirty) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Toolbar(viewModel)
            HorizontalDivider()
            if (state.loading) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            } else {
                BasicTextField(
                    value = state.text,
                    onValueChange = viewModel::onTextChange,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    textStyle = readingStyle.textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun Toolbar(viewModel: EditorViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(Icons.Filled.FormatBold, "粗体") { viewModel.applyDecoration("**") }
        ToolButton(Icons.Filled.Title, "斜体标记占位") { viewModel.applyDecoration("*") }
        ToolButton(Icons.Filled.FormatAlignLeft, "一级标题") { viewModel.insertLinePrefix("# ") }
        ToolButton(Icons.Filled.FormatListBulleted, "无序列表") { viewModel.insertLinePrefix("- ") }
        ToolButton(Icons.Filled.FormatQuote, "引用") { viewModel.insertLinePrefix("> ") }
        ToolButton(Icons.Filled.Code, "代码块") { viewModel.insertLinePrefix("```\n") }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
