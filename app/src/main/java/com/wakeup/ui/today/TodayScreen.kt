package com.wakeup.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wakeup.domain.Course
import com.wakeup.domain.Timetable
import com.wakeup.ui.ScheduleUiState
import com.wakeup.ui.adaptive.AdaptiveWindowInfo
import com.wakeup.ui.adaptive.FoldAwareSplit
import com.wakeup.ui.adaptive.contentMaxWidth
import com.wakeup.ui.theme.courseColorFor
import com.wakeup.util.TimeUtils
import java.time.LocalTime

/**
 * 今日课程：按时间顺序排开，并标出正在上的那一节。
 *
 * 列表使用 [LazyColumn] + [Card]；页面骨架仍是 [Scaffold] + [TopAppBar]（副标题为日期）。
 *
 * 自适应（[adaptive]）：
 * - 平板 / 大窗口：正文收窄到 840dp 并水平居中，不在 1000dp 宽的窗口里把单栏列表拉满；
 * - 横向铰链（桌面姿态）：课程列表在上，「今日概览 + 离线提示」排在铰链下方；
 * - 竖向铰链（书本姿态）：概览在左、课程列表在右，中间让出铰链宽度。
 */
@Composable
fun TodayScreen(
    state: ScheduleUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
    adaptive: AdaptiveWindowInfo = AdaptiveWindowInfo(),
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
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("今日") },
                subtitle = {
                    Text(
                        text = buildString {
                            append(TimeUtils.formatDateWithWeekday(today))
                            append(" · ")
                            append(if (state.isTermActive) "第 $week 周" else "非教学周")
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (state.isLoading) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "正在同步" },
                            contentAlignment = Alignment.Center,
                        ) {
                            LoadingIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "刷新",
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // 大屏正文：最宽 840dp，水平居中；手机上这个约束不会生效。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .contentMaxWidth()
                    .fillMaxHeight(),
            ) {
                TodayContent(
                    state = state,
                    courses = courses,
                    now = now,
                    adaptive = adaptive,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    onCourseClick = onCourseClick,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 三种排法共用同一份内容：
 * - 单栏（手机 / 平板无折叠）：概览 → 课程列表；
 * - 桌面姿态（横向铰链）：课程列表在上，概览与离线提示在铰链下方；
 * - 书本姿态（竖向铰链）：概览在左、列表在右。
 */
@Composable
private fun TodayContent(
    state: ScheduleUiState,
    courses: List<Course>,
    now: LocalTime,
    adaptive: AdaptiveWindowInfo,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 概览卡片 + 离线横幅只在三套排法里换位置，内容本身只写这一遍。
    val summaryAndNotice: @Composable (Modifier) -> Unit = { boxModifier ->
        Column(modifier = boxModifier) {
            TodaySummaryCard(
                week = state.currentWeek ?: state.selectedWeek,
                isTermActive = state.isTermActive,
                courseCount = courses.size,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            state.offlineNotice?.let { notice ->
                OfflineBanner(
                    notice = notice,
                    retryEnabled = !state.isLoading,
                    onRetry = onRefresh,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    when {
        adaptive.hasHorizontalFold -> FoldAwareSplit(
            vertical = false,
            foldBounds = adaptive.foldBounds,
            gap = adaptive.paneGap,
            modifier = modifier,
            firstWeight = 1.4f,
            secondWeight = 0.6f,
            first = {
                // 桌面姿态：课程列表在上，概览与离线提示在铰链下方
                TodayCourseList(
                    courses = courses,
                    state = state,
                    now = now,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    onCourseClick = onCourseClick,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            second = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    summaryAndNotice(Modifier.fillMaxWidth())
                }
            },
        )

        adaptive.hasVerticalFold -> FoldAwareSplit(
            vertical = true,
            foldBounds = adaptive.foldBounds,
            gap = adaptive.paneGap,
            modifier = modifier,
            firstWeight = 1f,
            secondWeight = 2f,
            first = {
                // 书本姿态：概览在左，列表在右
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    summaryAndNotice(Modifier.fillMaxWidth())
                }
            },
            second = {
                TodayCourseList(
                    courses = courses,
                    state = state,
                    now = now,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    onCourseClick = onCourseClick,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )

        else -> Column(modifier = modifier) {
            summaryAndNotice(Modifier.fillMaxWidth())
            TodayCourseList(
                courses = courses,
                state = state,
                now = now,
                onRefresh = onRefresh,
                onOpenSettings = onOpenSettings,
                onCourseClick = onCourseClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

// 时间统一走 Course.effectiveStartAt/effectiveEndAt：那是一处唯一定义，
// G/F 座（仅沈工大）的提前作息与 Smartspacer 的 Target 共用同一判断。
private fun Course.isHappeningAt(time: LocalTime, timetable: Timetable): Boolean {
    val start = effectiveStartAt(timetable) ?: return false
    val end = effectiveEndAt(timetable) ?: return false
    return !time.isBefore(start) && !time.isAfter(end)
}

private fun Course.hasFinishedBy(time: LocalTime, timetable: Timetable): Boolean {
    val end = effectiveEndAt(timetable) ?: return false
    return time.isAfter(end)
}

/** 今日课程列表（空状态也在这里处理），供三种排法复用。 */
@Composable
private fun TodayCourseList(
    courses: List<Course>,
    state: ScheduleUiState,
    now: LocalTime,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (courses.isEmpty()) {
        EmptyToday(
            hasData = state.hasData,
            errorMessage = state.errorMessage,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
            modifier = modifier,
        )
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = courses,
                key = { "${it.courseId}-${it.startPeriod}-${it.dayOfWeek}" },
            ) { course ->
                TodayCourseCard(
                    course = course,
                    timetable = state.timetable,
                    isNow = course.isHappeningAt(now, state.timetable),
                    isPast = course.hasFinishedBy(now, state.timetable),
                    onClick = { onCourseClick(course) },
                )
            }
        }
    }
}

/** 本周概览卡片：primaryContainer 上的两行信息。 */
@Composable
private fun TodaySummaryCard(
    week: Int,
    isTermActive: Boolean,
    courseCount: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isTermActive) "第 $week 周" else "非教学周",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (courseCount == 0) "今天没有安排" else "今天有 $courseCount 节课",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 离线 / 同步失败横幅（与课表页保持同一套视觉）。 */
@Composable
private fun OfflineBanner(
    notice: String,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = notice,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry, enabled = retryEnabled) {
                Text("重试")
            }
        }
    }
}

/** 今日课程卡片：左侧时间、课程色条、课程信息。 */
@Composable
private fun TodayCourseCard(
    course: Course,
    timetable: Timetable,
    isNow: Boolean,
    isPast: Boolean,
    onClick: () -> Unit,
) {
    val accent = courseColorFor(course.courseId)
    // 按本课程实际生效的作息显示（G/F 座仅在沈工大课表上提前 10 分钟）
    val start = course.effectiveStartAt(timetable)
    val end = course.effectiveEndAt(timetable)
    val containerColor = if (isPast) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isPast) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 时间列
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(56.dp),
            ) {
                Text(
                    text = start?.let { TimeUtils.formatTime(it) } ?: "--:--",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = end?.let { TimeUtils.formatTime(it) } ?: "--:--",
                    style = MaterialTheme.typography.labelMedium,
                    color = secondaryColor,
                )
            }

            Spacer(Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(accent, CircleShape),
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isNow) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                        ) {
                            Text(
                                text = "正在上课",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = secondaryColor,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = course.locationOrUnknown,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (course.teacher.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = secondaryColor,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = course.teacher,
                            style = MaterialTheme.typography.bodyMedium,
                            color = secondaryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "第 ${course.periodLabel} 节",
                    style = MaterialTheme.typography.labelMedium,
                    color = secondaryColor,
                )
            }
        }
    }
}

/** 空状态：图标 + titleMedium + bodyMedium + TextButton。 */
@Composable
private fun EmptyToday(
    hasData: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when {
        !hasData && errorMessage != null -> "同步失败"
        !hasData -> "还没有课表"
        else -> "今天没有课"
    }
    val message = when {
        !hasData && errorMessage != null -> errorMessage.orEmpty()
        !hasData -> "在「设置」里粘贴课表链接或 token，保存后会自动同步。"
        else -> "今天没有课程安排，好好休息。"
    }

    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (hasData) Icons.Outlined.EventAvailable else Icons.Outlined.EventBusy,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!hasData) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRefresh) {
                        Text(if (errorMessage != null) "重试" else "重新同步")
                    }
                    TextButton(onClick = onOpenSettings) { Text("去设置课表链接") }
                }
            }
        }
    }
}
