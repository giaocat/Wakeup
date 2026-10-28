package com.wakeup.wear.tile

import android.content.Context
import androidx.annotation.OptIn
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.expression.ProtoLayoutExperimental
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.wakeup.appContainer
import com.wakeup.domain.Course
import com.wakeup.domain.Timetable
import com.wakeup.domain.nextOccurrence
import com.wakeup.util.TimeUtils
import com.wakeup.wear.MainActivity
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 信息块（Tile）：在手表上从表盘往左滑就能看到的「今天课表 + 下一节课」。
 *
 * 和表盘复杂功能（[com.wakeup.wear.complication.NextClassComplicationService]）同源：
 * 都只读 `:core` 的内存/磁盘缓存（[com.wakeup.data.repository.TimetableRepository.timetableForDisplay]），
 * **不联网**。信息块由系统按 [setFreshnessIntervalMillis][TileBuilders.Tile.Builder.setFreshnessIntervalMillis]
 * 周期性重新请求，每次请求都同步拼一份新的内容。
 *
 * 布局只用 protolayout 的基础组件（`Box` / `Column` / `Text`），不依赖 Compose，
 * 也不使用任何图片资源，所以不需要 `onResourcesRequest`。
 */
@OptIn(ProtoLayoutExperimental::class)
class TimetableTileService : TileService() {

    /**
     * 系统来要 Tile。
     *
     * 注意：这里的返回类型是 Guava 的 `ListenableFuture`（tiles 1.6.2 的 Java API）。
     * 我们的内容完全来自本地缓存、同步就能拼完，所以直接返回一个已完成的 future，
     * 不引入协程或额外依赖，见 [CompletedListenableFuture]。
     */
    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = CompletedListenableFuture(buildTile(applicationContext))

    /** 拼一份完整的 Tile。 */
    private fun buildTile(context: Context): TileBuilders.Tile {
        val timeline = TimelineBuilders.Timeline.fromLayoutElement(buildLayout(context))
        return TileBuilders.Tile.Builder()
            // 没有图片资源，但每次请求都换一个资源版本号：内容变了系统才会重新渲染
            .setResourcesVersion(System.currentTimeMillis().toString())
            // 15 分钟刷新一次：足够跨到"下一节课"，也不至于频繁唤醒
            .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MS)
            .setTileTimeline(timeline)
            .build()
    }

    /** 圆角卡片 = 背景 + 内边距 +（可选）点击打开应用，内容是一列文字。 */
    private fun buildLayout(context: Context): LayoutElementBuilders.LayoutElement {
        val today = TimeUtils.today()
        val dayOfWeek = today.dayOfWeek.value

        // 读缓存可能抛（缓存文件损坏等）：失败就当没有课表，信息块不能因此崩掉
        val timetable = runCatching {
            context.appContainer.timetableRepository.timetableForDisplay()
        }.getOrNull()

        val rows = mutableListOf<LayoutElementBuilders.LayoutElement>()
        if (timetable == null) {
            // 从来没同步过：把"怎么办"也写上，否则用户只能看到一个空信息块
            rows += text("Wakeup 课表", TITLE_SP, LayoutElementBuilders.FONT_WEIGHT_MEDIUM, COLOR_SECONDARY)
            rows += spacer(4f)
            rows += text("还没同步课表", NEXT_CLASS_SP, LayoutElementBuilders.FONT_WEIGHT_BOLD, COLOR_PRIMARY)
            rows += spacer(2f)
            rows += text(
                "打开 Wakeup 或让手机同步一次",
                BODY_SP,
                LayoutElementBuilders.FONT_WEIGHT_NORMAL,
                COLOR_SECONDARY,
            )
        } else {
            rows += text(
                "今天 · ${weekdayLabel(dayOfWeek)}",
                TITLE_SP,
                LayoutElementBuilders.FONT_WEIGHT_MEDIUM,
                COLOR_SECONDARY,
            )
            rows += spacer(4f)
            rows += text(
                nextClassLine(timetable, today),
                NEXT_CLASS_SP,
                LayoutElementBuilders.FONT_WEIGHT_BOLD,
                COLOR_PRIMARY,
            )

            // 学期起止之外的日期（开学前/放假后）weekOf 返回 null，此时没有"今天的课"
            val week = timetable.term.weekOf(today)
            val courseLines = if (week == null) {
                emptyList()
            } else {
                timetable.slotsOfDay(week, dayOfWeek)
                    .mapNotNull { courseLine(timetable, it) }
                    .take(MAX_COURSE_LINES)
            }
            if (courseLines.isNotEmpty()) {
                rows += spacer(4f)
                courseLines.forEach { line ->
                    rows += text(line, BODY_SP, LayoutElementBuilders.FONT_WEIGHT_NORMAL, COLOR_BODY)
                }
            }
        }

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
        rows.forEach { column.addContent(it) }

        val modifiers = ModifiersBuilders.Modifiers.Builder()
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(ColorBuilders.argb(COLOR_BACKGROUND))
                    .setCorner(
                        ModifiersBuilders.Corner.Builder()
                            .setRadius(DimensionBuilders.dp(CORNER_RADIUS_DP))
                            .build(),
                    )
                    .build(),
            )
            .setPadding(
                ModifiersBuilders.Padding.Builder()
                    .setStart(DimensionBuilders.dp(PADDING_HORIZONTAL_DP))
                    .setEnd(DimensionBuilders.dp(PADDING_HORIZONTAL_DP))
                    .setTop(DimensionBuilders.dp(PADDING_VERTICAL_DP))
                    .setBottom(DimensionBuilders.dp(PADDING_VERTICAL_DP))
                    .build(),
            )
        // 点信息块打开手表端应用（可选功能：构建失败就不带点击，不影响内容）
        openAppClickable(context)?.let { modifiers.setClickable(it) }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(modifiers.build())
            .addContent(column.build())
            .build()
    }

    /** 下一节课一行：`下一节 10:15 高等数学 · F-105`；最近一周都没课时 `今天没有课`。 */
    private fun nextClassLine(timetable: Timetable, today: LocalDate): String {
        val occurrence = runCatching { timetable.nextOccurrence() }.getOrNull() ?: return "今天没有课"
        val clock = formatClock(occurrence.start)
        val room = occurrence.course.location.trim()
        // 不是今天的课就带上"明天/周三"：标题写着"今天"，光一个时间点会让人误读
        val dayPrefix = if (occurrence.date == today) "" else occurrence.dayLabel(today) + " "
        return if (room.isEmpty()) {
            "下一节 $dayPrefix$clock ${occurrence.name}"
        } else {
            "下一节 $dayPrefix$clock ${occurrence.name} · $room"
        }
    }

    /** 今天的一行课：`10:15 高数`；节次没有对应时间（异常数据）时返回 null 跳过。 */
    private fun courseLine(timetable: Timetable, course: Course): String? {
        val start = course.effectiveStartAt(timetable) ?: return null
        return "${formatClock(start)} ${compactCourseName(course.name)}"
    }

    /** 信息块上的一行文字：字号 + 字重 + 颜色。 */
    private fun text(
        value: String,
        sizeSp: Float,
        weight: Int,
        color: Int,
    ): LayoutElementBuilders.Text = LayoutElementBuilders.Text.Builder()
        .setText(value)
        .setFontStyle(
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sizeSp))
                .setWeight(weight)
                .setColor(ColorBuilders.argb(color))
                .build(),
        )
        .build()

    private fun spacer(heightDp: Float): LayoutElementBuilders.Spacer =
        LayoutElementBuilders.Spacer.Builder()
            .setHeight(DimensionBuilders.dp(heightDp))
            .build()

    /** 点击整个信息块打开手表端应用；任何一步失败都返回 null（内容本身不受影响）。 */
    private fun openAppClickable(context: Context): ModifiersBuilders.Clickable? = runCatching {
        val launchAction = ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(context.packageName)
                    .setClassName(MainActivity::class.java.name)
                    .build(),
            )
            .build()
        ModifiersBuilders.Clickable.Builder()
            .setOnClick(launchAction)
            .build()
    }.getOrNull()
}

