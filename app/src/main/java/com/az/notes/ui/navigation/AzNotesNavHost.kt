package com.az.notes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.az.notes.ui.MainViewModel
import com.az.notes.ui.editor.EditorScreen
import com.az.notes.ui.gate.GateScreen
import com.az.notes.ui.home.HomeScreen
import com.az.notes.ui.reader.ReaderScreen
import com.az.notes.ui.settings.SettingsScreen
import com.az.notes.ui.sync.SyncScreen

/** 全局导航图（§5.7）。path 参数以 URL 编码存放绝对路径。 */
@Composable
fun AzNotesNavHost(
    mainViewModel: MainViewModel,
    navController: NavHostController = rememberNavController()
) {
    NavHost(navController = navController, startDestination = Routes.GATE) {
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
            HomeScreen(
                onOpenFile = { navController.navigate(Routes.reader(it)) },
                onOpenEditor = { path, fresh -> navController.navigate(Routes.editor(path, fresh)) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                // 主页右上角 = 立即同步（进页自动开始扫描）
                onSync = { navController.navigate(Routes.sync(autoStart = true)) }
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
                }
            )
        ) {
            EditorScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onPreview = { path -> navController.navigate(Routes.reader(path)) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onSync = { navController.navigate(Routes.sync(autoStart = false)) },
                onChangeVault = {
                    // 清掉主界面与设置页，回到门禁重新选择 Vault
                    navController.navigate(Routes.GATE) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.SYNC,
            arguments = listOf(
                navArgument("autoStart") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            SyncScreen(
                autoStart = entry.arguments?.getBoolean("autoStart") ?: false,
                onBack = { navController.popBackStack() },
                viewModel = hiltViewModel()
            )
        }
    }
}
