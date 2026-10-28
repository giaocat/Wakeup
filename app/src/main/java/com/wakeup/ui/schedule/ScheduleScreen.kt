package com.wakeup.ui.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.luminance
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
import com.wakeup.ui.adaptive.AdaptiveWindowInfo
import com.wakeup.ui.adaptive.FoldAwareSplit
import com.wakeup.ui.components.CourseDetailPane
import com.wakeup.ui.theme.courseColorFor
import com.wakeup.ui.theme.courseColorsFor
import com.wakeup.ui.theme.courseOutlineFor
import com.wakeup.util.TimeUtils
import java.time.LocalDate

// 课表网格尺寸：优先自适应铺满屏宽，屏太窄则保持可读宽度并允许横向滚动。
// 所有数值都落在 4dp 基准网格上（列宽 44/60/96、表头 48、行高 56、格间距 4）。
private val GridPeriodColumnWidth = 44.dp
/** 单列可读下限：小于这个值就横向滚动，而不是把课程名挤成一条。 */
private val GridMinDayWidth = 60.dp
/** 单列上限：平板/横屏不把格子拉得过宽。 */
private val GridMaxDayWidth = 96.dp
private val GridHeaderHeight = 48.dp
private val GridRowHeight = 56.dp
private val GridCellGap = 4.dp

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

/**
 * 表头左上角的月份标签。
 *
 * 用**这一周七天的日期**算出来，而不是「今天」：
 * 翻到别的周次时标签要跟着变，否则会一直停在当前月份（踩过的坑：一直是「10月」）。
 *
 * 一周跨月时返回两个月，按时间先后排列（例如 12 月 → 1 月，不会变成 1 月 → 12 月）。
 */
internal fun headerMonthLabels(dates: List<LocalDate>): List<String> =
    dates.map { it.monthValue }.distinct().map { "${it}月" }

/**
 * 课表页。
 *
 * 版式遵循 Material 3：
 * - 顶栏用带副标题的 [TopAppBar]（周次 + 学期/本周），操作区放上一周、下一周、周次选择与溢出菜单；
 * - 离线提示是 `secondaryContainer` 横幅；
 * - 空/错误状态是「图标 + titleMedium + bodyMedium + TextButton」；
 * - 网格保持双轴滚动结构（左侧节次栏与右侧日网格共享滚动状态）。
 *
 * 自适应排列由 [adaptive] 决定（尺寸类来自 Activity 边界）：
 * - 手机竖屏 / 中等宽度：单栏课表，课程详情仍走底部面板（[showDetailPane] = false）；
 * - 展开宽度：课表在左、课程详情内嵌在右侧面板；
 * - 折叠屏：竖向铰链左右分栏、横向铰链（桌面姿态）改为上下分栏。
 */
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
    adaptive: AdaptiveWindowInfo = AdaptiveWindowInfo(),
    selectedCourse: Course? = null,
    showDetailPane: Boolean = false,
) {
    var weekPickerExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 顶部交给 TopAppBar，底部交给根 Scaffold 的 NavigationBar；这里只保留横向安全区。
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            ScheduleTopBar(
                state = state,
                weekPickerExpanded = weekPickerExpanded,
                onWeekPickerExpandedChange = { weekPickerExpanded = it },
                onPreviousWeek = onPreviousWeek,
                onNextWeek = onNextWeek,
                onSelectWeek = onSelectWeek,
                onRefresh = onRefresh,
            )
        },
        floatingActionButton = {
            val currentWeek = state.currentWeek
            AnimatedVisibility(
                visible = currentWeek != null && currentWeek != state.selectedWeek,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
            ) {
                SmallExtendedFloatingActionButton(
                    onClick = { currentWeek?.let(onSelectWeek) },
                    text = { Text("回到本周") },
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Today,
                            contentDescription = null,
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 已有数据时的刷新：细进度条，避免整页闪成加载态。
            if (state.isLoading && state.hasData) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.offlineNotice?.let { notice ->
                OfflineBanner(
                    notice = notice,
                    retryEnabled = !state.isLoading,
                    onRetry = onRefresh,
                )
            }

            when {
                state.isLoading && !state.hasData -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }

                !state.hasData -> EmptyTimetable(
                    state = state,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize(),
                )

                showDetailPane -> TimetableWithDetailPane(
                    state = state,
                    adaptive = adaptive,
                    selectedCourse = selectedCourse,
                    onCourseClick = onCourseClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                else -> {
                    TimetableGrid(
                        timetable = state.timetable,
                        week = state.selectedWeek,
                        today = state.today,
                        onCourseClick = onCourseClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                    )

                    UnscheduledSection(courses = state.unscheduledCourses)
                }
            }
        }
    }
}

