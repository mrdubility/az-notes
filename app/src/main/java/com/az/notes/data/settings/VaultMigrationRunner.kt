package com.az.notes.data.settings

import com.az.notes.data.storage.TrashRepository
import com.az.notes.data.sync.CredentialStore
import com.az.notes.data.sync.SyncConfigRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一次性迁移编排（旧版单仓库 → 多仓库 per-vault 数据）。
 *
 * 升级后首启时把所有旧版全局数据搬到「当前仓库」名下：
 * 仓库注册表建档 → 收藏 / 分享目录 → 同步配置 → WebDAV 密码 → 回收站批次目录。
 * 各步骤自身幂等；本类用 Mutex + 完成标记保证应用进程内至多执行一次。
 * 迁移失败不阻断启动（下次启动重试；全部 migrate* 方法在迁移完成前保留旧键回退）。
 */
@Singleton
class VaultMigrationRunner @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncConfigRepository: SyncConfigRepository,
    private val credentialStore: CredentialStore,
    private val trashRepository: TrashRepository
) {
    private val mutex = Mutex()

    @Volatile
    private var done = false

    /** 幂等执行全部迁移步骤；任意步骤失败仅内部记录、不抛出（下次启动重试）。 */
    suspend fun ensureMigrated() {
        if (done) return
        mutex.withLock {
            if (done) return
            runCatching {
                // 建档并取得当前仓库 id（全新安装由内置默认仓库兜底）；
                // 无旧数据时后续 migrate* 均为幂等空操作
                val vaultId = settingsRepository.ensureVaultRegistry() ?: return@runCatching
                settingsRepository.migrateLegacyVaultScoped()
                syncConfigRepository.migrateLegacyConfig()
                credentialStore.migrateLegacyPassword()
                trashRepository.migrateLegacyTrash(vaultId)
            }
            done = true
        }
    }
}
