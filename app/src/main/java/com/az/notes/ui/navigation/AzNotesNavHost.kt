package com.az.notes.ui.navigation

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.az.notes.ui.MainViewModel
import com.az.notes.ui.editor.EditorScreen
import com.az.notes.ui.favorites.FavoritesScreen
import com.az.notes.ui.gate.GateScreen
import com.az.notes.ui.home.HomeScreen
import com.az.notes.ui.reader.ReaderScreen
import com.az.notes.ui.settings.SettingsScreen
import com.az.notes.ui.settings.ToolbarSettingsScreen
import com.az.notes.ui.sync.SyncScreen
import com.az.notes.ui.trash.TrashScreen
import com.az.notes.util.StoragePermission

/** 全局导航图（§5.7）。path 参数以 URL 编码存放绝对路径。 */
@Composable
fun AzNotesNavHost(
    mainViewModel: MainViewModel,
    navController: NavHostController = rememberNavController()
) {
    val ready by mainViewModel.ready.collectAsStateWithLifecycle()
    val settings by mainViewModel.settings.collectAsStateWithLifecycle()

    if (!ready) {
        // 设置尚未回流完成：显示与启动画面一致的底色，防止引导页闪现（正常仅数毫秒）
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }

    // 起点在就绪后一次性固化（remember）：已配置且权限就绪直接进主页，
    // 从根上避免引导页一闪而过；此后设置变化不会重建导航图
    val startDestination = remember {
        if (settings.vaultPath.isNullOrBlank() || !StoragePermission.hasAllFilesAccess()) {
            Routes.GATE
        } else {
            Routes.HOME
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.GATE) {
            GateScreen(
                viewModel = mainViewModel,
                onReady = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.GATE) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            val sharedText by mainViewModel.sharedText.collectAsStateWithLifecycle()
            HomeScreen(
                onOpenFile = { navController.navigate(Routes.reader(it)) },
                onOpenEditor = { path, fresh, fromShare ->
                    navController.navigate(Routes.editor(path, fresh, fromShare))
                },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenFavorites = { navController.navigate(Routes.FAVORITES) },
                // 主页右上角“立即同步”在本页以弹窗完成（扫描 → 确认 → 执行），无需导航；
                // 同步配置从抽屉 → 设置 → 同步进入
                onSettings = { navController.navigate(Routes.SETTINGS) },
                // 系统分享 / 内容传送门传入的文本：主页消费后新建笔记
                sharedText = sharedText,
                onSharedTextConsumed = mainViewModel::consumeSharedText
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) {
            ReaderScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onEdit = { path -> navController.navigate(Routes.editor(path)) }
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("fresh") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("share") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            val fromShare = entry.arguments?.getBoolean("share") ?: false
            val context = LocalContext.current
            EditorScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onPreview = { path -> navController.navigate(Routes.reader(path)) },
                fromShare = fromShare,
                // 分享独立页：返回即退出应用（回到分享来源应用）
                onExitApp = { (context as? Activity)?.finish() },
                // 回列表：直接弹回主页（分享场景的栈为 [HOME, EDITOR]）
                onBackToHome = { navController.popBackStack(Routes.HOME, inclusive = false) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onSync = { navController.navigate(Routes.SYNC) },
                onChangeVault = {
                    // 清掉主界面与设置页，回到门禁重新选择 Vault
                    navController.navigate(Routes.GATE) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onOpenToolbarSettings = { navController.navigate(Routes.TOOLBAR_SETTINGS) }
            )
        }

        composable(Routes.TOOLBAR_SETTINGS) {
            ToolbarSettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.TRASH) {
            TrashScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.FAVORITES) {
            FavoritesScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onOpen = { path -> navController.navigate(Routes.reader(path)) }
            )
        }

        composable(Routes.SYNC) {
            SyncScreen(
                onBack = { navController.popBackStack() },
                viewModel = hiltViewModel()
            )
        }
    }
}
