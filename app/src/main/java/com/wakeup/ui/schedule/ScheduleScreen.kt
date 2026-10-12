package com.wakeup.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeup.domain.Course
import com.wakeup.domain.PeriodTime
import com.wakeup.domain.PeriodTimes
import com.wakeup.domain.Timetable
import com.wakeup.domain.TimetableCourse
import com.wakeup.domain.dayLabel
import com.wakeup.ui.ScheduleUiState
import com.wakeup.ui.theme.courseColorFor
import com.wakeup.ui.theme.courseColorsFor
import com.wakeup.ui.theme.courseOutlineFor
import com.wakeup.util.TimeUtils
import java.time.LocalDate

// 课表网格尺寸：优先自适应铺满屏宽，屏太窄则保持可读宽度并允许横向滚动
private val GridPeriodColumnWidth = 44.dp
/** 单列可读下限：小于这个值就横向滚动，而不是把课程名挤成一条。 */
private val GridMinDayWidth = 60.dp
/** 单列上限：平板/横屏不把格子拉得过宽。 */
private val GridMaxDayWidth = 96.dp
private val GridHeaderHeight = 44.dp
private val GridRowHeight = 54.dp
private val GridCellGap = 3.dp

/** 课表网格列宽计算结果。 */
internal data class GridLayout(val dayWidth: Float, val scrollHorizontally: Boolean)

/**
 * 计算列宽与是否需要横向滚动。
 *
 * 抽成纯函数便于测试：
 * 1. 一屏能放下七天（每列 ≥ [minDayWidth]）时，铺满可用宽度，不横向滚动；
 * 2. 放不下时，取「可读下限」与「等分宽度」中的较大值，并开启横向滚动；
 * 3. 宽带时不超过 [maxDayWidth]。
 */
