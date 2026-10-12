package com.wakeup.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 解析教务系统的周次表达式。
 *
 * 支持 `7-13`、`4-5,7-17`、`4,7-9,11-13`、`10-19` 以及单个 `14` 这类写法。
 */
object WeekExpression {

    /** 把 `4-5,7-17` 解析成 [4, 5, 7, 8, ... 17]。无法识别的片段会被忽略。 */
    fun parse(expression: String): Set<Int> {
        if (expression.isBlank()) return emptySet()
        val weeks = linkedSetOf<Int>()
        for (rawPart in expression.split(',', '，', ';', '；')) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue
            val rangeMatch = RANGE_REGEX.matchEntire(part)
            if (rangeMatch != null) {
                val from = rangeMatch.groupValues[1].toIntOrNull()
                val to = rangeMatch.groupValues[2].toIntOrNull()
                if (from != null && to != null && from <= to && to - from <= MAX_WEEKS) {
                    for (week in from..to) weeks.add(week)
                }
                continue
            }
            val single = part.toIntOrNull()
            if (single != null && single in 0..MAX_WEEKS) weeks.add(single)
        }
        return weeks
    }

    /** 把周次集合压缩回 `4-5,7-17` 这样的紧凑写法。 */
    fun format(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var previous = start
        for (index in 1 until sorted.size) {
            val current = sorted[index]
            if (current == previous + 1) {
                previous = current
                continue
            }
            parts.add(if (start == previous) "$start" else "$start-$previous")
            start = current
            previous = current
        }
        parts.add(if (start == previous) "$start" else "$start-$previous")
        return parts.joinToString(",")
    }

    private val RANGE_REGEX = Regex("^(\\d{1,2})\\s*-\\s*(\\d{1,2})$")
    private const val MAX_WEEKS = 40
}

/** 一节课的上课时间，用于课表侧栏与"今日"时间线。 */
data class PeriodTime(
    val period: Int,
    val start: LocalTime,
    val end: LocalTime,
) {
    val label: String
        get() = "%02d:%02d".format(start.hour, start.minute)

    val rangeLabel: String
        get() = "%02d:%02d-%02d:%02d".format(
            start.hour, start.minute, end.hour, end.minute,
        )
}

/**
 * 判断课表来源地址是否属于沈阳工业大学。
 *
 * GF 座作息是沈工大特有的，只有来源域名是沈工大才生效，
 * 避免其它学校的同名教室被误改上课时间。
 */
fun isSutDomain(sourceUrl: String?): Boolean {
    val url = sourceUrl?.trim()?.lowercase().orEmpty()
    if (url.isEmpty()) return false
    // 去掉协议，只留 host(/path)
    val host = url.substringAfter("://", url).substringBefore('/')
    return host == "sut.edu.cn" ||
        host.endsWith(".sut.edu.cn") ||
        host == "sut.edu.cn:443" ||
        host.startsWith("sut.edu.cn:")
}

/**
 * 沈阳工业大学作息时间表（每两节为一个大节）。
 * 仅用于展示课程起止时间，不影响课表数据本身。
 */
object PeriodTimes {

    val all: List<PeriodTime> = listOf(
        PeriodTime(1, LocalTime.of(8, 30), LocalTime.of(9, 15)),
        PeriodTime(2, LocalTime.of(9, 20), LocalTime.of(10, 5)),
        PeriodTime(3, LocalTime.of(10, 25), LocalTime.of(11, 10)),
        PeriodTime(4, LocalTime.of(11, 15), LocalTime.of(12, 0)),
        PeriodTime(5, LocalTime.of(13, 30), LocalTime.of(14, 15)),
        PeriodTime(6, LocalTime.of(14, 20), LocalTime.of(15, 5)),
        PeriodTime(7, LocalTime.of(15, 25), LocalTime.of(16, 10)),
        PeriodTime(8, LocalTime.of(16, 15), LocalTime.of(17, 0)),
        PeriodTime(9, LocalTime.of(18, 0), LocalTime.of(18, 45)),
        PeriodTime(10, LocalTime.of(18, 50), LocalTime.of(19, 35)),
        PeriodTime(11, LocalTime.of(19, 40), LocalTime.of(20, 25)),
        PeriodTime(12, LocalTime.of(20, 30), LocalTime.of(21, 15)),
    )

