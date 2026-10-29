package com.wakeup.wear

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.wakeup.sync.TimetableSyncPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wakeup.AppContainer
import com.wakeup.data.remote.SyncResult
import com.wakeup.data.remote.WakeupApiFactory
import com.wakeup.data.remote.tokenToTimetableUrl
import com.wakeup.data.repository.TimetableSourceKind
import com.wakeup.data.repository.UserSettings
import com.wakeup.domain.Course
import com.wakeup.domain.Timetable
import com.wakeup.sync.TimetableSyncBus
import com.wakeup.wear.complication.ComplicationRefresher
import com.wakeup.wear.tile.TileRefresher
import com.wakeup.util.TimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** 手表端界面状态。字段刻意比手机端少：表盘上只够看"哪天、哪几节、什么课"。 */
data class WearUiState(
    val timetable: Timetable = Timetable.empty(),
    /** 当前展示的周次。 */
    val week: Int = 1,
    val today: LocalDate = TimeUtils.today(),
    /** 当前展示的星期：1=周一 … 7=周日。 */
    val day: Int = TimeUtils.today().dayOfWeek.value,
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastSyncedAt: Long = 0L,
    /** 是否已配置课表来源。 */
    val hasSource: Boolean = true,
    /** 当前配置的课表链接（手表端只回显 token）。 */
    val sourceUrl: String = "",
    /** 「从手机同步」的状态文案（空串表示没操作过）。 */
    val phoneSyncStatus: String = "",
    /** null=进行中/未知，true=成功，false=失败。 */
    val phoneSyncOk: Boolean? = null,
) {
    val totalWeeks: Int get() = timetable.term.totalWeeks.coerceAtLeast(1)

    /** 当前真实周次；不在学期内时为 null。 */
    val currentWeek: Int? get() = timetable.term.weekOf(today)

    val isCurrentWeek: Boolean get() = currentWeek == week

    val hasData: Boolean get() = timetable.courses.isNotEmpty()

    fun coursesOf(day: Int): List<Course> = timetable.slotsOfDay(week, day)

    fun dateOf(day: Int): LocalDate = timetable.term.dateOf(week, day)
}

/**
 * 手表端 ViewModel。
 *
 * 数据层与手机端**完全共用**（`:core` 里的 settingsRepository / timetableRepository），
 * 这里只做手表需要的裁剪：没有主题、更新检查、Smartspacer，只有"周次 + 星期 + 课程"。
 */
class WearViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(WearUiState())
    val state: StateFlow<WearUiState> = _state.asStateFlow()

    init {
        var started = false
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _state.update {
                    it.copy(hasSource = settings.hasSource, sourceUrl = settings.timetableUrl)
                }
                if (!started) {
                    started = true
                    bootstrap(settings)
                }
            }
        }
        // 跨天 / 跨周：表盘上的"今天"不能停在打开应用那天
        viewModelScope.launch {
            while (true) {
                delay(CLOCK_TICK_MS)
                refreshClock()
            }
        }
        // 手机端推来了课表：直接换成本地缓存里的那份，不用等手表自己联网。
        // （真正写缓存的是 WatchTimetableSyncService，这里只负责把界面刷新过来。）
        viewModelScope.launch {
            TimetableSyncBus.events.collect {
                val timetable = container.timetableRepository.cachedTimetable() ?: return@collect
                // 用户正在翻别的周次时不要把他拽回本周
                val keepWeek = _state.value.week.takeIf { _state.value.currentWeek != it }
                applyTimetable(timetable)
                if (keepWeek != null) {
                    _state.update { state -> state.copy(week = keepWeek.coerceIn(1, state.totalWeeks)) }
                }
                // 手机端可能顺带把 token 也同步过来了
                val settings = container.settingsRepository.settings.first()
                _state.update {
                    it.copy(hasSource = settings.hasSource, sourceUrl = settings.timetableUrl)
                }
            }
        }
    }

    /** 先用缓存铺一屏；缓存够新就不联网，省手表电。 */
    private suspend fun bootstrap(settings: UserSettings) {
        if (!settings.hasSource) return
        val restored = container.timetableRepository.restoreFromCacheNow(settings.timetableUrl)
        if (restored) {
            container.timetableRepository.cachedTimetable()?.let { applyTimetable(it) }
        }
        // 手表电池比手机金贵：启动时如果拿到的缓存还在 [CACHE_FRESH_MS] 内，就不发网络请求，
        // 等用户手动刷新、或手机端推来新课表（Data Layer）再说。
        val cachedAt = container.timetableRepository.lastSyncedAt
        val fresh = restored && cachedAt > 0L &&
            System.currentTimeMillis() - cachedAt < CACHE_FRESH_MS
        if (!fresh) syncWith(settings)
    }

    /** 手动刷新（设置页 / 下拉重试）。 */
    fun refresh() {
        if (_state.value.isLoading) return
        viewModelScope.launch { syncWith(container.settingsRepository.settings.first()) }
    }

    private suspend fun syncWith(settings: UserSettings) {
        // 手表上没有浏览器，登录不了新教务系统：这类数据一律由手机同步后推过来。
        if (settings.sourceKind == TimetableSourceKind.ZF_LOGIN) {
            _state.update {
                it.copy(
                    isLoading = false,
                    error = if (it.hasData) null else "课表由手机同步：请在手机上登录教务系统并同步一次",
                )
            }
            return
        }
        if (!settings.hasSource) {
            _state.update { it.copy(isLoading = false, error = null) }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        when (val result = container.timetableRepository.refresh(settings.timetableUrl, settings.baseUrl)) {
            is SyncResult.Success -> {
                val timetable = container.timetableRepository.cachedTimetable()
                if (timetable == null) {
                    _state.update { it.copy(isLoading = false, error = "课表数据无法识别") }
                } else {
                    applyTimetable(timetable)
                    // 表盘复杂功能 / 信息块也跟着换数据
                    refreshWearSurfaces()
                }
            }

            is SyncResult.Failure -> _state.update {
                it.copy(isLoading = false, error = result.message)
            }
        }
    }

    private fun applyTimetable(timetable: Timetable) {
        val today = TimeUtils.today()
        val currentWeek = timetable.term.weekOf(today)
        _state.update { state ->
            state.copy(
                timetable = timetable,
                week = (currentWeek ?: state.week).coerceIn(1, timetable.term.totalWeeks.coerceAtLeast(1)),
                today = today,
                day = state.day.coerceIn(1, 7),
                isLoading = false,
                error = null,
                lastSyncedAt = container.timetableRepository.lastSyncedAt,
            )
        }
    }

    private fun refreshClock() {
        val today = TimeUtils.today()
        if (_state.value.today == today) return
        _state.update { state ->
            state.copy(
                today = today,
                // 只有正在看"当前这一周"时才跟着日期跳到今天那一天
                day = if (state.timetable.term.weekOf(today) == state.week) {
                    today.dayOfWeek.value
                } else {
                    state.day
                },
            )
        }
    }

    fun selectWeek(week: Int) {
        _state.update { it.copy(week = week.coerceIn(1, it.totalWeeks)) }
    }

    fun selectDay(day: Int) {
        _state.update { it.copy(day = day.coerceIn(1, 7)) }
    }

    /** 手表上没有输入框，只填数字 token；链接由 [tokenToTimetableUrl] 拼出来。 */
    fun saveToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val url = tokenToTimetableUrl(trimmed, WakeupApiFactory.DEFAULT_BASE_URL)
            container.settingsRepository.setTimetableUrl(url)
            // 换了来源，旧缓存不再适用
            container.timetableRepository.clearCache()
            _state.update {
                it.copy(
                    timetable = Timetable.empty(),
                    sourceUrl = url,
                    hasSource = true,
                    error = null,
                    lastSyncedAt = 0L,
                )
            }
            syncWith(
                UserSettings(
                    timetableUrl = url,
                    baseUrl = WakeupApiFactory.DEFAULT_BASE_URL,
                ),
            )
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            container.timetableRepository.clearCache()
            _state.update {
                it.copy(timetable = Timetable.empty(), lastSyncedAt = 0L, error = null)
            }
            // 缓存清掉了，表盘/信息块也该显示"未同步"而不是旧课表
            refreshWearSurfaces()
        }
    }

    /**
     * 刷新手表上的"外壳"数据源：表盘复杂功能与信息块。
     *
     * 系统只认 `UPDATE_PERIOD_SECONDS` / freshness 那套周期，不会知道我们刚换了课表，
     * 所以每次数据变化都主动请求一次；没有把 Wakeup 添加到表盘/信息块时调用是空操作。
     */

    /**
     * 「从手机同步」：给手机发一条请求消息，等它把课表推过来。
     *
     * 手表上没有浏览器、也登录不了教务系统，所以数据只能由手机提供；
     * 这里做三件事：发请求 → 等数据（15 秒）→ 把结果写进 [WearUiState.phoneSyncStatus]。
     */
    fun requestSyncFromPhone(context: Context) {
        if (_state.value.isLoading) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, phoneSyncStatus = "正在请求手机推送…", phoneSyncOk = null) }
            val sent = withContext(Dispatchers.IO) {
                runCatching {
                    val nodes = Tasks.await(
                        Wearable.getNodeClient(appContext).connectedNodes,
                        NODE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    val node = nodes.firstOrNull() ?: return@runCatching false
                    Tasks.await(
                        Wearable.getMessageClient(appContext).sendMessage(
                            node.id,
                            TimetableSyncPaths.REQUEST_SYNC,
                            ByteArray(0),
                        ),
                        NODE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                    )
                    true
                }.getOrDefault(false)
            }
            if (!sent) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        phoneSyncStatus = "没有连上的手机：请确认手机端已安装并已连接",
                        phoneSyncOk = false,
                    )
                }
                return@launch
            }
            val receivedAt = withTimeoutOrNull(SYNC_WAIT_MS) { TimetableSyncBus.events.first() }
            if (receivedAt != null) {
                container.timetableRepository.cachedTimetable()?.let { timetable ->
                    applyTimetable(timetable)
                    refreshWearSurfaces()
                }
                _state.update {
                    it.copy(isLoading = false, phoneSyncStatus = "已从手机同步 ✓", phoneSyncOk = true)
                }
            } else {
                _state.update {
                    it.copy(
                        isLoading = false,
                        phoneSyncStatus = "等待超时：请确认手机已同步过课表、且蓝牙/网络已连接",
                        phoneSyncOk = false,
                    )
                }
            }
        }
    }

    private fun refreshWearSurfaces() {
        val context = container.appContext
        ComplicationRefresher.requestUpdate(context)
        TileRefresher.requestUpdate(context)
    }

    companion object {
        /** 对时周期：一分钟一次，够跨天及时发现，也不明显耗电。 */
        private const val CLOCK_TICK_MS: Long = 60_000L

        /**
         * 启动时认为缓存"还新鲜"的时长。
         *
         * 课表一天最多变一两次，手表每次打开都联网既慢又费电；6 小时内直接用缓存，
         * 需要最新数据可以手动刷新，或者靠手机端同步后推过来。
         */
        private const val CACHE_FRESH_MS: Long = 6L * 60 * 60 * 1000

    /** 找手机 / 发消息的超时。 */
    private const val NODE_TIMEOUT_SECONDS = 8L

    /** 「从手机同步」等待数据的最长时间。 */
    private const val SYNC_WAIT_MS = 15_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(WearViewModel::class.java)) {
                        "无法创建 ViewModel：${modelClass.name}"
                    }
                    return WearViewModel(container) as T
                }
            }
    }
}