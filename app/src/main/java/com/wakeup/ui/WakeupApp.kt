package com.wakeup.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeup.domain.Course
import com.wakeup.ui.components.CourseDetailSheet
import com.wakeup.ui.schedule.ScheduleScreen
import com.wakeup.ui.settings.SettingsScreen
import com.wakeup.ui.today.TodayScreen

private enum class Destination(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    SCHEDULE("课表", Icons.Outlined.CalendarMonth),
    TODAY("今日", Icons.Outlined.Today),
    SETTINGS("设置", Icons.Outlined.Settings),
}

@Composable
fun WakeupApp(
    viewModel: WakeupViewModel,
    modifier: Modifier = Modifier,
) {
    val schedule by viewModel.schedule.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var destination by rememberSaveable { mutableStateOf(Destination.SCHEDULE) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }

    // 同步失败时提示
    LaunchedEffect(schedule.errorMessage) {
        val message = schedule.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
        viewModel.dismissError()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
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
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (destination) {
                Destination.SCHEDULE -> ScheduleScreen(
                    state = schedule,
                    onPreviousWeek = viewModel::previousWeek,
                    onNextWeek = viewModel::nextWeek,
                    onSelectWeek = viewModel::selectWeek,
                    onRefresh = viewModel::sync,
                    onOpenSettings = { destination = Destination.SETTINGS },
                    onCourseClick = { detailCourse = it },
                )

                Destination.TODAY -> TodayScreen(
                    state = schedule,
                    onRefresh = viewModel::sync,
                    onOpenSettings = { destination = Destination.SETTINGS },
                    onCourseClick = { detailCourse = it },
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
                )
            }
        }
    }

    detailCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onDismiss = { detailCourse = null },
        )
    }
}
