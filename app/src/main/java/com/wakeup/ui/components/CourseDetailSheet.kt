package com.wakeup.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wakeup.domain.Course
import com.wakeup.domain.PeriodTimes
import com.wakeup.domain.WeekExpression
import com.wakeup.domain.dayLabel
import com.wakeup.ui.theme.courseColorFor

/**
 * 课程详情底部面板（M3E ModalBottomSheet + 拖拽把手）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION") // rememberModalBottomSheetState 仍可用；替代 API 尚在实验阶段
@Composable
fun CourseDetailSheet(
    course: Course,
    isSutSource: Boolean = false,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val color = courseColorFor(course.courseId)
    // GF 座作息只在沈工大课表上生效
    val isGfShifted = isSutSource && course.looksLikeGfBuilding

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = color,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(width = 6.dp, height = 44.dp),
                ) {}
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = course.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = course.courseId,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            DetailRow(
                icon = Icons.Outlined.Schedule,
                label = "时间",
                value = buildString {
                    append("周${dayLabel(course.dayOfWeek)} 第 ${course.periodLabel} 节")
                    val start = course.startAtWithGf
                    val end = course.endAtWithGf
                    if (start != null && end != null) {
                        append("（%02d:%02d-%02d:%02d）".format(
                            start.hour, start.minute, end.hour, end.minute,
                        ))
                    }
                    if (isGfShifted) {
                        append("\nGF 座（沈工大）：3、4 节提前 10 分钟上下课")
                    }
                },
            )

            DetailRow(
                icon = Icons.Outlined.LocationOn,
                label = "地点",
                value = course.locationOrUnknown,
            )

            DetailRow(
                icon = Icons.Outlined.Person,
                label = "教师",
                value = course.teacherOrUnknown,
            )

            DetailRow(
                icon = Icons.Outlined.Schedule,
                label = "周次",
                value = WeekExpression.format(course.weeks).ifBlank { "未指定" },
            )
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
