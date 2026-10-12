package com.wakeup.util

import com.wakeup.domain.Course
import com.wakeup.domain.PeriodTime
import com.wakeup.domain.PeriodTimes
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 时间相关的工具函数，集中放在这里方便测试与替换。 */
object TimeUtils {

    val zone: ZoneId get() = ZoneId.systemDefault()

    fun now(): LocalDateTime = LocalDateTime.now(zone)

    fun today(): LocalDate = LocalDate.now(zone)

    fun currentTime(): LocalTime = LocalTime.now(zone)

    fun nowEpochSeconds(): Long = Instant.now().epochSecond

    /** 把日期格式化成 `10月9日 周五`。 */
    fun formatDateWithWeekday(date: LocalDate): String {
        val weekday = WEEKDAY_LABELS.getOrElse(date.dayOfWeek.value - 1) { "" }
        return "${date.monthValue}月${date.dayOfMonth}日 周$weekday"
    }

    fun formatDate(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    fun formatTime(time: LocalTime): String =
        "%02d:%02d".format(time.hour, time.minute)

    /** 相对时间，例如"刚刚""12 分钟前"。 */
    fun relativeTime(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        if (epochMillis <= 0L) return "尚未同步"
        val diff = (nowMillis - epochMillis) / 1000
        return when {
            diff < 60 -> "刚刚"
            diff < 3600 -> "${diff / 60} 分钟前"
            diff < 86_400 -> "${diff / 3600} 小时前"
            diff < 604_800 -> "${diff / 86_400} 天前"
            else -> DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
                Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate(),
            )
        }
    }

    /** 一天中的分钟数（用于课表时刻线定位）。 */
    fun minuteOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

    /**
     * 时刻线在课表网格中的纵向位置（单位：节次高度）。
     *
     * 返回浮点值表示"第几节位置"，例如 2.5 表示第 2 节与第 3 节之间。
     *
     * [periods] 是**当周实际生效**的作息。不能固定用标准作息：G 座 / F 座
     * 的 3、4 节提前 10 分钟，用标准时间算出来的时刻线会偏离格子里的课。
     */
    fun gridOffsetFor(
        time: LocalTime,
        periods: List<PeriodTime> = PeriodTimes.all,
    ): Float {
        if (periods.isEmpty()) return 0f
        val minutes = minuteOfDay(time)

        val first = periods.first()
        if (minutes <= minuteOfDay(first.start)) return 0f

        for (period in periods) {
            val start = minuteOfDay(period.start)
            val end = minuteOfDay(period.end)
            val index = period.period - 1
            when {
                minutes in start..end -> {
                    val fraction = (minutes - start).toFloat() / (end - start).coerceAtLeast(1)
                    return index + fraction
                }
                minutes < start -> {
                    // 在两个节次之间，按空档比例插值
                    val previousEnd = periods.getOrNull(index - 1)?.let { minuteOfDay(it.end) } ?: start
                    val span = (start - previousEnd).coerceAtLeast(1)
                    val fraction = (minutes - previousEnd).toFloat() / span
                    return (index - 1).coerceAtLeast(0) + fraction.coerceIn(0f, 1f)
                }
            }
        }
        return periods.size.toFloat()
    }

    /**
     * 某一周实际生效的作息。
     *
     * 以标准作息为基准，只要该周有使用提前作息的课（G 座 / F 座的 3、4 节），
     * 就把对应节次换成提前后的时间，使侧栏时间与格子内容一致。
     */
    fun effectivePeriods(courses: List<Course>): List<PeriodTime> =
        PeriodTimes.all.map { standard ->
            val early = courses
                .asSequence()
                .filter { it.isEarlyBuilding }
                .mapNotNull { PeriodTimes.of(standard.period, it.location) }
                .filter { it != standard }
                .minByOrNull { it.start }
            early ?: standard
        }

    private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
}
