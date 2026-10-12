package com.wakeup

import com.wakeup.data.update.UpdateChecker
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
            courseId = "c1", name = "工程训练", teacher = "老师", location = "F-204",
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
            courseId = "c2", name = "高等数学", teacher = "老师", location = "C-105",
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

    // ---------------- atom feed 解析 ----------------

    @Test
    fun `解析 releases_atom feed`() {
        val releases = UpdateChecker.parseAtom(SAMPLE_ATOM)
        assertEquals(2, releases.size)

        val first = releases[0]
        assertEquals("1.0.1", first.version) // 应去掉 v 前缀
        assertEquals("v1.0.1", first.title)
        assertEquals("https://github.com/giaocat/Wakeup/releases/tag/v1.0.1", first.url)
        assertTrue("发布说明应被解析出来", first.content.contains("修复了配色"))
        assertFalse("不应残留 HTML 标签", first.content.contains("<p>"))
        assertTrue("实体应被还原成 &", first.content.contains("A & B"))

        val second = releases[1]
        assertEquals("1.0.0", second.version)
        assertEquals("https://github.com/giaocat/Wakeup/releases/tag/v1.0.0", second.url)
    }

    @Test
    fun `atom 解析对残缺内容容错`() {
        assertEquals(0, UpdateChecker.parseAtom("").size)
        assertEquals(0, UpdateChecker.parseAtom("<feed></feed>").size)
        // 有 entry 但没有 id 的应被跳过
        assertEquals(
            0,
            UpdateChecker.parseAtom("<feed><entry><title>x</title></entry></feed>").size,
        )
    }

    @Test
    fun `HTML 实体还原与去标签`() {
        assertEquals("A & B", UpdateChecker.decodeEntities("A &amp; B"))
        assertEquals("引号 \" 和 '", UpdateChecker.decodeEntities("引号 &quot; 和 &#39;"))
        // 真实标签整段丢弃
        assertEquals("去掉标签", UpdateChecker.decodeEntities("<p>去</p><b>掉</b>标签"))
        // 关键：atom feed 里的内容是被转义的 HTML，必须先还原实体再去标签
        assertEquals("X", UpdateChecker.decodeEntities("&lt;p&gt;X&lt;/p&gt;"))
        assertEquals(
            "先还原再去标签",
            UpdateChecker.decodeEntities("&lt;p&gt;先还原&lt;/p&gt;&lt;p&gt;再去标签&lt;/p&gt;"),
        )
        // 段落之间的空行保留为换行
        assertEquals("换行\n\n保留", UpdateChecker.decodeEntities("<p>换行</p>\n\n\n<p>保留</p>"))
        // 数字实体
        assertEquals("A", UpdateChecker.decodeEntities("&#65;"))
        assertEquals("A", UpdateChecker.decodeEntities("&#x41;"))
        // 不认识的实体原样保留，不能丢内容
        assertEquals("&unknown; 保留", UpdateChecker.decodeEntities("&unknown; 保留"))
        // 单个 & 不应出错
        assertEquals("A & B", UpdateChecker.decodeEntities("A & B"))
        // 只解码一次：&amp;amp; 的结果是字面量 &amp;，不是 &
        assertEquals("A &amp; B", UpdateChecker.decodeEntities("A &amp;amp; B"))
        assertEquals("", UpdateChecker.decodeEntities(""))
        assertEquals("", UpdateChecker.decodeEntities("   "))
    }

    @Test
    fun `字面量尖括号会被当作标签去掉_这是已知取舍`() {
        // 解码后的 `< b & c >` 与真标签无法区分，这里选择"发布说明干净可读"，
        // 代价是文本里的字面量尖括号被吃掉（两侧空格保留）。
        // 仅影响弹窗展示，不做解析，因此安全。
        assertEquals("a d", UpdateChecker.decodeEntities("a &lt; b &amp; c &gt; d"))
        assertEquals("引用", UpdateChecker.decodeEntities("&lt;div&gt;引用&lt;/div&gt;"))
    }

    private companion object {
        /** 按 giaocat/Wakeup 实际返回的 `releases.atom` 结构构造。 */
        const val SAMPLE_ATOM = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/" xml:lang="en-US">
  <link type="text/html" rel="alternate" href="https://github.com/giaocat/Wakeup/releases"/>
  <title>Release notes from Wakeup</title>
  <entry>
    <id>tag:github.com,2008:Repository/123456789/v1.0.1</id>
    <updated>2026-10-11T07:07:26Z</updated>
    <link rel="alternate" type="text/html" href="https://github.com/giaocat/Wakeup/releases/tag/v1.0.1"/>
    <title>v1.0.1</title>
    <content type="html">&lt;p&gt;修复了配色区分度，A &amp; B&lt;/p&gt;</content>
  </entry>
  <entry>
    <id>tag:github.com,2008:Repository/123456789/v1.0.0</id>
    <updated>2026-10-10T07:07:26Z</updated>
    <link rel="alternate" type="text/html" href="https://github.com/giaocat/Wakeup/releases/tag/v1.0.0"/>
    <title>v1.0.0</title>
    <content type="html">&lt;p&gt;首个版本&lt;/p&gt;</content>
  </entry>
</feed>"""
    }
}
