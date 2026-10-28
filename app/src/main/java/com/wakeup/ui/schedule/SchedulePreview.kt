package com.wakeup.ui.schedule

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wakeup.data.repository.ThemeMode
import com.wakeup.domain.Course
import com.wakeup.domain.Term
import com.wakeup.domain.Timetable
import com.wakeup.domain.TimetableCourse
import com.wakeup.ui.ScheduleUiState
import com.wakeup.ui.adaptive.AdaptiveWindowInfo
import com.wakeup.ui.adaptive.FoldPosture
import com.wakeup.ui.theme.WakeupTheme
import java.time.LocalDate

/**
 * 课表页预览，用于在 Android Studio 里直接检查布局密度。
 *
 * 预览用的是构造出来的示例数据，只服务于设计稿，不会进入 App 的任何运行时路径。
 */
@Preview(name = "课表 手机", showBackground = true, widthDp = 360, heightDp = 640)
@Preview(name = "课表 大屏", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun ScheduleScreenPreview() {
    WakeupTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ScheduleScreen(
                state = previewState(),
                onPreviousWeek = {},
                onNextWeek = {},
                onSelectWeek = {},
                onRefresh = {},
                onOpenSettings = {},
                onCourseClick = {},
            )
        }
    }
}

/** 空状态预览：检查「图标 + 标题 + 说明 + 文字按钮」的官方空状态版式。 */
@Preview(name = "课表 空状态", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun ScheduleScreenEmptyPreview() {
    WakeupTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ScheduleScreen(
                state = ScheduleUiState(timetable = Timetable.empty(), hasSource = false),
                onPreviousWeek = {},
                onNextWeek = {},
                onSelectWeek = {},
                onRefresh = {},
                onOpenSettings = {},
                onCourseClick = {},
            )
        }
    }
}

/**
 * 平板 / 折叠屏展开态预览：左侧课表 + 右侧内嵌课程详情（列表-详情双栏）。
 *
 * 用来检查「Expanded 宽度不再弹底部面板」这一版式；预览数据只服务设计稿。
 */
@Preview(name = "课表 平板双栏", showBackground = true, widthDp = 1100, heightDp = 760)
@Composable
private fun ScheduleScreenTwoPanePreview() {
    WakeupTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val state = previewState()
            ScheduleScreen(
                state = state,
                onPreviousWeek = {},
                onNextWeek = {},
                onSelectWeek = {},
                onRefresh = {},
                onOpenSettings = {},
                onCourseClick = {},
                adaptive = AdaptiveWindowInfo(widthSizeClass = WindowWidthSizeClass.Expanded),
                selectedCourse = state.timetable.slotsOfWeek(state.selectedWeek).firstOrNull(),
                showDetailPane = true,
            )
        }
    }
}

/**
 * 折叠屏桌面姿态预览：横向铰链把课表与课程详情上下分开，中间是铰链高度。
 */
@Preview(name = "课表 折叠屏桌面姿态", showBackground = true, widthDp = 900, heightDp = 800)
@Composable
private fun ScheduleScreenTabletopPreview() {
    WakeupTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val state = previewState()
            ScheduleScreen(
                state = state,
                onPreviousWeek = {},
                onNextWeek = {},
                onSelectWeek = {},
                onRefresh = {},
                onOpenSettings = {},
                onCourseClick = {},
                adaptive = AdaptiveWindowInfo(
                    widthSizeClass = WindowWidthSizeClass.Expanded,
                    foldPosture = FoldPosture.HORIZONTAL,
                    foldGap = 32.dp,
                ),
                selectedCourse = state.timetable.slotsOfWeek(state.selectedWeek).firstOrNull(),
                showDetailPane = true,
            )
        }
    }
}

/** 构造一份形态接近真实课表的数据：满课、跨节次、以及一个冲突格。全部为虚构课程。 */
private fun previewState(): ScheduleUiState {
    val weeks = (1..20).toSet()
    val courses = listOf(
        previewCourse("高等数学A（一）", "甲老师", "A-101", 1, 1, 2, weeks),
        previewCourse("思想道德与法治", "丁老师", "D-105", 1, 3, 4, weeks),
        previewCourse("程序设计基础", "丙老师", "C-303", 1, 5, 6, weeks),
        previewCourse("高等数学A（一）", "甲老师", "A-101", 2, 3, 4, weeks),
        previewCourse("大学英语（一）", "戊老师", "E-201", 2, 5, 6, weeks),
        previewCourse("数据结构与算法", "子老师", "C-303", 2, 7, 8, weeks),
        previewCourse("大学物理（一）", "乙老师", "B-202", 3, 3, 4, weeks),
        previewCourse("军事理论", "庚老师", "F-404", 3, 9, 10, weeks),
        previewCourse("体育（一）", "己老师", "", 4, 5, 6, weeks),
        previewCourse("程序设计基础", "丙老师", "C-303", 4, 7, 8, weeks),
        previewCourse("大学生心理健康教育", "辛老师", "G-101", 4, 9, 10, weeks),
        previewCourse("高等数学A（一）", "甲老师", "A-101", 5, 1, 2, weeks),
        previewCourse("大学英语（一）", "戊老师", "E-201", 5, 3, 4, weeks),
        // 与上一门同一天同一节，用来展示冲突合并
        previewCourse("数据结构与算法", "子老师", "C-303", 5, 3, 4, weeks),
        previewCourse("程序设计实验", "丑老师", "C-401", 5, 11, 12, weeks),
        // GF 座：3、4 节提前 10 分钟（10:15 开始），格子上会标出"提前"
        previewCourse("工程训练", "壬老师", "GF-204", 2, 3, 4, weeks),
        previewCourse("工程训练", "壬老师", "GF-204", 6, 3, 4, weeks),
    )

    val term = Term(
        name = "2026-2027-1",
        totalWeeks = 20,
        startEpochSeconds = 1_787_500_800L,
    )
    return ScheduleUiState(
        timetable = Timetable(term = term, courses = courses),
        selectedWeek = 7,
        currentWeek = 7,
        today = LocalDate.of(2026, 10, 6),
        lastSyncedAt = System.currentTimeMillis(),
        userName = "预览",
    )
}

private fun previewCourse(
    name: String,
    teacher: String,
    location: String,
    dayOfWeek: Int,
    startPeriod: Int,
    endPeriod: Int,
    weeks: Set<Int>,
): TimetableCourse {
    val slot = Course(
        courseId = "$name-$dayOfWeek-$startPeriod",
        name = name,
        teacher = teacher,
        location = location,
        dayOfWeek = dayOfWeek,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = weeks,
    )
    return TimetableCourse(
        courseId = slot.courseId,
        name = name,
        teacher = teacher,
        slots = listOf(slot),
    )
}
