package com.wakeup

import com.wakeup.data.update.VersionCompare
import com.wakeup.domain.Course
import com.wakeup.domain.Term
import com.wakeup.domain.Timetable
import com.wakeup.domain.TimetableCourse
import com.wakeup.domain.isSutDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * GF 座作息的**域名校验**，以及「检查更新」的版本比较。
 *
 * GF 座（3、4 节提前 10 分钟）是沈阳工业大学特有的作息，必须同时满足：
 * 1. 课表来源域名是沈工大（`sut.edu.cn` / `*.sut.edu.cn`）；
 * 2. 教室名以 `GF` 开头。
 * 任何一条不满足都只能用标准作息 —— 否则别的学校有间叫 GF-xxx 的教室就会被误改时间。
 */
class UpdateAndSchoolTest {

    // ---------------- 沈工大域名识别 ----------------

    @Test
    fun `识别沈工大域名`() {
        val sut = listOf(
            "https://sutjwt.sut.edu.cn/user/kb/table/token/100000",
            "http://sutjwt.sut.edu.cn/user/kb/table/token/1",
            "https://sut.edu.cn/",
            "https://sut.edu.cn",
            "https://jw.sut.edu.cn/kb",
            "sut.edu.cn",
            "sutjwt.sut.edu.cn",
            "HTTPS://SUTJWT.SUT.EDU.CN/user/kb/table/token/1", // 大小写不敏感
            "  https://sutjwt.sut.edu.cn/x  ", // 前后空格
        )
        for (url in sut) {
            assertTrue("「$url」应被识别为沈工大", isSutDomain(url))
        }
    }

    @Test
    fun `其它学校域名不被识别为沈工大`() {
        val others = listOf(
            "https://jwt.example.edu.cn/user/kb/table/token/12345",
            "https://kb.another-university.cn/timetable/abc",
            "https://notsut.edu.cn/", // 不是 .sut.edu.cn 子域
            "https://sut.edu.com/", // 不同的顶级域
            "https://sutedu.cn/",
            "https://evil.com/?u=https://sut.edu.cn", // 只匹配 host，不匹配 path/query
            "https://fake-sut.edu.cn.example.com/", // 后缀不匹配
            "",
            "   ",
        )
        for (url in others) {
            assertFalse("「$url」不应被识别为沈工大", isSutDomain(url))
        }
        assertFalse("null 不应被识别", isSutDomain(null))
    }

    @Test
    fun `只有沈工大课表上的 GF 教室才用 GF 作息`() {
        val gfSlot = Course(
            courseId = "c1", name = "工程训练", teacher = "老师", location = "GF-204",
            dayOfWeek = 1, startPeriod = 3, endPeriod = 4, weeks = setOf(1),
        )
        val gfCourse = TimetableCourse("c1", "工程训练", "老师", listOf(gfSlot))

        val sutTimetable = Timetable(
            term = Term("2026-2027-1", 20, 1_787_500_800L),
            courses = listOf(gfCourse),
            sourceUrl = "https://sutjwt.sut.edu.cn/user/kb/table/token/100000",
        )
        val otherTimetable = sutTimetable.copy(
            sourceUrl = "https://jwt.example.edu.cn/user/kb/table/token/12345",
        )
        val emptySource = sutTimetable.copy(sourceUrl = "")

        assertTrue("沈工大 + GF 教室 -> 用 GF 作息", gfSlot.usesGfSchedule(sutTimetable))
        assertFalse("外校 + GF 教室 -> 不用 GF 作息", gfSlot.usesGfSchedule(otherTimetable))
        assertFalse("无来源 -> 不用 GF 作息", gfSlot.usesGfSchedule(emptySource))
    }

