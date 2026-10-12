package com.wakeup

import com.wakeup.domain.Course
import com.wakeup.domain.Term
import com.wakeup.domain.Timetable
import com.wakeup.domain.TimetableCourse
import com.wakeup.domain.nextOccurrence
import com.wakeup.domain.subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 「下一节课」的推导与展示文案。
 *
 * 这是 Smartspacer Target 的数据来源：Target 每次被取用时都用当前时间现算，
 * 所以这段逻辑正确性直接决定「一览」里显示得对不对。
 *
 * 学期起点 1787500800 = 2026-08-24（周一），因此第 1 周 = 8/24–8/30，第 2 周从 8/31 开始。
 */
class NextClassTest {

    private val sutUrl = "https://sutjwt.sut.edu.cn/user/kb/table/token/100000"
    private val otherUrl = "https://jwt.example.edu.cn/user/kb/table/token/12345"

    private val term = Term(
        name = "2026-2027-1",
        totalWeeks = 20,
        startEpochSeconds = 1_787_500_800L,
    )

    @Test
    fun `学期起点是 2026-08-24 周一`() {
        assertEquals(LocalDate.of(2026, 8, 24), term.firstMonday)
        assertEquals(LocalTime.of(10, 25), com.wakeup.domain.PeriodTimes.startOf(3))
    }

    @Test
    fun `没有课表时返回 null`() {
        val empty = Timetable(term = term, courses = emptyList(), sourceUrl = sutUrl)
        assertNull(empty.nextOccurrence(LocalDateTime.of(2026, 8, 24, 9, 0)))
    }

