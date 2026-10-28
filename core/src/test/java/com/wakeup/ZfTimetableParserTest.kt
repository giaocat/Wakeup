package com.wakeup

import com.wakeup.data.remote.TimetableMapper
import com.wakeup.data.remote.ZfTimetableParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强智教务系统（`jwxt.sut.edu.cn`）「我的课表」页面的解析。
 *
 * 页面结构按学校实际截图构造：
 * - 表头 `第10周 / 20周`（当前周 / 总周数）；
 * - 行是**两节连排**并用中文数字：`第一二节(01,02小节)`、`第九十节(09,10小节)`、`第十一十二节(11,12小节)`；
 * - 单元格里的课程名被 CSS 截断（`高等数学A（...`），完整名称/教师/周次/教室在 `title` 属性里。
 */
class ZfTimetableParserTest {

    private val gridHtml = """
        <html><head><title>教学一体化服务平台</title></head><body>
        <div class="week">第10周 / 20周</div>
        <div class="date">2026-10-28</div>
        <table id="kbtable" class="Nsb_r_list Nsb_table">
          <tr><th>周/节次</th><th>星期一</th><th>星期二</th><th>星期三</th>
              <th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th></tr>
          <tr>
            <td class="Nsb_r_title">第一二节<br><span>(01,02小节)</span></td>
            <td><div class="kbcontent" title="高等数学A（1） 教师：张三 周次：1-16 教室：教学楼A101">高等数学A（...</div></td>
            <td><div class="kbcontent" title="思想道德与法治 教师：李四 周次：1-16 教室：教学楼B203">思想道德与法...</div></td>
            <td><div class="kbcontent" title="中国近现代史纲要 教师：王五 周次：1-8 教室：教学楼C305">中国近现代史...</div></td>
            <td></td>
            <td><div class="kbcontent" title="高等数学A（1） 教师：张三 周次：1-16 教室：教学楼A101">高等数学A（...</div></td>
            <td></td><td></td>
          </tr>
          <tr>
            <td class="Nsb_r_title">第三四节<br><span>(03,04小节)</span></td>
            <td><div class="kbcontent" title="中国近现代史纲要 教师：王五 周次：9-16 教室：教学楼C305">中国近现代史...</div></td>
            <td><div class="kbcontent" title="大学外语B（1） 教师：赵六 周次：1-16 教室：教学楼D401">大学外语B（...</div></td>
            <td><div class="kbcontent" title="高等数学A（1） 教师：张三 周次：1-16 教室：教学楼A101">高等数学A（...</div></td>
            <td></td><td></td><td></td><td></td>
          </tr>
          <tr>
            <td class="Nsb_r_title">第五六节<br><span>(05,06小节)</span></td>
            <td><div class="kbcontent" title="C语言程序设计 教师：钱七 周次：1-16 教室：实验楼E201">C语言程序设...</div></td>
            <td><div class="kbcontent" title="学科专业导论 教师：孙八 周次：2-16周(双) 教室：教学楼F105">学科专业导论...</div></td>
            <td></td>
            <td><div class="kbcontent" title="大学外语B（1） 教师：赵六 周次：1-16 教室：教学楼D401">大学外语B（...</div></td>
            <td><div class="kbcontent" title="大学体育（1） 教师：周九 周次：1-16 教室：体育馆">大学体育（...</div></td>
            <td></td><td></td>
          </tr>
        </table>
        <div class="bz">请注意：教学楼F、G座3、4节课的上课时间为10:15——11:50，望周知。</div>
        </body></html>
    """.trimIndent()

