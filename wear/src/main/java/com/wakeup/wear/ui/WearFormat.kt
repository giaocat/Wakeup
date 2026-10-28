package com.wakeup.wear.ui

import com.wakeup.domain.Course
import com.wakeup.domain.Term
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 一天有几天 —— 手表端横向翻页一共 7 页。 */
internal const val DAYS_OF_WEEK: Int = 7

private val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val MONTH_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

/** `周三`。 */
internal fun weekdayName(day: Int): String =
    WEEKDAY_NAMES.getOrElse(day - 1) { "周?" }

/** `10月28日`。 */
internal fun formatDate(date: LocalDate): String = MONTH_DAY.format(date)

/** `10:15`。 */
internal fun formatClock(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

/** 课程起止时间；节次没有对应时间时返回空串。 */
internal fun clockRange(course: Course, timetable: com.wakeup.domain.Timetable): String {
    val start = course.effectiveStartAt(timetable) ?: return ""
    val end = course.effectiveEndAt(timetable) ?: return ""
    return "${formatClock(start)}–${formatClock(end)}"
}

/** 某一周的日期范围：`10.26 - 11.01`。 */
internal fun weekRangeLabel(term: Term, week: Int): String {
    val monday = term.mondayOfWeek(week)
    val sunday = monday.plusDays(6)
    return "${monday.monthValue}.${monday.dayOfMonth} - ${sunday.monthValue}.${sunday.dayOfMonth}"
}

/** 课程在列表里的稳定 key。 */
internal fun courseKey(course: Course): String =
    "${course.courseId}-${course.dayOfWeek}-${course.startPeriod}"
