package com.wakeup.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wakeup.data.remote.WakeupApiFactory
import com.wakeup.data.remote.ensureTrailingSlash
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/** 主题模式。 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** 用户设置快照。 */
data class UserSettings(
    /**
     * 课表来源。两种写法都支持：
     * - 完整链接：`https://<教务域名>/user/kb/table/token/<token>`
     * - 仅 token：`100000`（会用 [baseUrl] 自动补全路径）
     *
     * 因为是完整链接，换成其它学校/其它域名的课表页也能直接用。
     * 默认为空：App 不绑定任何一份具体课表，首次使用由用户自己填。
     */
    val timetableUrl: String = "",
    /** 只填 token 时用来补全链接的服务地址。 */
    val baseUrl: String = WakeupApiFactory.DEFAULT_BASE_URL,
    /** 是否使用 Android 12+ 的动态取色。 */
    val dynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 课表底部隐藏"未排课"课程。 */
    val hideUnscheduled: Boolean = false,
    /** 上课前提醒（预留，功能后续接入）。 */
    val reminderEnabled: Boolean = false,
    val reminderMinutes: Int = 15,
) {
    /** 是否已经配置过课表来源。 */
    val hasSource: Boolean get() = timetableUrl.isNotBlank()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wakeup_settings")

/** 基于 DataStore 的设置读写。 */
class SettingsRepository(private val context: Context) {

    private val _cacheRevision = MutableStateFlow(0)

    /** 本地缓存发生变化时自增，用于让界面刷新缓存占用显示。 */
    val cacheRevision: StateFlow<Int> = _cacheRevision.asStateFlow()

    /** 标记缓存已变化。 */
    fun notifyCacheChanged() {
        _cacheRevision.update { it + 1 }
    }

    val settings: Flow<UserSettings> = context.dataStore.data
        .onStart { migrateLegacyToken() }
        .map { preferences ->
            UserSettings(
                timetableUrl = preferences[KEY_TIMETABLE_URL].orEmpty(),
                baseUrl = preferences[KEY_BASE_URL] ?: WakeupApiFactory.DEFAULT_BASE_URL,
                dynamicColor = preferences[KEY_DYNAMIC_COLOR] ?: true,
                themeMode = ThemeMode.fromName(preferences[KEY_THEME_MODE]),
                hideUnscheduled = preferences[KEY_HIDE_UNSCHEDULED] ?: false,
                reminderEnabled = preferences[KEY_REMINDER_ENABLED] ?: false,
                reminderMinutes = (preferences[KEY_REMINDER_MINUTES] ?: DEFAULT_REMINDER_MINUTES.toLong()).toInt(),
            )
        }

    /**
     * 迁移旧版本单独保存的 token，并把课表链接初始化为空串，
     * 这样「已配置过 / 没配置过」可以被明确区分出来。
     */
    private suspend fun migrateLegacyToken() {
        context.dataStore.edit { preferences ->
            val legacy = preferences[KEY_LEGACY_TOKEN]
            if (legacy != null && preferences[KEY_TIMETABLE_URL] == null) {
                preferences[KEY_TIMETABLE_URL] = tokenToUrl(legacy)
            } else if (preferences[KEY_TIMETABLE_URL] == null) {
                preferences[KEY_TIMETABLE_URL] = ""
            }
        }
    }

    suspend fun setTimetableUrl(url: String) = edit { it[KEY_TIMETABLE_URL] = url.trim() }

    suspend fun setBaseUrl(baseUrl: String) = edit { it[KEY_BASE_URL] = baseUrl.trim() }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME_MODE] = mode.name }

    suspend fun setHideUnscheduled(hidden: Boolean) = edit { it[KEY_HIDE_UNSCHEDULED] = hidden }

    suspend fun setReminderEnabled(enabled: Boolean) = edit { it[KEY_REMINDER_ENABLED] = enabled }

    suspend fun setReminderMinutes(minutes: Int) = edit { it[KEY_REMINDER_MINUTES] = minutes.toLong() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun tokenToUrl(token: String): String =
        "${WakeupApiFactory.DEFAULT_BASE_URL.ensureTrailingSlash()}user/kb/table/token/${token.trim('/')}"

    private companion object {
        val KEY_TIMETABLE_URL = stringPreferencesKey("timetable_url")
        val KEY_LEGACY_TOKEN = stringPreferencesKey("token")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_HIDE_UNSCHEDULED = booleanPreferencesKey("hide_unscheduled")
        val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val KEY_REMINDER_MINUTES = longPreferencesKey("reminder_minutes")
        const val DEFAULT_REMINDER_MINUTES = 15
    }
}
