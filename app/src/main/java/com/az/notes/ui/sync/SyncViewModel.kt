package com.az.notes.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.data.local.ConflictRecordDao
import com.az.notes.data.local.ConflictRecordEntity
import com.az.notes.data.local.SyncLogDao
import com.az.notes.data.local.SyncLogEntity
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.data.sync.CredentialStore
import com.az.notes.data.sync.SyncConfigRepository
import com.az.notes.data.sync.SyncEngine
import com.az.notes.domain.model.ConflictStrategy
import com.az.notes.domain.model.SyncConfig
import com.az.notes.domain.model.SyncInterval
import com.az.notes.domain.model.SyncMode
import com.az.notes.domain.model.SyncPlan
import com.az.notes.domain.model.SyncSummary
import com.az.notes.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 同步页状态机：空闲 → 扫描 → 预览确认 → 执行 → 完成 / 失败。 */
enum class SyncPhase { IDLE, TESTING, SCANNING, AWAIT_CONFIRM, EXECUTING, DONE, ERROR }

data class SyncUiState(
    val config: SyncConfig = SyncConfig(),
    /** 编辑中的密码（预填自加密存储；仅内存 + EncryptedSharedPreferences，不落明文） */
    val password: String = "",
    val vaultPath: String? = null,
    /** 配置与偏好是否已从 DataStore 载入；自动开始的同步必须等它就绪，否则会误报“未配置”。 */
    val loaded: Boolean = false,
    val phase: SyncPhase = SyncPhase.IDLE,
    val statusText: String = "",
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    /** 待确认的执行计划（预览确认），null 表示无 */
    val plan: SyncPlan? = null,
    val summary: SyncSummary? = null,
    val testMessage: String? = null,
    val error: String? = null
) {
    val busy: Boolean
        get() = phase == SyncPhase.TESTING || phase == SyncPhase.SCANNING || phase == SyncPhase.EXECUTING

    val canStart: Boolean
        get() = config.configured && !vaultPath.isNullOrBlank() && !busy
}

