package com.az.notes.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 轻量加密键值存储（§6.6 / §11）：值经 AES-256-GCM 加密后以 Base64 落盘普通
 * SharedPreferences，密钥由 Android Keystore 托管（[keyAlias]，不可导出、绑定本机）。
 * 替代 androidx security-crypto（Tink，约 1,800 类），安全语义不变：
 * 绝不落明文 / 不入日志 / 不进云备份。
 *
 * 兼容性：与旧版 EncryptedSharedPreferences 的落盘格式不互通。首次构造时一次性清空
 * 同名 prefs（旧 Tink 密文与密文键名均不可解），并删除旧共享主密钥别名；密码 /
 * API Key 需用户重新输入（已确认的产品决策）。
 *
 * 失败降级：密钥失配 / 密文损坏 / Keystore 异常时读写按「无值」处理，不崩溃。
 */
class SecurePreferences(
    context: Context,
    prefsName: String,
    private val keyAlias: String
) {
    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM 推荐 IV 长度；Android Keystore AES/GCM 加密时自动生成的 IV 恒为 12 字节。 */
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128

        /** 本实现落盘格式的哨兵键（旧版数据无此键 → 触发一次性清空）。 */
        const val SCHEMA_KEY = "secure_schema_v2"

        /** androidx MasterKey.DEFAULT_MASTER_KEY_ALIAS：旧版 webdav / ai 两处 Store 共享的主密钥别名。 */
        const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    init {
        // 一次性迁移清理：旧版 security-crypto 的键名与值均为 Tink 密文，本实现读不出，
        // 也不应继续保留；其共享主密钥条目一并删除（幂等，两处 Store 各自触发一次）。
        if (!prefs.getBoolean(SCHEMA_KEY, false)) {
            prefs.edit().clear().putBoolean(SCHEMA_KEY, true).apply()
            runCatching {
                KeyStore.getInstance(KEYSTORE).apply { load(null) }
                    .deleteEntry(LEGACY_MASTER_KEY_ALIAS)
            }
        }
    }

    /** 读取并解密；键不存在或解密失败 → [defValue]。 */
    fun getString(key: String, defValue: String): String {
        val stored = prefs.getString(key, null) ?: return defValue
        return runCatching { decrypt(stored) }.getOrDefault(defValue)
    }

    /** 加密并落盘；加密失败（Keystore 异常）时静默丢弃写入（绝不降级为明文）。 */
    fun putString(key: String, value: String) {
        val encrypted = runCatching { encrypt(value) }.getOrNull() ?: return
        prefs.edit().putString(key, encrypted).apply()
    }

    /** 移除条目。 */
    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** 加密：输出 Base64(IV ‖ 密文+tag)。 */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    /** 解密：前 [IV_BYTES] 字节为 IV，其余为密文（GCM tag 含在密文尾部）。 */
    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        require(raw.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_BYTES)
        )
        val body = cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
        return String(body, Charsets.UTF_8)
    }

    /** 取（或首次生成）Keystore 中的 AES-256 密钥；密钥材料不落磁盘。 */
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
