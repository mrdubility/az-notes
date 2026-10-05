package com.az.notes.domain.model

/** 主题三态（§5.6）：跟随系统 / 浅色 / 深色 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 字体族（§5.6） */
enum class FontFamilyPreference { SANS, SERIF, MONO }

/**
 * 应用偏好设置（DataStore 持久化，§5.6）。
 * 字号同时作用于编辑器正文字号；行高影响阅读与编辑。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontFamily: FontFamilyPreference = FontFamilyPreference.SANS,
    val fontSizeSp: Float = 16f,
    val lineHeightRatio: Float = 1.5f,
    val vaultPath: String? = null,
    val dynamicColor: Boolean = true
)
