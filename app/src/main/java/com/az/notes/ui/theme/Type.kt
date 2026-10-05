package com.az.notes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.az.notes.domain.model.FontFamilyPreference

// Material 3 基础排版（UI 控件用）。正文阅读/编辑的字体族与字号
// 由 LocalReadingStyle 动态提供，见 [ReadingStyle]。

private val BaseTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp
    )
)

fun azNotesTypography(): Typography = BaseTypography

/** 把字体族偏好映射到 Compose FontFamily（系统内置，无需随包字体文件）。 */
fun FontFamilyPreference.toComposeFontFamily(): FontFamily = when (this) {
    FontFamilyPreference.SANS -> FontFamily.SansSerif
    FontFamilyPreference.SERIF -> FontFamily.Serif
    FontFamilyPreference.MONO -> FontFamily.Monospace
}

/**
 * 阅读 / 编辑文本样式（§5.6）。字号 12–24sp、行高 1.2–2.0 可调，
 * 正文字号同时影响编辑器。
 */
data class ReadingStyle(
    val fontFamily: FontFamily = FontFamily.SansSerif,
    val fontSizeSp: Float = 16f,
    val lineHeightRatio: Float = 1.5f
) {
    val textStyle: TextStyle
        get() = TextStyle(
            fontFamily = fontFamily,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * lineHeightRatio).sp,
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.None
            ),
            letterSpacing = 0.01.em
        )
}