/**
 * 一个**已经完成**的 [ListenableFuture]。
 *
 * tiles 1.6.2 的 [TileService.onTileRequest] 返回 Guava 的 `ListenableFuture`，
 * 但 `androidx.concurrent:concurrent-futures-ktx`（提供 `future {}`）只声明在 tiles 的
 * runtime 配置里，编译期拿不到；而本模块不允许新增依赖。我们的 Tile 是同步拼出来的，
 * 直接给系统一个已完成的结果即可 —— 系统只会对它 `addListener` 再 `get()`。
 */
private class CompletedListenableFuture<T>(private val value: T) : ListenableFuture<T> {

    override fun addListener(listener: Runnable, executor: Executor) {
        executor.execute(listener)
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

    override fun isCancelled(): Boolean = false

    override fun isDone(): Boolean = true

    override fun get(): T = value

    override fun get(timeout: Long, unit: TimeUnit): T = value
}

/** `10:15`。 */
private fun formatClock(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

/** `周三`。 */
private fun weekdayLabel(dayOfWeek: Int): String =
    WEEKDAY_LABELS.getOrElse(dayOfWeek - 1) { "周?" }

/** 信息块一行放不下长课程名：`高等数学` 原样保留，太长的取前 8 字 + `…`。 */
private fun compactCourseName(name: String, maxLength: Int = COURSE_NAME_MAX_LENGTH): String {
    val compact = name.trim()
    return if (compact.length <= maxLength) compact else compact.take(maxLength) + "…"
}

private val WEEKDAY_LABELS: List<String> = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 系统重新拉取 Tile 的周期：15 分钟。 */
private const val FRESHNESS_INTERVAL_MS: Long = 15 * 60 * 1000L

/** 信息块上最多列今天的前 3 门课，再多就挤了。 */
private const val MAX_COURSE_LINES: Int = 3

private const val TITLE_SP: Float = 12f
private const val NEXT_CLASS_SP: Float = 15f
private const val BODY_SP: Float = 13f

private const val CORNER_RADIUS_DP: Float = 20f
private const val PADDING_HORIZONTAL_DP: Float = 12f
private const val PADDING_VERTICAL_DP: Float = 10f

private const val COURSE_NAME_MAX_LENGTH: Int = 8

/** 卡片背景：接近纯黑，OLED 手表上省电也耐看。 */
private val COLOR_BACKGROUND: Int = 0xFF141414.toInt()

/** 主要文字：白色。 */
private val COLOR_PRIMARY: Int = 0xFFFFFFFF.toInt()

/** 次要文字（标题/提示）：70% 白。 */
private val COLOR_SECONDARY: Int = 0xB3FFFFFF.toInt()

/** 课表行：80% 白。 */
private val COLOR_BODY: Int = 0xCCFFFFFF.toInt()
