package com.az.notes.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用展示格式化（UI 线程 / 组合期使用）。
 * [SimpleDateFormat] 非线程安全，因此仅在主线程调用；实例缓存复用，
 * 避免列表滚动时逐条新建（与 HomeNoteList 既有缓存先例一致）。
 */

/** 日期时间（分钟精度）：yyyy-MM-dd HH:mm（回收站 / 收藏列表）。 */
private val DATE_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/** 日期时间（秒精度）：yyyy-MM-dd HH:mm:ss（主页列表 / 文档信息）。 */
private val DATE_TIME_SECONDS_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

/** 日期时间：yyyy-MM-dd HH:mm（分钟精度）。 */
internal fun formatDateTime(millis: Long): String = DATE_TIME_FORMAT.format(Date(millis))

/** 日期时间：yyyy-MM-dd HH:mm:ss（秒精度）。 */
internal fun formatDateTimeSeconds(millis: Long): String = DATE_TIME_SECONDS_FORMAT.format(Date(millis))

/** 文件大小：B / KB / MB（各档保留一位小数）。 */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
