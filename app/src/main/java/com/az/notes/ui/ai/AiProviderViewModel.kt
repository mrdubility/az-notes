package com.az.notes.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.az.notes.R
import com.az.notes.data.ai.AiConnectionTester
import com.az.notes.data.ai.AiKeyStore
import com.az.notes.data.ai.AiProviderRepository
import com.az.notes.data.ai.AiTestResult
import com.az.notes.data.ai.ModelListResult
import com.az.notes.domain.ai.AiModel
import com.az.notes.domain.ai.AiProtocol
import com.az.notes.domain.ai.AiProvider
import com.az.notes.domain.ai.BaseUrlNormalizer
import com.az.notes.ui.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 表单草稿中的一个模型行；[uid] 仅用于列表稳定标识（不落库）。 */
data class AiModelDraft(
    val uid: Long,
    val id: String = "",
    val vision: Boolean = false
)

/** 编辑草稿（非空 = 表单态；列表 ⇄ 表单为单页内切换）。 */
data class ProviderDraft(
    /** 已有供应商 id；null = 新增。 */
    val providerId: String?,
    val name: String = "",
    val protocol: AiProtocol = AiProtocol.OPENAI_CHAT,
    val baseUrl: String = "",
    /** 表单中输入的 Key；编辑已有供应商时留空 = 保持原值。 */
    val apiKeyInput: String = "",
    /** 编辑已有供应商且已加密存储 Key（显示「已保存，留空保持不变」）。 */
    val keyPresent: Boolean = false,
    val models: List<AiModelDraft> = emptyList()
) {
    val isNew: Boolean get() = providerId == null
}

/**
 * 供应商管理页 ViewModel（B1 配置底座）：供应商增删改 + 测试连接。
 * 表单与列表同页切换：draft 非空即表单态；Key 永不回显（编辑时留空保持原值）。
 */
