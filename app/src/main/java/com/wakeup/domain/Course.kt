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

    /** 教室是否属于 G 座 / F 座（这两栋楼的 3、4 节提前 10 分钟）。 */
    val isEarlyBuilding: Boolean get() = PeriodTimes.isEarlyBuilding(location)

    /** 旧名，等价于 [isEarlyBuilding]。 */
    val looksLikeGfBuilding: Boolean get() = isEarlyBuilding

    /**
     * 本课程的节次是否按 G/F 座的提前作息（3、4 节提前 10 分钟）。
     *
     * G/F 座是**沈阳工业大学**的楼，所以除了教室名匹配，还要求数据来源是沈工大域名
     * （见 [Timetable.isSutSource]）。其它学校的课表即使教室名以 G/F 开头也不会被改时间。
     */
    fun usesGfSchedule(timetable: Timetable): Boolean =
        timetable.isSutSource && isEarlyBuilding

    /** 起始节次时间（G/F 座含提前偏移）。 */
    val startAtWithGf: java.time.LocalTime? get() = PeriodTimes.startOf(startPeriod, location)

    /** 结束节次时间（G/F 座含提前偏移）。 */
    val endAtWithGf: java.time.LocalTime? get() = PeriodTimes.endOf(endPeriod, location)

    /** 起始节次的标准时间（不含偏移）。 */
    val standardStartAt: java.time.LocalTime? get() = PeriodTimes.startOf(startPeriod)

    /** 结束节次的标准时间（不含偏移）。 */
    val standardEndAt: java.time.LocalTime? get() = PeriodTimes.endOf(endPeriod)

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
    /** 课表来源地址；用来判断是哪所学校的课表（见 [isSutSource]）。 */
    val sourceUrl: String = "",
) {
    /**
     * 数据是否来自沈阳工业大学。
     *
     * GF 座（3、4 节提前 10 分钟）是沈工大特有的作息，必须挂在域名上判断，
     * 否则别的学校只要有间教室叫「GF-xxx」就会被错误地改时间。
     */
    val isSutSource: Boolean get() = isSutDomain(sourceUrl)

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
