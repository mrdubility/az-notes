package com.az.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.ui.MainViewModel
import com.az.notes.ui.navigation.AzNotesNavHost
import com.az.notes.ui.theme.AzNotesTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一 Activity（§5.7：单 Activity + Compose Navigation）。
 * 主题三态切换走 state 驱动、不重建 Activity，避免阅读位置丢失（§5.6）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        handleShareIntent(intent)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            AzNotesTheme(settings = settings) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AzNotesNavHost(mainViewModel = mainViewModel)
                }
            }
        }
    }

    /** 已在前台时收到新的分享 / 处理文字请求（launchMode="singleTask"），复用实例不重建。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * 系统分享（ACTION_SEND）与文本选择菜单的“处理文字”（ACTION_PROCESS_TEXT）入口：
     * 提取纯文本交给主页新建笔记并进入编辑页；其它 Intent 忽略。
     */
    private fun handleShareIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND ->
                if (intent.type?.startsWith("text/") == true) {
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                } else null
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!text.isNullOrBlank()) mainViewModel.setSharedText(text)
    }
}
