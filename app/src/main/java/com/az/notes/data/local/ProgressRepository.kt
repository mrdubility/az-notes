package com.az.notes.data.local

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 阅读进度仓库（§5.5）。封装 [ReadProgressDao]，提供恢复与保存。
 * UI 层负责 500ms 去抖后再调用 [save]。
 */
@Singleton
class ProgressRepository @Inject constructor(
    private val dao: ReadProgressDao
) {
    fun observe(path: String): Flow<ReadProgressEntity?> = dao.observeProgress(path)

    suspend fun load(path: String): ReadProgressEntity? = dao.getProgress(path)

    suspend fun save(path: String, scrollIndex: Int, scrollOffset: Int, percent: Float) {
        dao.upsert(
            ReadProgressEntity(
                path = path,
                scrollIndex = scrollIndex,
                scrollOffset = scrollOffset,
                percent = percent.coerceIn(0f, 1f),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clear(path: String) = dao.delete(path)
}
