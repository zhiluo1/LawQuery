package com.lawquery.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 深色模式偏好取值(需求 F6):跟随系统 / 浅色 / 深色 */
object DarkModePref {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/**
 * 设置存储(项目文档 4.5):字号档位、免责声明已读标记。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val FONT_SIZE_INDEX = intPreferencesKey("font_size_index")
        val DISCLAIMER_ACK = booleanPreferencesKey("disclaimer_ack")
        val FLK_DIRECT_NOTICE_ACK = booleanPreferencesKey("flk_direct_notice_ack")
        val DARK_MODE = intPreferencesKey("dark_mode")
        val LINE_SPACING_INDEX = intPreferencesKey("line_spacing_index")
        /** 人民法院案例库「已对接」标记(仅布尔,不含任何会话凭据) */
        val ALK_LINKED = booleanPreferencesKey("alk_linked")

    }

    /** 字号档位 0..4(共 5 档,需求 F3/F6),全局记忆 */
    val fontSizeIndex: Flow<Int> = context.dataStore.data.map { it[Keys.FONT_SIZE_INDEX] ?: 2 }

    /** 免责声明首次启动弹窗(需求 7.2.7 / E4) */
    val disclaimerAcknowledged: Flow<Boolean> = context.dataStore.data.map { it[Keys.DISCLAIMER_ACK] ?: false }

    /**
     * 深色模式(需求 F6):0=跟随系统 / 1=浅色 / 2=深色。
     * 全局生效:主题在 Activity 根部按此值即时切换,并同步系统栏对比度与窗口背景。
     */
    val darkMode: Flow<Int> = context.dataStore.data.map { it[Keys.DARK_MODE] ?: 0 }

    suspend fun setFontSizeIndex(index: Int) {
        context.dataStore.edit { it[Keys.FONT_SIZE_INDEX] = index.coerceIn(0, 4) }
    }

    suspend fun acknowledgeDisclaimer() {
        context.dataStore.edit { it[Keys.DISCLAIMER_ACK] = true }
    }

    suspend fun setDarkMode(mode: Int) {
        context.dataStore.edit { it[Keys.DARK_MODE] = mode.coerceIn(0, 2) }
    }

    /** 行间距档位 0..2(紧凑/适中/宽松),对应正文行高系数 1.5/1.7/1.9,全局记忆 */
    val lineSpacingIndex: Flow<Int> = context.dataStore.data.map { it[Keys.LINE_SPACING_INDEX] ?: 1 }

    suspend fun setLineSpacingIndex(index: Int) {
        context.dataStore.edit { it[Keys.LINE_SPACING_INDEX] = index.coerceIn(0, 2) }
    }

    /**
     * 人民法院案例库「已对接」标记:用户完成一次官方登录后置位(数据源管理据此展示状态)。
     * 只存布尔,不存会话票据 —— 票据由 AlkSession 仅驻内存。
     */
    val alkLinked: Flow<Boolean> = context.dataStore.data.map { it[Keys.ALK_LINKED] ?: false }

    suspend fun setAlkLinked(linked: Boolean) {
        context.dataStore.edit { it[Keys.ALK_LINKED] = linked }
    }

}