    private val byPeriod: Map<Int, PeriodTime> = all.associateBy { it.period }

    /** GF 座的教学楼代码前缀（如 `GF-101`）。 */
    const val GF_BUILDING_PREFIX: String = "GF"

    /** GF 座在 3、4 节提前 10 分钟上下课。 */
    private val gfTimes: Map<Int, PeriodTime> = mapOf(
        3 to PeriodTime(3, LocalTime.of(10, 15), LocalTime.of(11, 0)),
        4 to PeriodTime(4, LocalTime.of(11, 5), LocalTime.of(11, 50)),
    )

    fun of(period: Int): PeriodTime? = byPeriod[period]

    fun startOf(period: Int): LocalTime? = byPeriod[period]?.start

    fun endOf(period: Int): LocalTime? = byPeriod[period]?.end

    /** 某个教室的上课时间；除 GF 座的 3、4 节外与标准作息一致。 */
    fun of(period: Int, location: String?): PeriodTime? {
        if (isGfBuilding(location)) gfTimes[period]?.let { return it }
        return byPeriod[period]
    }

    fun startOf(period: Int, location: String?): LocalTime? = of(period, location)?.start

    fun endOf(period: Int, location: String?): LocalTime? = of(period, location)?.end

    fun isGfBuilding(location: String?): Boolean =
        location?.trim()?.uppercase()?.startsWith(GF_BUILDING_PREFIX) == true

    /** 该教室在指定节次是否与标准作息不同。 */
    fun differsFromStandard(period: Int, location: String?): Boolean =
        of(period, location) != byPeriod[period]

    /** 当前时间处于第几节（不在任何一节课内时返回最接近的节次）。 */
    fun periodAt(time: LocalTime): Int? {
        byPeriod.values.firstOrNull { !time.isBefore(it.start) && !time.isAfter(it.end) }
            ?.let { return it.period }
        return byPeriod.values.lastOrNull { time.isAfter(it.end) }?.period ?: 1
    }
}

/** 学期信息。 */
data class Term(
    /** 形如 `2026-2027-1`。 */
    val name: String,
    /** 总周数。 */
    val totalWeeks: Int,
    /** 开学第一周周一 00:00 的时间戳（秒）。 */
    val startEpochSeconds: Long,
) {
    /** 开学第一周周一。 */
    /**
     * 开学第一周周一。
     *
     * 必须按**学校所在时区**解释服务端时间戳，不能用 [ZoneId.systemDefault()]：
     * 同一时间戳在不同时区可能落到不同日期（1787500800 在 UTC 是 8/24 00:00，
     * 在 UTC+8 是 8/24 08:00），否则结果会随构建/运行机器时区变化。
     */
    val firstMonday: LocalDate
        get() = java.time.Instant.ofEpochSecond(startEpochSeconds)
            .atZone(TERM_ZONE)
            .toLocalDate()

    /** 指定周次的周一。 */
    fun mondayOfWeek(week: Int): LocalDate = firstMonday.plusWeeks((week - 1).toLong())

    /** 指定周次、星期几（1=周一 … 7=周日）的日期。 */
    fun dateOf(week: Int, dayOfWeek: Int): LocalDate =
        mondayOfWeek(week).plusDays((dayOfWeek - 1).toLong())

    /** 某个日期落在第几周；不在学期内时返回 null。 */
    fun weekOf(date: LocalDate): Int? {
        val first = firstMonday
        if (date.isBefore(first)) return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(first, date)
        val week = (days / 7).toInt() + 1
        return if (week in 1..totalWeeks) week else null
    }

    companion object {
        /**
         * 学校所在时区。
         *
         * 学期起始时间戳由服务端给出，必须固定用这个时区解释，不能跟随机器时区，
         * 否则同一份数据在不同时区会解析出不同的开学日期。
         */
        val TERM_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** ISO 星期（周一=1 … 周日=7）。 */
        fun isoDayOfWeek(date: LocalDate): Int = date.dayOfWeek.value

        fun dayOfWeek(date: LocalDate): DayOfWeek = date.dayOfWeek
    }
}
