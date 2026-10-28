package com.wakeup.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import com.wakeup.domain.Course
import com.wakeup.wear.WearUiState

/** 手表端的几个界面。层级很浅，用一个 sealed 接口就够，不需要 navigation 库。 */
sealed interface WearDestination {
    data object Days : WearDestination
    data object Weeks : WearDestination
    data object Settings : WearDestination
    data object Token : WearDestination
    data class CourseDetail(val course: Course) : WearDestination
}

/**
 * 手表端根界面。
 *
 * 用 `AppScaffold` 提供顶部的弧形时间（TimeText）与各个屏幕统一的背景，
 * 具体页面各自套 `ScreenScaffold` + `ScalingLazyColumn`：内容滚到屏幕圆边时会缩小、
 * 边缘留白也由组件按屏幕形状算好，不会出现"字被圆角裁掉"的问题。
 */
@Composable
fun WearApp(
    state: WearUiState,
    onRefresh: () -> Unit,
    onSelectWeek: (Int) -> Unit,
    onSelectDay: (Int) -> Unit,
    onSaveToken: (String) -> Unit,
    onClearCache: () -> Unit,
) {
    var destination by remember { mutableStateOf<WearDestination>(WearDestination.Days) }
    val backToDays: () -> Unit = { destination = WearDestination.Days }

    // 系统返回键/返回手势：回到课程页；已经在课程页时交给系统（退出应用）
    BackHandler(enabled = destination != WearDestination.Days) { backToDays() }

    AppScaffold {
        when (val current = destination) {
            WearDestination.Days -> DayScreen(
                state = state,
                onSelectDay = onSelectDay,
                onRefresh = onRefresh,
                onOpenWeekPicker = { destination = WearDestination.Weeks },
                onOpenSettings = { destination = WearDestination.Settings },
                onOpenToken = { destination = WearDestination.Token },
                onCourseClick = { destination = WearDestination.CourseDetail(it) },
            )

            WearDestination.Weeks -> WeekScreen(
                state = state,
                onSelectWeek = {
                    onSelectWeek(it)
                    backToDays()
                },
                onBack = backToDays,
            )

            WearDestination.Settings -> SettingsScreen(
                state = state,
                onRefresh = onRefresh,
                onOpenWeekPicker = { destination = WearDestination.Weeks },
                onOpenToken = { destination = WearDestination.Token },
                onClearCache = {
                    onClearCache()
                    backToDays()
                },
                onBack = backToDays,
            )

            WearDestination.Token -> TokenScreen(
                state = state,
                onConfirm = {
                    onSaveToken(it)
                    backToDays()
                },
                onBack = backToDays,
            )

            is WearDestination.CourseDetail -> CourseDetailScreen(
                course = current.course,
                timetable = state.timetable,
                onBack = backToDays,
            )
        }
    }
}
