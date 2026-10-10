package com.az.notes.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局同步运行状态：任意入口（手动同步页 / 自动同步 Worker）的同步会话置位——
 * 由 SyncEngine.runExclusive 在会话开始（含扫描阶段）置位、结束（无论成败）复位，
 * 供 UI 层展示“同步中”指示（列表页顶栏图标变化示意）与“同步完成后刷新”信号。
 * 手动与自动同步共享同一实例（Hilt @Singleton）。
 */
@Singleton
class SyncRunNotifier @Inject constructor() {

    private val _running = MutableStateFlow(false)

    /** 是否有同步正在执行（手动或自动）。 */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastCompletedAt = MutableStateFlow(0L)

    /** 最近一次同步完成的时间戳（毫秒，0 表示本进程内尚未完成过同步）。 */
    val lastCompletedAt: StateFlow<Long> = _lastCompletedAt.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    /** 同步执行结束（无论成功失败）时调用。 */
    fun markCompleted() {
        _running.value = false
        _lastCompletedAt.value = System.currentTimeMillis()
    }
}