internal fun computeGridLayout(
    availableWidth: Float,
    periodColumnWidth: Float = 44f,
    minDayWidth: Float = 60f,
    maxDayWidth: Float = 96f,
): GridLayout {
    if (availableWidth <= 0f) return GridLayout(minDayWidth, scrollHorizontally = true)
    val perDay = (availableWidth - periodColumnWidth) / 7f
    return when {
        perDay >= minDayWidth -> GridLayout(perDay.coerceAtMost(maxDayWidth), scrollHorizontally = false)
        else -> GridLayout(minDayWidth.coerceAtLeast(perDay).coerceAtMost(maxDayWidth), scrollHorizontally = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSelectWeek: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    var weekPickerExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 标题栏与周次切换合成一行，避免两行都占高度
            CompactHeader(
                state = state,
                weekPickerExpanded = weekPickerExpanded,
                onWeekPickerExpandedChange = { weekPickerExpanded = it },
                onPreviousWeek = onPreviousWeek,
                onNextWeek = onNextWeek,
                onSelectWeek = onSelectWeek,
                onRefresh = onRefresh,
            )

            state.offlineNotice?.let { notice ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
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

            if (state.isLoading && state.timetable.courses.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
                return@Column
            }

            if (state.timetable.courses.isEmpty()) {
                EmptyTimetable(
                    message = state.errorMessage
                        ?: "还没有课表数据。请到「设置」里粘贴你的课表链接或 token，然后点刷新。",
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }

            TimetableGrid(
                timetable = state.timetable,
                week = state.selectedWeek,
                today = state.today,
                onCourseClick = onCourseClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            UnscheduledSection(
                courses = state.unscheduledCourses,
            )
        }
    }
}

/** 单行标题栏：上一周 / 周次（含本周标记）/ 周次选择 / 下一周 / 刷新。 */
@Composable
private fun CompactHeader(
    state: ScheduleUiState,
    weekPickerExpanded: Boolean,
    onWeekPickerExpandedChange: (Boolean) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSelectWeek: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    val isCurrentWeekShown = state.currentWeek == state.selectedWeek
    val totalWeeks = state.timetable.term.totalWeeks.coerceAtLeast(1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 0.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val termName = state.timetable.term.name
            val title = buildString {
                append("第 ${state.selectedWeek} 周")
                if (isCurrentWeekShown) append(" · 本周")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = when {
                !state.hasSource -> "未设置课表链接"
                termName.isNotBlank() -> termName
                else -> null
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 切换上下周的两个按钮紧贴周次选择器两侧
        IconButton(onClick = onPreviousWeek) {
            Icon(
                imageVector = Icons.Outlined.ChevronLeft,
                contentDescription = "上一周",
                modifier = Modifier.size(22.dp),
            )
        }

        Box {
            Surface(
                onClick = { onWeekPickerExpandedChange(true) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "${state.selectedWeek}/$totalWeeks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }

            DropdownMenu(
                expanded = weekPickerExpanded,
                onDismissRequest = { onWeekPickerExpandedChange(false) },
            ) {
                // 注意：不能用 LazyColumn —— DropdownMenu 的弹窗给的是无限高度约束，
                // 惰性布局在无限约束下测量会直接抛异常（点开即闪退）。
                // 周次最多几十项，用可滚动的 Column 既安全又够快。
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .widthIn(min = 140.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (week in 1..totalWeeks) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = buildString {
                                        append("第 $week 周")
                                        if (state.currentWeek == week) append(" · 本周")
                                        if (week == state.selectedWeek) append(" ✓")
                                    },
                                    color = if (week == state.selectedWeek) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                            onClick = {
                                onWeekPickerExpandedChange(false)
                                onSelectWeek(week)
                            },
                        )
                    }
                }
            }
        }

        IconButton(onClick = onNextWeek) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "下一周",
                modifier = Modifier.size(22.dp),
            )
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
            }
        } else {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "刷新课表",
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}


/** 课表主网格：七天自适应铺满可用宽度，左侧节次栏固定，整体纵向滚动。 */
@Composable
private fun TimetableGrid(
    timetable: Timetable,
    week: Int,
    today: LocalDate,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = remember(timetable, week) { timetable.cellsOfWeek(week) }
    // 颜色按整门课的出现顺序分配，保证相邻课程颜色不同（见 courseColorsFor）
    val courseColors = remember(timetable) {
        courseColorsFor(timetable.courses).mapValues { (_, color) -> color }
    }
    val weekDates = remember(timetable, week) {
        (1..7).map { day -> timetable.term.dateOf(week, day) }
    }
    val todayDayOfWeek = today.dayOfWeek.value
    val isCurrentWeekShown = timetable.term.weekOf(today) == week
    val todayIndex = if (isCurrentWeekShown) todayDayOfWeek - 1 else -1

    // 当周实际生效的作息：有 G/F 座的课时，3、4 节是提前 10 分钟的
    val effectivePeriods = remember(cells) { TimeUtils.effectivePeriods(cells.values.flatten()) }

    var nowOffset by remember(effectivePeriods) {
        mutableFloatStateOf(TimeUtils.gridOffsetFor(TimeUtils.currentTime(), effectivePeriods))
    }
    LaunchedEffect(effectivePeriods) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            nowOffset = TimeUtils.gridOffsetFor(TimeUtils.currentTime(), effectivePeriods)
        }
    }

    BoxWithConstraints(modifier = modifier) {
        // 一屏放得下就铺满；放不下就保持可读列宽并允许横向滚动
        val layout = computeGridLayout(
            availableWidth = maxWidth.value,
            periodColumnWidth = GridPeriodColumnWidth.value,
            minDayWidth = GridMinDayWidth.value,
            maxDayWidth = GridMaxDayWidth.value,
        )
        val dayWidth = layout.dayWidth.dp

        // 表头与主体共用同一个横向 ScrollState。两侧的课区宽度一致（都是 7×dayWidth），
        // 因此 Compose 推导出的最大滚动距离相同，滚动时始终保持对齐。
        val horizontalScroll = rememberScrollState()
        val verticalScroll = rememberScrollState()

        Column(modifier = Modifier.fillMaxSize()) {
            // 表头：月份 + 星期 / 日期（与主体共用横向滚动）
            Row(modifier = Modifier.fillMaxWidth().height(GridHeaderHeight)) {
                Box(
                    modifier = Modifier
                        .width(GridPeriodColumnWidth)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${today.monthValue}月",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(horizontalScroll),
                ) {
                    Row {
                        (1..7).forEach { day ->
                            DayHeader(
                                label = "周${dayLabel(day)}",
                                date = weekDates[day - 1].dayOfMonth,
                                isToday = day == todayDayOfWeek && isCurrentWeekShown,
                                modifier = Modifier
                                    .width(dayWidth)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }

            // 主体：左侧节次栏与右侧网格共用纵向滚动，右侧另有横向滚动
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier
                        .width(GridPeriodColumnWidth)
                        .verticalScroll(verticalScroll),
                ) {
                    effectivePeriods.forEach { period ->
                        PeriodLabel(period = period)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(verticalScroll)
                        .horizontalScroll(horizontalScroll),
                ) {
                    GridContent(
                        cells = cells,
                        dayWidth = dayWidth,
                        courseColors = courseColors,
                        isSutSource = timetable.isSutSource,
                        onCourseClick = onCourseClick,
                        nowOffset = nowOffset,
                        showNowLine = isCurrentWeekShown,
                        todayIndex = todayIndex,
                        modifier = Modifier.height(GridRowHeight * PeriodTimes.all.size),
                    )
                }
            }
        }
    }
}

/** 左侧节次标签：节次号 + 开始时间，尽量紧凑。 */
@Composable
private fun PeriodLabel(period: PeriodTime) {
    Box(
        modifier = Modifier
            .height(GridRowHeight)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${period.period}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "%02d:%02d".format(period.start.hour, period.start.minute),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayHeader(
    label: String,
    date: Int,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = if (isToday) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = "$date",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    color = if (isToday) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** 网格内容：行背景 + 冲突合并后的课程卡片 + 当前时刻线。 */
@Composable
private fun GridContent(
    cells: Map<String, List<Course>>,
    dayWidth: Dp,
    courseColors: Map<String, Color>,
    isSutSource: Boolean,
    onCourseClick: (Course) -> Unit,
    nowOffset: Float,
    showNowLine: Boolean,
    todayIndex: Int,
    modifier: Modifier = Modifier,
) {
    val periods = PeriodTimes.all
    val stripeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val nowColor = MaterialTheme.colorScheme.error
    val cellsIsSut = isSutSource

    Box(
        modifier = modifier.width(dayWidth * 7),
    ) {
        // 交错的行背景，便于横向阅读
        Column(modifier = Modifier.fillMaxSize()) {
            periods.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GridRowHeight)
                        .background(if (index % 2 == 0) stripeColor else Color.Transparent),
                )
            }
        }

        // 星期分隔线
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(7) {
                Box(
                    modifier = Modifier
                        .width(dayWidth)
                        .fillMaxSize()
                        .border(width = 0.5.dp, color = lineColor),
                )
            }
        }

        // 课程格子
        cells.forEach { (_, group) ->
            val anchor = group.firstOrNull() ?: return@forEach
            CourseBlock(
                courses = group,
                merged = group.size > 1,
                color = courseColors[anchor.courseId],
                isSutSource = cellsIsSut,                onClick = { onCourseClick(anchor) },
                modifier = Modifier
                    .offset(
                        x = dayWidth * (anchor.dayOfWeek - 1) + GridCellGap,
                        y = GridRowHeight * (anchor.startPeriod - 1) + GridCellGap,
                    )
                    .width(dayWidth - GridCellGap * 2)
                    .height(GridRowHeight * anchor.periodCount - GridCellGap * 2),
            )
        }

        // 当前时刻线
        if (showNowLine && todayIndex in 0..6) {
            Box(
                modifier = Modifier
                    .offset(
                        x = dayWidth * todayIndex + GridCellGap,
                        y = GridRowHeight * nowOffset,
                    )
                    .width(dayWidth - GridCellGap * 2)
                    .height(2.dp)
                    .background(nowColor, RoundedCornerShape(1.dp)),
            )
        }
    }
}

@Composable
private fun CourseBlock(
    courses: List<Course>,
    merged: Boolean,
    color: Color?,
    isSutSource: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = courses.first()
    val resolved = if (merged) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        color ?: courseColorFor(primary.courseId)
    }
    val onColor = if (merged) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    val outline = if (merged) MaterialTheme.colorScheme.outline else courseOutlineFor(resolved)
    // GF 座是沈工大的楼：既要教室名匹配，也要课表来源是沈工大域名
    val gfShifted = !merged &&
        isSutSource &&
        primary.looksLikeGfBuilding &&
        (PeriodTimes.differsFromStandard(primary.startPeriod, primary.location) ||
            PeriodTimes.differsFromStandard(primary.endPeriod, primary.location))

    Surface(
        onClick = onClick,
        color = resolved,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, outline),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp, vertical = 3.dp),
            ) {
                Text(
                    text = if (merged) "${primary.name} 等" else primary.name,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.Medium,
                    color = onColor,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = primary.locationOrUnknown,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = onColor.copy(alpha = 0.82f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (gfShifted) {
                    Text(
                        text = primary.startAtWithGf?.let { "提前 %02d:%02d".format(it.hour, it.minute) }
                            ?: "提前 10 分钟",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = onColor.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (merged) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiary,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                ) {
                    Text(
                        text = "${courses.size}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                        color = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.padding(horizontal = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UnscheduledSection(courses: List<TimetableCourse>) {
    if (courses.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "未排课（${courses.size}）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = courses.joinToString("、") { it.name },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyTimetable(
    message: String,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    onClick = onOpenSettings,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "去设置课表链接",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                Surface(
                    onClick = onRefresh,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "重新同步",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
