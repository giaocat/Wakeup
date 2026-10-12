package com.wakeup.data.remote

import com.wakeup.domain.Course
import com.wakeup.domain.Term
import com.wakeup.domain.Timetable
import com.wakeup.domain.TimetableCourse
import com.wakeup.domain.WeekExpression
import com.wakeup.data.model.CourseEntry
import com.wakeup.data.model.CourseSlot
import com.wakeup.data.model.TimetableResponse
import java.time.Instant

/** 把接口 DTO 转换成 UI 直接可用的领域模型。 */
object TimetableMapper {

    private const val MAX_PERIOD = 12

    /**
     * 把接口 DTO 转成领域模型。
     *
     * [sourceUrl] 会挂到 [Timetable.sourceUrl]，用于判断学校 ——
     * 例如 GF 座作息只在沈阳工业大学的课表上生效。
     */
    fun toDomain(response: TimetableResponse, sourceUrl: String = ""): Timetable {
        val table = response.table
            ?: throw IllegalStateException(response.errmsg ?: "课表数据为空")

        if (response.code != 200) {
            throw IllegalStateException(response.errmsg ?: "课表接口返回错误码 ${response.code}")
        }
        if (table.code != 200) {
            throw IllegalStateException(table.errmsg ?: "课表数据返回错误码 ${table.code}")
        }

        val term = toTerm(table.term?.total, table.term?.starttime ?: 0L, table.term?.term)
        val courses = table.list.map { toCourse(it) }

        return Timetable(
            term = term,
            courses = courses.sortedWith(compareBy({ it.name }, { it.courseId })),
            sourceUrl = sourceUrl,
        )
    }

    private fun toTerm(total: String?, starttime: Long, name: String?): Term {
        val totalWeeks = total?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        // starttime 是"开学第一周周一"所在那一周的某个时刻，这里归一到当天 00:00。
        // 一律按学校时区解释，避免同一份数据在不同时区解析出不同日期。
        val firstMonday = if (starttime > 0L) {
            val date = Instant.ofEpochSecond(starttime).atZone(Term.TERM_ZONE).toLocalDate()
            date.minusDays((date.dayOfWeek.value - 1).toLong())
        } else {
            java.time.LocalDate.now(Term.TERM_ZONE).with(java.time.DayOfWeek.MONDAY)
        }
        val normalized = firstMonday.atStartOfDay(Term.TERM_ZONE).toEpochSecond()
        return Term(
            name = name.orEmpty(),
            totalWeeks = totalWeeks,
            startEpochSeconds = normalized,
        )
    }

    private fun toCourse(entry: CourseEntry): TimetableCourse {
        val slots = entry.info.mapNotNull { slot -> toSlot(entry, slot) }
        return TimetableCourse(
            courseId = entry.courseId,
            name = entry.courseName.ifBlank { "未命名课程" },
            teacher = entry.teacher.orEmpty(),
            slots = slots,
        )
    }

    private fun toSlot(entry: CourseEntry, slot: CourseSlot): Course? {
        val dayOfWeek = parseDayOfWeek(slot) ?: return null
        val periods = parsePeriods(slot)
        if (periods.isEmpty()) return null

        val weeks = WeekExpression.parse(slot.weekExpression)
            .ifEmpty { slot.weekDetail.mapNotNull { it.trim().toIntOrNull() }.toSet() }
        if (weeks.isEmpty()) return null

        return Course(
            courseId = entry.courseId,
            name = entry.courseName.ifBlank { "未命名课程" },
            teacher = entry.teacher.orEmpty(),
            location = slot.room.orEmpty(),
            dayOfWeek = dayOfWeek,
            startPeriod = periods.first(),
            endPeriod = periods.last(),
            weeks = weeks,
        )
    }

    /**
     * 节次编码形如 `30102`：首位是星期，其后每两位表示一个节次。
     * 也兼容 `KCSJMX` 这种 `["301","302"]` 的明细数组。
     */
    private fun parsePeriods(slot: CourseSlot): List<Int> {
        val fromDetail = slot.periodDetail
            .mapNotNull { code -> code.trim().takeIf { it.length >= 3 }?.drop(1)?.toIntOrNull() }
            .filter { it in 1..MAX_PERIOD }
        if (fromDetail.isNotEmpty()) return fromDetail.distinct().sorted()

        val code = slot.periodCode.trim()
        if (code.length < 3) return emptyList()
        val periods = mutableListOf<Int>()
        var index = 1
        while (index + 1 < code.length) {
            val period = code.substring(index, index + 2).toIntOrNull()
            if (period != null && period in 1..MAX_PERIOD) periods.add(period)
            index += 2
        }
        return periods.distinct().sorted()
    }

    private fun parseDayOfWeek(slot: CourseSlot): Int? {
        slot.periodDetail.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { code ->
            code.first().digitToIntOrNull()?.let { if (it in 1..7) return it }
        }
        val code = slot.periodCode.trim()
        if (code.isNotEmpty()) {
            code.first().digitToIntOrNull()?.let { if (it in 1..7) return it }
        }
        return null
    }
}
