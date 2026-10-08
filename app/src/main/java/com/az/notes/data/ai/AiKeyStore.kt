package com.az.notes.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 供应商 API Key 加密存储（§11）：完全复用 CredentialStore 模式
 * （EncryptedSharedPreferences + AES256-GCM，密钥存 Android Keystore），
 * 绝不落明文 / 不入日志 / 不入配置备份 JSON（对齐「密码永不导出」先例）。
 * Keystore 异常（如云备份恢复后密钥失配）时降级为不可用，避免崩溃。
 *
 * 键为「ai_key_<providerId>」：供应商配置存 DataStore，Key 单独加密存储。
 */
@Singleton
class AiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val PREFS_NAME = "az_notes_ai_secure"

        fun keyOf(providerId: String) = "ai_key_$providerId"
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

    /** 读取指定供应商的 API Key（未设置 → 空串）。 */
    suspend fun getKey(providerId: String): String = withContext(Dispatchers.IO) {
        prefs()?.getString(keyOf(providerId), "").orEmpty()
    }

    /** 写入指定供应商的 API Key（去除首尾空白）。 */
    suspend fun setKey(providerId: String, key: String) = withContext(Dispatchers.IO) {
        prefs()?.edit()?.putString(keyOf(providerId), key.trim())?.apply()
    }

    /** 清除指定供应商的 API Key（删除供应商时调用）。 */
    suspend fun clearKey(providerId: String) = withContext(Dispatchers.IO) {
        prefs()?.edit()?.remove(keyOf(providerId))?.apply()
    }
}
