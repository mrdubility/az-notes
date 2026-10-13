package com.az.notes.data.storage

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 重命名 / 移动路径广播（编辑页 → 预览页）。
 *
 * 编辑页改名或移动成功后把「旧绝对路径 → 新绝对路径」登记在案；预览页从编辑页
 * 返回重新进入组合时按当前路径查表跟进（见 ReaderViewModel.applyPathUpdate）。
 * 相比经由 NavBackStackEntry.savedStateHandle 跨 entry 回传，这条通道完全在
 * 进程内自有代码闭环，不依赖导航库的状态共享语义，行为确定。
 * 容量上限防御：长期未被消费的映射（如改名后用户直接杀进程）最多堆积数条。
 */
@Singleton
class PathUpdateBus @Inject constructor() {

    private val updates = LinkedHashMap<String, String>()

    /** 登记一次路径变更（旧 → 新）；同一旧路径重复登记以最后一次为准。 */
    @Synchronized
    fun post(oldPath: String, newPath: String) {
        updates[oldPath] = newPath
        while (updates.size > MAX_ENTRIES) {
            val oldest = updates.keys.firstOrNull() ?: break
            updates.remove(oldest)
        }
    }

    /** 取走并清除 [oldPath] 对应的新路径；无登记时返回 null。 */
    @Synchronized
    fun consume(oldPath: String): String? = updates.remove(oldPath)

    private companion object {
        const val MAX_ENTRIES = 8
    }
}