    private val listHtml = """
        <html><head><title>课表查询</title></head><body>
        <table id="kblist">
          <tr><th>课程名称</th><th>上课时间</th><th>上课地点</th><th>教师</th></tr>
          <tr>
            <td>数据结构</td>
            <td>星期一第5-6节{第1-16周}</td>
            <td>四教D401</td>
            <td>赵六</td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    private fun parseGrid() = ZfTimetableParser.parse(gridHtml, termStartEpochSeconds = 1_787_500_800L)

    private fun domain() = TimetableMapper.toDomain(
        parseGrid(),
        sourceUrl = "https://jwxt.sut.edu.cn/jsxsd/framework/xsMain.jsp",
    )

    @Test
    fun `两节连排的行映射到正确的节次`() {
        val courses = domain().scheduled
        val math = courses.first { it.name == "高等数学A（1）" }
        // 周一 1-2 节（第一二节）；周五同一门课也是 1-2 节
        val monday = math.slots.first { it.dayOfWeek == 1 }
        assertEquals(1, monday.startPeriod)
        assertEquals(2, monday.endPeriod)
        assertEquals((1..16).toSet(), monday.weeks)
        assertTrue(math.slots.any { it.dayOfWeek == 5 })
    }

    @Test
    fun `中文数字节次与括号数字都能认`() {
        assertEquals(1..2, ZfTimetableParser.parsePeriodRange("第一二节\n(01,02小节)"))
        assertEquals(9..10, ZfTimetableParser.parsePeriodRange("第九十节\n(09,10小节)"))
        assertEquals(11..12, ZfTimetableParser.parsePeriodRange("第十一十二节\n(11,12小节)"))
        assertEquals(5..6, ZfTimetableParser.parsePeriodRange("第5-6节"))
        assertEquals(3..3, ZfTimetableParser.parsePeriodRange("3"))
        assertNull(ZfTimetableParser.parsePeriodRange("周/节次"))
    }

    @Test
    fun `title 里的教师、周次与教室`() {
        val courses = domain().scheduled
        val moral = courses.first { it.name == "思想道德与法治" }
        assertEquals("李四", moral.teacher)
        assertEquals("教学楼B203", moral.slots.single().location)
        assertEquals((1..16).toSet(), moral.slots.single().weeks)

        // 同一门课分两段周次上课（1-8 / 9-16），应当合并成一门课两个时段
        val history = courses.first { it.name == "中国近现代史纲要" }
        assertEquals(2, history.slots.size)
        assertTrue(history.slots.any { it.weeks == (1..8).toSet() })
        assertTrue(history.slots.any { it.weeks == (9..16).toSet() })
    }

    @Test
    fun `单双周过滤与第五节次`() {
        val courses = domain().scheduled
        val physics = courses.first { it.name == "学科专业导论" }
        val slot = physics.slots.single()
        assertEquals(2, slot.dayOfWeek)
        assertEquals(5, slot.startPeriod)
        assertEquals(6, slot.endPeriod)
        assertEquals((2..16 step 2).toSet(), slot.weeks)
    }

    @Test
    fun `页面里的当前周与总周数`() {
        val response = parseGrid()
        assertEquals(20, response.table?.term?.total?.toIntOrNull())
        // 显式传入的学期起始时间优先
        assertEquals(1_787_500_800L, response.table?.term?.starttime)
    }

    @Test
    fun `登录页会被识别成需要重新登录`() {
        val loginHtml = """
            <html><head><title>登录</title></head><body>
            <form action="/jsxsd/xk/LoginToXk" method="post">
              <input type="text" name="userAccount">
              <input type="password" name="userPassword">
              <input type="text" name="RANDOMCODE">
            </form></body></html>
        """.trimIndent()
        assertTrue(ZfTimetableParser.isLoginPage(loginHtml))
        assertThrows(ZfTimetableParser.LoginRequiredException::class.java) {
            ZfTimetableParser.parse(loginHtml)
        }
        assertTrue(!ZfTimetableParser.isLoginPage(gridHtml))
    }

    @Test
    fun `页面里没有表格时给出可操作的错误`() {
        val error = assertThrows(ZfTimetableParser.UnsupportedPageException::class.java) {
            ZfTimetableParser.parse("<html><body><p>系统维护中</p></body></html>")
        }
        assertTrue(error.message.orEmpty().contains("课表"))
    }

    @Test
    fun `课程列表表格作为兜底也能解析`() {
        val response = ZfTimetableParser.parse(listHtml, termStartEpochSeconds = 1_787_500_800L)
        val timetable = TimetableMapper.toDomain(response, sourceUrl = "https://jwxt.sut.edu.cn/jsxsd/xskb/xskb_list.do")
        val course = timetable.scheduled.single()
        assertEquals("数据结构", course.name)
        assertEquals("赵六", course.teacher)
        assertEquals("四教D401", course.slots.single().location)
        assertEquals(1, course.slots.single().dayOfWeek)
        assertEquals(5, course.slots.single().startPeriod)
        assertEquals(6, course.slots.single().endPeriod)
        assertEquals((1..16).toSet(), course.slots.single().weeks)
    }

    @Test
    fun `周次表达式兼容多种写法`() {
        assertEquals((1..16).toList().map { it.toString() }, ZfTimetableParser.parseWeeks("高等数学\n1-16周"))
        assertEquals((1..16).toList().map { it.toString() }, ZfTimetableParser.parseWeeks("{第1-16周}"))
        assertEquals(
            (2..16 step 2).toList().map { it.toString() },
            ZfTimetableParser.parseWeeks("2-16周(双)"),
        )
        assertEquals(listOf("1", "3", "5"), ZfTimetableParser.parseWeeks("第1,3,5周"))
        assertNull(ZfTimetableParser.parseWeeks("没有周次的一段文字"))
    }

    @Test
    fun `教师与教室的启发式判断`() {
        assertTrue(ZfTimetableParser.looksLikeTeacher("张三", weeksText = ""))
        assertTrue(ZfTimetableParser.looksLikeTeacher("张三,李四", weeksText = ""))
        assertTrue(!ZfTimetableParser.looksLikeTeacher("一教A101", weeksText = ""))
        assertTrue(!ZfTimetableParser.looksLikeTeacher("第1-16周", weeksText = ""))
        assertTrue(ZfTimetableParser.looksLikeRoom("一教A101"))
        assertTrue(ZfTimetableParser.looksLikeRoom("二教B203"))
        assertTrue(!ZfTimetableParser.looksLikeRoom("高等数学"))
    }

    @Test
    fun `页面缺当前周时用设置里的开学日期`() {
        val html = gridHtml.replace("第10周 / 20周", "")
        val response = ZfTimetableParser.parse(html, termStartEpochSeconds = 1_787_500_800L)
        assertEquals(1_787_500_800L, response.table?.term?.starttime)
        assertNotNull(response.table)
    }
}
