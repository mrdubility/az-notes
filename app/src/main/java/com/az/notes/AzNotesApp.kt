package com.az.notes

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。[@HiltAndroidApp] 触发 Hilt 代码生成并作为依赖图根。
 * WorkManager 周期同步任务（§6.4）在 M3 接入时于此处初始化。
 */
@HiltAndroidApp
class AzNotesApp : Application()
