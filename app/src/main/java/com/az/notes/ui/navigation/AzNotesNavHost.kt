package com.az.notes.ui.navigation

import android.app.Activity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.az.notes.ui.MainViewModel
import com.az.notes.ui.ai.AiChatScreen
import com.az.notes.ui.ai.AiProviderScreen
import com.az.notes.ui.debug.DebugLogScreen
import com.az.notes.ui.editor.EditorScreen
import com.az.notes.ui.favorites.FavoritesScreen
import com.az.notes.ui.gallery.GalleryScreen
import com.az.notes.ui.home.HomeScreen
import com.az.notes.ui.reader.ReaderScreen
import com.az.notes.ui.settings.BackupScreen
import com.az.notes.ui.settings.SettingsScreen
import com.az.notes.ui.settings.ToolbarSettingsScreen
import com.az.notes.ui.sync.SyncConflictScreen
import com.az.notes.ui.sync.SyncLogScreen
import com.az.notes.ui.sync.SyncScreen
import com.az.notes.ui.trash.TrashScreen
import com.az.notes.ui.vault.VaultScreen

/** 全局导航图（§5.7）。path 参数以 URL 编码存放绝对路径。 */
@Composable
fun AzNotesNavHost(
    mainViewModel: MainViewModel,
    navController: NavHostController = rememberNavController()
) {
    val ready by mainViewModel.ready.collectAsStateWithLifecycle()

    if (!ready) {
        // 设置尚未回流完成：显示与启动画面一致的底色（正常仅数毫秒）
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }

    // 无门禁引导页：首启由内置默认仓库（App 私有目录）兜底，直接进入主页；
    // 外部目录的「所有文件访问权限」改为添加仓库时按需引导（见仓库管理页）
    // 页面切换不渐隐：默认 700ms 交叉渐隐拖慢连续操作（列表 → 预览等），四类过渡全部即时完成
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None }
    ) {
        composable(Routes.HOME) {
            val sharedText by mainViewModel.sharedText.collectAsStateWithLifecycle()
            val sharedImageUri by mainViewModel.sharedImageUri.collectAsStateWithLifecycle()
            HomeScreen(
                onOpenFile = { navController.navigate(Routes.reader(it)) },
                // 新建待办：直接进待办预览页（fresh=true → 退出时无条目则清理占位文件）
                onOpenTask = { navController.navigate(Routes.reader(it, fresh = true)) },
                onOpenEditor = { path, fresh, fromShare ->
                    navController.navigate(Routes.editor(path, fresh, fromShare))
                },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenFavorites = { navController.navigate(Routes.FAVORITES) },
                onOpenGallery = { navController.navigate(Routes.GALLERY) },
                // AI 对话（B2）：抽屉入口
                onOpenAiChat = { navController.navigate(Routes.AI_CHAT) },
                // 主页右上角“立即同步”在本页以弹窗完成（扫描 → 确认 → 执行），无需导航；
                // 同步配置从抽屉 → 设置 → 同步进入
                onSettings = { navController.navigate(Routes.SETTINGS) },
                // 顶栏切换仓库（记忆当前仓库）；仓库菜单 → 管理仓库页
                onSwitchVault = mainViewModel::switchVault,
                onOpenVaults = { navController.navigate(Routes.VAULTS) },
                // 系统分享 / 内容传送门传入的文本：主页消费后新建笔记
                sharedText = sharedText,
                onSharedTextConsumed = mainViewModel::consumeSharedText,
                // 系统分享图片（image/*）：主页消费后导入图片并新建图文笔记
                sharedImageUri = sharedImageUri,
                onSharedImageConsumed = mainViewModel::consumeSharedImageUri
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument("path") { type = NavType.StringType },
                navArgument("fresh") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { entry ->
            ReaderScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStackSafely(entry) },
                onEdit = { path ->
                    // 去重：编辑页已是栈顶时直接回退复用，避免反复入栈
                    navController.navigateOrBack(entry, Routes.EDITOR_BASE, path, Routes.editor(path))
                },
                // 加入 AI 对话（B3）：附件已入待发列表，跳转聊天页
                onAddToAiChat = { navController.navigate(Routes.AI_CHAT) },
                fresh = entry.arguments?.getBoolean("fresh") ?: false
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
                onBack = { navController.popBackStackSafely(entry) },
                onPreview = { path ->
                    // 去重：预览页已是栈顶时直接回退复用，避免反复入栈
                    navController.navigateOrBack(entry, Routes.READER_BASE, path, Routes.reader(path))
                },
                fromShare = fromShare,
                // 分享独立页：返回即退出应用（回到分享来源应用）
                onExitApp = { (context as? Activity)?.finish() },
                // 回列表：直接弹回主页（分享场景的栈为 [HOME, EDITOR]）
                onBackToHome = { navController.popBackStack(Routes.HOME, inclusive = false) }
            )
        }

        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStackSafely(entry) },
                onSync = { navController.navigate(Routes.SYNC) },
                onOpenVaults = { navController.navigate(Routes.VAULTS) },
                onOpenToolbarSettings = { navController.navigate(Routes.TOOLBAR_SETTINGS) },
                onOpenAiProviders = { navController.navigate(Routes.AI_PROVIDERS) },
                onOpenBackup = { navController.navigate(Routes.BACKUP) },
                onOpenDebugLogs = { navController.navigate(Routes.DEBUG_LOGS) }
            )
        }

        composable(Routes.BACKUP) { entry ->
            BackupScreen(
                onBack = { navController.popBackStackSafely(entry) },
                viewModel = hiltViewModel()
            )
        }

        composable(Routes.AI_PROVIDERS) { entry ->
            AiProviderScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStackSafely(entry) }
            )
        }

        composable(Routes.AI_CHAT) { entry ->
            AiChatScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStackSafely(entry) },
                // 空态 / 错误条的「去设置」：直达供应商管理页
                onOpenProviders = { navController.navigate(Routes.AI_PROVIDERS) },
                // 导出完成 Snackbar 的「查看」：跳阅读器（接收绝对路径）
                onOpenNote = { path -> navController.navigate(Routes.reader(path)) }
            )
        }

        composable(Routes.VAULTS) { entry ->
            VaultScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStackSafely(entry) }
            )
        }

        composable(Routes.TOOLBAR_SETTINGS) { entry ->
            ToolbarSettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStackSafely(entry) }
            )
        }

        composable(Routes.TRASH) { entry ->
            TrashScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStackSafely(entry) }
            )
        }

        composable(Routes.GALLERY) { entry ->
            GalleryScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStackSafely(entry) }
            )
        }

        composable(Routes.FAVORITES) { entry ->
            FavoritesScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStackSafely(entry) },
                onOpen = { path -> navController.navigate(Routes.reader(path)) }
            )
        }

        composable(Routes.SYNC) { entry ->
            SyncScreen(
                onBack = { navController.popBackStackSafely(entry) },
                onOpenConflicts = { navController.navigate(Routes.SYNC_CONFLICTS) },
                onOpenLogs = { navController.navigate(Routes.SYNC_LOGS) },
                viewModel = hiltViewModel()
            )
        }

        composable(Routes.SYNC_CONFLICTS) { entry ->
            SyncConflictScreen(
                onBack = { navController.popBackStackSafely(entry) },
                viewModel = hiltViewModel()
            )
        }

        composable(Routes.SYNC_LOGS) { entry ->
            SyncLogScreen(
                onBack = { navController.popBackStackSafely(entry) },
                viewModel = hiltViewModel()
            )
        }

        composable(Routes.DEBUG_LOGS) { entry ->
            DebugLogScreen(
                onBack = { navController.popBackStackSafely(entry) }
            )
        }
    }
}

