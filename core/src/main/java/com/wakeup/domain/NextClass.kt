package com.wakeup.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 某一门课在**某个具体日期**的上课安排 —— 把「周次 + 星期几」落到真实日期上。
 *
 * 课表数据本身只有周次集合（如"第 4-5 周"）和星期几，界面要显示"接下来哪天几点上什么课"
 * 就必须先算出具体日期，这个类就是这个产物。
 */
data class CourseOccurrence(
    val course: Course,
    /** 实际上课日期。 */
    val date: LocalDate,
    /** 该日期所在的周次。 */
    val week: Int,
    /** 上课时间（已按教室应用 G/F 座的提前作息）。 */
    val start: LocalTime,
    /** 下课时间（同上）。 */
    val end: LocalTime,
) {
    val name: String get() = course.name
    val periodLabel: String get() = course.periodLabel
    val location: String get() = course.locationOrUnknown

    /** [time] 是否正处在这节课内。 */
    fun isOngoing(time: LocalTime): Boolean = !time.isBefore(start) && !time.isAfter(end)

    /**
     * 相对 [today] 的简短日期描述：`今天` / `明天` / `后天` / `周三`。
     *
     * 用绝对日期而不是"还有几小时"，这样展示出来的内容不会随时间变旧 ——
     * 桌面小组件的刷新并不可靠，渲染时才计算的内容才靠得住。
     */
    fun dayLabel(today: LocalDate): String = when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> "周" + WEEKDAY_CHARS[date.dayOfWeek.value - 1]
    }

    companion object {
        internal val WEEKDAY_CHARS = listOf("一", "二", "三", "四", "五", "六", "日")
    }
}

/**
 * 展示用的副标题：`今天 第3-4节 10:15 · F-105`。
 *
 * 正在上课时换成 `正在上课 · 至 11:50 · F-105` —— 否则"下一节"读起来别扭。
 * 用绝对时间而不是"还有 N 分钟"，内容不会因为小组件刷新不及时而变旧。
 */
fun CourseOccurrence.subtitle(today: LocalDate, time: LocalTime): String = if (
    date == today && isOngoing(time)
) {
    "正在上课 · 至 ${formatClock(end)} · $location"
} else {
    "${dayLabel(today)} 第${periodLabel}节 ${formatClock(start)} · $location"
}

private fun formatClock(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

/** 往后最多找几天。一周多一天，足以覆盖"今天上完了、看下周同一天"。 */
const val NEXT_COURSE_LOOKAHEAD_DAYS: Int = 7

/**
 * 找出 [now] 之后最近的一节课，**包含"正在进行"的那一节**。
 *
 * 从今天起逐天查找：今天取第一节还没下课的课（正在上的也算），之后几天取最早的一节。
 * 周次、假期、以及 G/F 座的提前作息都在这里处理。返回 null 表示未来一周都没有课。
 *
 * 时间统一按学校时区（[Term.TERM_ZONE]）解释，与课表数据保持一致。
 */
fun Timetable.nextOccurrence(
    now: LocalDateTime = LocalDateTime.now(Term.TERM_ZONE),
    lookAheadDays: Int = NEXT_COURSE_LOOKAHEAD_DAYS,
): CourseOccurrence? {
    val today = now.toLocalDate()
    val nowTime = now.toLocalTime()
    for (offset in 0..lookAheadDays) {
        val date = today.plusDays(offset.toLong())
        val week = term.weekOf(date) ?: continue
        val occurrences = slotsOfDay(week, date.dayOfWeek.value)
            .mapNotNull { slot ->
                val start = slot.effectiveStartAt(this) ?: return@mapNotNull null
                val end = slot.effectiveEndAt(this) ?: return@mapNotNull null
                CourseOccurrence(slot, date, week, start, end)
            }
            .sortedBy { it.start }
        val found = if (offset == 0) {
            // 今天：跳过已经下课的，正在上课的算"最近一节"
            occurrences.firstOrNull { !nowTime.isAfter(it.end) }
        } else {
            occurrences.firstOrNull()
        }
        if (found != null) return found
    }
    return null
}
