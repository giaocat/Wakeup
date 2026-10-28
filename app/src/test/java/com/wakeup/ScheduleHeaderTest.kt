package com.wakeup

import com.wakeup.ui.schedule.headerMonthLabels
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 课表表头左上角「月份」标签的规则。
 *
 * 这里锁住一个真实 bug：标签以前取的是 `state.today.monthValue`（只在同步时赋值），
 * 所以翻到别的周次时左上角一直停在打开 App 的那个月（表现为"一直是 10 月"）。
 * 现在改成由展示中的这一周的日期推算。
 */
class ScheduleHeaderTest {

    /** 从周一开始的七天。 */
    private fun week(monday: LocalDate): List<LocalDate> = (0L..6L).map { monday.plusDays(it) }

    @Test
    fun `week inside one month shows that month`() {
        // 2026-10-05（周一）到 10-11，整周都在 10 月
        assertEquals(listOf("10月"), headerMonthLabels(week(LocalDate.of(2026, 10, 5))))
    }

    @Test
    fun `later month is not stuck on the current month`() {
        // 翻到 11 月的周次后必须显示 11 月，而不是停在 10 月
        assertEquals(listOf("11月"), headerMonthLabels(week(LocalDate.of(2026, 11, 2))))
    }

    @Test
    fun `week crossing a month boundary shows both months`() {
        // 2026-10-26（周一）到 11-01（周日），月底跨到 11 月
        assertEquals(
            listOf("10月", "11月"),
            headerMonthLabels(week(LocalDate.of(2026, 10, 26))),
        )
    }

    @Test
    fun `year crossing keeps chronological order`() {
        // 2026-12-28 所在周跨年：12 月在前、1 月在后，不能因为排序变成「1月」「12月」
        assertEquals(
            listOf("12月", "1月"),
            headerMonthLabels(week(LocalDate.of(2026, 12, 28))),
        )
    }

    @Test
    fun `cross month week is detected no matter where the boundary falls`() {
        // 周一到周四在 9 月，周五起进入 10 月
        assertEquals(
            listOf("9月", "10月"),
            headerMonthLabels(week(LocalDate.of(2026, 9, 28))),
        )
    }

    @Test
    fun `empty dates produce no label`() {
        assertEquals(emptyList<String>(), headerMonthLabels(emptyList()))
    }
}
