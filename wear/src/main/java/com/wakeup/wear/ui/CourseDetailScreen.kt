package com.wakeup.wear.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wakeup.domain.Course
import com.wakeup.domain.Timetable
import com.wakeup.domain.WeekExpression

/** 课程详情：手表屏幕小，这里只放最关键的几行。 */
@Composable
fun CourseDetailScreen(
    course: Course,
    timetable: Timetable,
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val clock = clockRange(course, timetable)
    val weeks = WeekExpression.format(course.weeks)

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
    ) { padding ->
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 10.dp,
                end = 10.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item(key = "header") {
                ListHeader {
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            item(key = "time") {
                DetailCard(
                    label = "时间",
                    value = buildString {
                        append(weekdayName(course.dayOfWeek))
                        append(" 第${course.periodLabel}节")
                        if (clock.isNotEmpty()) {
                            append("\n")
                            append(clock)
                        }
                    },
                )
            }

            item(key = "place") {
                DetailCard(label = "教室", value = course.locationOrUnknown)
            }

            item(key = "teacher") {
                DetailCard(label = "教师", value = course.teacherOrUnknown)
            }

            item(key = "weeks") {
                DetailCard(
                    label = "周次",
                    value = if (weeks.isBlank()) "未标注" else "第${weeks}周",
                )
            }
        }
    }
}

@Composable
private fun DetailCard(label: String, value: String) {
    Card(
        onClick = { },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
