package com.az.notes.domain.model

/** 主题三态（§5.6）：跟随系统 / 浅色 / 深色 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 字体族（§5.6） */
enum class FontFamilyPreference { SANS, SERIF, MONO }

/** 笔记列表排序方式（主页右上角可切换） */
enum class NoteSortOrder { MODIFIED_DESC, MODIFIED_ASC, NAME_ASC, NAME_DESC }

/** 右下角加号：点击时执行的默认行为（长按始终弹出全部选项） */
enum class FabAction { NEW_NOTE, NEW_FOLDER, SHOW_MENU }

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
    val dynamicColor: Boolean = true,
    /** 列表中正文预览截取的字符数 */
    val previewChars: Int = 100,
    /** 列表默认排序方式 */
    val sortOrder: NoteSortOrder = NoteSortOrder.MODIFIED_DESC,
    /** 新建笔记时使用的默认文件名（不含扩展名，重名自动追加序号） */
    val defaultNoteName: String = "新建笔记",
    /** 回收站自动清理天数（0 = 永不清理）；同步启动前执行 */
    val trashRetentionDays: Int = 30,
    /** 右下角加号点击的默认行为（长按始终弹出全部选项） */
    val fabAction: FabAction = FabAction.NEW_NOTE
)
