package com.az.notes.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * “所有文件访问权限”辅助（§2.1）。
 * Android 11(R)/12 边界：API 30+ 用 [Environment.isExternalStorageManager] 判断，
 * 低版本走 legacy 存储，视为已可用。
 */
object StoragePermission {

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /** 构造跳转到系统授权页的 Intent（找不到时回退到通用页）。 */
    fun buildIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val scoped = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            if (scoped.resolveActivity(context.packageManager) != null) return scoped
        }
        // 回退：直接打开“所有文件访问权限”总列表页
        return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    /** 外部存储根路径（/storage/emulated/0），供 Vault 路径默认值使用。 */
    fun externalStorageRoot(): String =
        Environment.getExternalStorageDirectory().absolutePath
}
