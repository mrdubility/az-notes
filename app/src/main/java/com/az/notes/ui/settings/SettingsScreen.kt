package com.az.notes.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.az.notes.R
import com.az.notes.domain.model.FontFamilyPreference
import com.az.notes.domain.model.ThemeMode

/** 设置页（§5.6）：主题 / 字体 / 同步策略入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle(stringResource(R.string.settings_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                        label = { Text(mode.label()) }
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Material You 动态取色")
                Switch(checked = settings.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
            }

            SectionTitle(stringResource(R.string.settings_font_family))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FontFamilyPreference.entries.forEach { fam ->
                    FilterChip(
                        selected = settings.fontFamily == fam,
                        onClick = { viewModel.setFontFamily(fam) },
                        label = { Text(fam.label()) }
                    )
                }
            }

            SectionTitle("${stringResource(R.string.settings_font_size)}：${settings.fontSizeSp.toInt()}sp")
            Slider(
                value = settings.fontSizeSp,
                onValueChange = { viewModel.setFontSize(it) },
                valueRange = 12f..24f,
                steps = 11
            )

            SectionTitle("${stringResource(R.string.settings_line_height)}：%.1f".format(settings.lineHeightRatio))
            Slider(
                value = settings.lineHeightRatio,
                onValueChange = { viewModel.setLineHeight(it) },
                valueRange = 1.2f..2.0f
            )

            SectionTitle("Vault")
            Text(
                settings.vaultPath ?: "未设置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.TextButton(onClick = viewModel::resetVault) {
                    Text("更换目录")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun FontFamilyPreference.label(): String = when (this) {
    FontFamilyPreference.SANS -> "无衬线"
    FontFamilyPreference.SERIF -> "衬线"
    FontFamilyPreference.MONO -> "等宽"
}
