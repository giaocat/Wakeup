package com.wakeup.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowLayoutInfo
import com.wakeup.domain.Course
import com.wakeup.ui.adaptive.rememberAdaptiveWindowInfo
import com.wakeup.ui.components.CourseDetailSheet
import com.wakeup.ui.components.UpdateDialog
import com.wakeup.ui.schedule.ScheduleScreen
import com.wakeup.ui.settings.SettingsScreen
import com.wakeup.ui.today.TodayScreen

/** 导航的三个目的地（底部导航栏与侧边导航轨共用）。 */
private enum class Destination(
    val label: String,
    val icon: ImageVector,
) {
    SCHEDULE("课表", Icons.Outlined.CalendarMonth),
    TODAY("今日", Icons.Outlined.Today),
    SETTINGS("设置", Icons.Outlined.Settings),
}

/**
 * 课程的可保存身份。
 *
 * [Course] 不是 Parcelable/Serializable，不能直接塞进 `rememberSaveable`；
 * 用「课程号 + 星期 + 起始节次」就能唯一定位一门课的一个时段，重建后再从课表里解析回来。
 */
private val Course.saveableKey: String
    get() = "$courseId|$dayOfWeek|$startPeriod"

/**
 * 应用外壳（自适应版）。
 *
 * 只保留一个 [Scaffold]：它负责导航（底部导航栏或侧边导航轨）、Snackbar 和全局内边距；
 * 每个页面自己带一个 TopAppBar（见各 Screen 的 Scaffold），从而让顶栏跟随页面语义
 * （课表页是周次操作，今日页是刷新，设置页是返回）。
 *
 * 自适应规则（官方 Material 3 窗口尺寸类）：
 * - Compact（手机竖屏）：底部 [NavigationBar]，课程详情用底部面板；
 * - Medium / Expanded：起始端 [NavigationRail]、移除底部导航栏、内容放在导航轨右侧；
 * - Expanded：课表改为「列表 + 详情」双栏，设置与今日收窄到 840dp 居中；
 * - 折叠屏：竖向铰链把两块内容分到左右、横向铰链（桌面姿态）改成上下分栏。
 *
 * [windowSizeClass] / [windowLayoutInfo] 由 `MainActivity` 在 Activity 边界处计算后传入；
 * 默认值让 [WakeupApp] 在预览里也能直接使用（按手机竖屏渲染）。
 */
@Composable
fun WakeupApp(
    viewModel: WakeupViewModel,
    modifier: Modifier = Modifier,
    windowSizeClass: WindowSizeClass? = null,
    windowLayoutInfo: WindowLayoutInfo? = null,
) {
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 尺寸类 + 折叠姿态合并成一份，屏幕内部只读这个对象。
    val adaptive = rememberAdaptiveWindowInfo(windowSizeClass, windowLayoutInfo)

    // 启动时自动检查一次更新
    LaunchedEffect(Unit) { viewModel.checkForUpdate(auto = true) }

    // 当前目的地与选中的课程都放在 saveable 状态里：旋转 / 分屏尺寸变化不会丢。
    var destination by rememberSaveable { mutableStateOf(Destination.SCHEDULE) }
    var selectedCourseKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCourse = remember(selectedCourseKey, schedule.timetable) {
        selectedCourseKey?.let { key ->
            schedule.timetable.scheduled
                .asSequence()
                .flatMap { it.slots.asSequence() }
                .firstOrNull { it.saveableKey == key }
        }
    }

    // 展开宽度或存在分隔折叠特征：详情改成内嵌面板；手机竖屏 / 中等宽度仍用底部面板。
    val inlineDetail = adaptive.usesInlineDetail

    // 同步失败时用 Snackbar 提示（可恢复的失败由各页的离线横幅持续展示）
    LaunchedEffect(schedule.errorMessage) {
        val message = schedule.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
        viewModel.dismissError()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Medium / Expanded 改用侧边导航轨，底部导航栏整体移除。
            if (!adaptive.usesNavigationRail) {
                NavigationBar {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        // 三个页面只写一次：导航轨与底部导航栏两条分支共用同一份内容。
        val screen: @Composable (Modifier) -> Unit = { screenModifier ->
            when (destination) {
                Destination.SCHEDULE -> ScheduleScreen(
                    state = schedule,
                    onPreviousWeek = viewModel::previousWeek,
                    onNextWeek = viewModel::nextWeek,
                    onSelectWeek = viewModel::selectWeek,
                    onRefresh = viewModel::sync,
                    onOpenSettings = { destination = Destination.SETTINGS },
                    onCourseClick = { selectedCourseKey = it.saveableKey },
                    modifier = screenModifier,
                    adaptive = adaptive,
                    selectedCourse = selectedCourse,
                    showDetailPane = inlineDetail,
                )

                Destination.TODAY -> TodayScreen(
                    state = schedule,
                    onRefresh = viewModel::sync,
                    onOpenSettings = { destination = Destination.SETTINGS },
                    onCourseClick = { selectedCourseKey = it.saveableKey },
                    modifier = screenModifier,
                    adaptive = adaptive,
                )

                Destination.SETTINGS -> SettingsScreen(
                    settings = settings,
                    state = schedule,
                    cacheSizeBytes = cacheSizeBytes,
                    onTimetableUrlChange = viewModel::updateTimetableUrl,
                    onBaseUrlChange = viewModel::updateBaseUrl,
                    onThemeModeChange = viewModel::setThemeMode,
                    onDynamicColorChange = viewModel::setDynamicColor,
                    onHideUnscheduledChange = viewModel::setHideUnscheduled,
                    onRefresh = viewModel::sync,
                    onClearCache = viewModel::clearCache,
                    onOpenRelease = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                    onBack = { destination = Destination.SCHEDULE },
                    updateChecking = update.checking,
                    updateError = update.error,
                    latestVersion = update.info?.version,
                    onCheckUpdate = { viewModel.checkForUpdate(auto = false) },
                    modifier = screenModifier,
                    adaptive = adaptive,
                )
            }
        }

        if (adaptive.usesNavigationRail) {
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                NavigationRail {
                    Destination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    screen(Modifier.fillMaxSize())
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                screen(Modifier.fillMaxSize())
            }
        }
    }

    // 手机 / 中等宽度：详情走底部面板（大屏双栏时由课表页自己的内嵌面板负责）。
    if (!inlineDetail) {
        selectedCourse?.let { course ->
            CourseDetailSheet(
                course = course,
                isSutSource = schedule.timetable.isSutSource,
                onDismiss = { selectedCourseKey = null },
            )
        }
    }

    update.info?.let { info ->
        UpdateDialog(
            info = info,
            onDismiss = viewModel::dismissUpdate,
        )
    }
}