    @Test
    fun `取今天还没开始的下一节`() {
        val table = timetable(
            course("c1", "高等数学", "C-303", dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
        )
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 9, 0))!!
        assertEquals("高等数学", found.name)
        assertEquals(LocalDate.of(2026, 8, 24), found.date)
        assertEquals(1, found.week)
        assertEquals(LocalTime.of(10, 25), found.start)
        assertEquals(LocalTime.of(12, 0), found.end)
        assertEquals("3-4", found.periodLabel)
        assertEquals("C-303", found.location)
    }

    @Test
    fun `正在上课时返回当前这一节`() {
        val table = timetable(
            course("c1", "高等数学", "C-303", dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
        )
        val now = LocalDateTime.of(2026, 8, 24, 10, 40)
        val found = table.nextOccurrence(now)!!
        assertEquals("高等数学", found.name)
        assertTrue("10:40 应算正在上课", found.isOngoing(now.toLocalTime()))
    }

    @Test
    fun `今天已上完则顺延到后一天`() {
        val table = timetable(
            course("c1", "高等数学", "C-303", dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
            course("c2", "大学物理", "A-101", dayOfWeek = 2, startPeriod = 1, endPeriod = 2),
        )
        // 周一 13:00，周一那节 12:00 已下课
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 13, 0))!!
        assertEquals("大学物理", found.name)
        assertEquals(LocalDate.of(2026, 8, 25), found.date)
        assertEquals(LocalTime.of(8, 30), found.start)
    }

    @Test
    fun `同一格内的多门课取最早的一节`() {
        val table = timetable(
            course("c1", "晚一点的课", "C-303", dayOfWeek = 1, startPeriod = 5, endPeriod = 6),
            course("c2", "早一点的课", "C-101", dayOfWeek = 1, startPeriod = 1, endPeriod = 2),
        )
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 7, 0))!!
        assertEquals("早一点的课", found.name)
        assertEquals(LocalTime.of(8, 30), found.start)
    }

    @Test
    fun `不在本周次范围内的课会被跳过`() {
        // 这门课只在第 5 周上，而现在是第 1 周，往后 7 天也只到第 2 周
        val table = timetable(
            course("c1", "第五周的课", "C-303", dayOfWeek = 1, startPeriod = 3, endPeriod = 4, weeks = setOf(5)),
        )
        assertNull(table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 9, 0)))
    }

    @Test
    fun `跨周到下一周时能取到第二周的课`() {
        // 第 2 周周一 = 2026-08-31，从 8/24 起往后 7 天正好落在范围内
        val table = timetable(
            course("c1", "第二周的课", "C-303", dayOfWeek = 1, startPeriod = 1, endPeriod = 2, weeks = setOf(2)),
        )
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 9, 0))!!
        assertEquals(LocalDate.of(2026, 8, 31), found.date)
        assertEquals(2, found.week)
    }

    @Test
    fun `超出学期范围返回 null`() {
        val table = timetable(
            course("c1", "高等数学", "C-303", dayOfWeek = 1, startPeriod = 3, endPeriod = 4, weeks = setOf(1)),
        )
        // 20 周之后，早已放假
        assertNull(table.nextOccurrence(LocalDateTime.of(2027, 6, 1, 9, 0)))
    }

    @Test
    fun `F 座且来源是沈工大时用提前 10 分钟的时间`() {
        val table = timetable(
            course("c1", "工程训练", "F-105", dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
            sourceUrl = sutUrl,
        )
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 9, 0))!!
        assertEquals(LocalTime.of(10, 15), found.start)
        assertEquals(LocalTime.of(11, 50), found.end)
    }

    @Test
    fun `外校来源即使教室叫 F-105 也不用提前时间`() {
        val table = timetable(
            course("c1", "工程训练", "F-105", dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
            sourceUrl = otherUrl,
        )
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 9, 0))!!
        assertEquals(LocalTime.of(10, 25), found.start)
        assertEquals(LocalTime.of(12, 0), found.end)
    }

    @Test
    fun `非 G F 座教室不受影响`() {
        val table = timetable(
            course("c1", "高等数学", "C-303", dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
            sourceUrl = sutUrl,
        )
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 9, 0))!!
        assertEquals(LocalTime.of(10, 25), found.start)
    }

    // ---------------- 展示文案 ----------------

    @Test
    fun `dayLabel 的相对日期`() {
        val table = timetable(
            course("c1", "高等数学", "C-303", dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
        )
        val occurrence = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 9, 0))!!
        val date = LocalDate.of(2026, 8, 24)
        assertEquals("今天", occurrence.dayLabel(date))
        assertEquals("明天", occurrence.dayLabel(date.minusDays(1)))
        assertEquals("后天", occurrence.dayLabel(date.minusDays(2)))
        // 8/24 是周一，从 8/20（周四）看过去是 4 天后
        assertEquals("周一", occurrence.dayLabel(LocalDate.of(2026, 8, 20)))
    }

    @Test
    fun `subtitle 未开始时的文案`() {
        val table = timetable(
            course("c1", "高等数学", "C-303", dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
        )
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 9, 0))!!
        assertEquals(
            "今天 第3-4节 10:25 · C-303",
            found.subtitle(LocalDate.of(2026, 8, 24), LocalTime.of(9, 0)),
        )
    }

    @Test
    fun `subtitle 正在上课时的文案`() {
        val table = timetable(
            course("c1", "高等数学", "C-303", dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
        )
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 10, 40))!!
        assertEquals(
            "正在上课 · 至 12:00 · C-303",
            found.subtitle(LocalDate.of(2026, 8, 24), LocalTime.of(10, 40)),
        )
    }

    @Test
    fun `subtitle 第二天的文案带明天前缀`() {
        val table = timetable(
            course("c1", "大学物理", "A-101", dayOfWeek = 2, startPeriod = 1, endPeriod = 2),
        )
        val found = table.nextOccurrence(LocalDateTime.of(2026, 8, 24, 20, 0))!!
        assertEquals(
            "明天 第1-2节 08:30 · A-101",
            found.subtitle(LocalDate.of(2026, 8, 24), LocalTime.of(20, 0)),
        )
    }

    // ---------------- 辅助 ----------------

    private fun course(
        id: String,
        name: String,
        location: String,
        dayOfWeek: Int,
        startPeriod: Int,
        endPeriod: Int,
        weeks: Set<Int> = setOf(1),
    ) = Course(
        courseId = id,
        name = name,
        teacher = "老师",
        location = location,
        dayOfWeek = dayOfWeek,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = weeks,
    )

    private fun timetable(vararg courses: Course, sourceUrl: String = sutUrl) = Timetable(
        term = term,
        courses = courses.map { slot ->
            TimetableCourse(
                courseId = slot.courseId,
                name = slot.name,
                teacher = slot.teacher,
                slots = listOf(slot),
            )
        },
        sourceUrl = sourceUrl,
    )
}