/** 单行顶栏：周次标题 + 学期副标题，操作区为上一周 / 下一周 / 周次选择 / 溢出菜单。 */
@Composable
private fun ScheduleTopBar(
    state: ScheduleUiState,
    weekPickerExpanded: Boolean,
    onWeekPickerExpandedChange: (Boolean) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onSelectWeek: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    val totalWeeks = state.timetable.term.totalWeeks.coerceAtLeast(1)
    val isCurrentWeekShown = state.currentWeek == state.selectedWeek

    TopAppBar(
        title = { Text("第 ${state.selectedWeek} 周") },
        subtitle = {
            Text(
                text = buildString {
                    if (isCurrentWeekShown) append("本周")
                    val termName = state.timetable.term.name
                    if (termName.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(termName)
                    }
                    if (!state.hasSource) {
                        if (isNotEmpty()) append(" · ")
                        append("未设置课表链接")
                    }
                    if (isEmpty()) append("共 $totalWeeks 周")
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        actions = {
            IconButton(onClick = onPreviousWeek, enabled = state.selectedWeek > 1) {
                Icon(
                    imageVector = Icons.Outlined.ChevronLeft,
                    contentDescription = "上一周",
                )
            }
            IconButton(onClick = onNextWeek, enabled = state.selectedWeek < totalWeeks) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = "下一周",
                )
            }

            Box {
                IconButton(onClick = { onWeekPickerExpandedChange(true) }) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = "选择周次",
                    )
                }
                WeekPickerMenu(
                    expanded = weekPickerExpanded,
                    onDismissRequest = { onWeekPickerExpandedChange(false) },
                    selectedWeek = state.selectedWeek,
                    currentWeek = state.currentWeek,
                    totalWeeks = totalWeeks,
                    onSelectWeek = onSelectWeek,
                )
            }

            Box {
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "更多操作",
                    )
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("刷新") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                            )
                        },
                        enabled = !state.isLoading,
                        onClick = {
                            overflowExpanded = false
                            onRefresh()
                        },
                    )
                }
            }
        },
    )
}

/**
 * 周次选择菜单。
 *
 * 注意：不能用 LazyColumn —— DropdownMenu 的弹窗给的是无限高度约束，
 * 惰性布局在无限约束下测量会直接抛异常（点开即闪退）。
 * 周次最多几十项，用可滚动的 Column 既安全又够快。
 */
@Composable
private fun WeekPickerMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    selectedWeek: Int,
    currentWeek: Int?,
    totalWeeks: Int,
    onSelectWeek: (Int) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .widthIn(min = 160.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for (week in 1..totalWeeks) {
                val isSelected = week == selectedWeek
                DropdownMenuItem(
                    text = {
                        Text(
                            text = buildString {
                                append("第 $week 周")
                                if (currentWeek == week) append(" · 本周")
                            },
                        )
                    },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        onDismissRequest()
                        onSelectWeek(week)
                    },
                )
            }
        }
    }
}

