package com.wakeup

import com.wakeup.domain.Course
import com.wakeup.domain.NextClassPresentation
import com.wakeup.domain.NextClassPresenter
import com.wakeup.domain.Term
import com.wakeup.domain.Timetable
import com.wakeup.domain.TimetableCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 「下一节课」统一文案（`:core` 的 NextClassPresenter）。
 *
 * 这段逻辑现在被四个地方共用：手机今日页、Smartspacer Target、手表表盘复杂功能、手表信息块。
 * 所以这里锁的是"四种展示端拿到的文案必须一致且规则确定"：
 * 截断规则、正在上课的措辞、无课/未同步的兜底、以及教室为空时不留下多余的 ` · `。
 *
 * 学期起点 1787500800 = 2026-08-24（周一），第 1 周 = 8/24–8/30。
 */
class NextClassPresenterTest {

    private val sutUrl = "https://sutjwt.sut.edu.cn/user/kb/table/token/100000"

    private val term = Term(
        name = "2026-2027-1",
        totalWeeks = 20,
        startEpochSeconds = 1_787_500_800L,
    )

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

    private fun timetable(vararg courses: Course) = Timetable(
        term = term,
        courses = courses.map { slot ->
            TimetableCourse(
                courseId = slot.courseId,
                name = slot.name,
                teacher = slot.teacher,
                slots = listOf(slot),
            )
        },
        sourceUrl = sutUrl,
    )

    private fun table(name: String = "高等数学", location: String = "C-303") = timetable(
        course("c1", name, location, dayOfWeek = 1, startPeriod = 3, endPeriod = 4),
    )

    @Test
    fun `有课时四端文案都齐了`() {
        val presentation = NextClassPresenter.present(
            table(),
            LocalDateTime.of(2026, 8, 24, 9, 0),
        )

        assertTrue(presentation.hasTimetable)
        assertTrue(presentation.hasClass)
        assertFalse(presentation.ongoing)
        assertEquals("今天", presentation.dayLabel)
        assertEquals("10:25", presentation.clock)
        assertEquals("3-4", presentation.periodLabel)
        assertEquals("C-303", presentation.room)
        // 表盘短文本：时间 + 截断后的课程名
        assertEquals("10:25 高等数学", presentation.shortText)
        // 表盘长文本：标题 + 正文
        assertEquals("今天", presentation.title)
        assertEquals("10:25 高等数学 · C-303", presentation.longText)
        // Smartspacer / 今日页副标题
        assertEquals("今天 第3-4节 10:25 · C-303", presentation.description)
    }

    @Test
    fun `表盘短文本会截断超长课程名`() {
        val presentation = NextClassPresenter.present(
            table(name = "机器学习与模式识别"),
            LocalDateTime.of(2026, 8, 24, 9, 0),
        )
        // 长文本用全名，短文本才截断（前 6 个字 + 省略号）
        assertEquals("10:25 机器学习与模…", presentation.shortText)
        assertEquals("10:25 机器学习与模式识别 · C-303", presentation.longText)
    }

    @Test
    fun `正在上课时的措辞与 ongoing 标记`() {
        val presentation = NextClassPresenter.present(
            table(),
            LocalDateTime.of(2026, 8, 24, 10, 40),
        )
        assertTrue(presentation.ongoing)
        assertEquals("正在上课 · 至 12:00 · C-303", presentation.description)
    }

    @Test
    fun `教室为空时不留多余的间隔符`() {
        val presentation = NextClassPresenter.present(
            table(location = ""),
            LocalDateTime.of(2026, 8, 24, 9, 0),
        )
        assertEquals("", presentation.room)
        assertEquals("10:25 高等数学", presentation.longText)
    }

    @Test
    fun `明天的课带明天前缀`() {
        val table = timetable(
            course("c2", "大学物理", "A-201", dayOfWeek = 2, startPeriod = 1, endPeriod = 2),
        )
        val presentation = NextClassPresenter.present(
            table,
            LocalDateTime.of(2026, 8, 24, 21, 0),
        )
        assertEquals("明天", presentation.dayLabel)
        assertEquals("明天 第1-2节 08:30 · A-201", presentation.description)
    }

    @Test
    fun `从未同步过课表时是未同步`() {
        val presentation = NextClassPresenter.present(null)
        assertFalse(presentation.hasTimetable)
        assertFalse(presentation.hasClass)
        assertEquals(NextClassPresentation.NO_TIMETABLE, presentation)
        assertEquals("未同步", presentation.shortText)
        assertTrue(presentation.description.isNotBlank())
    }

    @Test
    fun `有课表但近期无课显示无课`() {
        val presentation = NextClassPresenter.present(
            Timetable(term = term, courses = emptyList(), sourceUrl = sutUrl),
            LocalDateTime.of(2026, 8, 24, 9, 0),
        )
        assertTrue(presentation.hasTimetable)
        assertFalse(presentation.hasClass)
        assertEquals("无课", presentation.shortText)
        assertTrue(presentation.description.isNotBlank())
    }

    @Test
    fun `预览数据是固定的`() {
        // 表盘编辑器里选复杂功能时看到的就是这份，必须与真实数据无关、且字段都不为空
        val preview = NextClassPresentation.PREVIEW
        assertTrue(preview.hasClass)
        assertEquals("10:15 高数", preview.shortText)
        assertEquals("今天", preview.title)
        assertEquals("10:15 高等数学 · F-105", preview.longText)
        assertFalse(preview.description.isBlank())
    }

    @Test
    fun `短名截断规则`() {
        assertEquals("高等数学", NextClassPresenter.shortCourseName("高等数学"))
        assertEquals("高等数学", NextClassPresenter.shortCourseName("  高等数学  "))
        assertEquals("机器学习与模…", NextClassPresenter.shortCourseName("机器学习与模式识别"))
        assertEquals("ABCDEF…", NextClassPresenter.shortCourseName("ABCDEFG", maxLength = 6))
    }

    @Test
    fun `信息块列表行与下一节行`() {
        val table = table()
        val mondayMorning = LocalDateTime.of(2026, 8, 24, 9, 0)
        val slot = table.slotsOfDay(1, 1).single()

        // 当天课程列表行：时间 + 截断后的课程名
        assertEquals("10:25 高等数学", NextClassPresenter.briefLine(slot, table))
        // 下一节整行：时间 + 全名 + 教室（now 必须显式传入：默认值是"此刻"，测试里不可控）
        assertEquals(
            "下一节 10:25 高等数学 · C-303",
            NextClassPresenter.nextClassLine(table, mondayMorning),
        )

        // 无课时没有"下一节"这一行
        val empty = Timetable(term = term, courses = emptyList(), sourceUrl = sutUrl)
        assertNull(NextClassPresenter.nextClassLine(empty, mondayMorning))
        assertNull(NextClassPresenter.nextClassLine(null, mondayMorning))
    }

    @Test
    fun `时钟与日期格式化`() {
        assertEquals("08:05", NextClassPresenter.clockOf(java.time.LocalTime.of(8, 5)))
        assertEquals("10月28日", NextClassPresenter.dateLabel(java.time.LocalDate.of(2026, 10, 28)))
    }
}
