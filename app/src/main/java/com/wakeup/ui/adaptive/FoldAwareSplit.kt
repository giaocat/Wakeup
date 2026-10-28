package com.wakeup.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 单块内容的最小尺寸：低于它就退回加权分栏，避免在折叠屏上切出两条细缝。 */
internal val MinSplitPaneSize: Dp = 180.dp

/**
 * 分栏时第一块应该占多少像素。
 *
 * 抽成纯函数是为了能单测：这里以前把"铰链位置换算 + 可行性判断"直接写在 Composable 里，
 * 而它恰恰是最容易算错几个 dp、又最难在真机上复现的一段。
 *
 * 返回 null 表示折叠线这次不可用，调用方应退回按权重分栏：
 * - 容器还没完成布局（[availablePx] <= 0）；
 * - 折叠线落在容器起点之前，或紧贴起点，切出来的第一块小于 [minPanePx]；
 * - 折叠线太靠后，减去铰链厚度后第二块也小于 [minPanePx]。
 *
 * @param containerStartPx 内容容器在窗口坐标系里的起点（竖向分栏取 left，横向取 top）
 * @param foldStartPx 折叠线在窗口坐标系里的起点
 * @param foldThicknessPx 折叠线厚度（铰链宽度/高度；只是一条折痕时为 0）
 * @param availablePx 内容容器沿分栏方向的可用尺寸
 * @param minPanePx 单块内容的最小尺寸
 * @param minGapPx 两块之间至少留的间隙
 */
internal fun exactFirstPanePx(
    containerStartPx: Float,
    foldStartPx: Float,
    foldThicknessPx: Float,
    availablePx: Float,
    minPanePx: Float,
    minGapPx: Float,
): Float? {
    if (availablePx <= 0f) return null
    val start = foldStartPx - containerStartPx
    val gapPx = splitGapPx(foldThicknessPx, minGapPx)
    if (start < minPanePx) return null
    if (availablePx - start - gapPx < minPanePx) return null
    return start
}

/**
 * 两块内容之间的间隙：铰链有厚度就用铰链厚度，否则用最小间隙（折痕本身没有厚度）。
 */
internal fun splitGapPx(foldThicknessPx: Float, minGapPx: Float): Float =
    foldThicknessPx.coerceAtLeast(minGapPx)

/**
 * 沿折叠线把内容分成两块，且不让内容跨过折叠线。
 *
 * [vertical] 为 true 时左右分栏（竖向铰链 / 书本姿态），否则上下分栏（横向铰链 / 桌面姿态）。
 *
 * 分栏位置优先按铰链在窗口中的真实位置计算：用 [onGloballyPositioned] 取内容容器的窗口坐标，
 * 再交给 [exactFirstPanePx] 与折叠线相减，因此即使内容区因为有导航轨、顶栏或内边距
 * 而不在窗口正中，分栏线也会正好落在折叠线上。
 *
 * 折叠线不可用（没有折叠信息 / 容器还没布局 / 切出来的两块太小）时退回
 * [firstWeight] / [secondWeight] 加权分栏，此时至少保证 [gap] 的视觉分隔。
 */
@Composable
fun FoldAwareSplit(
    vertical: Boolean,
    foldBounds: Rect?,
    gap: Dp,
    modifier: Modifier = Modifier,
    firstWeight: Float = 1f,
    secondWeight: Float = 1f,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var containerBounds by remember { mutableStateOf(Rect.Zero) }

    BoxWithConstraints(
        modifier = modifier.onGloballyPositioned { containerBounds = it.boundsInWindow() },
    ) {
        val available = if (vertical) maxWidth else maxHeight
        val foldThickness = foldBounds?.let { if (vertical) it.width else it.height }
        val exactFirstPx = foldBounds?.let { fold ->
            with(density) {
                exactFirstPanePx(
                    containerStartPx = if (vertical) containerBounds.left else containerBounds.top,
                    foldStartPx = if (vertical) fold.left else fold.top,
                    foldThicknessPx = foldThickness ?: 0f,
                    availablePx = available.toPx(),
                    minPanePx = MinSplitPaneSize.toPx(),
                    minGapPx = gap.toPx(),
                )
            }
        }
        val paneGap: Dp = if (exactFirstPx != null) {
            with(density) { splitGapPx(foldThickness ?: 0f, gap.toPx()).toDp() }
        } else {
            gap
        }
        val exactFirst: Dp? = exactFirstPx?.let { with(density) { it.toDp() } }

        if (vertical) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = if (exactFirst != null) Modifier.width(exactFirst) else Modifier.weight(firstWeight),
                ) {
                    first()
                }
                Spacer(Modifier.width(paneGap))
                Box(modifier = Modifier.weight(secondWeight)) {
                    second()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = if (exactFirst != null) Modifier.height(exactFirst) else Modifier.weight(firstWeight),
                ) {
                    first()
                }
                Spacer(Modifier.height(paneGap))
                Box(modifier = Modifier.weight(secondWeight)) {
                    second()
                }
            }
        }
    }
}