@HiltViewModel
class AiProviderViewModel @Inject constructor(
    private val repository: AiProviderRepository,
    private val keyStore: AiKeyStore,
    private val tester: AiConnectionTester
) : ViewModel() {

    /** 供应商列表（插入顺序）。 */
    val providers: StateFlow<List<AiProvider>> = repository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 首次回流完成（避免「空列表」状态在数据到达前一闪而过）。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 表单草稿（null = 列表态）。 */
    private val _draft = MutableStateFlow<ProviderDraft?>(null)
    val draft: StateFlow<ProviderDraft?> = _draft.asStateFlow()

    /** 表单内「测试连接」进行中。 */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    /** 表单内「测试连接」结果（内联展示；草稿变化后自动失效）。 */
    private val _testResult = MutableStateFlow<AiTestResult?>(null)
    val testResult: StateFlow<AiTestResult?> = _testResult.asStateFlow()

    /** 表单内「获取模型」进行中（B1 补丁快速添加）。 */
    private val _fetchingModels = MutableStateFlow(false)
    val fetchingModels: StateFlow<Boolean> = _fetchingModels.asStateFlow()

    /** 「获取模型」返回的候选列表（非空 = 显示选择对话框；null = 不显示）。 */
    private val _modelOptions = MutableStateFlow<List<String>?>(null)
    val modelOptions: StateFlow<List<String>?> = _modelOptions.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** 一次性提示（Snackbar）。 */
    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    private var nextDraftUid = 0L

    init {
        viewModelScope.launch {
            repository.providers.first()
            _loaded.value = true
        }
    }

    // ---------------------------------------------------------------- 列表态

    /** 从模板 / 空白新建：仅预填名称 + 协议 + baseUrl，模型列表留空。 */
    fun startAdd(name: String, protocol: AiProtocol, baseUrl: String) {
        _draft.value = ProviderDraft(
            providerId = null,
            name = name,
            protocol = protocol,
            baseUrl = baseUrl
        )
        _testResult.value = null
        _modelOptions.value = null
    }

    fun startEdit(id: String) {
        viewModelScope.launch {
            val provider = providers.value.firstOrNull { it.id == id } ?: return@launch
            _draft.value = ProviderDraft(
                providerId = provider.id,
                name = provider.name,
                protocol = provider.protocol,
                baseUrl = provider.baseUrl,
                keyPresent = keyStore.getKey(provider.id).isNotEmpty(),
                models = provider.models.map {
                    AiModelDraft(uid = nextDraftUid++, id = it.id, vision = it.vision)
                }
            )
            _testResult.value = null
            _modelOptions.value = null
        }
    }

    fun cancelEdit() {
        _draft.value = null
        _testResult.value = null
        _testing.value = false
        _modelOptions.value = null
    }

    /** 删除供应商（含其加密存储的 Key）；UI 层已二次确认。 */
    fun delete(id: String) {
        viewModelScope.launch {
            val name = providers.value.firstOrNull { it.id == id }?.name.orEmpty()
            repository.delete(id)
            _message.value = UiText.of(R.string.ai_provider_deleted, name)
        }
    }

    /** 列表菜单「测试连接」：用已保存的配置与 Key 测试，结果走 Snackbar。 */
    fun testSavedProvider(id: String) {
        viewModelScope.launch {
            val provider = providers.value.firstOrNull { it.id == id } ?: return@launch
            _message.value = UiText.of(R.string.ai_provider_testing)
            val result = tester.test(provider, keyStore.getKey(id))
            _message.value = resultText(result)
        }
    }

    // ---------------------------------------------------------------- 表单编辑

    fun setDraftName(value: String) = mutateDraft { it.copy(name = value) }

    fun setDraftProtocol(value: AiProtocol) = mutateDraft { it.copy(protocol = value) }

    fun setDraftBaseUrl(value: String) = mutateDraft { it.copy(baseUrl = value) }

    fun setDraftApiKey(value: String) = mutateDraft { it.copy(apiKeyInput = value) }

    fun addDraftModel() =
        mutateDraft { it.copy(models = it.models + AiModelDraft(uid = nextDraftUid++)) }

    fun setDraftModelId(uid: Long, value: String) = mutateDraft { draft ->
        draft.copy(models = draft.models.map { if (it.uid == uid) it.copy(id = value) else it })
    }

    fun setDraftModelVision(uid: Long, value: Boolean) = mutateDraft { draft ->
        draft.copy(models = draft.models.map { if (it.uid == uid) it.copy(vision = value) else it })
    }

    fun removeDraftModel(uid: Long) = mutateDraft { draft ->
        draft.copy(models = draft.models.filterNot { it.uid == uid })
    }

    /** 表单内「测试连接」：用草稿当前值（Key 未改动则读已存值），结果内联显示。 */
    fun testDraft() {
        val draft = _draft.value ?: return
        if (_testing.value) return
        val normalized = BaseUrlNormalizer.normalize(draft.baseUrl)
        if (normalized == null) {
            _message.value = UiText.of(R.string.ai_provider_err_url)
            return
        }
        viewModelScope.launch {
            val key = effectiveKey(draft) ?: return@launch
            _testing.value = true
            try {
                val probe = AiProvider(
                    id = draft.providerId.orEmpty(),
                    name = draft.name.trim(),
                    protocol = draft.protocol,
                    baseUrl = normalized
                )
                _testResult.value = tester.test(probe, key)
            } finally {
                _testing.value = false
            }
        }
    }

    /**
     * 表单内「获取模型」（B1 补丁）：成功后弹出候选列表快速添加；
     * 失败以 Snackbar 提示原因（复用测试连接文案），手填路径不受影响。
     */
    fun fetchDraftModels() {
        val draft = _draft.value ?: return
        if (_fetchingModels.value) return
        val normalized = BaseUrlNormalizer.normalize(draft.baseUrl)
        if (normalized == null) {
            _message.value = UiText.of(R.string.ai_provider_err_url)
            return
        }
        viewModelScope.launch {
            val key = effectiveKey(draft) ?: return@launch
            _fetchingModels.value = true
            try {
                val probe = AiProvider(
                    id = draft.providerId.orEmpty(),
                    name = draft.name.trim(),
                    protocol = draft.protocol,
                    baseUrl = normalized
                )
                when (val result = tester.fetchModels(probe, key)) {
                    is ModelListResult.Success -> _modelOptions.value = result.modelIds
                    is ModelListResult.Failure ->
                        _message.value = resultText(AiTestResult.Failure(result.kind))
                }
            } finally {
                _fetchingModels.value = false
            }
        }
    }

    /** 把获取到的模型 ID 追加进草稿（按 id 去重；对话框保持打开可连续添加）。 */
    fun addFetchedModel(id: String) = mutateDraft { draft ->
        val trimmed = id.trim()
        if (trimmed.isEmpty() || draft.models.any { it.id.trim() == trimmed }) {
            draft
        } else {
            draft.copy(models = draft.models + AiModelDraft(uid = nextDraftUid++, id = trimmed))
        }
    }

    fun dismissModelOptions() {
        _modelOptions.value = null
    }

    /** 保存草稿：校验 → 规范化 baseUrl → 落库（新增拿 id 后写 Key；编辑保留未编辑字段）。 */
    fun save() {
        val draft = _draft.value ?: return
        if (_saving.value) return
        val name = draft.name.trim()
        if (name.isEmpty()) {
            _message.value = UiText.of(R.string.ai_provider_err_name)
            return
        }
        val normalized = BaseUrlNormalizer.normalize(draft.baseUrl)
        if (normalized == null) {
            _message.value = UiText.of(R.string.ai_provider_err_url)
            return
        }
        // 模型行：去空白、剔除空 ID、按 ID 去重（保留首个）
        val models = draft.models
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { id ->
                val vision = draft.models.firstOrNull { it.id.trim() == id }?.vision == true
                AiModel(id = id, vision = vision)
            }

        viewModelScope.launch {
            _saving.value = true
            try {
                val providerId = draft.providerId
                if (providerId == null) {
                    val newId = repository.add(
                        AiProvider(
                            id = "",
                            name = name,
                            protocol = draft.protocol,
                            baseUrl = normalized,
                            models = models
                        )
                    )
                    if (draft.apiKeyInput.isNotBlank()) keyStore.setKey(newId, draft.apiKeyInput)
                } else {
                    repository.update(providerId) { old ->
                        old.copy(
                            name = name,
                            protocol = draft.protocol,
                            baseUrl = normalized,
                            models = models
                        )
                    }
                    if (draft.apiKeyInput.isNotBlank()) keyStore.setKey(providerId, draft.apiKeyInput)
                }
                _draft.value = null
                _testResult.value = null
                _modelOptions.value = null
                _message.value = UiText.of(R.string.ai_provider_saved)
            } finally {
                _saving.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---------------------------------------------------------------- 内部

    /** 草稿的有效 Key：新输入优先，编辑态回退已存值；为空时提示并返回 null。 */
    private suspend fun effectiveKey(draft: ProviderDraft): String? {
        val key = when {
            draft.apiKeyInput.isNotBlank() -> draft.apiKeyInput.trim()
            draft.providerId != null -> keyStore.getKey(draft.providerId)
            else -> ""
        }
        if (key.isEmpty()) {
            _message.value = UiText.of(R.string.ai_provider_err_key)
            return null
        }
        return key
    }

    private fun mutateDraft(transform: (ProviderDraft) -> ProviderDraft) {
        val current = _draft.value ?: return
        _draft.value = transform(current)
        // 配置变更后旧测试结果失效，避免展示与当前输入不符的结论
        _testResult.value = null
    }

    companion object {
        /** 测试结果 → 显示文本（表单内联与 Snackbar 共用）。 */
        fun resultText(result: AiTestResult): UiText = when (result) {
            is AiTestResult.Success -> result.modelCount?.let {
                UiText.of(R.string.ai_provider_test_ok_models, it)
            } ?: UiText.of(R.string.ai_provider_test_ok)

            is AiTestResult.Failure -> UiText.of(
                when (result.kind) {
                    AiTestResult.FailureKind.UNAUTHORIZED -> R.string.ai_provider_test_err_unauthorized
                    AiTestResult.FailureKind.NOT_FOUND -> R.string.ai_provider_test_err_not_found
                    AiTestResult.FailureKind.RATE_LIMITED -> R.string.ai_provider_test_err_rate_limited
                    AiTestResult.FailureKind.NETWORK -> R.string.ai_provider_test_err_network
                    AiTestResult.FailureKind.SERVER -> R.string.ai_provider_test_err_server
                    AiTestResult.FailureKind.UNKNOWN -> R.string.ai_provider_test_err_unknown
                }
            )
        }
    }
}
