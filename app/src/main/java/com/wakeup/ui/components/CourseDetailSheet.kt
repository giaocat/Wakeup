package com.wakeup.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wakeup.domain.Course
import com.wakeup.domain.WeekExpression
import com.wakeup.domain.dayLabel
import com.wakeup.ui.theme.courseColorFor

/**
 * 课程详情内容（标题 + 字段列表）。
 *
 * 手机上的底部面板 [CourseDetailSheet] 与大屏 / 折叠屏上的内嵌面板 [CourseDetailPane]
 * 共用这一份内容，两种版式的字段、顺序与文案不可能各写一遍而慢慢跑偏。
 */
@Composable
fun CourseDetailContent(
    course: Course,
    isSutSource: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = courseColorFor(course.courseId)
    // GF 座作息只在沈工大课表上生效
    val isGfShifted = isSutSource && course.looksLikeGfBuilding

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = course.courseId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

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
                    append("\nG 座 / F 座（沈工大）：3、4 节提前 10 分钟上下课")
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
            icon = Icons.Outlined.DateRange,
            label = "周次",
            value = WeekExpression.format(course.weeks).ifBlank { "未指定" },
        )
    }
}

/**
 * 课程详情底部面板（手机竖屏 / 中等宽度）。
 *
 * 采用 Material 3 的 [ModalBottomSheet] + 默认拖拽把手；内容见 [CourseDetailContent]。
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        CourseDetailContent(
            course = course,
            isSutSource = isSutSource,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

/**
 * 课程详情内嵌面板（平板 / 折叠屏）。
 *
 * 与底部面板共用 [CourseDetailContent]：选中课程时显示同一套字段；没有选中时给出
 * 「点左侧课程查看详情」的空状态，避免右侧留出一块没有语义的空白。
 */
@Composable
fun CourseDetailPane(
    course: Course?,
    isSutSource: Boolean,
    modifier: Modifier = Modifier,
    emptyText: String = "点左侧课程查看详情",
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        if (course == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "课程详情",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp),
                )
                Spacer(Modifier.height(4.dp))
                CourseDetailContent(course = course, isSutSource = isSutSource)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        overlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
    )
}
