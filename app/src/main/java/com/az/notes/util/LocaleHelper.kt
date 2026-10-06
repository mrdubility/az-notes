package com.az.notes.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用语言应用器。DataStore 读取是异步的，而 Activity 构造 Context 时必须同步
 * 得知语言，因此设置页切换语言时把语言标签双写到 SharedPreferences 镜像；
 * [wrap] 在 attachBaseContext 阶段按镜像覆盖 locales，切换后由设置页 recreate()
 * 重建 Activity 生效。“跟随系统”即清除镜像键。
 */
object LocaleHelper {

    private const val PREFS = "az_notes_locale"
    private const val KEY = "language_tag"

    /** 读取镜像语言标签（null = 跟随系统）。 */
    fun languageTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    /** 写入 / 清除镜像（null = 跟随系统）。 */
    fun persist(context: Context, tag: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (tag == null) editor.remove(KEY) else editor.putString(KEY, tag)
        editor.apply()
    }

    /** 覆盖 base context 的 locales；跟随系统时原样返回。 */
    fun wrap(context: Context): Context {
        val tag = languageTag(context) ?: return context
        val locale = Locale(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
