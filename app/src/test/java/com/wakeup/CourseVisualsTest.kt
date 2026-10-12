package com.wakeup

import com.wakeup.domain.Course
import com.wakeup.domain.PeriodTimes
import com.wakeup.domain.TimetableCourse
import com.wakeup.ui.theme.CourseColorSlots
import com.wakeup.ui.theme.courseColorHexSlots
import com.wakeup.ui.theme.courseColorsFor
import com.wakeup.ui.theme.hslToArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.math.abs

/**
 * 课表配色与作息时间的校验。
 *
 * 两件事必须被锁住：
 * 1. 相邻课程的颜色要能一眼分辨（颜色按下标取用，所以相邻槽位必须分属不同色相组）；
 * 2. GF 座提前 10 分钟上下课**只对 GF 座生效**，不能波及其他教学楼。
 */
class CourseVisualsTest {

    // ---------------- 配色 ----------------

    @Test
    fun `配色槽位数量为 7 色相 × 4 档`() {
        val slots = courseColorHexSlots()
        assertEquals(28, slots.size)
        assertEquals(CourseColorSlots.size, slots.size)
    }

    @Test
    fun `所有槽位颜色互不相同`() {
        val slots = courseColorHexSlots()
        assertEquals("槽位存在重复颜色", slots.size, slots.toSet().size)
    }

    @Test
    fun `任意相邻槽位颜色差异明显`() {
        val slots = courseColorHexSlots()

        fun channel(color: Long, shift: Int): Int = ((color shr shift) and 0xFF).toInt()
        fun distance(a: Long, b: Long): Double {
            val dr = channel(a, 16) - channel(b, 16)
            val dg = channel(a, 8) - channel(b, 8)
            val db = channel(a, 0) - channel(b, 0)
            return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
        }

        // 课表里挨着的两门课取相邻下标，必须明显不同。
        // 阈值 64 远低于"同色系相邻"的距离（约 30 上下），足以挡住回归。
        for (index in 0 until slots.size - 1) {
            val d = distance(slots[index], slots[index + 1])
            assertTrue(
                "槽位 $index 与 ${index + 1} 颜色太接近（距离 $d）",
                d > 64.0,
            )
        }
    }

    @Test
    fun `相邻槽位色相确实拉开`() {
        // 从 RGB 反推色相，验证"交错排列让相邻槽位跨色相组"这一设计真的生效。
        val hues = courseColorHexSlots().map { hueOf(it) }
        for (index in 0 until hues.size - 1) {
            val diff = hueDistance(hues[index], hues[index + 1])
            assertTrue(
                "槽位 $index（色相 ${hues[index]}）与 ${index + 1}（色相 ${hues[index + 1]}）色相太接近",
                diff >= 25f,
            )
        }
    }

