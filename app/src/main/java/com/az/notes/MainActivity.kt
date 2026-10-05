package com.az.notes

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
}
