package com.az.notes.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography

/**
 * 应用统一的 Markdown 标题排版（对话正文与阅读器共用）。
 *
 * mikepenz M3 默认把 h1-h3 映射到 display 级（h1=displayLarge≈57sp、h2=displayMedium、
 * h3=displaySmall），在手机窄幅上观感突兀；此处仅覆盖 h1-h6 为紧凑层级（正文 16sp 基准：
 * h1 22sp / h2 20sp / h3 18sp 加粗，h4 16sp / h5 15sp / h6 14sp 半粗），
 * 其余样式（正文 / 引用 / 代码 / 链接 / 表格等）沿用库默认映射。
 */
@Composable
fun azNotesMarkdownTypography(): MarkdownTypography = markdownTypography(
    h1 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
    h2 = MaterialTheme.typography.titleLarge.copy(
        fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold
    ),
    h3 = MaterialTheme.typography.titleMedium.copy(
        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold
    ),
    h4 = MaterialTheme.typography.titleSmall.copy(
        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold
    ),
    h5 = MaterialTheme.typography.titleSmall.copy(
        fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold
    ),
    h6 = MaterialTheme.typography.titleSmall.copy(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold
    )
)
