package com.wakeup.util

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
     */
    fun gridOffsetFor(time: LocalTime): Float {
        val periods = PeriodTimes.all
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

    private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")
}
