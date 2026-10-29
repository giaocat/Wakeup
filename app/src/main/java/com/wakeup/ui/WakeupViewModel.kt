package com.wakeup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.wakeup.AppContainer
import com.wakeup.data.remote.SyncResult
import com.wakeup.data.repository.ThemeMode
import com.wakeup.data.repository.TimetableSourceKind
import com.wakeup.data.repository.UserSettings
import com.wakeup.data.export.ImportResult
import com.wakeup.data.session.ZfSession
import com.wakeup.data.update.UpdateChecker
import com.wakeup.data.update.UpdateInfo
import com.wakeup.data.update.UpdateResult
import kotlinx.coroutines.flow.first
import com.wakeup.domain.Course
import com.wakeup.domain.Term
import com.wakeup.domain.Timetable
import com.wakeup.domain.TimetableCourse
import com.wakeup.sync.PhoneTimetableSync
import com.wakeup.util.TimeUtils
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** 课表来源：新教务系统（登录）还是旧分享链接。 */
    val sourceKind: TimetableSourceKind = TimetableSourceKind.ZF_LOGIN,
    /** 需要用户去登录教务系统（会话缺失/过期）；界面据此弹出登录页。 */
    val needsZfLogin: Boolean = false,
    /** 当前是否持有教务系统登录会话。 */
    val zfLoggedIn: Boolean = false,
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
                _schedule.update {
                    it.copy(
                        // 走教务系统登录时不看"token 是否填过"：有会话就能同步
                        hasSource = latest.sourceKind == TimetableSourceKind.ZF_LOGIN || latest.hasSource,
                        hideUnscheduled = latest.hideUnscheduled,
                        sourceKind = latest.sourceKind,
                        zfLoggedIn = container.zfSessionStore.loadNow() != null,
                    )
                }
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
        // 「今天」原先只在同步成功时写一次。App 常驻后台跨天之后，时刻线、今天高亮
        // 和表头都会停在旧日期，所以这里每分钟对一次表，跨天才真正更新状态。
        viewModelScope.launch {
            while (true) {
                delay(WALL_CLOCK_TICK_MS)
                refreshToday()
            }
        }
    }

    /**
     * 跨天时刷新「今天」与当前周次。
     *
     * 只改日期相关的两个字段，不动用户正在查看的周次；日期没变时直接返回，
     * 避免每分钟都触发一次无意义的重组。
     */
    private fun refreshToday() {
        val today = TimeUtils.today()
        if (_schedule.value.today == today) return
        _schedule.update { state ->
            state.copy(
                today = today,
                currentWeek = state.timetable.term.weekOf(today),
            )
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
        val current = settings.value
        // 新教务系统要带登录会话，走另一条链路（缺会话时弹登录页而不是报"网络错误"）
        if (current.sourceKind == TimetableSourceKind.ZF_LOGIN) {
            syncZf(current)
        } else {
            syncShareLink(current)
        }
    }

    /** 旧分享链接（token）的同步；旧接口恢复后仍然可用。 */
    private fun syncShareLink(current: UserSettings) {
        _schedule.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
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
            applySyncResult(result, hadDataBefore)
            pushToWatch(result)
        }
    }

    /**
     * 新教务系统（正方）的同步。
     *
     * 会话存在就直接带 Cookie 抓课表页；会话缺失或过期（服务端把我们重定向回登录页）时
     * 置 `needsZfLogin = true`，由界面弹出 WebView 登录页，用户登录后再继续同步。
     */
    private fun syncZf(current: UserSettings) {
        _schedule.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val session = container.zfSessionStore.loadNow()
            if (session == null || !session.isValid) {
                android.util.Log.w("WakeupSync", "本地没有教务系统会话，弹出登录页")
                _schedule.update {
                    it.copy(isLoading = false, needsZfLogin = true, errorMessage = null, zfLoggedIn = false)
                }
                return@launch
            }

            val hadDataBefore = _schedule.value.hasData
            val result = container.timetableRepository.refreshZf(
                baseUrl = current.zfBaseUrl,
                path = current.zfTimetablePath,
                cookie = session.cookie,
                termStartEpochSeconds = parseTermStart(current.zfTermStartDate),
            )

            if (result is SyncResult.Failure && result.needsLogin) {
                // 会话失效：清掉旧会话并请用户重新登录（不清课表缓存，先让旧课表继续可见）
                android.util.Log.w("WakeupSync", "同步要求重新登录：${result.message}")
                container.zfSessionStore.clear()
                _schedule.update {
                    it.copy(isLoading = false, needsZfLogin = true, zfLoggedIn = false, errorMessage = null)
                }
                return@launch
            }

            applySyncResult(result, hadDataBefore)
            pushToWatch(result)
        }
    }

    /** 把同步结果落到界面状态（两条数据源共用）。 */
    private fun applySyncResult(result: SyncResult, hadDataBefore: Boolean) {
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
                        zfLoggedIn = state.sourceKind == TimetableSourceKind.ZF_LOGIN,
                    )
                }

                is SyncResult.Failure -> state.copy(
                    isLoading = false,
                    errorMessage = result.message,
                )
            }
        }
    }

    /** 同步成功后顺手把手表需要的课表推过去；没配对手表时静默失败。 */
    private fun pushToWatch(result: SyncResult) {
        if (result is SyncResult.Success) {
            viewModelScope.launch { PhoneTimetableSync.push(container.appContext) }
        }
    }

    /** `2026-08-24` → 当天 00:00（学校时区）的秒级时间戳；没填/写错返回 null。 */
    private fun parseTermStart(date: String): Long? = runCatching {
        LocalDate.parse(date.trim()).atStartOfDay(Term.TERM_ZONE).toEpochSecond()
    }.getOrNull()

    /**
     * 用户在应用内登录成功后调用（[cookie] 来自 WebView 的 CookieManager）。
     *
     * 保存会话后立刻同步一次；失败会按普通错误显示，不会循环弹登录页。
     */
    fun onZfLoggedIn(cookie: String) {
        val trimmed = cookie.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            container.zfSessionStore.save(
                ZfSession(
                    cookie = trimmed,
                    savedAt = System.currentTimeMillis(),
                    baseUrl = settings.value.zfBaseUrl,
                ),
            )
            _schedule.update { it.copy(needsZfLogin = false, zfLoggedIn = true) }
            sync()
        }
    }

    /** 用户关掉登录页（不登录也允许继续看旧缓存）。 */
    fun dismissZfLogin() {
        _schedule.update { it.copy(needsZfLogin = false) }
    }

    /** 手动打开登录页（设置页里点「登录」）。 */
    fun requestZfLogin() {
        _schedule.update { it.copy(needsZfLogin = true) }
    }

    /** 切换课表数据源；切换后清掉旧来源的数据并重新同步。 */
    fun setSourceKind(kind: TimetableSourceKind) {
        viewModelScope.launch {
            if (kind == settings.value.sourceKind) return@launch
            container.settingsRepository.setSourceKind(kind)
            _schedule.update {
                it.copy(
                    timetable = Timetable.empty(),
                    isFromCache = false,
                    lastSyncedAt = 0L,
                    userName = null,
                    errorMessage = null,
                    sourceKind = kind,
                    dataRevision = it.dataRevision + 1,
                )
            }
            container.timetableRepository.clearCache()
            sync()
        }
    }

    /** 学期第一周周一（`2026-08-24`）；空字符串表示交给页面「当前周」反推。 */
    fun setZfTermStartDate(date: String) {
        viewModelScope.launch {
            container.settingsRepository.setZfTermStartDate(date.trim())
            sync()
        }
    }



