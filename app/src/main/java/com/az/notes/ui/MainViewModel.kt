package com.az.notes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.az.notes.data.settings.SettingsRepository
import com.az.notes.di.CoilHolder
import com.az.notes.domain.model.AppSettings
import com.az.notes.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 承载全局偏好与仓库打开状态的主 ViewModel。 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val coilHolder: CoilHolder
) : ViewModel() {

    init {
        // 启动时建档仓库注册表：首启创建内置默认仓库（App 私有目录）；幂等，失败不阻断
        viewModelScope.launch { runCatching { settingsRepository.ensureVaultRegistry() } }
    }

    /**
     * 设置是否已从 DataStore 首次回流：启动画面据此延迟退场、导航图据此决定起点，
     * 避免已配置 Vault 时先闪一帧空界面。
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .onEach {
            _ready.value = true
            // 网络图片护栏阈值回流：体积上限走 provider 即时生效，超时变化重建客户端
            coilHolder.applySettings(it.remoteImageMaxBytes, it.remoteImageTimeoutSeconds)
        }
        .catch { _ready.value = true } // 读取失败也放行，避免卡在启动画面
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings()
        )

    /** 切换当前仓库（顶栏下拉 / 仓库管理页）：持久化记忆并立即对齐周期同步调度。 */
    fun switchVault(id: String) {
        viewModelScope.launch {
            settingsRepository.setCurrentVault(id)
            runCatching { syncScheduler.reschedulePeriodic() }
        }
    }

    /**
     * 系统分享（ACTION_SEND）与文本处理（ACTION_PROCESS_TEXT）传入的待写入文本。
     * 由主页消费后清空；仓库未就绪时暂存，就绪后仍会送达。
     */
    private val _sharedText = MutableStateFlow<String?>(null)
    val sharedText: StateFlow<String?> = _sharedText.asStateFlow()

    /** 接收系统分享的文本；空白内容忽略。 */
    fun setSharedText(text: String) {
        if (text.isBlank()) return
        _sharedText.value = text
    }

    /** 分享文本已交给主页建笔记，清空避免重复处理。 */
    fun consumeSharedText() {
        _sharedText.value = null
    }

    /**
     * 系统分享（ACTION_SEND，image 类型）传入的待导入图片 URI。
     * 由主页消费后清空；与 [_sharedText] 互斥（一次分享只携一种类型）。
     */
    private val _sharedImageUri = MutableStateFlow<Uri?>(null)
    val sharedImageUri: StateFlow<Uri?> = _sharedImageUri.asStateFlow()

    /** 接收系统分享的图片 URI。 */
    fun setSharedImageUri(uri: Uri) {
        _sharedImageUri.value = uri
    }

    /** 分享图片已交给主页建笔记，清空避免重复处理。 */
    fun consumeSharedImageUri() {
        _sharedImageUri.value = null
    }

    /** App 退到后台时冲刷排队中的“保存后同步”（详见 SyncScheduler.flushPendingSaveSync）。 */
    fun flushPendingSync() {
        viewModelScope.launch { runCatching { syncScheduler.flushPendingSaveSync() } }
    }
}