/**
 * 同步页 ViewModel（§6）：配置编辑（地址/账号/密码/远端目录/策略/过滤规则/自动同步）、
 * 连接测试、手动同步（Scan → Plan 预览确认 → Execute）、冲突记录与日志展示。
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncEngine: SyncEngine,
    private val syncConfigRepository: SyncConfigRepository,
    private val credentialStore: CredentialStore,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    syncLogDao: SyncLogDao,
    private val conflictRecordDao: ConflictRecordDao
) : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    val logs: StateFlow<List<SyncLogEntity>> = syncLogDao.recent(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 冲突记录（最近 50 条）：供人工合并后清除（§6.2）。 */
    val conflicts: StateFlow<List<ConflictRecordEntity>> = conflictRecordDao.recent(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 预填加密存储中的密码（用户已开始输入则不覆盖）
        viewModelScope.launch {
            val password = withContext(Dispatchers.IO) { credentialStore.getPassword() }
            if (password.isNotEmpty()) {
                _state.update { s -> if (s.password.isEmpty()) s.copy(password = password) else s }
            }
        }
        viewModelScope.launch {
            // 合并两个 DataStore flow：首次发射即代表同步配置与 Vault 均已就绪
            combine(syncConfigRepository.config, settingsRepository.settings) { config, settings ->
                config to settings.vaultPath
            }.collect { (config, vaultPath) ->
                _state.update { it.copy(config = config, vaultPath = vaultPath, loaded = true) }
            }
        }
    }

    // ---------------------------------------------------------------- 配置编辑

    fun updateServerUrl(url: String) = viewModelScope.launch { syncConfigRepository.setServerUrl(url) }
    fun updateUsername(name: String) = viewModelScope.launch { syncConfigRepository.setUsername(name) }
    fun updateRemoteDir(dir: String) = viewModelScope.launch { syncConfigRepository.setRemoteDir(dir) }
    fun updateMode(mode: SyncMode) = viewModelScope.launch { syncConfigRepository.setMode(mode) }

    fun updateConflictStrategy(strategy: ConflictStrategy) =
        viewModelScope.launch { syncConfigRepository.setConflictStrategy(strategy) }

    fun updateIgnoreRules(rules: String) =
        viewModelScope.launch { syncConfigRepository.setIgnoreRules(rules) }

    fun updateMaxFileSizeMb(mb: Int) =
        viewModelScope.launch { syncConfigRepository.setMaxFileSizeMb(mb) }

    fun updateAutoSyncOnStart(enabled: Boolean) = viewModelScope.launch {
        syncConfigRepository.setAutoSyncOnStart(enabled)
    }

    fun updatePeriodicInterval(interval: SyncInterval) = viewModelScope.launch {
        syncConfigRepository.setPeriodicInterval(interval)
        // 周期任务随即与配置对齐（选“关闭”则撤销已有任务）
        syncScheduler.reschedulePeriodic()
    }

    fun updateSyncAfterSave(enabled: Boolean) = viewModelScope.launch {
        syncConfigRepository.setSyncAfterSave(enabled)
    }

    /** 人工合并完成后清除全部冲突记录（顶栏角标随之消失）。 */
    fun clearConflicts() = viewModelScope.launch { conflictRecordDao.clear() }

    fun updatePassword(password: String) {
        _state.update { it.copy(password = password) }
        credentialStore.setPassword(password)
    }

    /** 坚果云预设：一键填入官方 WebDAV 地址（§6.6）。 */
    fun applyNutstorePreset() {
        viewModelScope.launch { syncConfigRepository.setServerUrl(SyncConfig.DEFAULT_SERVER_URL) }
    }

    // ---------------------------------------------------------------- 连接测试

    fun testConnection() {
        val config = _state.value.config
        if (!config.configured) {
            _state.update { it.copy(testMessage = "请先填写服务器地址与账号") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = SyncPhase.TESTING,
                    statusText = "正在测试连接…",
                    testMessage = null,
                    error = null
                )
            }
            // 限流等待（自动重试中）时同步展示到进度区，避免只看到进度条无文字
            val result = syncEngine.testConnection(config) { status ->
                _state.update { it.copy(statusText = status) }
            }
            result.fold(
                onSuccess = {
                    _state.update {
                        it.copy(phase = SyncPhase.IDLE, statusText = "", testMessage = "连接成功")
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            phase = SyncPhase.IDLE,
                            statusText = "",
                            testMessage = e.message ?: "连接失败：未知错误"
                        )
                    }
                }
            )
        }
    }

    // ---------------------------------------------------------------- 手动同步

    /**
     * 开始同步：扫描两端生成计划，等用户预览确认后执行。
     *
     * 先占位为“扫描中”拦截连点，再等配置从 DataStore 载入完成后做前置检查——
     * 否则刚进页就点同步（或主页“立即同步”）会因为配置尚未回流而误报“未配置”。
     * 前置检查未通过时回退空闲并用 Toast 提示。
     */
    fun startSync() {
        if (_state.value.busy) {
            _state.update { it.copy(testMessage = "同步正在进行中，请稍候") }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = SyncPhase.SCANNING,
                    statusText = "准备中…",
                    plan = null,
                    summary = null,
                    error = null
                )
            }
            val snapshot = state.first { it.loaded }
            if (!snapshot.config.configured) {
                _state.update {
                    it.copy(
                        phase = SyncPhase.IDLE,
                        statusText = "",
                        testMessage = "请先在同步设置中填写服务器地址、账号与应用密码"
                    )
                }
                return@launch
            }
            if (snapshot.vaultPath.isNullOrBlank()) {
                _state.update {
                    it.copy(phase = SyncPhase.IDLE, statusText = "", testMessage = "尚未选择 Vault 目录")
                }
                return@launch
            }
            try {
                // 与自动同步互斥：拿不到会话锁说明后台同步正在进行，放弃本次
                var planResult: SyncPlan? = null
                val ran = syncEngine.runExclusive {
                    planResult = syncEngine.plan(snapshot.config) { status ->
                        _state.update { it.copy(statusText = status) }
                    }
                }
                if (!ran) {
                    _state.update {
                        it.copy(
                            phase = SyncPhase.IDLE,
                            statusText = "",
                            testMessage = "其他同步正在进行中，请稍后再试"
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(phase = SyncPhase.AWAIT_CONFIRM, plan = planResult, statusText = "") }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                _state.update { it.copy(phase = SyncPhase.ERROR, error = t.message ?: "同步计划生成失败") }
            }
        }
    }

    /** 确认执行预览中的计划。 */
    fun confirmExecute() {
        val snapshot = _state.value
        val plan = snapshot.plan ?: return
        if (snapshot.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(phase = SyncPhase.EXECUTING, progressDone = 0, progressTotal = plan.ops.size)
            }
            try {
                var result: SyncSummary? = null
                val ran = syncEngine.runExclusive {
                    result = syncEngine.execute(snapshot.config, plan) { done, total, label ->
                        _state.update {
                            it.copy(progressDone = done, progressTotal = total, statusText = label)
                        }
                    }
                }
                if (!ran) {
                    _state.update {
                        it.copy(
                            phase = SyncPhase.IDLE,
                            plan = null,
                            statusText = "",
                            testMessage = "其他同步正在进行中，请稍后再试"
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(phase = SyncPhase.DONE, summary = result, plan = null, statusText = "")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Exception) {
                _state.update { it.copy(phase = SyncPhase.ERROR, error = t.message ?: "同步执行失败") }
            }
        }
    }

    /** 放弃计划（不执行任何操作）。 */
    fun cancelPlan() {
        _state.update { it.copy(phase = SyncPhase.IDLE, plan = null, statusText = "") }
    }

    /** 收起完成 / 失败提示。 */
    fun dismissResult() {
        _state.update { it.copy(phase = SyncPhase.IDLE, summary = null, error = null) }
    }

    /** Toast 展示后清除连接测试结果，防止重组 / 重建时重复弹出。 */
    fun clearTestMessage() {
        _state.update { it.copy(testMessage = null) }
    }
}
