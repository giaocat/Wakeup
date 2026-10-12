package com.wakeup.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wakeup.domain.Course
import com.wakeup.domain.PeriodTimes
import com.wakeup.ui.ScheduleUiState
import com.wakeup.ui.theme.courseColorFor
import com.wakeup.util.TimeUtils
import java.time.LocalTime

/**
 * 今日课程：按时间顺序排开，并标出正在上的那一节。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: ScheduleUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = state.today
    val week = state.currentWeek ?: state.selectedWeek
    val courses = remember(state.timetable, week, today) {
        state.timetable.slotsOfDay(week, today.dayOfWeek.value)
    }

    var now by remember { mutableStateOf(TimeUtils.currentTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = TimeUtils.currentTime()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "今日",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = TimeUtils.formatDateWithWeekday(today),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.isLoading) {
                        LoadingIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            WeekBanner(
                week = week,
                isTermActive = state.currentWeek != null,
                courseCount = courses.size,
            )

            state.offlineNotice?.let { notice ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRefresh, enabled = !state.isLoading) {
                            Text("重试")
                        }
                    }
                }
            }

            if (courses.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = if (state.timetable.courses.isEmpty()) {
                                state.errorMessage ?: "还没有课表数据。请到「设置」里配置你的课表链接。"
                            } else {
                                "今天没有课，好好休息 🎉"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (state.timetable.courses.isEmpty()) {
                            TextButton(onClick = onOpenSettings) {
                                Text("去设置课表链接")
                            }
                        }
                    }
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(courses, key = { "${it.courseId}-${it.startPeriod}-${it.dayOfWeek}" }) { course ->
                    TodayCourseCard(
                        course = course,
                        isSutSource = state.timetable.isSutSource,
                        isNow = course.isHappeningAt(now, state.timetable.isSutSource),
                        isPast = course.hasFinishedBy(now, state.timetable.isSutSource),
                        onClick = { onCourseClick(course) },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private fun Course.isHappeningAt(time: LocalTime, isSutSource: Boolean): Boolean {
    // 用本课程自己的上课时间：GF 座（仅沈工大）3、4 节比标准作息提前 10 分钟
    val gf = isSutSource && looksLikeGfBuilding
    val start = if (gf) startAtWithGf else standardStartAt
    val end = if (gf) endAtWithGf else standardEndAt
    if (start == null || end == null) return false
    return !time.isBefore(start) && !time.isAfter(end)
}

private fun Course.hasFinishedBy(time: LocalTime, isSutSource: Boolean): Boolean {
    val gf = isSutSource && looksLikeGfBuilding
    val end = if (gf) endAtWithGf else standardEndAt
    return end != null && time.isAfter(end)
}

@Composable
private fun WeekBanner(
    week: Int,
    isTermActive: Boolean,
    courseCount: Int,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isTermActive) "第 $week 周" else "非教学周",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (courseCount == 0) "今天没有安排" else "今天有 $courseCount 节课",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun TodayCourseCard(
    course: Course,
    isSutSource: Boolean,
    isNow: Boolean,
    isPast: Boolean,
    onClick: () -> Unit,
) {
    val accent = courseColorFor(course.courseId)
    val alpha = if (isPast) 0.55f else 1f
    // 按本课程教室的作息显示（GF 座仅在沈工大课表上提前 10 分钟）
    val gf = isSutSource && course.looksLikeGfBuilding
    val start = if (gf) course.startAtWithGf else course.standardStartAt
    val end = if (gf) course.endAtWithGf else course.standardEndAt

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (isNow) 3.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 时间列
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(56.dp),
            ) {
                Text(
                    text = start?.let { TimeUtils.formatTime(it) } ?: "--:--",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                Text(
                    text = end?.let { TimeUtils.formatTime(it) } ?: "--:--",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }

            Spacer(Modifier.width(12.dp))

            Surface(
                color = accent.copy(alpha = alpha),
                shape = CircleShape,
                modifier = Modifier.size(width = 4.dp, height = 52.dp),
            ) {}

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isNow) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape,
                        ) {
                            Text(
                                text = "正在上课",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = course.locationOrUnknown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    )
                    if (course.teacher.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = course.teacher,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "第 ${course.periodLabel} 节",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        }
    }
}
