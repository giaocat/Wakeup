package com.wakeup

import com.wakeup.data.remote.TimetableMapper
import com.wakeup.data.remote.ZfTimetableParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**学校真实页面**的结构做回归（来自保存下来的 `xsMain_new.html`）。
 *
 * 真实页面与通用模板的差别都在这里锁住：
 * - 课表表格没有 id，只有 `class="... kb_table"`；
 * - 表头 `周/节次 | 星期一 …`；
 * - 行首是 `第一二节<br>(01,02小节)`；
 * - 单元格是 `<p title="课程学分：5&lt;br/&gt;…课程名称：高等数学A（一）&lt;br/&gt;上课时间：第10周 星期一 [01-02]节&lt;br/&gt;上课地点：F-105">高等数学A（..</p>`；
 * - 周次只写了「第10周」（这一页是按周显示的），个别格子的 title 甚至是 `undefined`。
 */
class ZfRealPageTest {

    private val realPage = """
        <html><head><title>教学一体化服务平台</title></head><body>
        <div class="middletopleftrqbox"><div class=" pr5 middletopleftzc" id="li_showWeek">
          <span class="main_text main_color">第10周</span>/20周</div></div>
        <input type="text" id="rq" value="2026-10-28" readonly="">
        <table class="table table-bordered table-hover table-striped kb_table" style="overflow: scroll;height: 95%;">
          <thead>
            <tr><th style="width: 14%;">周/节次</th><th style="width: 12%;">星期一</th><th style="width: 12%;">星期二</th>
                <th style="width: 12%;">星期三</th><th style="width: 12%;">星期四</th><th style="width: 12%;">星期五</th>
                <th style="width: 12%;">星期六</th><th style="width: 12%;">星期日</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>第一二节<br>(01,02小节)</td>
              <td><p title="课程学分：5&lt;br&gt;&lt;/p&gt;课程属性：必修&lt;br/&gt;课程名称：高等数学A（一）&lt;br/&gt;上课时间：第10周 星期一 [01-02]节&lt;br/&gt;上课地点：F-105" style="text-align: left;font-size: 12px;font-weight: bold;">高等数学A（..</p></td>
              <td><p title="课程学分：3&lt;br&gt;&lt;/p&gt;课程属性：必修&lt;br/&gt;课程名称：思想道德与法治&lt;br/&gt;上课时间：第10周 星期二 [01-02]节&lt;br/&gt;上课地点：F-208" style="text-align: left;font-size: 12px;font-weight: bold;">思想道德与法..</p></td>
              <td></td><td></td>
              <td><p title="课程学分：5&lt;br&gt;&lt;/p&gt;课程属性：必修&lt;br/&gt;课程名称：高等数学A（一）&lt;br/&gt;上课时间：第10周 星期五 [01-02]节&lt;br/&gt;上课地点：F-105" style="text-align: left;font-size: 12px;font-weight: bold;">高等数学A（..</p></td>
              <td></td><td></td>
            </tr>
            <tr>
              <td>第五六节<br>(05,06小节)</td>
              <td><p title="课程学分：3&lt;br&gt;&lt;/p&gt;课程属性：必修&lt;br/&gt;课程名称：C语言程序设计A&lt;br/&gt;上课时间：第10周 星期一 [05-06]节&lt;br/&gt;上课地点：F-207" style="text-align: left;font-size: 12px;font-weight: bold;">C语言程序设..</p></td>
              <td></td><td></td>
              <td><p title="课程学分：2&lt;br&gt;&lt;/p&gt;课程属性：必修&lt;br/&gt;课程名称：大学外语B（一）&lt;br/&gt;上课时间：第10周 星期四 [05-06]节&lt;br/&gt;上课地点：C-101" style="text-align: left;font-size: 12px;font-weight: bold;">大学外语B（..</p></td>
              <td><p title="undefined" style="text-align: left;font-size: 12px;font-weight: bold;">大学体育（一..</p></td>
              <td></td><td></td>
            </tr>
          </tbody>
        </table>
        </body></html>
    """.trimIndent()

