package com.wakeup

import com.wakeup.domain.Course
import com.wakeup.domain.PeriodTimes
import com.wakeup.domain.TimetableCourse
import com.wakeup.ui.theme.CourseColorSlots
import com.wakeup.ui.theme.courseColorHexSlots
import com.wakeup.ui.theme.courseColorsFor
import com.wakeup.ui.theme.hslToArgb
import com.wakeup.util.TimeUtils
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
            timetableCourse("c1", "高等数学", "G-101"),
            timetableCourse("c2", "大学物理", "A-101"),
            timetableCourse("c1", "高等数学", "G-101"), // 同一门课的不同时段
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
        assertEquals(LocalTime.of(10, 15), PeriodTimes.startOf(3, "G-101"))
        assertEquals(LocalTime.of(11, 0), PeriodTimes.endOf(3, "G-101"))
        assertEquals(LocalTime.of(11, 5), PeriodTimes.startOf(4, "G-101"))
        assertEquals(LocalTime.of(11, 50), PeriodTimes.endOf(4, "G-101"))

        // 与标准作息正好差 10 分钟
        assertEquals(
            10,
            java.time.Duration.between(
                PeriodTimes.startOf(3, "G-101"),
                PeriodTimes.startOf(3),
            ).toMinutes(),
        )
    }

    @Test
    fun `GF 座只影响 3 和 4 节_其余节次与标准一致`() {
        val standard = PeriodTimes.all
        for (period in standard) {
            val gf = PeriodTimes.of(period.period, "G-101")
            if (period.period == 3 || period.period == 4) {
                assertNotEquals("第 ${period.period} 节应被调整", period, gf)
            } else {
                assertEquals("第 ${period.period} 节不应被调整", period, gf)
            }
        }
        // 明确断言 1、2、5 节等没被动
        assertEquals(LocalTime.of(8, 30), PeriodTimes.startOf(1, "G-101"))
        assertEquals(LocalTime.of(10, 5), PeriodTimes.endOf(2, "G-101"))
        assertEquals(LocalTime.of(13, 30), PeriodTimes.startOf(5, "G-101"))
        assertEquals(LocalTime.of(21, 15), PeriodTimes.endOf(12, "G-101"))
    }

    @Test
    fun `非 G F 座教学楼完全不受影响`() {
        // 只有 G 座、F 座提前；其它楼栋必须保持标准作息
        val otherLocations = listOf(
            "A-101", "B-109", "B-202", "C-101", "C-303", "C-401",
            "D-105", "E-201", "H-301",
            null, "", "   ", "无教室",
        )
        for (location in otherLocations) {
            assertFalse(
                "「$location」不应被识别为 G/F 座",
                PeriodTimes.isEarlyBuilding(location),
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
    fun `G 座与 F 座识别_大小写与空格容错`() {
        // 真实教室名形如 G-101 / F-105
        assertTrue(PeriodTimes.isEarlyBuilding("G-101"))
        assertTrue(PeriodTimes.isEarlyBuilding("F-105"))
        assertTrue(PeriodTimes.isEarlyBuilding("g-101"))
        assertTrue(PeriodTimes.isEarlyBuilding("f-105"))
        assertTrue(PeriodTimes.isEarlyBuilding("  G-303  "))
        assertTrue(PeriodTimes.isEarlyBuilding("G"))
        assertTrue(PeriodTimes.isEarlyBuilding("F"))
        // 没写分隔符也认
        assertTrue(PeriodTimes.isEarlyBuilding("G101"))
        assertTrue(PeriodTimes.isEarlyBuilding("F207"))
        // 关键回归：其它楼栋不受影响
        assertFalse(PeriodTimes.isEarlyBuilding("A-101"))
        assertFalse(PeriodTimes.isEarlyBuilding("B-109"))
        assertFalse(PeriodTimes.isEarlyBuilding("C-303"))
        assertFalse(PeriodTimes.isEarlyBuilding("D-105"))
        assertFalse(PeriodTimes.isEarlyBuilding("E-201"))
        assertFalse(PeriodTimes.isEarlyBuilding("H-301"))
        assertFalse(PeriodTimes.isEarlyBuilding(null))
        assertFalse(PeriodTimes.isEarlyBuilding(""))
        assertFalse(PeriodTimes.isEarlyBuilding("   "))
        // 旧名与新名一致
        assertEquals(PeriodTimes.isEarlyBuilding("F-105"), PeriodTimes.isGfBuilding("F-105"))
    }

    @Test
    fun `G 座和 F 座的 3 4 节都提前 10 分钟`() {
        for (location in listOf("G-101", "F-105", "g-202", "F404")) {
            assertEquals("$location 第 3 节开始", LocalTime.of(10, 15), PeriodTimes.startOf(3, location))
            assertEquals("$location 第 3 节结束", LocalTime.of(11, 0), PeriodTimes.endOf(3, location))
            assertEquals("$location 第 4 节开始", LocalTime.of(11, 5), PeriodTimes.startOf(4, location))
            assertEquals("$location 第 4 节结束", LocalTime.of(11, 50), PeriodTimes.endOf(4, location))
        }
        // 对照：C 座不提前
        assertEquals(LocalTime.of(10, 25), PeriodTimes.startOf(3, "C-303"))
        assertEquals(LocalTime.of(11, 15), PeriodTimes.startOf(4, "C-303"))
    }

    @Test
    fun `课程的起止时间按自己的教室计算`() {
        val gfCourse = course("c1", "工程训练", "F-204", startPeriod = 3, endPeriod = 4)
        assertEquals(LocalTime.of(10, 15), gfCourse.startAtWithGf)
        assertEquals(LocalTime.of(11, 50), gfCourse.endAtWithGf)
        // 标准时间仍是原值，二者相差 10 分钟
        assertEquals(LocalTime.of(10, 25), gfCourse.standardStartAt)
        assertEquals(LocalTime.of(12, 0), gfCourse.standardEndAt)
        assertEquals(
            10,
            java.time.Duration.between(gfCourse.startAtWithGf, gfCourse.standardStartAt).toMinutes(),
        )

        val normalCourse = course("c2", "高等数学", "C-303", startPeriod = 3, endPeriod = 4)
        assertEquals(LocalTime.of(10, 25), normalCourse.startAtWithGf)
        assertEquals(LocalTime.of(12, 0), normalCourse.endAtWithGf)

        // 同一节次，G/F 座比普通教室早 10 分钟
        assertEquals(
            10,
            java.time.Duration.between(gfCourse.startAtWithGf, normalCourse.startAtWithGf).toMinutes(),
        )
    }

    @Test
    fun `当周有 G F 座的课时_侧栏与时刻线都用提前后的作息`() {
        val gCourse = course("c1", "工程训练", "G-101", startPeriod = 3, endPeriod = 4)
        val cCourse = course("c2", "高等数学", "C-303", startPeriod = 1, endPeriod = 2)

        // 没有 G/F 座课程时：等于标准作息
        val without = TimeUtils.effectivePeriods(listOf(cCourse))
        assertEquals(LocalTime.of(10, 25), without.first { it.period == 3 }.start)

        // 有 G/F 座课程时：3、4 节换成提前 10 分钟
        val with = TimeUtils.effectivePeriods(listOf(cCourse, gCourse))
        assertEquals(LocalTime.of(10, 15), with.first { it.period == 3 }.start)
        assertEquals(LocalTime.of(11, 0), with.first { it.period == 3 }.end)
        assertEquals(LocalTime.of(11, 5), with.first { it.period == 4 }.start)
        assertEquals(LocalTime.of(11, 50), with.first { it.period == 4 }.end)
        // 其它节次不受影响
        assertEquals(LocalTime.of(8, 30), with.first { it.period == 1 }.start)
        assertEquals(LocalTime.of(13, 30), with.first { it.period == 5 }.start)

        // 时刻线：10:20 在提前作息下已经属于第 3 节，标准作息下还在第 2、3 节之间的空档
        val earlyOffset = TimeUtils.gridOffsetFor(LocalTime.of(10, 20), with)
        val standardOffset = TimeUtils.gridOffsetFor(LocalTime.of(10, 20), without)
        assertTrue(
            "提前作息下时刻线应更靠下（提前=$earlyOffset，标准=$standardOffset）",
            earlyOffset > standardOffset,
        )
        assertTrue("应落在第 3 节内", earlyOffset >= 2f && earlyOffset < 3f)
    }

    @Test
    fun `differsFromStandard 只在 G F 座的 3 和 4 节为真`() {
        assertTrue(PeriodTimes.differsFromStandard(3, "G-101"))
        assertTrue(PeriodTimes.differsFromStandard(4, "F-105"))
        assertFalse(PeriodTimes.differsFromStandard(1, "G-101"))
        assertFalse(PeriodTimes.differsFromStandard(5, "F-105"))
        assertFalse(PeriodTimes.differsFromStandard(3, "C-101"))
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
