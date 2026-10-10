package com.wakeup.domain

/** 一节具体的课：某天、某个节次区间、某个周次集合。 */
data class Course(
    /** 教务课程号，同一门课的多个时段共用。 */
    val courseId: String,
    val name: String,
    val teacher: String,
    val location: String,
    /** 星期：1=周一 … 7=周日。 */
    val dayOfWeek: Int,
    /** 起始节次（1..12）。 */
    val startPeriod: Int,
    /** 结束节次（含）。 */
    val endPeriod: Int,
    /** 上课周次集合。 */
    val weeks: Set<Int>,
) {
    val periodCount: Int get() = (endPeriod - startPeriod + 1).coerceAtLeast(1)

    val periodLabel: String
        get() = if (startPeriod == endPeriod) "$startPeriod" else "$startPeriod-$endPeriod"

    val locationOrUnknown: String get() = location.ifBlank { "无教室" }

    val teacherOrUnknown: String get() = teacher.ifBlank { "未安排" }

    fun isActiveIn(week: Int): Boolean = week in weeks

    /** 用于冲突检测：同一格内的课程会被合并展示。 */
    val cellKey: String get() = "${dayOfWeek}_$startPeriod"
}

/** 同一门课的所有时段。 */
data class TimetableCourse(
    val courseId: String,
    val name: String,
    val teacher: String,
    val slots: List<Course>,
) {
    val isScheduled: Boolean get() = slots.isNotEmpty()

    val weeks: Set<Int> get() = slots.flatMapTo(linkedSetOf()) { it.weeks }

    val weekLabel: String get() = WeekExpression.format(weeks)

    val locations: List<String>
        get() = slots.map { it.locationOrUnknown }.distinct()
}

/** 整份课表。 */
data class Timetable(
    val term: Term,
    val courses: List<TimetableCourse>,
) {
    /** 已排课的课程。 */
    val scheduled: List<TimetableCourse> get() = courses.filter { it.isScheduled }

    /** 未排课的课程（培养方案里有、但本学期没有排时间的）。 */
    val unscheduled: List<TimetableCourse> get() = courses.filter { !it.isScheduled }

    /** 某一周的全部上课时段，已按星期与节次排序。 */
    fun slotsOfWeek(week: Int): List<Course> =
        scheduled.asSequence()
            .flatMap { it.slots.asSequence() }
            .filter { it.isActiveIn(week) }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod }, { it.name }))
            .toList()

    /** 某一周某天的全部上课时段。 */
    fun slotsOfDay(week: Int, dayOfWeek: Int): List<Course> =
        slotsOfWeek(week).filter { it.dayOfWeek == dayOfWeek }

    /** 按 `星期_起始节次` 归组，方便课表绘制与冲突合并。 */
    fun cellsOfWeek(week: Int): Map<String, List<Course>> =
        slotsOfWeek(week).groupBy { it.cellKey }

    companion object {
        fun empty(): Timetable = Timetable(
            term = Term(name = "", totalWeeks = 20, startEpochSeconds = 0L),
            courses = emptyList(),
        )
    }
}

/** 星期几的中文短名。 */
val DAY_LABELS: List<String> = listOf("一", "二", "三", "四", "五", "六", "日")

fun dayLabel(dayOfWeek: Int): String =
    DAY_LABELS.getOrElse(dayOfWeek - 1) { "?" }