/** 导出当前课表为 JSON 文本（供「导出课表」写文件）；没有可导出的数据时返回 null。 */
    fun exportTimetableJson(): String? {
        val current = settings.value
        val sourceUrl = if (current.sourceKind == TimetableSourceKind.ZF_LOGIN) {
            current.zfBaseUrl
        } else {
            current.timetableUrl
        }
        return container.timetableRepository.exportJson(sourceUrl)
    }

    /** 导入课表文件；结果通过 Snackbar（errorMessage 通道）告知用户。 */
    fun importTimetableJson(text: String) {
        viewModelScope.launch {
            when (val result = container.timetableRepository.importFromExport(text)) {
                is ImportResult.Success -> {
                    container.timetableRepository.cachedTimetable()?.let { timetable ->
                        _schedule.update { state ->
                            val currentWeek = timetable.term.weekOf(TimeUtils.today())
                            state.copy(
                                timetable = timetable,
                                selectedWeek = (currentWeek ?: state.selectedWeek)
                                    .coerceIn(1, timetable.term.totalWeeks.coerceAtLeast(1)),
                                currentWeek = currentWeek,
                                today = TimeUtils.today(),
                                lastSyncedAt = container.timetableRepository.lastSyncedAt,
                                userName = container.timetableRepository.lastUser,
                                isFromCache = false,
                                errorMessage = "已导入课表：${result.courseCount} 门课",
                                dataRevision = state.dataRevision + 1,
                            )
                        }
                    }
                }

                is ImportResult.Failure -> _schedule.update { it.copy(errorMessage = result.message) }
            }
        }
    }
    /** 退出教务系统登录（会话被清，下次同步会要求重新登录）。 */
    fun logoutZf() {
        viewModelScope.launch {
            container.zfSessionStore.clear()
            _schedule.update {
                it.copy(zfLoggedIn = false, needsZfLogin = false, errorMessage = null)
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

        /** 对时周期：一分钟一次，够跨天及时发现，也不会明显耗电。 */
        private const val WALL_CLOCK_TICK_MS: Long = 60_000L
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
