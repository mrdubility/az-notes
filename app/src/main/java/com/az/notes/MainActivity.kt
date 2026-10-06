package com.az.notes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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
import com.az.notes.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一 Activity（§5.7：单 Activity + Compose Navigation）。
 * 主题三态切换走 state 驱动、不重建 Activity，避免阅读位置丢失（§5.6）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    /**
     * 应用语言必须在本 Activity 构造 Context 时同步生效（DataStore 是异步的），
     * 因此按 SharedPreferences 镜像覆盖 locales；设置页切换语言后 recreate() 生效。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        // 设置回流完成前保持系统启动画面（最多 2.5s 兜底），
        // 避免已配置 Vault 时先闪一帧引导页
        val splashWaitStart = SystemClock.uptimeMillis()
        splashScreen.setKeepOnScreenCondition {
            !mainViewModel.ready.value &&
                SystemClock.uptimeMillis() - splashWaitStart < SPLASH_MAX_WAIT_MS
        }
        super.onCreate(savedInstanceState)

        // 仅在全新启动时处理分享 intent：配置变化 / 进程重建时 savedInstanceState 非空，
        // 跳过可避免分享内容重放（重复建笔记并跳进编辑页）
        if (savedInstanceState == null) handleShareIntent(intent)

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
        if (!text.isNullOrBlank()) {
            mainViewModel.setSharedText(text)
            // 消费后清掉启动 intent：Activity 重建时 getIntent() 不再包含分享文本
            setIntent(Intent())
        }
    }

    private companion object {
        /** 启动画面等待设置回流的上限（毫秒）；DataStore 正常仅需数毫秒。 */
        const val SPLASH_MAX_WAIT_MS = 2500L
    }
}
