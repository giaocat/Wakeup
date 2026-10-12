package com.wakeup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wakeup.AppContainer
import com.wakeup.data.remote.SyncResult
import com.wakeup.data.repository.ThemeMode
import com.wakeup.data.repository.UserSettings
import com.wakeup.data.update.UpdateChecker
import com.wakeup.data.update.UpdateInfo
import com.wakeup.data.update.UpdateResult
import kotlinx.coroutines.flow.first
import com.wakeup.domain.Course
import com.wakeup.domain.Timetable
import com.wakeup.domain.TimetableCourse
import com.wakeup.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 课表页状态。 */
data class ScheduleUiState(
    val timetable: Timetable = Timetable.empty(),
    /** 当前正在查看的周次。 */
    val selectedWeek: Int = 1,
    /** 当前真实周次；不在学期内时为 null。 */
    val currentWeek: Int? = null,
    val today: LocalDate = TimeUtils.today(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** 最近一次同步成功时间（毫秒），来自本地缓存或网络。 */
    val lastSyncedAt: Long = 0L,
    val userName: String? = null,
    /** 当前展示的数据来自本地缓存（而不是本次网络同步）。 */
    val isFromCache: Boolean = false,
    /** 是否已经配置课表来源。 */
    val hasSource: Boolean = true,
    /** 是否隐藏「未排课」课程（来自设置）。 */
    val hideUnscheduled: Boolean = false,
    /** 数据版本号，数据变化时自增，方便 UI 判断刷新。 */
    val dataRevision: Int = 0,
) {
    val isTermActive: Boolean get() = currentWeek != null

    val hasData: Boolean get() = timetable.courses.isNotEmpty()

    /** 当前查看周次的全部课程格（同一格可能包含多门冲突课程）。 */
    val weekCells: Map<String, List<Course>> get() = timetable.cellsOfWeek(selectedWeek)

    /** 当前查看周次、今天那一天的课程。 */
    val todayCourses: List<Course>
        get() = timetable.slotsOfDay(selectedWeek, today.dayOfWeek.value)

    /** 未排课课程；按设置隐藏时返回空列表。 */
    val unscheduledCourses: List<TimetableCourse>
        get() = if (hideUnscheduled) emptyList() else timetable.unscheduled

    /** 离线/失败提示；没有需要提示的内容时返回 null。 */
    val offlineNotice: String?
        get() {
            if (!hasData) return null
            val error = errorMessage
            return when {
                error != null -> "同步失败：$error（当前显示本地缓存）"
                isFromCache -> "当前显示本地缓存，联网后会自动更新"
                else -> null
            }
        }
}

/** 「检查更新」的界面状态。 */
data class UpdateUiState(
    val checking: Boolean = false,
    /** 发现的新版本；没有则为 null。 */
    val info: UpdateInfo? = null,
    /** 检查失败的原因（用于设置页提示）。 */
    val error: String? = null,
    /** 已经检查过（避免用户关掉弹窗后又自动弹出）。 */
    val checked: Boolean = false,
) {
    val available: Boolean get() = info != null
}

/**
 * 课表 / 设置 / 主题的统一 ViewModel。
 */
class WakeupViewModel(
    private val container: AppContainer,
    private val updateChecker: UpdateChecker = UpdateChecker(),
) : ViewModel() {

    val settings: StateFlow<UserSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    private val _schedule = MutableStateFlow(ScheduleUiState())
    val schedule: StateFlow<ScheduleUiState> = _schedule.asStateFlow()

    /** 缓存占用（字节），供设置页展示。 */
    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _update = MutableStateFlow(UpdateUiState())
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()

    init {
        var started = false
        // 设置首次到达后：用本地缓存铺数据，然后联网同步。
        viewModelScope.launch {
            settings.collect { latest ->
                _schedule.update { it.copy(hasSource = latest.hasSource, hideUnscheduled = latest.hideUnscheduled) }
                if (!started) {
                    started = true
                    restoreFromCache(latest.timetableUrl)
                    sync()
                }
            }
        }
        // 缓存变化时刷新占用显示。
        viewModelScope.launch {
            container.settingsRepository.cacheRevision.collect {
                _cacheSizeBytes.value = container.timetableRepository.cacheSizeBytes()
            }
        }
    }

    /** 从磁盘缓存恢复上一次的课表。 */
    private suspend fun restoreFromCache(source: String) {
        if (!container.timetableRepository.restoreFromCache(source)) return
        val data = container.timetableRepository.cachedTimetable() ?: return
        _schedule.update { state ->
            val currentWeek = data.term.weekOf(TimeUtils.today())
            state.copy(
                timetable = data,
                selectedWeek = currentWeek ?: state.selectedWeek,
                currentWeek = currentWeek,
                today = TimeUtils.today(),
                isFromCache = true,
                lastSyncedAt = container.timetableRepository.lastSyncedAt,
                userName = container.timetableRepository.lastUser,
                dataRevision = state.dataRevision + 1,
            )
        }
        _cacheSizeBytes.value = container.timetableRepository.cacheSizeBytes()
    }

    /** 拉取最新课表。 */
    fun sync() {
        if (_schedule.value.isLoading) return
        _schedule.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val current = settings.value
            if (!current.hasSource) {
                _schedule.update {
                    it.copy(
                        isLoading = false,
                        isFromCache = false,
                        errorMessage = null,
                    )
                }
                return@launch
            }

            val hadDataBefore = _schedule.value.hasData
            val result = container.timetableRepository.refresh(
                source = current.timetableUrl,
                baseUrl = current.baseUrl,
            )

            _schedule.update { state ->
                when (result) {
                    is SyncResult.Success -> {
                        val timetable = container.timetableRepository.cachedTimetable() ?: state.timetable
                        val currentWeek = timetable.term.weekOf(TimeUtils.today())
                        val selected = if (hadDataBefore) state.selectedWeek else (currentWeek ?: 1)
                        state.copy(
                            timetable = timetable,
                            selectedWeek = selected.coerceIn(1, timetable.term.totalWeeks.coerceAtLeast(1)),
                            currentWeek = currentWeek,
                            today = TimeUtils.today(),
                            isLoading = false,
                            errorMessage = null,
                            lastSyncedAt = container.timetableRepository.lastSyncedAt,
                            userName = container.timetableRepository.lastUser,
                            isFromCache = false,
                            dataRevision = state.dataRevision + 1,
                        )
                    }

                    is SyncResult.Failure -> state.copy(
                        isLoading = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun selectWeek(week: Int) {
        _schedule.update { state ->
            val max = state.timetable.term.totalWeeks.coerceAtLeast(1)
            state.copy(selectedWeek = week.coerceIn(1, max))
        }
    }

    fun previousWeek() = selectWeek(_schedule.value.selectedWeek - 1)

    fun nextWeek() = selectWeek(_schedule.value.selectedWeek + 1)

    fun dismissError() {
        _schedule.update { it.copy(errorMessage = null) }
    }

    // ---- 设置项 ----

    /** 修改课表链接（完整链接或 token），并立即重新同步。 */
    fun updateTimetableUrl(url: String) {
        viewModelScope.launch {
            val normalized = url.trim()
            if (normalized == settings.value.timetableUrl) {
                sync()
                return@launch
            }
            container.settingsRepository.setTimetableUrl(normalized)
            // 旧缓存属于上一个来源，不再展示；新链接同步成功后会自动覆盖缓存
            _schedule.update {
                it.copy(
                    timetable = Timetable.empty(),
                    isFromCache = false,
                    lastSyncedAt = 0L,
                    userName = null,
                    errorMessage = null,
                    dataRevision = it.dataRevision + 1,
                )
            }
            sync()
        }
    }

    fun updateBaseUrl(baseUrl: String) {
        viewModelScope.launch {
            container.settingsRepository.setBaseUrl(baseUrl)
            sync()
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setDynamicColor(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { container.settingsRepository.setThemeMode(mode) }
    }

    fun setHideUnscheduled(hidden: Boolean) {
        viewModelScope.launch { container.settingsRepository.setHideUnscheduled(hidden) }
    }

    /** 清空本地缓存。 */
    fun clearCache() {
        viewModelScope.launch {
            container.timetableRepository.clearCache()
            _schedule.update {
                it.copy(
                    timetable = Timetable.empty(),
                    isFromCache = false,
                    lastSyncedAt = 0L,
                    userName = null,
                    errorMessage = null,
                    dataRevision = it.dataRevision + 1,
                )
            }
        }
    }

    /** 缓存占用自动跟随 `cacheRevision` 刷新，无需额外接口。 */

    // ---- 检查更新 ----

    /**
     * 检查是否有新版本。
     *
     * [auto] 为 true 时表示是启动自动检查：只提示一次，用户关掉后不再弹出。
     */
    fun checkForUpdate(auto: Boolean = false) {
        if (_update.value.checking) return
        if (auto && _update.value.checked) return
        _update.update { it.copy(checking = true, error = null, checked = true) }
        viewModelScope.launch {
            if (auto) {
                val lastCheck = container.settingsRepository.lastUpdateCheckAt.first()
                val elapsed = System.currentTimeMillis() - lastCheck
                if (lastCheck > 0L && elapsed in 0 until AUTO_CHECK_COOLDOWN_MS) {
                    // 还没到冷却时间，跳过请求，避免撞匿名限流
                    _update.update { it.copy(checking = false) }
                    return@launch
                }
            }

            when (val result = updateChecker.check()) {
                is UpdateResult.Available -> {
                    container.settingsRepository.setLastUpdateCheckAt(System.currentTimeMillis())
                    _update.update { it.copy(checking = false, info = result.info, error = null) }
                }

                is UpdateResult.UpToDate -> {
                    container.settingsRepository.setLastUpdateCheckAt(System.currentTimeMillis())
                    _update.update { it.copy(checking = false, info = null, error = null) }
                }

                is UpdateResult.Failed -> _update.update {
                    it.copy(checking = false, info = null, error = result.message)
                }
            }
        }
    }

    /** 用户关掉更新弹窗。 */
    fun dismissUpdate() {
        _update.update { it.copy(info = null) }
    }

    companion object {
        /**
         * 自动检查更新的冷却时间：一天。
         *
         * GitHub Releases 是匿名调用（60 次/小时/IP），启动就查会被限流，
         * 所以自动检查一天最多一次；用户手动点「检查」不受此限制。
         */
        const val AUTO_CHECK_COOLDOWN_MS: Long = 24L * 60 * 60 * 1000
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(WakeupViewModel::class.java)) {
                        "无法创建 ViewModel：${modelClass.name}"
                    }
                    return WakeupViewModel(container) as T
                }
            }
    }
}
