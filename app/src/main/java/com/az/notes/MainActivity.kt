package com.az.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
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

        // 仅在「真正的新启动」时处理分享 intent，避免 task 启动 intent 被重放：
        // 1) 配置变化 / 进程重建：savedInstanceState 非空 → 跳过；
        // 2) 从最近任务恢复：系统重放 task 的启动 intent（如原始 SEND）并附
        //    FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY——分享新建笔记后退出、再从最近
        //    任务进入会重复建笔记，即此路径（旧实现只查 savedInstanceState，拦不住
        //    recents 恢复的 null savedInstanceState）→ 跳过。
        val fromHistory =
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !fromHistory) handleShareIntent(intent)

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
     * 退到后台时冲刷排队中的“保存后同步”：把 30 秒防抖任务提前为立即执行，
     * 缩小用户退出应用后本地变更尚未上传的窗口（详见 SyncScheduler）。
     */
    override fun onStop() {
        super.onStop()
        mainViewModel.flushPendingSync()
    }

    /**
     * 系统分享（ACTION_SEND / ACTION_SEND_MULTIPLE 文本/图片）与文本选择菜单的“处理文字”
     * （ACTION_PROCESS_TEXT）入口：文本交纯文本新建笔记；图片（MIME 以 image 开头）取
     * EXTRA_STREAM（多选取首张）走图片导入建笔记。各分支互斥消费后清掉启动 intent；
     * 其它 Intent 忽略。
     */
    private fun handleShareIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (type.startsWith("image/")) {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        mainViewModel.setSharedImageUri(uri)
                        setIntent(Intent())
                    }
                } else if (type.startsWith("text/")) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        mainViewModel.setSharedText(text)
                        setIntent(Intent())
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // 相册多选分享：取首张走与 SEND 相同的图片导入管线
                if (intent.type.orEmpty().startsWith("image/")) {
                    @Suppress("DEPRECATION")
                    val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    val first = uris?.firstOrNull()
                    if (first != null) {
                        mainViewModel.setSharedImageUri(first)
                        setIntent(Intent())
                    }
                }
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!text.isNullOrBlank()) {
                    mainViewModel.setSharedText(text)
                    setIntent(Intent())
                }
            }
        }
    }

    private companion object {
        /** 启动画面等待设置回流的上限（毫秒）；DataStore 正常仅需数毫秒。 */
        const val SPLASH_MAX_WAIT_MS = 2500L
    }
}
