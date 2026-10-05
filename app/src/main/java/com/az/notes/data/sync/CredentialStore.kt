package com.az.notes.data.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV 凭据加密存储（§6.6）：密码用 [EncryptedSharedPreferences]
 * （AES256-GCM，密钥存 Android Keystore），绝不落明文 / 不入日志。
 * Keystore 异常（如云备份恢复后密钥失配）时降级为空密码，避免崩溃。
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val PREFS_NAME = "az_notes_secure"
        const val KEY_PASSWORD = "webdav_password"
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

    fun getPassword(): String = prefs()?.getString(KEY_PASSWORD, "").orEmpty()

    fun setPassword(password: String) {
        prefs()?.edit()?.putString(KEY_PASSWORD, password)?.apply()
    }
}
