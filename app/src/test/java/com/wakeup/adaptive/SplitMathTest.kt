package com.wakeup.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 折叠屏分栏位置计算（纯函数）。
 *
 * 这段逻辑决定"两条内容会不会画到折痕上"，之前写在 Composable 里既难测也难复盘，
 * 所以抽成 [exactFirstPanePx] / [splitGapPx] 并在这里锁住边界：
 * 容器没布局、折叠线贴边、折叠线太靠后、容器有偏移、折痕没有厚度。
 */
class SplitMathTest {

    private val minPane = 180f
    private val minGap = 8f

    @Test
    fun `折叠线在中间时第一块正好到折叠线`() {
        val first = exactFirstPanePx(
            containerStartPx = 0f,
            foldStartPx = 400f,
            foldThicknessPx = 8f,
            availablePx = 1000f,
            minPanePx = minPane,
            minGapPx = minGap,
        )
        assertEquals(400f, first!!, 0.01f)
    }

    @Test
    fun `容器有偏移时按相对位置计算`() {
        // 内容区从窗口 x=100 开始（前面有导航轨），折叠线在窗口 x=500 → 相对 400
        val first = exactFirstPanePx(
            containerStartPx = 100f,
            foldStartPx = 500f,
            foldThicknessPx = 8f,
            availablePx = 900f,
            minPanePx = minPane,
            minGapPx = minGap,
        )
        assertEquals(400f, first!!, 0.01f)
    }

    @Test
    fun `折叠线贴边时退回加权分栏`() {
        assertNull(
            exactFirstPanePx(
                containerStartPx = 0f,
                foldStartPx = 100f,
                foldThicknessPx = 8f,
                availablePx = 1000f,
                minPanePx = minPane,
                minGapPx = minGap,
            ),
        )
    }

    @Test
    fun `折叠线太靠后导致第二块太小时退回加权分栏`() {
        // 1000 - 900 - 20 = 80 < 180
        assertNull(
            exactFirstPanePx(
                containerStartPx = 0f,
                foldStartPx = 900f,
                foldThicknessPx = 20f,
                availablePx = 1000f,
                minPanePx = minPane,
                minGapPx = minGap,
            ),
        )
    }

    @Test
    fun `容器还没布局时返回 null`() {
        assertNull(
            exactFirstPanePx(
                containerStartPx = 0f,
                foldStartPx = 400f,
                foldThicknessPx = 0f,
                availablePx = 0f,
                minPanePx = minPane,
                minGapPx = minGap,
            ),
        )
    }

    @Test
    fun `折痕没有厚度时用最小间隙`() {
        assertEquals(8f, splitGapPx(foldThicknessPx = 0f, minGapPx = minGap), 0.01f)
        // 铰链有厚度就用铰链厚度
        assertEquals(24f, splitGapPx(foldThicknessPx = 24f, minGapPx = minGap), 0.01f)
    }

    @Test
    fun `第一块刚够最小尺寸时仍然按折叠线分栏`() {
        val first = exactFirstPanePx(
            containerStartPx = 0f,
            foldStartPx = minPane, // 起点正好 180，不算"贴边"
            foldThicknessPx = 8f,
            availablePx = 1000f,
            minPanePx = minPane,
            minGapPx = minGap,
        )
        assertEquals(minPane, first!!, 0.01f)
    }
}
