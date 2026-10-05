package com.az.notes.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.ModalDrawerSheet
import com.az.notes.R
import com.az.notes.ui.tree.TreeContent
import com.az.notes.ui.tree.TreeViewModel
import kotlinx.coroutines.launch

/**
 * 主界面（§5.7）：单 Activity + Drawer 目录树 + 顶部同步/设置入口。
 * 目录树条目点击后关闭抽屉并跳转阅读器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenFile: (String) -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    treeViewModel: TreeViewModel = hiltViewModel()
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { treeViewModel.refresh() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                TreeContent(
                    viewModel = treeViewModel,
                    onOpenFile = { node ->
                        scope.launch { drawerState.close() }
                        onOpenFile(node.absolutePath)
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, "目录")
                        }
                    },
                    actions = {
                        IconButton(onClick = onSync) {
                            Icon(Icons.Outlined.Sync, stringResource(R.string.action_sync))
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.action_settings))
                        }
                    }
                )
            }
        ) { inner ->
            Column(
                modifier = Modifier.fillMaxSize().padding(inner).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "从左上角目录树选择笔记开始阅读",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "标准 Markdown · 大纲 · 阅读进度 · 暗色主题 · 字体可调",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
