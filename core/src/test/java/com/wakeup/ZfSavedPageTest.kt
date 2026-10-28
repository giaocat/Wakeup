package com.wakeup

import com.wakeup.data.remote.TimetableMapper
import com.wakeup.data.remote.ZfTimetableParser
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**学校真实保存下来的整页 HTML**（`core/src/test/resources/zf/xsMain_new.html`）解析，
 * 校验六门课都在、且星期/节次没有错位。
 *
 * 期望（来自页面 title 里的 `上课时间`）：
 * - 高等数学A（一）：周一 1-2、周三 3-4、周五 1-2
 * - 思想道德与法治：周二 1-2、周五 3-4
 * - 中国近现代史纲要：周一 3-4、周三 1-2
 * - C语言程序设计A：周一 5-6
 * - 大学外语B（一）：周二 3-4、周四 5-6
 * - 学科专业导论：周二 5-6
 * - 大学体育（一）（页面 title 是 undefined，只能靠可见文字）：周五 5-6
 */
class ZfSavedPageTest {

    private val html: String = checkNotNull(javaClass.getResource("/zf/xsMain_new.html")).readText()

    private fun timetable() = TimetableMapper.toDomain(
        ZfTimetableParser.parse(html, termStartEpochSeconds = 1_787_500_800L),
        sourceUrl = "https://jwxt.sut.edu.cn/jsxsd/framework/xsMain.jsp",
    )

    @Test
    fun `真实页面里的六门课都在且位置正确`() {
        val courses = timetable().scheduled
        println(
            "DEBUG " + courses.joinToString(" | ") { course ->
                course.name + " -> " + course.slots.joinToString(",") { slot ->
                    "${slot.dayOfWeek}/${slot.startPeriod}-${slot.endPeriod}/${slot.location}"
                }
            },
        )

        fun slotsOf(name: String) = courses.firstOrNull { it.name == name }?.slots
            ?: error("缺少课程：$name（实际：${courses.map { it.name }}）")

        assertSlots(slotsOf("高等数学A（一）"), setOf(1 to 1, 3 to 3, 5 to 1))
        assertSlots(slotsOf("思想道德与法治"), setOf(2 to 1))
        assertSlots(slotsOf("中国近现代史纲要"), setOf(1 to 3, 3 to 1))
        assertSlots(slotsOf("C语言程序设计A"), setOf(1 to 5))
        assertSlots(slotsOf("大学外语B（一）"), setOf(2 to 3, 4 to 5))
        assertSlots(slotsOf("学科专业导论"), setOf(2 to 5))
        assertTrue(courses.any { it.name.startsWith("大学体育") })

        // 「备注：请注意教学楼F、G座3、4节…」是整行说明，不能被当成课程
        assertTrue(
            "备注行被误当成课程：${courses.map { it.name }}",
            courses.none { it.name.contains("备注") || it.name.contains("请注意") },
        )
        // 可见文字被截断留下的 `..` 要清掉
        assertTrue(courses.none { it.name.endsWith("..") })
    }

    /** 断言"星期 → 起始节次"的集合完全相等（每门课占两节）。 */
    private fun assertSlots(
        slots: List<com.wakeup.domain.Course>,
        expected: Set<Pair<Int, Int>>,
    ) {
        val actual = slots.map { it.dayOfWeek to it.startPeriod }.toSet()
        assertTrue("星期/节次不对：期望 $expected，实际 $actual", actual == expected)
        // 每段都是两节连排
        assertTrue("节次范围错误", slots.all { it.endPeriod == it.startPeriod + 1 })
    }
}
