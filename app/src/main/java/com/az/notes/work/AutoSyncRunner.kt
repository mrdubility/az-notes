package com.az.notes.work

import com.az.notes.data.debug.DebugLogLevel
import com.az.notes.data.debug.DebugLogRepository
import com.az.notes.data.debug.DebugLogType
import com.az.notes.data.local.SyncBaselineDao
import com.az.notes.data.local.SyncLogDao
import com.az.notes.data.local.SyncLogEntity
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.settings.VaultMigrationRunner
import com.az.notes.data.sync.SyncConfigRepository
import com.az.notes.data.sync.SyncEngine
import com.az.notes.domain.model.SyncPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动同步执行器（§6.4 / §6.5）：后台无 UI 场景下的同步会话。
 *
 * 安全阀（§6.5-1）：首次同步（尚无基线）或待执行删除类操作过多（≥ 20 项、
 * 或占计划操作 30% 以上）时不自动执行，仅写日志并在同步页可见，等用户
 * 人工确认；手动同步不受此限制（用户已在预览中确认）。
 * 与手动同步共享 [SyncEngine.runExclusive] 会话锁，绝不并发执行。
 */
@Singleton
class AutoSyncRunner @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val vaultMigrationRunner: VaultMigrationRunner,
    private val syncConfigRepository: SyncConfigRepository,
    private val syncEngine: SyncEngine,
    private val baselineDao: SyncBaselineDao,
    private val syncLogDao: SyncLogDao,
    private val debugLogRepository: DebugLogRepository
) {

    /** 执行一次自动同步会话；[trigger] 为触发方式（写入日志便于追溯）。 */
    suspend fun run(trigger: String) {
        // 升级迁移优先：保证 per-vault 配置 / 基线 / 回收站就位后再读取
        vaultMigrationRunner.ensureMigrated()
        val vaultId = settingsRepository.requireCurrentVaultId() ?: return
        val config = runCatching { syncConfigRepository.config.first() }.getOrNull() ?: return
        if (!config.configured) return
        val ran = syncEngine.runExclusive {
            try {
                val firstSync = withContext(Dispatchers.IO) { baselineDao.getAll(vaultId).isEmpty() }
                val plan = syncEngine.plan(config) { }
                // 无变更且没有跳过大文件：静默结束，不写日志避免周期任务刷屏
                if (plan.isEmpty && plan.skippedLarge == 0) return@runExclusive
                val veto = safetyVeto(plan, firstSync)
                if (veto != null) {
                    log(vaultId, "AUTO_SKIP", "$trigger：$veto")
                    debugLog(
                        DebugLogLevel.WARN,
                        "自动同步转人工确认",
                        mapOf("trigger" to trigger, "reason" to veto)
                    )
                    return@runExclusive
                }
                val summary = syncEngine.execute(config, plan) { _, _, _ -> }
                val detail = buildString {
                    append("$trigger：上传 ${summary.uploaded} / 下载 ${summary.downloaded}")
                    append(" / 删除云端 ${summary.deletedRemote} / 回收站 ${summary.trashedLocal}")
                    if (summary.moved > 0) append(" / 改名 ${summary.moved}")
                    if (summary.conflictCopies > 0) append(" / 冲突副本 ${summary.conflictCopies}")
                    if (summary.skippedLarge > 0) append(" / 跳过大文件 ${summary.skippedLarge}")
                    if (summary.failed > 0) append(" / 失败 ${summary.failed}")
                }
                log(vaultId, if (summary.failed > 0) "FAIL" else "OK", detail)
                debugLog(
                    DebugLogLevel.INFO,
                    "自动同步完成",
                    mapOf(
                        "trigger" to trigger,
                        "uploaded" to summary.uploaded,
                        "downloaded" to summary.downloaded,
                        "deletedRemote" to summary.deletedRemote,
                        "trashedLocal" to summary.trashedLocal,
                        "conflictCopies" to summary.conflictCopies,
                        "failed" to summary.failed
                    )
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                log(vaultId, "FAIL", "$trigger：${t.message ?: "自动同步失败"}")
                debugLog(
                    DebugLogLevel.ERROR,
                    "自动同步失败",
                    mapOf("trigger" to trigger, "error" to (t.message ?: t::class.java.simpleName))
                )
            }
        }
        if (!ran) log(vaultId, "AUTO_SKIP", "$trigger：已有同步会话进行中，本次跳过")
    }

    /** 安全阀判定（§6.5-1）；返回需要人工确认的原因，通过时返回 null。 */
    private fun safetyVeto(plan: SyncPlan, firstSync: Boolean): String? = when {
        firstSync -> "首次同步需要人工确认，请在同步页手动执行"
        plan.destructiveCount >= DESTRUCTIVE_ABS_LIMIT ->
            "待删除 / 移入回收站 ${plan.destructiveCount} 项（达到 $DESTRUCTIVE_ABS_LIMIT 项阈值），需人工确认"
        plan.ops.isNotEmpty() && plan.destructiveCount * 100 > plan.ops.size * DESTRUCTIVE_RATIO_PERCENT ->
            "删除类操作 ${plan.destructiveCount} / ${plan.ops.size} 项，占比超过 $DESTRUCTIVE_RATIO_PERCENT%，需人工确认"
        else -> null
    }

    /** 调试日志快捷入口（type=WORK）。 */
    private fun debugLog(level: DebugLogLevel, msg: String, extra: Map<String, Any?>) =
        debugLogRepository.log(level, DebugLogType.WORK, msg, extra)

    private suspend fun log(vaultId: String, result: String, detail: String) {
        withContext(Dispatchers.IO) {
            syncLogDao.insertAll(
                listOf(
                    SyncLogEntity(
                        vaultId = vaultId,
                        ts = System.currentTimeMillis(),
                        op = LOG_OP_AUTO,
                        path = "",
                        result = result,
                        detail = detail
                    )
                )
            )
            syncLogDao.trimTo(vaultId, 500)
        }
    }

    private companion object {
        /** 日志中的自动同步操作名（不属于 SyncOpType，仅用于展示）。 */
        const val LOG_OP_AUTO = "AUTO_SYNC"

        /** 删除类操作绝对阈值（§6.5-1）：达到即转人工确认。 */
        const val DESTRUCTIVE_ABS_LIMIT = 20

        /** 删除类操作占比阈值（§6.5-1，单位 %）。 */
        const val DESTRUCTIVE_RATIO_PERCENT = 30
    }
}
