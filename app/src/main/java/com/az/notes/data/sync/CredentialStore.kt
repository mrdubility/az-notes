package com.az.notes.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.az.notes.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * WebDAV 凭据加密存储（§6.6）：密码用 [EncryptedSharedPreferences]
 * （AES256-GCM，密钥存 Android Keystore），绝不落明文 / 不入日志。
 * Keystore 异常（如云备份恢复后密钥失配）时降级为空密码，避免崩溃。
 *
 * 多仓库：密码按仓库 id 存「webdav_password_<id>」，跟随当前仓库切换。
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private companion object {
        const val PREFS_NAME = "az_notes_secure"

        fun keyOf(vaultId: String) = "webdav_password_$vaultId"
    }

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(): SharedPreferences? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val created = runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrNull()
            cached = created
            return created
        }
    }

    /** 读取当前仓库的密码（无仓库 / 未设置 → 空串）。 */
    suspend fun getPassword(): String {
        val vaultId = settingsRepository.settings.first().currentVaultId ?: return ""
        return withContext(Dispatchers.IO) {
            prefs()?.getString(keyOf(vaultId), "").orEmpty()
        }
    }

    /** 写入当前仓库的密码（无仓库时忽略）。 */
    suspend fun setPassword(password: String) {
        val vaultId = settingsRepository.settings.first().currentVaultId ?: return
        withContext(Dispatchers.IO) {
            prefs()?.edit()?.putString(keyOf(vaultId), password)?.apply()
        }
    }

    /** 清除指定仓库的密码（移除仓库时调用；不影响其它仓库）。 */
    suspend fun clearPassword(vaultId: String) {
        withContext(Dispatchers.IO) {
            prefs()?.edit()?.remove(keyOf(vaultId))?.apply()
        }
    }
}