/**
 * 安全返回：快速点击返回按钮时，第二击会落在上一页正在退场的残留 UI 上再触发一次
 * 弹栈；若此时栈中只剩起始页 Home，连它一起弹出会让 NavHost 变成空白（需杀进程
 * 恢复）。仅当来源页面仍处于 RESUMED（确实是栈顶活跃页）时才执行弹栈。
 */
private fun NavHostController.popBackStackSafely(from: NavBackStackEntry) {
    if (from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        popBackStack()
    }
}

/**
 * 预览 ↔ 编辑互跳去重：目标页若已在返回栈顶（同一笔记的预览/编辑），
 * 直接回退以复用原页面，避免 E→R→E→R 反复入栈、返回时需按多次返回键。
 * 同样对来源页做 RESUMED 守护：快速双击互跳按钮时第二击落在残留 UI 上，
 * 若放行会误把去重条件判为不满足而重复入栈。
 * 注意：NavType.StringType 读取参数时已自动解码，可直接与原始路径比较。
 */
private fun NavHostController.navigateOrBack(
    from: NavBackStackEntry,
    targetBase: String,
    path: String,
    route: String
) {
    if (!from.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
    val prev = previousBackStackEntry
    if (prev != null &&
        prev.destination.route?.startsWith("$targetBase/") == true &&
        prev.arguments?.getString("path") == path
    ) {
        popBackStack()
    } else {
        navigate(route)
    }
}
