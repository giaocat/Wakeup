package com.wakeup.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.wakeup.domain.Course
import com.wakeup.domain.Timetable
import com.wakeup.wear.WearUiState

/**
 * 课程页：横向翻页切换星期（周一到周日），每一页是当天的课程列表。
 *
 * 这是手表上最自然的手势：表盘左右滑就是"前一天/后一天"，
 * 底部用一个圆点翻页指示器告诉用户一共 7 天、现在停在哪一天。
 * 换周次放在顶部卡片里点一下进"选择周次"（周次变化远没有天频繁）。
 */
@Composable
fun DayScreen(
    state: WearUiState,
    onSelectDay: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpenWeekPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenToken: () -> Unit,
    onCourseClick: (Course) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = (state.day - 1).coerceIn(0, DAYS_OF_WEEK - 1),
        pageCount = { DAYS_OF_WEEK },
    )
    // 翻到哪一天就记住哪一天：换周次回来时还停在这天
    LaunchedEffect(pagerState.currentPage) { onSelectDay(pagerState.currentPage + 1) }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            DayPage(
                state = state,
                day = page + 1,
                onRefresh = onRefresh,
                onOpenWeekPicker = onOpenWeekPicker,
                onOpenSettings = onOpenSettings,
                onOpenToken = onOpenToken,
                onCourseClick = onCourseClick,
            )
        }
        HorizontalPageIndicator(
            pagerState = pagerState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
        )
    }
}

@Composable
private fun DayPage(
    state: WearUiState,
    day: Int,
    onRefresh: () -> Unit,
    onOpenWeekPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenToken: () -> Unit,
    onCourseClick: (Course) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val courses = remember(state.timetable, state.week, day) { state.coursesOf(day) }
    val isToday = state.isCurrentWeek && state.today.dayOfWeek.value == day

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
        },
    ) { padding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                // 顶部留给弧形时间，底部留给翻页指示器
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 22.dp,
            ),
        ) {
            item(key = "header") {
                DayHeaderCard(
                    state = state,
                    day = day,
                    isToday = isToday,
                    onClick = onOpenWeekPicker,
                )
            }

            state.error?.let { message ->
                item(key = "error") {
                    InfoCard(
                        title = "同步失败",
                        body = message,
                        hint = "点击重试",
                        onClick = onRefresh,
                    )
                }
            }

            if (courses.isEmpty()) {
                item(key = "empty") {
                    // 没配置来源时，点这里直接去填 token —— 手表上唯一的入口
                    val needsToken = !state.hasSource
                    InfoCard(
                        title = when {
                            state.isLoading -> "正在同步…"
                            needsToken -> "还没配置课表"
                            else -> "这天没有课"
                        },
                        body = if (needsToken) {
                            "点一下用数字键盘填 token"
                        } else {
                            "${weekdayName(day)} · 第${state.week}周"
                        },
                        hint = if (state.isLoading) null else if (needsToken) "去填写" else "点击刷新",
                        onClick = if (needsToken) onOpenToken else onRefresh,
                    )
                }
            } else {
                items(
                    count = courses.size,
                    key = { index -> courseKey(courses[index]) },
                ) { index ->
                    CourseCard(
                        course = courses[index],
                        timetable = state.timetable,
                        onClick = { onCourseClick(courses[index]) },
                    )
                }
            }
        }
    }
}

/** 顶部一周 / 日期卡片，点一下进"选择周次"。 */
@Composable
private fun DayHeaderCard(
    state: WearUiState,
    day: Int,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val date = state.dateOf(day)
    TitleCard(
        onClick = onClick,
        time = {
            Text(
                text = if (isToday) "今天" else weekdayName(day),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text("第${state.week}周") },
        subtitle = {
            Text(
                text = "${weekdayName(day)} ${formatDate(date)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** 一节课：课程名 + 起止时间/节次 + 教室。 */
@Composable
private fun CourseCard(
    course: Course,
    timetable: Timetable,
    onClick: () -> Unit,
) {
    val clock = clockRange(course, timetable)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Text(
            text = course.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                if (clock.isNotEmpty()) {
                    append(clock)
                    append(" · ")
                }
                append("第${course.periodLabel}节")
                if (course.isEarlyBuilding && timetable.isSutSource) append(" · 提前")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = course.locationOrUnknown,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 空状态 / 错误状态统一用的小卡片。 */
@Composable
private fun InfoCard(
    title: String,
    body: String,
    hint: String?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        if (body.isNotBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
