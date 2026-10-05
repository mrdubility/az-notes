package com.az.notes.ui.navigation

import android.net.Uri

/** 路由常量。单 Activity + Compose Navigation（§5.7）。 */
object Routes {
    const val GATE = "gate"                 // 授权 + Vault 选择门禁
    const val HOME = "home"                 // 主界面（Drawer 目录树 + 阅读器）
    const val SETTINGS = "settings"
    const val SYNC = "sync"

    private const val READER_BASE = "reader"
    private const val EDITOR_BASE = "editor"

    /** 阅读器：path 为编码后的绝对路径 */
    const val READER = "$READER_BASE/{path}"
    const val EDITOR = "$EDITOR_BASE/{path}"

    // Uri.encode 会把 '/' 编成 %2F（保持单一路径段），空格编成 %20；
    // NavType.StringType 读取时会自动 Uri.decode 还原为原始路径，故 VM 侧无需再解码。
    fun reader(path: String): String = "$READER_BASE/${Uri.encode(path)}"
    fun editor(path: String): String = "$EDITOR_BASE/${Uri.encode(path)}"
}