/** 离线 / 同步失败的横幅：secondaryContainer + labelLarge。 */
@Composable
private fun OfflineBanner(
    notice: String,
    retryEnabled: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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

/**
 * 大屏 / 折叠屏的「课表 + 课程详情」双栏。
 *
 * 两种排法都不会把内容画到折叠线上（分栏由 [FoldAwareSplit] 按铰链真实位置切开）：
 * - 竖向铰链（书本姿态）或普通展开宽度：课表在左、详情在右；
 * - 横向铰链（桌面姿态）：课表在上、详情在下。
 *
 * 「未排课」列表跟着课表一起放：横向分栏时放在下半区详情面板下方，避免挤占网格高度。
 */
@Composable
private fun TimetableWithDetailPane(
    state: ScheduleUiState,
    adaptive: AdaptiveWindowInfo,
    selectedCourse: Course?,
    onCourseClick: (Course) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timetable = state.timetable

    // 左栏（竖向分栏）/ 上半区（横向分栏）：课表 + 未排课
    val gridPane: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
            TimetableGrid(
                timetable = timetable,
                week = state.selectedWeek,
                today = state.today,
                onCourseClick = onCourseClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp),
            )
            UnscheduledSection(courses = state.unscheduledCourses)
        }
    }

    if (adaptive.hasHorizontalFold) {
        // 桌面姿态：课表在上，详情在铰链下方
        FoldAwareSplit(
            vertical = false,
            foldBounds = adaptive.foldBounds,
            gap = adaptive.paneGap,
            modifier = modifier,
            firstWeight = 1.1f,
            secondWeight = 0.9f,
            first = {
                TimetableGrid(
                    timetable = timetable,
                    week = state.selectedWeek,
                    today = state.today,
                    onCourseClick = onCourseClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                )
            },
            second = {
                Column(modifier = Modifier.fillMaxSize()) {
                    CourseDetailPane(
                        course = selectedCourse,
                        isSutSource = timetable.isSutSource,
                        emptyText = "点上方课程查看详情",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                    )
                    UnscheduledSection(courses = state.unscheduledCourses)
                }
            },
        )
    } else {
        // 书本姿态（竖向铰链）或普通展开宽度：课表在左，详情在右
        FoldAwareSplit(
            vertical = true,
            foldBounds = if (adaptive.hasVerticalFold) adaptive.foldBounds else null,
            gap = adaptive.paneGap,
            modifier = modifier,
            firstWeight = 1.8f,
            secondWeight = 1f,
            first = { gridPane() },
            second = {
                CourseDetailPane(
                    course = selectedCourse,
                    isSutSource = timetable.isSutSource,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 8.dp, end = 16.dp),
                )
            },
        )
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
    // 表头左上角的月份标签：由**展示中的这一周**的日期推出来，翻周次会跟着变。
    // 还没拿到课表（term 是占位的空值）时退回当前月份，别显示 1970 年的月份。
    val headerMonths = remember(weekDates, timetable) {
        if (timetable.courses.isEmpty()) {
            listOf("${today.monthValue}月")
        } else {
            headerMonthLabels(weekDates)
        }
    }
    val todayDayOfWeek = today.dayOfWeek.value
    val isCurrentWeekShown = timetable.term.weekOf(today) == week
    val todayIndex = if (isCurrentWeekShown) todayDayOfWeek - 1 else -1

    // 左侧时间轴固定显示**官方作息**（见 PeriodTimes.all）。
    // 不要按"当周有没有 G/F 座的课"去改轴上的时间：右侧每门课有各自的时间，
    // 轴一旦跟着某门课走，就会和大多数课程对不上（这是踩过的坑）。
    // G/F 座的提前时间只体现在格子内部（"提前 10:15"）。
    var nowOffset by remember {
        mutableFloatStateOf(TimeUtils.gridOffsetFor(TimeUtils.currentTime()))
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            nowOffset = TimeUtils.gridOffsetFor(TimeUtils.currentTime())
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
                    // 月份必须跟随**当前展示的这一周**，而不是打开 App 那天所在的月份。
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        headerMonths.forEach { month ->
                            Text(
                                text = month,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 主体：左侧节次栏与右侧网格共用纵向滚动，右侧另有横向滚动
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier
                        .width(GridPeriodColumnWidth)
                        .verticalScroll(verticalScroll),
                ) {
                    PeriodTimes.all.forEach { period ->
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

/** 左侧节次标签：节次号 + 开始时间；时间用 8sp 轴标签（唯一的字号例外）。 */
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
            )
            Text(
                text = "%02d:%02d".format(period.start.hour, period.start.minute),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 星期 / 日期表头；今天用主色圆形强调（Material 日历的既有做法）。 */
@Composable
private fun DayHeader(
    label: String,
    date: Int,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            color = if (isToday) primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    color = if (isToday) primary else Color.Transparent,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$date",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                color = if (isToday) onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 网格内容：行底色 + 今天列强调 + 课程卡片 + 当前时刻线。 */
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
    val rowTint = MaterialTheme.colorScheme.surfaceContainerLow
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val todayTint = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
    val nowColor = MaterialTheme.colorScheme.error

    Box(
        modifier = modifier.width(dayWidth * 7),
    ) {
        // 交错的行底色 + 行分隔线，便于横向阅读
        Column(modifier = Modifier.fillMaxSize()) {
            periods.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GridRowHeight)
                        .background(if (index % 2 == 0) rowTint else Color.Transparent),
                ) {
                    HorizontalDivider(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        color = lineColor.copy(alpha = 0.6f),
                    )
                }
            }
        }

        // 今天那一列的底色强调
        if (todayIndex in 0..6) {
            Box(
                modifier = Modifier
                    .offset(x = dayWidth * todayIndex)
                    .width(dayWidth)
                    .fillMaxHeight()
                    .background(todayTint),
            )
        }

        // 星期分隔线
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(7) {
                Box(
                    modifier = Modifier
                        .width(dayWidth)
                        .fillMaxHeight()
                        .border(width = 1.dp, color = lineColor.copy(alpha = 0.5f)),
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
                isSutSource = isSutSource,
                onClick = { onCourseClick(anchor) },
                modifier = Modifier
                    .offset(
                        x = dayWidth * (anchor.dayOfWeek - 1) + GridCellGap,
                        y = GridRowHeight * (anchor.startPeriod - 1) + GridCellGap,
                    )
                    .width(dayWidth - GridCellGap * 2)
                    .height(GridRowHeight * anchor.periodCount - GridCellGap * 2),
            )
        }

        // 当前时刻线（2dp）
        if (showNowLine && todayIndex in 0..6) {
            Box(
                modifier = Modifier
                    .offset(
                        x = dayWidth * todayIndex + GridCellGap,
                        y = GridRowHeight * nowOffset,
                    )
                    .width(dayWidth - GridCellGap * 2)
                    .height(2.dp)
                    .background(nowColor, CircleShape),
            )
        }
    }
}

/**
 * 单门课 / 冲突合并后的课程卡片。
 *
 * 颜色使用 `ui/theme/Color.kt` 里的课程色槽位；文字颜色按底色亮度在黑白之间取反，
 * 保证浅色槽位上依然有足够对比度。
 */
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
    val onColor = when {
        merged -> MaterialTheme.colorScheme.onSurfaceVariant
        // 0.18 是黑白文字对比度相等的临界相对亮度：低于它用白字、高于它用黑字，
        // 这样 28 个课程色槽位（深浅不同、色相不同）上的文字都能保持 ≥ 4.5:1。
        resolved.luminance() > 0.18f -> Color.Black
        else -> Color.White
    }
    val outline = if (merged) MaterialTheme.colorScheme.outline else courseOutlineFor(resolved)
    // GF 座是沈工大的楼：既要教室名匹配，也要课表来源是沈工大域名
    val gfShifted = !merged &&
        isSutSource &&
        primary.looksLikeGfBuilding &&
        (PeriodTimes.differsFromStandard(primary.startPeriod, primary.location) ||
            PeriodTimes.differsFromStandard(primary.endPeriod, primary.location))
    val periodCount = primary.periodCount
    // 格子可用高度 = 56×节数 − 8（格间距）− 8（内边距）。按行高预算分配行数，
    // 避免 1 节的小格子里教室行被裁掉。
    val maxNameLines = when {
        periodCount == 1 -> 1
        periodCount == 2 -> 2
        else -> 3
    }
    val maxLocationLines = if (periodCount == 1) 1 else 2
    // 「提前」提示只在 2 节及以上的格子里显示（1 节格子放不下三行）。
    val showGfNote = gfShifted && periodCount >= 2

    Surface(
        onClick = onClick,
        color = resolved,
        contentColor = onColor,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, outline),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            ) {
                Text(
                    text = if (merged) "${primary.name} 等" else primary.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = onColor,
                    maxLines = maxNameLines,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = primary.locationOrUnknown,
                    style = MaterialTheme.typography.labelSmall,
                    color = onColor.copy(alpha = 0.9f),
                    maxLines = maxLocationLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showGfNote) {
                    Text(
                        text = primary.startAtWithGf?.let { "提前 %02d:%02d".format(it.hour, it.minute) }
                            ?: "提前 10 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = onColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (merged) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${courses.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
        }
    }
}

/** 未排课课程：培养方案里有、但本学期没有安排时间的课程。 */
@Composable
private fun UnscheduledSection(courses: List<TimetableCourse>) {
    if (courses.isEmpty()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "未排课（${courses.size}）",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = courses.joinToString("、") { it.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 空数据 / 同步失败状态：图标 + titleMedium + bodyMedium + TextButton。 */
@Composable
private fun EmptyTimetable(
    state: ScheduleUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasError = state.errorMessage != null
    val title = when {
        hasError -> "同步失败"
        !state.hasSource -> "还没有课表"
        else -> "还没有排课数据"
    }
    val message = when {
        hasError -> state.errorMessage.orEmpty()
        !state.hasSource -> "在「设置」里粘贴课表链接或 token，保存后会自动同步。"
        else -> "当前学期还没有可以展示的课程，点「重新同步」再试一次。"
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
                imageVector = Icons.Outlined.EventBusy,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh) {
                    Text(if (hasError) "重试" else "重新同步")
                }
                TextButton(onClick = onOpenSettings) {
                    Text(if (state.hasSource) "修改课表链接" else "去设置课表链接")
                }
            }
        }
    }
}
