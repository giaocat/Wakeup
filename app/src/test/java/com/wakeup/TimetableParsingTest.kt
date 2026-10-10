package com.wakeup

import com.wakeup.data.cache.CachedTimetable
import com.wakeup.data.cache.toResponseOrNull
import com.wakeup.data.remote.TimetableMapper
import com.wakeup.data.remote.TimetablePayloadParser
import com.wakeup.data.remote.TimetableRemoteDataSource
import com.wakeup.domain.Term
import com.wakeup.domain.WeekExpression
import com.wakeup.ui.ScheduleUiState
import com.wakeup.ui.schedule.computeGridLayout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 解析链路单元测试。
 *
 * 输入是 `test/resources/timetable_payload.json` —— 一份**构造的**示例数据，
 * 结构与教务系统页面完全一致（字段名、节次编码、周次表达式），但课程、教师、
 * 学生全部是虚构的。要换成自己的数据，直接替换该文件即可。
 */
class TimetableParsingTest {

    private val sample: String
        get() = javaClass.getResourceAsStream("/timetable_payload.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    @Test
    fun `从页面脚本中抽取 this_init 的 JSON`() {
        val html = "<script>var vm = new Vue({ created: function () { this.init($sample); } });</script>"
        val payload = TimetablePayloadParser.extractPayload(html)
        assertNotNull(payload)
        assertTrue(payload!!.startsWith("{"))
        assertTrue(payload.endsWith("}"))
        assertEquals(sample.trim(), payload.trim())
    }

    @Test
    fun `页面里没有数据时返回 null`() {
        assertNull(TimetablePayloadParser.extractPayload("<html><body>暂无课表</body></html>"))
    }

    @Test
    fun `反序列化并映射成课表`() {
        val response = TimetablePayloadParser.parsePayload(sample)
        assertEquals(200, response.code)
        assertEquals("2026-2027-1", response.table?.term?.term)
        assertEquals(14, response.table?.list?.size)
        assertEquals("测试同学", response.user)

        val timetable = TimetableMapper.toDomain(response)
        assertEquals(20, timetable.term.totalWeeks)
        assertEquals(12, timetable.scheduled.size)
        assertEquals(2, timetable.unscheduled.size)
        assertTrue(timetable.unscheduled.any { it.name == "军训" })
        assertTrue(timetable.unscheduled.any { it.name == "数学建模实践" })
    }

    @Test
    fun `节次编码解析出星期与节次`() {
        val timetable = TimetableMapper.toDomain(TimetablePayloadParser.parsePayload(sample))

        // "10102" -> 周一 第 1、2 节
        val slot = timetable.scheduled
            .flatMap { it.slots }
            .first { it.name == "高等数学A（一）" && it.startPeriod == 1 }

        assertEquals(1, slot.dayOfWeek)
        assertEquals(1, slot.startPeriod)
        assertEquals(2, slot.endPeriod)
        assertEquals("A-101", slot.location)
        assertEquals("甲老师", slot.teacher)

        // "30910" -> 周三 第 9、10 节
        val evening = timetable.scheduled
            .flatMap { it.slots }
            .first { it.name == "军事理论" }
        assertEquals(3, evening.dayOfWeek)
        assertEquals(9, evening.startPeriod)
        assertEquals(10, evening.endPeriod)
    }

    @Test
    fun `周次表达式解析与格式化`() {
        assertEquals(setOf(1, 2, 3, 4, 5, 6), WeekExpression.parse("1-6"))
        assertEquals(setOf(4, 5, 7, 8, 9, 10, 11, 12), WeekExpression.parse("4-5,7-12"))
        assertEquals(setOf(3, 4, 5, 7, 8, 9, 10, 11), WeekExpression.parse("3-5,7-11"))
        assertEquals(setOf(14), WeekExpression.parse("14"))
        assertEquals(emptySet<Int>(), WeekExpression.parse(""))
        assertEquals(emptySet<Int>(), WeekExpression.parse("待定"))

        assertEquals("4-5,7-12", WeekExpression.format(WeekExpression.parse("4-5,7-12")))
        assertEquals("3-5,7-11", WeekExpression.format(WeekExpression.parse("3-5,7-11")))
    }

    @Test
    fun `按周次取课并排序`() {
        val timetable = TimetableMapper.toDomain(TimetablePayloadParser.parsePayload(sample))

        // 第 1 周已经有课（高等数学、思想道德与法治、体育）
        assertTrue(timetable.slotsOfWeek(1).isNotEmpty())
        assertTrue(timetable.slotsOfWeek(10).isNotEmpty())

        val week7 = timetable.slotsOfWeek(7)
        assertEquals(week7.sortedWith(compareBy({ it.dayOfWeek }, { it.startPeriod })), week7)
    }

    @Test
    fun `周次到日期的换算`() {
        val timetable = TimetableMapper.toDomain(TimetablePayloadParser.parsePayload(sample))
        val term = timetable.term

        assertEquals(2026, term.firstMonday.year)
        assertEquals(8, term.firstMonday.monthValue)
        assertEquals(24, term.firstMonday.dayOfMonth)
        assertEquals(term.firstMonday, term.dateOf(1, 1))
        assertEquals(term.firstMonday.plusDays(2), term.dateOf(1, 3))
        assertEquals(1, term.weekOf(term.firstMonday))
        assertNull(term.weekOf(term.firstMonday.minusDays(1)))
    }

    @Test
    fun `学期起始时间按学校时区解释_不随机器时区变化`() {
        // CI runner 是 UTC，开发机是 UTC+8。若用 ZoneId.systemDefault() 解释服务端
        // 时间戳，同一份数据在两地可能解析出不同日期（UTC 00:00 与 UTC+8 08:00 同天，
        // 但 22:00 UTC 就已经是次日）。这里锁定"一律按 Asia/Shanghai 解释"。
        assertEquals(ZoneId.of("Asia/Shanghai"), Term.TERM_ZONE)

        val epoch = 1787500800L
        val term = Term(name = "2026-2027-1", totalWeeks = 20, startEpochSeconds = epoch)
        assertEquals(2026, term.firstMonday.year)
        assertEquals(8, term.firstMonday.monthValue)
        assertEquals(24, term.firstMonday.dayOfMonth)

        // 边界用例：+22h 后 UTC 仍是 8/24，而 Asia/Shanghai 已是 8/25。
        // 固定时区后仍应归到同一周的周一 8/24。
        val boundary = Term(
            name = "2026-2027-1",
            totalWeeks = 20,
            startEpochSeconds = epoch + 22 * 3600L,
        )
        assertEquals(LocalDate.of(2026, 8, 24), boundary.firstMonday)

        // 直接验证映射结果与机器时区无关
        val mapped = TimetableMapper.toDomain(TimetablePayloadParser.parsePayload(sample)).term
        val expected = Instant.ofEpochSecond(1787500800L)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .toLocalDate()
            .let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        assertEquals(expected, mapped.firstMonday)
    }

    @Test
    fun `同一格内的课程可归并为冲突`() {
        val timetable = TimetableMapper.toDomain(TimetablePayloadParser.parsePayload(sample))

        // 同一门课的多个时段可能落在同一周的同一格，应被归并到一起
        val mergedCells = (1..timetable.term.totalWeeks)
            .flatMap { week -> timetable.cellsOfWeek(week).values }
            .filter { it.size > 1 }

        assertTrue("应至少存在一个合并格", mergedCells.isNotEmpty())
        assertEquals(
            "冲突应发生在周三第 7、8 节",
            setOf(3 to 7),
            mergedCells.flatMap { it }.map { it.dayOfWeek to it.startPeriod }.toSet(),
        )
        assertTrue(
            "合并格内的课程必须同一天、同一起始节次",
            mergedCells.all { group ->
                group.map { it.dayOfWeek }.distinct().size == 1 &&
                    group.map { it.startPeriod }.distinct().size == 1
            },
        )
    }

    // ---- 可配置的课表链接 ----

    private val remote = TimetableRemoteDataSource()

    @Test
    fun `完整链接直接使用_支持任意域名`() {
        val url = "https://jwt.example.edu.cn/user/kb/table/token/12345"
        assertEquals(url, remote.urlFor(url))
        // 换一个学校的域名也应该原样使用
        val other = "https://kb.another-university.cn/timetable/abc"
        assertEquals(other, remote.urlFor(other))
    }

    @Test
    fun `只填 token 时按服务地址补全`() {
        assertEquals(
            "https://sutjwt.sut.edu.cn/user/kb/table/token/100000",
            remote.urlFor("100000"),
        )
        assertEquals(
            "https://sutjwt.sut.edu.cn/user/kb/table/token/100000",
            remote.urlFor("  100000  "),
        )
        assertEquals(
            "https://sutjwt.sut.edu.cn/user/kb/table/token/100000",
            remote.urlFor("100000/"),
        )
    }

    @Test
    fun `token 模式下可自定义服务地址`() {
        assertEquals(
            "https://my.school.edu.cn/user/kb/table/token/888",
            remote.urlFor("888", "https://my.school.edu.cn"),
        )
        assertEquals(
            "https://my.school.edu.cn/user/kb/table/token/888",
            remote.urlFor("888", "https://my.school.edu.cn/"),
        )
    }

    @Test
    fun `空链接返回空地址`() {
        assertEquals("", remote.urlFor(""))
        assertEquals("", remote.urlFor("   "))
    }

    // ---- 本地缓存 ----

    @Test
    fun `缓存序列化往返且能还原出课表`() {
        val json = Json { ignoreUnknownKeys = true }
        val entry = CachedTimetable(
            sourceUrl = "https://sutjwt.sut.edu.cn/user/kb/table/token/100000",
            payload = sample,
            syncedAt = 1_787_500_800_000L,
        )

        val text = json.encodeToString(CachedTimetable.serializer(), entry)
        val restored = json.decodeFromString(CachedTimetable.serializer(), text)

        assertEquals(entry, restored)
        assertTrue(restored.isValid)

        // 缓存里的原始 JSON 必须还能解析回完整课表
        val response = restored.toResponseOrNull()
        assertNotNull(response)
        val timetable = TimetableMapper.toDomain(response!!)
        assertEquals(20, timetable.term.totalWeeks)
        assertEquals(12, timetable.scheduled.size)
    }

    @Test
    fun `损坏的缓存内容解析返回 null`() {
        val broken = CachedTimetable(sourceUrl = "x", payload = "{ 这不是 JSON", syncedAt = 1L)
        assertNull(broken.toResponseOrNull())

        val empty = CachedTimetable(sourceUrl = "x", payload = "", syncedAt = 0L)
        assertFalse("空 payload 不应被视为有效缓存", empty.isValid)
    }

    // ---- 隐藏未排课开关 ----

    @Test
    fun `隐藏未排课开关影响列表内容`() {
        val timetable = TimetableMapper.toDomain(TimetablePayloadParser.parsePayload(sample))
        assertEquals(2, timetable.unscheduled.size)

        val shown = ScheduleUiState(timetable = timetable, hideUnscheduled = false)
        assertEquals("开关关闭时应显示全部未排课课程", 2, shown.unscheduledCourses.size)
        assertTrue(shown.unscheduledCourses.any { it.name == "军训" })

        val hidden = ScheduleUiState(timetable = timetable, hideUnscheduled = true)
        assertTrue("开关打开时应隐藏未排课课程", hidden.unscheduledCourses.isEmpty())
        // 已排课课程不受影响
        assertTrue(hidden.hasData)
        assertEquals(timetable.scheduled.size, hidden.timetable.scheduled.size)
    }

    // ---- 课表网格列宽 ----

    @Test
    fun `一屏放得下时七天铺满且不横向滚动`() {
        // 500dp 宽：减去 44dp 节次栏后每天约 65.1dp，高于 60dp 可读下限
        val layout = computeGridLayout(availableWidth = 500f, periodColumnWidth = 44f)
        assertFalse("应该一屏放下，不需要横向滚动", layout.scrollHorizontally)
        assertEquals(500f - 44f, layout.dayWidth * 7f, 0.01f)
    }

    @Test
    fun `常见手机宽度会横向滚动而不是把课程名挤扁`() {
        // 412dp 宽减去 44dp 节次栏后每天约 52.6dp，低于 60dp 下限
        val layout = computeGridLayout(availableWidth = 412f, periodColumnWidth = 44f)
        assertTrue(layout.scrollHorizontally)
        assertEquals(60f, layout.dayWidth, 0.01f)
        assertTrue(layout.dayWidth * 7f > 412f - 44f)
    }

    @Test
    fun `极窄屏仍然一屏放下_只要每列不低于可读下限`() {
        // 464dp 正好是 7×60 + 44 的分界
        val layout = computeGridLayout(availableWidth = 464f, periodColumnWidth = 44f)
        assertFalse(layout.scrollHorizontally)
        assertEquals(60f, layout.dayWidth, 0.01f)
    }

    @Test
    fun `放不下时保持可读宽度并开启横向滚动`() {
        // 360dp 宽减去 44dp 只剩 45.1dp 每天，低于 60dp 下限
        val layout = computeGridLayout(availableWidth = 360f, periodColumnWidth = 44f)
        assertTrue("列宽低于可读下限时应改为横向滚动", layout.scrollHorizontally)
        assertEquals(60f, layout.dayWidth, 0.01f)
        // 内容宽度会超出视口，这正是需要横向滚动的原因
        assertTrue(layout.dayWidth * 7f > 360f - 44f)
    }

    @Test
    fun `宽屏单列不超过上限`() {
        val layout = computeGridLayout(availableWidth = 1200f, periodColumnWidth = 44f)
        assertFalse(layout.scrollHorizontally)
        assertEquals(96f, layout.dayWidth, 0.01f)
        assertTrue("上限生效后内容会窄于视口", layout.dayWidth * 7f < 1200f - 44f)
    }

    @Test
    fun `宽度未知时按可读下限处理并允许滚动`() {
        val layout = computeGridLayout(availableWidth = 0f)
        assertTrue(layout.scrollHorizontally)
        assertEquals(60f, layout.dayWidth, 0.01f)
    }
}