    @Test
    fun `非 GF 教室在任何学校都不用 GF 作息`() {
        val slot = Course(
            courseId = "c2", name = "高等数学", teacher = "老师", location = "F-105",
            dayOfWeek = 1, startPeriod = 3, endPeriod = 4, weeks = setOf(1),
        )
        val timetable = Timetable(
            term = Term("2026-2027-1", 20, 1_787_500_800L),
            courses = listOf(TimetableCourse("c2", "高等数学", "老师", listOf(slot))),
            sourceUrl = "https://sutjwt.sut.edu.cn/user/kb/table/token/100000",
        )
        assertFalse(slot.usesGfSchedule(timetable))
        // 时间也不受影响
        assertEquals(LocalTime.of(10, 25), slot.startAtWithGf)
    }

    @Test
    fun `课表来源地址会挂到领域模型上`() {
        val url = "https://sutjwt.sut.edu.cn/user/kb/table/token/100000"
        val timetable = Timetable(
            term = Term("x", 20, 0L),
            courses = emptyList(),
            sourceUrl = url,
        )
        assertEquals(url, timetable.sourceUrl)
        assertTrue(timetable.isSutSource)

        // 默认值为空 -> 不是沈工大
        assertFalse(Timetable(term = Term("x", 20, 0L), courses = emptyList()).isSutSource)
    }

    // ---------------- 版本比较 ----------------

    @Test
    fun `版本号解析`() {
        assertEquals(listOf(1, 0, 0), VersionCompare.parse("1.0.0"))
        assertEquals(listOf(1, 0, 0), VersionCompare.parse("v1.0.0"))
        assertEquals(listOf(1, 0, 0), VersionCompare.parse("V1.0.0"))
        assertEquals(listOf(1, 2), VersionCompare.parse("1.2"))
        assertEquals(listOf(2, 10, 3), VersionCompare.parse("2.10.3"))
        assertEquals(listOf(1, 2, 3), VersionCompare.parse("1.2.3-beta1"))
        assertEquals(listOf(1, 2, 3), VersionCompare.parse("1.2.3+build.5"))
        assertEquals(emptyList<Int>(), VersionCompare.parse(""))
        // 完全没有数字的 tag 视为版本 0（比任何正常版本都旧），而不是解析失败
        assertEquals(listOf(0), VersionCompare.parse("abc"))
        // 中间段非纯数字时，该段按 0 处理，但仍占位
        assertEquals(listOf(1, 0, 2), VersionCompare.parse("1.x.2"))
    }

    @Test
    fun `新版本判定`() {
        assertTrue(VersionCompare.isNewer("1.0.1", "1.0.0"))
        assertTrue(VersionCompare.isNewer("1.1.0", "1.0.9"))
        assertTrue(VersionCompare.isNewer("2.0.0", "1.9.9"))
        assertTrue(VersionCompare.isNewer("v1.0.1", "1.0.0")) // 带 v 也认
        assertTrue(VersionCompare.isNewer("1.0.0", "0.9.9"))
        // 段数不同：1.0 与 1.0.0 等价
        assertFalse(VersionCompare.isNewer("1.0", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.1"))
        assertFalse(VersionCompare.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `预发布版本低于同号正式版`() {
        assertEquals(0, VersionCompare.compare("1.0.0", "1.0.0"))
        assertTrue("1.0.0-beta1 应低于 1.0.0", VersionCompare.compare("1.0.0-beta1", "1.0.0") < 0)
        assertTrue("1.0.0 应高于 1.0.0-beta1", VersionCompare.compare("1.0.0", "1.0.0-beta1") > 0)
        assertFalse(VersionCompare.isNewer("1.0.0-beta1", "1.0.0"))
        // 但 1.0.1-beta1 仍然高于 1.0.0
        assertTrue(VersionCompare.isNewer("1.0.1-beta1", "1.0.0"))
    }

    @Test
    fun `比较结果不会因负号溢出`() {
        // Int.MIN_VALUE 之类的极端输入不应因为减法溢出而返回错误符号
        assertTrue(VersionCompare.compare("2.0.0", "1.0.0") > 0)
        assertTrue(VersionCompare.compare("1.0.0", "2.0.0") < 0)
    }
}
