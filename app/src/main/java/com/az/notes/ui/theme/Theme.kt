package com.az.notes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.az.notes.domain.model.AppSettings
import com.az.notes.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = GreenDark,
    tertiary = AmberAccent,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    outline = OutlineLight
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = GreenDark,
    secondary = GreenLight,
    tertiary = AmberAccent,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    outline = OutlineDark
)

/** 阅读/编辑文本样式的全局注入点（§5.6）。 */
val LocalReadingStyle = staticCompositionLocalOf { ReadingStyle() }

@Composable
fun AzNotesTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit
) {
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // 状态栏图标深浅由 MainActivity 统一处理（配合 edge-to-edge）；
    // 此处仅根据 darkTheme 决定配色方案，不在组合期改动窗口属性。

    val readingStyle = ReadingStyle(
        fontFamily = settings.fontFamily.toComposeFontFamily(),
        fontSizeSp = settings.fontSizeSp,
        lineHeightRatio = settings.lineHeightRatio
    )

    CompositionLocalProvider(LocalReadingStyle provides readingStyle) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = azNotesTypography(),
            shapes = AzNotesShapes,
            content = content
        )
    }
}