    /** 从 ARGB 反推色相（0..360）。 */
    private fun hueOf(argb: Long): Float {
        val r = ((argb shr 16) and 0xFF).toFloat() / 255f
        val g = ((argb shr 8) and 0xFF).toFloat() / 255f
        val b = (argb and 0xFF).toFloat() / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta < 1e-6f) return 0f
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return (hue + 360f) % 360f
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val raw = abs(a - b)
        return minOf(raw, 360f - raw)
    }

    @Test
    fun `同一门课颜色保持一致`() {
        val courses = listOf(
            timetableCourse("c1", "高等数学", "GF-101"),
            timetableCourse("c2", "大学物理", "A-101"),
            timetableCourse("c1", "高等数学", "GF-101"), // 同一门课的不同时段
        )
        val colors = courseColorsFor(courses)
        assertEquals("只应为 2 门不同的课分配颜色", 2, colors.size)
        assertNotEquals(colors["c1"], colors["c2"])
    }

    @Test
    fun `课表内相邻课程颜色不同`() {
        val courses = (1..10).map { timetableCourse("c$it", "课程$it", "A-10$it") }
        val colors = courseColorsFor(courses)
        val ordered = courses.map { colors[it.courseId] }
        for (index in 0 until ordered.size - 1) {
            assertNotEquals(
                "第 $index 门与第 ${index + 1} 门课颜色相同",
                ordered[index],
                ordered[index + 1],
            )
        }
    }

    @Test
    fun `HSL 转换产出合法 ARGB`() {
        val red = hslToArgb(0f, 1f, 0.5f)
        assertEquals(0xFFFF0000L, red)
        val green = hslToArgb(120f, 1f, 0.5f)
        assertEquals(0xFF00FF00L, green)
        val blue = hslToArgb(240f, 1f, 0.5f)
        assertEquals(0xFF0000FFL, blue)
        // 全部不透明
        courseColorHexSlots().forEach { color ->
            assertEquals("alpha 必须为 FF", 0xFFL, (color shr 24) and 0xFF)
        }
    }

    // ---------------- GF 座作息 ----------------

    @Test
    fun `GF 座 3 和 4 节提前 10 分钟`() {
        assertEquals(LocalTime.of(10, 15), PeriodTimes.startOf(3, "GF-101"))
        assertEquals(LocalTime.of(11, 0), PeriodTimes.endOf(3, "GF-101"))
        assertEquals(LocalTime.of(11, 5), PeriodTimes.startOf(4, "GF-101"))
        assertEquals(LocalTime.of(11, 50), PeriodTimes.endOf(4, "GF-101"))

        // 与标准作息正好差 10 分钟
        assertEquals(
            10,
            java.time.Duration.between(
                PeriodTimes.startOf(3, "GF-101"),
                PeriodTimes.startOf(3),
            ).toMinutes(),
        )
    }

    @Test
    fun `GF 座只影响 3 和 4 节_其余节次与标准一致`() {
        val standard = PeriodTimes.all
        for (period in standard) {
            val gf = PeriodTimes.of(period.period, "GF-101")
            if (period.period == 3 || period.period == 4) {
                assertNotEquals("第 ${period.period} 节应被调整", period, gf)
            } else {
                assertEquals("第 ${period.period} 节不应被调整", period, gf)
            }
        }
        // 明确断言 1、2、5 节等没被动
        assertEquals(LocalTime.of(8, 30), PeriodTimes.startOf(1, "GF-101"))
        assertEquals(LocalTime.of(10, 5), PeriodTimes.endOf(2, "GF-101"))
        assertEquals(LocalTime.of(13, 30), PeriodTimes.startOf(5, "GF-101"))
        assertEquals(LocalTime.of(21, 15), PeriodTimes.endOf(12, "GF-101"))
    }

    @Test
    fun `非 GF 教学楼完全不受影响`() {
        // 本工程实际数据中出现过的其它楼栋，必须全部保持标准作息
        val otherLocations = listOf(
            "F-105", "F-304", "B-109", "B-202", "C-101", "C-303", "C-401",
            "A-101", "D-105", "E-201", "H-301", "G-101", "F-207", "A-407",
            null, "", "   ", "无教室",
        )
        for (location in otherLocations) {
            assertFalse(
                "「$location」不应被识别为 GF 座",
                PeriodTimes.isGfBuilding(location),
            )
            for (period in PeriodTimes.all) {
                assertEquals(
                    "「$location」第 ${period.period} 节不应被调整",
                    PeriodTimes.of(period.period),
                    PeriodTimes.of(period.period, location),
                )
            }
        }
    }

    @Test
    fun `GF 前缀识别大小写与空格容错`() {
        // 规则：去空格、忽略大小写后以 "GF" 开头（真实格式为 GF-101 / GF101）
        assertTrue(PeriodTimes.isGfBuilding("GF-101"))
        assertTrue(PeriodTimes.isGfBuilding("gf-101")) // 数据大小写不规范也认
        assertTrue(PeriodTimes.isGfBuilding("Gf-202"))
        assertTrue(PeriodTimes.isGfBuilding("  GF-303  "))
        assertTrue(PeriodTimes.isGfBuilding("GF"))
        assertTrue(PeriodTimes.isGfBuilding("GFA-101")) // 以 GF 开头，按前缀规则命中
        // 这些不是 GF 座
        assertFalse(PeriodTimes.isGfBuilding("G-101"))
        assertFalse(PeriodTimes.isGfBuilding("F-101"))
        assertFalse(PeriodTimes.isGfBuilding("AGF-101")) // 不是以 GF 开头
        assertFalse(PeriodTimes.isGfBuilding("FG-101"))
        assertFalse(PeriodTimes.isGfBuilding(null))
        assertFalse(PeriodTimes.isGfBuilding(""))
        assertFalse(PeriodTimes.isGfBuilding("   "))
    }

    @Test
    fun `课程的起止时间按自己的教室计算`() {
        val gfCourse = course("c1", "工程训练", "GF-204", startPeriod = 3, endPeriod = 4)
        assertEquals(LocalTime.of(10, 15), gfCourse.startAtWithGf)
        assertEquals(LocalTime.of(11, 50), gfCourse.endAtWithGf)
        // 标准时间仍是原值，二者相差 10 分钟
        assertEquals(LocalTime.of(10, 25), gfCourse.standardStartAt)
        assertEquals(LocalTime.of(12, 0), gfCourse.standardEndAt)
        assertEquals(
            10,
            java.time.Duration.between(gfCourse.startAtWithGf, gfCourse.standardStartAt).toMinutes(),
        )

        val normalCourse = course("c2", "高等数学", "F-105", startPeriod = 3, endPeriod = 4)
        assertEquals(LocalTime.of(10, 25), normalCourse.startAtWithGf)
        assertEquals(LocalTime.of(12, 0), normalCourse.endAtWithGf)

        // 同一节次，GF 座比普通教室早 10 分钟
        assertEquals(
            10,
            java.time.Duration.between(gfCourse.startAtWithGf, normalCourse.startAtWithGf).toMinutes(),
        )
    }

    @Test
    fun `differsFromStandard 只在 GF 座的 3 和 4 节为真`() {
        assertTrue(PeriodTimes.differsFromStandard(3, "GF-101"))
        assertTrue(PeriodTimes.differsFromStandard(4, "GF-101"))
        assertFalse(PeriodTimes.differsFromStandard(1, "GF-101"))
        assertFalse(PeriodTimes.differsFromStandard(5, "GF-101"))
        assertFalse(PeriodTimes.differsFromStandard(3, "F-101"))
        assertFalse(PeriodTimes.differsFromStandard(4, "A-101"))
        assertFalse(PeriodTimes.differsFromStandard(3, null))
    }

    @Test
    fun `标准作息本身未被打乱`() {
        // 防止有人直接把 all 改成 GF 时间
        assertEquals(LocalTime.of(10, 25), PeriodTimes.startOf(3))
        assertEquals(LocalTime.of(11, 10), PeriodTimes.endOf(3))
        assertEquals(LocalTime.of(11, 15), PeriodTimes.startOf(4))
        assertEquals(LocalTime.of(12, 0), PeriodTimes.endOf(4))
        assertEquals(12, PeriodTimes.all.size)
        assertEquals((1..12).toList(), PeriodTimes.all.map { it.period })
    }

    private fun course(
        id: String,
        name: String,
        location: String,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
    ): Course = Course(
        courseId = id,
        name = name,
        teacher = "老师",
        location = location,
        dayOfWeek = 1,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = setOf(1, 2, 3),
    )

    private fun timetableCourse(
        id: String,
        name: String,
        location: String,
    ): TimetableCourse = TimetableCourse(
        courseId = id,
        name = name,
        teacher = "老师",
        slots = listOf(course(id, name, location)),
    )
}
