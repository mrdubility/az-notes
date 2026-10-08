package com.az.notes.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.data.ai.AiTestResult
import com.az.notes.domain.ai.AiProvider
import com.az.notes.ui.common.resolve

/**
 * AI 供应商管理页（B1 配置底座）：列表 ⇄ 表单单页内切换（用户已确认交互）。
 * - 列表态：预览行（点击编辑 / ⋮ 菜单）+ 底部「添加供应商」（模板菜单）
 * - 表单态：draft 非空；系统返回键先回列表（BackHandler），再按一次才退出页面
 * - API Key 永不回显：编辑已有供应商时留空 = 保持原值（加密存储在 AiKeyStore）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderScreen(
    viewModel: AiProviderViewModel,
    onBack: () -> Unit
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val fetchingModels by viewModel.fetchingModels.collectAsStateWithLifecycle()
    val modelOptions by viewModel.modelOptions.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // 删除二次确认目标（仅列表态可达）
    var deleteTarget by remember { mutableStateOf<AiProvider?>(null) }

    // 表单态：系统返回键先回列表，而不是退出页面
    BackHandler(enabled = draft != null) { viewModel.cancelEdit() }

    // 一次性提示 → Snackbar
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.resolve(context))
        viewModel.consumeMessage()
    }

    val currentDraft = draft
    val titleRes = when {
        currentDraft == null -> R.string.ai_provider_title
        currentDraft.isNew -> R.string.ai_provider_add
        else -> R.string.ai_provider_edit_title
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (currentDraft != null) viewModel.cancelEdit() else onBack() }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        when {
            // 首次回流完成前不渲染列表，避免「空状态」一闪而过
            !loaded -> Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            currentDraft != null -> ProviderForm(
                viewModel = viewModel,
                draft = currentDraft,
                testing = testing,
                testResult = testResult,
                fetchingModels = fetchingModels,
                saving = saving,
                contentPadding = inner
            )

            else -> ProviderList(
                providers = providers,
                onPick = { preset ->
                    // VM 无资源依赖：模板名在 UI 层按当前语言解析
                    viewModel.startAdd(
                        context.getString(preset.nameResId),
                        preset.protocol,
                        preset.baseUrl
                    )
                },
                onEdit = viewModel::startEdit,
                onTest = viewModel::testSavedProvider,
                onDelete = { deleteTarget = it },
                contentPadding = inner
            )
        }
    }

    // 获取模型候选列表（B1 补丁）：点击即添加；关闭时清空
    modelOptions?.let { options ->
        FetchModelsDialog(
            models = options,
            addedIds = currentDraft?.models?.map { it.id.trim() }?.toSet().orEmpty(),
            onAdd = viewModel::addFetchedModel,
            onDismiss = viewModel::dismissModelOptions
        )
    }

    // 删除确认：Key 一并删除，需二次确认
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.ai_provider_delete_title)) },
            text = { Text(stringResource(R.string.ai_provider_delete_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** 列表态：说明行 + 供应商预览行 + 底部「添加供应商」入口。 */
@Composable
private fun ProviderList(
    providers: List<AiProvider>,
    onPick: (AiProviderPreset) -> Unit,
    onEdit: (String) -> Unit,
    onTest: (String) -> Unit,
    onDelete: (AiProvider) -> Unit,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.ai_provider_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        if (providers.isEmpty()) {
            item { EmptyProviders() }
        } else {
            items(providers, key = { it.id }) { provider ->
                ProviderRow(
                    provider = provider,
                    onEdit = { onEdit(provider.id) },
                    onTest = { onTest(provider.id) },
                    onDelete = { onDelete(provider) }
                )
            }
        }
        item { AddProviderRow(onPick = onPick) }
    }
}

/** 表单态：基础信息 → 连接测试 → 模型列表 → 保存。 */
@Composable
private fun ProviderForm(
    viewModel: AiProviderViewModel,
    draft: ProviderDraft,
    testing: Boolean,
    testResult: AiTestResult?,
    fetchingModels: Boolean,
    saving: Boolean,
    contentPadding: PaddingValues
) {
    // 明文可见状态随表单组合存亡（回列表即销毁重置）
    var keyVisible by remember { mutableStateOf(false) }

    LazyColumn(
        // imePadding：键盘弹出时收缩列表视口（对齐编辑页先例）；配合 imeReveal 校正聚焦字段
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = draft.name,
                onValueChange = viewModel::setDraftName,
                label = { Text(stringResource(R.string.ai_provider_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).imeReveal()
            )
        }
        item {
            ProtocolSelector(selected = draft.protocol, onSelect = viewModel::setDraftProtocol)
        }
        item {
            OutlinedTextField(
                value = draft.baseUrl,
                onValueChange = viewModel::setDraftBaseUrl,
                label = { Text(stringResource(R.string.ai_provider_base_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().imeReveal()
            )
        }
        // 明文传输提醒（本地 Ollama 等场景可接受，仅提示不阻断）
        if (draft.baseUrl.trim().lowercase().startsWith("http://")) {
            item {
                Text(
                    text = stringResource(R.string.ai_provider_http_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        item {
            OutlinedTextField(
                value = draft.apiKeyInput,
                onValueChange = viewModel::setDraftApiKey,
                label = { Text(stringResource(R.string.ai_provider_api_key)) },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Outlined.VisibilityOff
                            else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (keyVisible) R.string.ai_provider_key_hide
                                else R.string.ai_provider_key_show
                            )
                        )
                    }
                },
                // 编辑已有供应商且已存 Key：留空保持不变
                supportingText = if (draft.keyPresent) {
                    { Text(stringResource(R.string.ai_provider_key_saved_hint)) }
                } else null,
                modifier = Modifier.fillMaxWidth().imeReveal()
            )
        }
        item {
            ConnectionTestRow(testing = testing, result = testResult, onTest = viewModel::testDraft)
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ai_provider_models),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                FetchModelsButton(fetching = fetchingModels, onClick = viewModel::fetchDraftModels)
            }
        }
        items(draft.models, key = { it.uid }) { model ->
            ModelEditRow(
                model = model,
                onIdChange = { viewModel.setDraftModelId(model.uid, it) },
                onVisionChange = { viewModel.setDraftModelVision(model.uid, it) },
                onRemove = { viewModel.removeDraftModel(model.uid) }
            )
        }
        item { AddModelRow(onClick = viewModel::addDraftModel) }
        item {
            Button(
                onClick = viewModel::save,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}
