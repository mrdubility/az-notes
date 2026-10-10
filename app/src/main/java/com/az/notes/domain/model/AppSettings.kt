package com.az.notes.domain.model

/** 主题三态（§5.6）：跟随系统 / 浅色 / 深色 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 字体族（§5.6） */
enum class FontFamilyPreference { SANS, SERIF, MONO }

/** 笔记列表排序方式（主页右上角可切换） */
enum class NoteSortOrder { MODIFIED_DESC, MODIFIED_ASC, NAME_ASC, NAME_DESC }

/** 打开笔记的默认落点（设置 → 编辑器和查看器）：查看页（阅读器）/ 编辑页。 */
enum class DefaultNoteMode { VIEW, EDIT }

/** 应用语言：跟随系统 / 简体中文 / English；[tag] 为 Locale 标签（null = 跟随系统）。 */
enum class AppLanguage(val tag: String?) { SYSTEM(null), ZH("zh"), EN("en") }

/**
 * 应用偏好设置（DataStore 持久化，§5.6）。
 * 字号同时作用于编辑器正文字号；行高影响阅读与编辑。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontFamily: FontFamilyPreference = FontFamilyPreference.SANS,
    val fontSizeSp: Float = 16f,
    val lineHeightRatio: Float = 1.5f,
    /** 已添加的笔记仓库注册表（多仓库；旧版单仓库升级后为单元素列表） */
    val vaults: List<VaultInfo> = emptyList(),
    /** 当前使用的仓库 id（null = 尚未选择任何仓库） */
    val currentVaultId: String? = null,
    val dynamicColor: Boolean = true,
    /** 列表中正文预览截取的字符数 */
    val previewChars: Int = 100,
    /** 列表默认排序方式 */
    val sortOrder: NoteSortOrder = NoteSortOrder.MODIFIED_DESC,
    /** 新建笔记默认名模板（`$...$` 包裹片段按日期变量解析；默认当前时间戳） */
    val defaultNoteName: String = "\$yyyyMMdd-HHmmss\$",
    /** 接收分享笔记默认名模板（同上；默认「分享笔记 + 时间戳」） */
    val shareNoteName: String = "分享笔记 \$yyyyMMdd-HHmmss\$",
    /** AI 对话导出笔记默认名模板（同上；默认「AI对话 + 时间戳」） */
    val aiExportNoteName: String = "AI对话 \$yyyyMMdd-HHmmss\$",
    /** 回收站自动清理天数（0 = 永不清理；上限 90）；同步启动前执行 */
    val trashRetentionDays: Int = 30,
    /** 应用语言（切换后由设置页重建 Activity 生效） */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** 是否启用回收站；关闭后删除将直接物理删除且抽屉隐藏入口 */
    val trashEnabled: Boolean = true,
    /** 编辑器工具栏的工具顺序（存 [EditorTool.id] 列表） */
    val editorToolOrder: List<String> = EditorTool.defaultOrder,
    /** 编辑器工具栏中被禁用的工具 id 集合 */
    val editorToolDisabled: Set<String> = emptySet(),
    /** 打开笔记时的默认落点：查看页（阅读器）或编辑页 */
    val defaultNoteMode: DefaultNoteMode = DefaultNoteMode.VIEW,
    /** 收藏的笔记相对路径集合（相对当前仓库根；仅本地，不参与同步） */
    val favoritePaths: Set<String> = emptySet(),
    /** 最近查看的笔记相对路径（最近优先，上限 50；仅本地，不参与同步，按仓库隔离） */
    val recentPaths: List<String> = emptyList(),
    /** 分享进入的笔记默认保存文件夹（相对当前仓库根；null = 跟随当前目录） */
    val shareFolder: String? = null,
    /** 导入图片时是否压缩（固定参数：长边 1568 / JPEG 质量 80；关闭则原样复制）。 */
    val imageCompressEnabled: Boolean = true,
    /** 删除 / 移动笔记时是否提示附件引用情况（关闭则不查询、不联动）。 */
    val attachmentPromptEnabled: Boolean = true,
    /** 预览页单张网络图片的体积上限（MB）；0 = 不加载网络图片。 */
    val remoteImageMaxMb: Int = DEFAULT_REMOTE_IMAGE_MAX_MB,
    /** 网络图片读取超时（秒）：超时即中止加载。 */
    val remoteImageTimeoutSeconds: Int = DEFAULT_REMOTE_IMAGE_TIMEOUT_SECONDS,
    /** AI 对话隐私告知是否已确认（§11.3；首次发送弹一次性对话框后持久化，此后不再弹）。 */
    val aiPrivacyAcknowledged: Boolean = false
) {
    /**
     * 当前仓库的绝对路径（由 [vaults] 与 [currentVaultId] 派生）。
     * 保留原属性名，使既有消费方（笔记列表 / 阅读器 / 同步引擎）无需感知多仓库细节。
     */
    val vaultPath: String?
        get() = vaults.firstOrNull { it.id == currentVaultId }?.path

    /** 网络图片体积上限（字节）；0 = 不加载。 */
    val remoteImageMaxBytes: Long
        get() = if (remoteImageMaxMb <= 0) 0L else remoteImageMaxMb.toLong() * 1024 * 1024

    companion object {
        const val DEFAULT_REMOTE_IMAGE_MAX_MB = 10
        const val DEFAULT_REMOTE_IMAGE_TIMEOUT_SECONDS = 5

        /** 网络图片体积上限输入最大值（MB）：50 已覆盖常见网络图片，避免误设天数字。 */
        const val REMOTE_IMAGE_MAX_MB_LIMIT = 50

        /** 超时允许区间（秒）。 */
        val REMOTE_IMAGE_TIMEOUT_RANGE = 1..30
    }
}