    private fun timetable(termStart: Long? = null) = TimetableMapper.toDomain(
        ZfTimetableParser.parse(realPage, termStartEpochSeconds = termStart),
        sourceUrl = "https://jwxt.sut.edu.cn/jsxsd/framework/xsMain.jsp",
    )

    @Test
    fun `没有 id 的 kb_table 也能被找到`() {
        val courses = timetable().scheduled
        // 高数(2 个时段, 1 门) + 思政 + C语言 + 大学外语 + 大学体育(无 title) = 5 门
        assertEquals(5, courses.size)
        assertTrue(courses.map { it.name }.contains("高等数学A（一）"))
    }

    @Test
    fun `真实 title 里的名称、教室与节次`() {
        val math = timetable().scheduled.first { it.name == "高等数学A（一）" }
        assertEquals(2, math.slots.size) // 周一 + 周五
        val monday = math.slots.first { it.dayOfWeek == 1 }
        assertEquals(1, monday.startPeriod)
        assertEquals(2, monday.endPeriod)
        assertEquals("F-105", monday.location)

        val c = timetable().scheduled.first { it.name == "C语言程序设计A" }
        assertEquals("F-207", c.slots.single().location)
        assertEquals(5, c.slots.single().startPeriod)
        assertEquals(6, c.slots.single().endPeriod)
    }

    @Test
    fun `按周显示时把第10周退化成整学期`() {
        // 这一页只写「第10周」，不知道真实范围：不能因此让整门课只在第 10 周出现
        val math = timetable().scheduled.first { it.name == "高等数学A（一）" }
        val weeks = math.slots.first().weeks
        assertEquals(20, weeks.size)
        assertTrue(weeks.containsAll(listOf(1, 10, 20)))
    }

    @Test
    fun `title 为 undefined 的格子仍能按可见文字识别`() {
        val pe = timetable().scheduled.first { it.name.startsWith("大学体育") }
        val slot = pe.slots.single()
        assertEquals(5, slot.dayOfWeek)
        assertEquals(5, slot.startPeriod)
        assertEquals(6, slot.endPeriod)
    }

    @Test
    fun `学期起始日期来自设置`() {
        val t = timetable(termStart = 1_787_500_800L)
        assertEquals(1_787_500_800L, t.term.startEpochSeconds)
        assertEquals(20, t.term.totalWeeks)
    }

    @Test
    fun `若页面给了完整周次范围则按范围解析`() {
        // 学期理论课表（xskb_list.do）的写法：单元格里有 {第1-16周}
        val html = realPage.replace(
            "上课时间：第10周 星期一 [01-02]节",
            "上课时间：第1-16周 星期一 [01-02]节",
        )
        val courses = TimetableMapper.toDomain(ZfTimetableParser.parse(html)).scheduled
        val math = courses.first { it.name == "高等数学A（一）" }
        assertEquals((1..16).toSet(), math.slots.first().weeks)
    }

    @Test
    fun `按周合并后每门课拿到真实周次集合`() {
        // 第 1 周：高数 + 思政；第 3 周：只有高数（思政那格换成别的课）
        val week1 = realPage.replace("第10周", "第1周")
        val week3 = realPage
            .replace("第10周", "第3周")
            .replace("思想道德与法治", "习近平新时代中国特色社会主义思想概论")
            .replace("F-208", "F-209")

        val merged = ZfTimetableParser.mergeWeeks(
            weekHtml = listOf(1 to week1, 3 to week3),
            termStartEpochSeconds = 1_787_500_800L,
            totalWeeks = 20,
        )
        val timetable = TimetableMapper.toDomain(merged)

        val math = timetable.scheduled.first { it.name == "高等数学A（一）" }
        assertTrue("高数两周都上", math.slots.all { it.weeks == setOf(1, 3) })

        val moral = timetable.scheduled.first { it.name == "思想道德与法治" }
        assertEquals(setOf(1), moral.slots.single().weeks)

        val newCourse = timetable.scheduled.first { it.name.startsWith("习近平") }
        assertEquals(setOf(3), newCourse.slots.single().weeks)

        assertEquals(20, timetable.term.totalWeeks)
        assertEquals(1_787_500_800L, timetable.term.startEpochSeconds)
    }
}
