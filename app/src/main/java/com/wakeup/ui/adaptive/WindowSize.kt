package com.wakeup.ui.adaptive

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo

/**
 * 大屏正文的最大宽度。
 *
 * Android / Material 3 自适应规范给出的大屏「舒适阅读宽度」是 ~840dp：再宽的话
 * 单栏文字会拉成长句、视线回扫成本明显上升。大屏上的设置页与「今日」都用它居中收窄。
 */
val ContentMaxWidth: Dp = 840.dp

/** 分栏之间至少让出的间隙（没有可测量铰链时也用它保证「清晰分隔」）。 */
val MinPaneGap: Dp = 8.dp

/** 折叠屏姿态（由「会分隔内容」的 [FoldingFeature] 推导）。 */
enum class FoldPosture {
    /** 没有分隔特征：退回普通窗口尺寸类规则。 */
    NONE,

    /** 竖向铰链 = 书本姿态：内容分列在铰链左右两侧。 */
    VERTICAL,

    /** 横向铰链 = 桌面姿态：内容上下分栏（课表在上、信息在下）。 */
    HORIZONTAL,
}

/**
 * 各页共用的窗口适配信息。
 *
 * 尺寸类在 Activity 边界处用 `calculateWindowSizeClass` 算一次，折叠姿态来自
 * `WindowInfoTracker.windowLayoutInfo` 这条持续发射的流；两者合并成这一个不可变对象，
 * 屏幕内部只需要读它，不必各算各的。
 */
data class AdaptiveWindowInfo(
    val widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    val heightSizeClass: WindowHeightSizeClass = WindowHeightSizeClass.Compact,
    val foldPosture: FoldPosture = FoldPosture.NONE,
    /** 分隔特征占用的空隙：竖向折叠是铰链宽度，横向折叠是铰链高度。 */
    val foldGap: Dp = 0.dp,
    /**
     * 分隔特征在窗口坐标系里的位置（px）。
     *
     * 分栏时用它把分栏线精确压到折叠线上：课表页的正文区域可能因为导航轨 / 内边距
     * 而不在窗口正中，只按权重分栏会把内容画到折叠线上。
     */
    val foldBounds: Rect? = null,
) {
    /** 手机竖屏：底部导航栏 + 底部面板。 */
    val isCompactWidth: Boolean get() = widthSizeClass == WindowWidthSizeClass.Compact

    /** 展开态（平板横屏 / 大窗口）：课表改双栏、正文收窄居中。 */
    val isExpandedWidth: Boolean get() = widthSizeClass == WindowWidthSizeClass.Expanded

    /** Medium / Expanded：改用侧边导航轨，底部导航栏移除。 */
    val usesNavigationRail: Boolean get() = !isCompactWidth

    /** 存在会分隔内容的折叠线（铰链 / 分割线）。 */
    val hasFold: Boolean get() = foldPosture != FoldPosture.NONE

    /** 竖向铰链（书本姿态）。 */
    val hasVerticalFold: Boolean get() = foldPosture == FoldPosture.VERTICAL

    /** 横向铰链（桌面姿态）。 */
    val hasHorizontalFold: Boolean get() = foldPosture == FoldPosture.HORIZONTAL

    /**
     * 课程表「列表 + 详情」用内嵌面板而不是底部面板。
     *
     * 展开宽度天然够并排；只要存在分隔特征也必须拆开，否则内容会被画到折叠线上。
     */
    val usesInlineDetail: Boolean get() = isExpandedWidth || hasFold

    /** 分栏间隙：有铰链就用铰链厚度，否则用 [MinPaneGap]。 */
    val paneGap: Dp get() = if (hasFold) foldGap.coerceAtLeast(MinPaneGap) else MinPaneGap
}

/** 取出「会分隔内容」的折叠特征；没有则返回 null。 */
fun WindowLayoutInfo.separatingFold(): FoldingFeature? =
    displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull { it.isSeparating }

/** 折叠特征在版面上占用的厚度：竖向折叠取宽度，横向折叠取高度。 */
fun FoldingFeature.hingeThickness(density: Density): Dp = with(density) {
    if (orientation == FoldingFeature.Orientation.VERTICAL) {
        bounds.width().toDp()
    } else {
        bounds.height().toDp()
    }
}

/**
 * 合并窗口尺寸类与折叠信息。
 *
 * 两个入参都可以为 null（预览 / 没有折叠信息时），此时按「手机竖屏、无折叠」处理，
 * 保证 `WakeupApp` 及其各页在 @Preview 里仍能独立渲染。
 */
@Composable
fun rememberAdaptiveWindowInfo(
    windowSizeClass: WindowSizeClass? = null,
    windowLayoutInfo: WindowLayoutInfo? = null,
): AdaptiveWindowInfo {
    val density = LocalDensity.current
    val fold = windowLayoutInfo?.separatingFold()
    return remember(windowSizeClass, windowLayoutInfo, density) {
        AdaptiveWindowInfo(
            widthSizeClass = windowSizeClass?.widthSizeClass ?: WindowWidthSizeClass.Compact,
            heightSizeClass = windowSizeClass?.heightSizeClass ?: WindowHeightSizeClass.Compact,
            foldPosture = when {
                fold == null -> FoldPosture.NONE
                fold.orientation == FoldingFeature.Orientation.VERTICAL -> FoldPosture.VERTICAL
                else -> FoldPosture.HORIZONTAL
            },
            foldGap = fold?.hingeThickness(density) ?: 0.dp,
            // androidx.window 给的是 android.graphics.Rect，这里转成 Compose 的 Rect，
            // 后面分栏时再用它把分栏线压到折叠线上。
            foldBounds = fold?.bounds?.let { rect ->
                Rect(
                    left = rect.left.toFloat(),
                    top = rect.top.toFloat(),
                    right = rect.right.toFloat(),
                    bottom = rect.bottom.toFloat(),
                )
            },
        )
    }
}

/**
 * 大屏正文宽度约束：占满可用宽度，但不超过 [ContentMaxWidth]。
 *
 * 配合 `Box(Modifier.fillMaxSize())` 里的 `Modifier.align(Alignment.TopCenter)`
 * 使用，即可得到官方规范的「居中 + 最大内容宽」版式。
 */
fun Modifier.contentMaxWidth(maxWidth: Dp = ContentMaxWidth): Modifier =
    widthIn(max = maxWidth).fillMaxWidth()
