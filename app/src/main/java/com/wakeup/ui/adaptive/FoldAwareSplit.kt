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
private val MinSplitPaneSize: Dp = 180.dp

/**
 * 沿折叠线把内容分成两块，且不让内容跨过折叠线。
 *
 * [vertical] 为 true 时左右分栏（竖向铰链 / 书本姿态），否则上下分栏（横向铰链 / 桌面姿态）。
 *
 * 分栏位置优先按铰链在窗口中的真实位置计算：用 [onGloballyPositioned] 取内容容器的窗口坐标，
 * 再与 [foldBounds] 相减，得到「铰链相对内容区的偏移」，因此即使内容区因为有导航轨、
 * 顶栏或内边距而不在窗口正中，分栏线也会正好落在折叠线上。
 *
 * 下列情况退回 [firstWeight] / [secondWeight] 加权分栏（此时至少保证 [gap] 的视觉分隔）：
 * - 没有折叠信息（普通大屏）；
 * - 容器还没完成第一次布局；
 * - 折叠线落在容器之外，或切出来的两块小于 [MinSplitPaneSize]。
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
        val laidOut = containerBounds.width > 0f && containerBounds.height > 0f
        val available = if (vertical) maxWidth else maxHeight
        // 铰链相对内容区的起点，以及铰链本身的厚度
        val rawStart = foldBounds?.let { fold ->
            with(density) {
                if (vertical) {
                    (fold.left - containerBounds.left).toDp()
                } else {
                    (fold.top - containerBounds.top).toDp()
                }
            }
        }
        val rawSize = foldBounds?.let { fold ->
            with(density) { (if (vertical) fold.width else fold.height).toDp() }
        }
        // 铰链没有厚度（只是一条折痕）时，用最小分隔保证两块内容不粘连
        val exactGap = (rawSize ?: gap).coerceAtLeast(gap)
        val exactFirst = rawStart?.takeIf { start ->
            laidOut &&
                start >= MinSplitPaneSize &&
                available - start - exactGap >= MinSplitPaneSize
        }

        if (vertical) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = if (exactFirst != null) {
                        Modifier.width(exactFirst)
                    } else {
                        Modifier.weight(firstWeight)
                    },
                ) {
                    first()
                }
                Spacer(Modifier.width(if (exactFirst != null) exactGap else gap))
                Box(modifier = Modifier.weight(secondWeight)) {
                    second()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = if (exactFirst != null) {
                        Modifier.height(exactFirst)
                    } else {
                        Modifier.weight(firstWeight)
                    },
                ) {
                    first()
                }
                Spacer(Modifier.height(if (exactFirst != null) exactGap else gap))
                Box(modifier = Modifier.weight(secondWeight)) {
                    second()
                }
            }
        }
    }
}
