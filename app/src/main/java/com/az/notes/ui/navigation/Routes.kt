package com.az.notes.ui.navigation

import android.net.Uri

/** 路由常量。单 Activity + Compose Navigation（§5.7）。 */
object Routes {
    const val GATE = "gate"                 // 授权 + Vault 选择门禁
    const val HOME = "home"                 // 主界面（抽屉菜单 + 笔记列表）
    const val SETTINGS = "settings"

    /** 编辑器工具栏设置页（工具开关 / 顺序 / 恢复默认）；从设置页进入。 */
    const val TOOLBAR_SETTINGS = "toolbar_settings"

    /** 同步设置页（服务器 / 账号 / 策略 / 手动同步 / 日志）；从设置 → 同步进入。 */
    const val SYNC = "sync"

    /** 回收站：同步时被远端删除波及的本地文件（保留 30 天）；从抽屉进入。 */
    const val TRASH = "trash"

    private const val READER_BASE = "reader"
    private const val EDITOR_BASE = "editor"

    /** 阅读器：path 为编码后的绝对路径 */
    const val READER = "$READER_BASE/{path}"

    /** 编辑器：fresh=true 表示刚从“新建笔记”进入（退出时若仍无内容则清理空文件）；
     *  share=true 表示从系统分享进入（独立页面：返回即退出应用，顶栏提供回列表）。 */
    const val EDITOR = "$EDITOR_BASE/{path}?fresh={fresh}&share={share}"

    // Uri.encode 会把 '/' 编成 %2F（保持单一路径段），空格编成 %20；
    // NavType.StringType 读取时会自动 Uri.decode 还原为原始路径，故 VM 侧无需再解码。
    fun reader(path: String): String = "$READER_BASE/${Uri.encode(path)}"
    fun editor(path: String, fresh: Boolean = false, fromShare: Boolean = false): String =
        "$EDITOR_BASE/${Uri.encode(path)}?fresh=$fresh&share=$fromShare"
}
