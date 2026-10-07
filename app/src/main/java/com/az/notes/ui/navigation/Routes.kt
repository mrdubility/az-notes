package com.az.notes.ui.navigation

import android.net.Uri

/** 路由常量。单 Activity + Compose Navigation（§5.7）。 */
object Routes {
    const val HOME = "home"                 // 主界面（抽屉菜单 + 笔记列表）
    const val SETTINGS = "settings"

    /** 编辑器工具栏设置页（工具开关 / 顺序 / 恢复默认）；从设置页进入。 */
    const val TOOLBAR_SETTINGS = "toolbar_settings"

    /** 同步设置页（服务器 / 账号 / 策略 / 手动同步）；从设置 → 同步进入。 */
    const val SYNC = "sync"

    /** 冲突记录页；从同步页进入。 */
    const val SYNC_CONFLICTS = "sync_conflicts"

    /** 同步日志页；从同步页进入。 */
    const val SYNC_LOGS = "sync_logs"

    /** 调试日志设置页；从设置 → 关于（版本条目）进入。 */
    const val DEBUG_LOGS = "debug_logs"

    /** 备份与恢复页：导出 / 导入全部配置；从设置 → 备份进入。 */
    const val BACKUP = "backup"

    /** 回收站：同步时被远端删除波及的本地文件（保留天数可配置）；从抽屉进入。 */
    const val TRASH = "trash"

    /** 孤儿图片页：未被任何笔记引用的图片；从设置 → 图片进入。 */
    const val ORPHAN_IMAGES = "orphan_images"

    /** 收藏夹：跨文件夹展示收藏的笔记（仅本地）；从抽屉进入。 */
    const val FAVORITES = "favorites"

    /** 仓库管理页：切换 / 添加 / 重命名 / 移除仓库；从设置页与顶栏仓库菜单进入。 */
    const val VAULTS = "vaults"

    const val READER_BASE = "reader"
    const val EDITOR_BASE = "editor"

    /** 阅读器：path 为编码后的绝对路径；fresh=true 表示刚从“新建待办”进入
     *  （退出时若正文仍为空则清理占位文件，与空笔记 fresh 清理一致）。 */
    const val READER = "$READER_BASE/{path}?fresh={fresh}"

    /** 编辑器：fresh=true 表示刚从“新建笔记”进入（退出时若仍无内容则清理空文件）；
     *  share=true 表示从系统分享进入（独立页面：返回即退出应用，顶栏提供回列表）。 */
    const val EDITOR = "$EDITOR_BASE/{path}?fresh={fresh}&share={share}"

    // Uri.encode 会把 '/' 编成 %2F（保持单一路径段），空格编成 %20；
    // NavType.StringType 读取时会自动 Uri.decode 还原为原始路径，故 VM 侧无需再解码。
    fun reader(path: String, fresh: Boolean = false): String =
        "$READER_BASE/${Uri.encode(path)}?fresh=$fresh"
    fun editor(path: String, fresh: Boolean = false, fromShare: Boolean = false): String =
        "$EDITOR_BASE/${Uri.encode(path)}?fresh=$fresh&share=$fromShare"
}
