package com.wakeup.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 「下一节课」的统一呈现结果。
 *
 * 课表里同一个"下一节课"要在**四个地方**显示，此前各写一套文案：
 * 手机今日页、Smartspacer 的「一览」Target、手表表盘的复杂功能、手表信息块（Tile）。
 * 文案一旦不同步，用户就会看到"桌面写 10:15、表盘写 10:20"这种矛盾。
 *
 * 所以把"取哪节课 + 拼哪几句话"集中到 [NextClassPresenter]，各端只挑自己需要的字段：
 *
 * | 字段 | 手机今日页/Smartspacer | 表盘短文本 | 表盘长文本 | 信息块 |
 * | --- | --- | --- | --- | --- |
 * | [description] | ✅ 副标题 | | | |
 * | [shortText] | | ✅ | | ✅ |
 * | [longText] | | | ✅ | |
 * | [title] | | | ✅ | |
 *
 * 只依赖领域模型与 `java.time`，不碰 Android，因此可以直接单测。
 */
data class NextClassPresentation(
    /** 本地是否已经有课表（false 表示从未同步过）。 */
    val hasTimetable: Boolean,
    /** 未来一周内是否有课。 */
    val hasClass: Boolean,
    /** 是否正在上这节课。 */
    val ongoing: Boolean,
    /** 相对日期标签：`今天` / `明天` / `后天` / `周三`。 */
    val dayLabel: String,
    /** 上课时间 `10:15`；无课时为空串。 */
    val clock: String,
    /** 课程名；无课时为空串。 */
    val courseName: String,
    /** 教室；无课时为空串。 */
    val room: String,
    /** 节次标签 `3-4`；无课时为空串。 */
    val periodLabel: String,
    /** 短文本：`10:15 高数`（课程名截断，给表盘窄槽位）。 */
    val shortText: String,
    /** 长文本标题：`今天`。 */
    val title: String,
    /** 长文本正文：`10:15 高等数学 · F-105`。 */
    val longText: String,
    /**
     * 一句话完整描述（无障碍朗读 / Smartspacer 副标题）：
     * `今天 第3-4节 10:15 · F-105`，正在上课时是 `正在上课 · 至 11:50 · F-105`。
     */
    val description: String,
) {
    val isEmpty: Boolean get() = !hasClass

    companion object {
        /** 从未同步过课表。 */
        val NO_TIMETABLE = NextClassPresentation(
            hasTimetable = false,
            hasClass = false,
            ongoing = false,
            dayLabel = "",
            clock = "",
            courseName = "",
            room = "",
            periodLabel = "",
            shortText = "未同步",
            title = "课表",
            longText = "未同步",
            description = "Wakeup 还没有同步课表",
        )

        /** 有课表，但未来一周没有课（假期 / 学期已结束 / 今天之后都没课）。 */
        val NO_CLASS = NextClassPresentation(
            hasTimetable = true,
            hasClass = false,
            ongoing = false,
            dayLabel = "",
            clock = "",
            courseName = "",
            room = "",
            periodLabel = "",
            shortText = "无课",
            title = "今天",
            longText = "无课",
            description = "近期没有课",
        )

        /** 表盘编辑器里的固定样例（不读本地数据，保证预览不是"未同步"）。 */
        val PREVIEW = NextClassPresentation(
            hasTimetable = true,
            hasClass = true,
            ongoing = false,
            dayLabel = "今天",
            clock = "10:15",
            courseName = "高等数学",
            room = "F-105",
            periodLabel = "3-4",
            shortText = "10:15 高数",
            title = "今天",
            longText = "10:15 高等数学 · F-105",
            description = "今天 第3-4节 10:15 · F-105",
        )
    }
}

/**
 * 「下一节课」文案的唯一实现。
 *
 * 见 [NextClassPresentation] 的说明：四个展示端都从这里取文案。
 */
object NextClassPresenter {

    /** 短文本里课程名最多保留几个字（表盘窄槽位大约放得下「10:15 + 6 字」）。 */
    const val SHORT_NAME_MAX_LENGTH: Int = 6

    /**
     * 取 [now] 之后最近的一节课并拼好各端文案。
     *
     * [now] 默认按学校时区（[Term.TERM_ZONE]）解释，避免手表时区不同导致"今天"算错；
     * 周次、假期、G/F 座提前作息都在 [nextOccurrence] 里处理。
     *
     * [timetable] 为 null 表示从未同步过（返回 [NextClassPresentation.NO_TIMETABLE]）。
     */
    fun present(
        timetable: Timetable?,
        now: LocalDateTime = LocalDateTime.now(Term.TERM_ZONE),
    ): NextClassPresentation {
        if (timetable == null) return NextClassPresentation.NO_TIMETABLE

        val today = now.toLocalDate()
        val time = now.toLocalTime()
        val occurrence = timetable.nextOccurrence(now) ?: return NextClassPresentation.NO_CLASS

        val clock = formatClock(occurrence.start)
        val room = occurrence.course.location.trim()
        val shortName = shortCourseName(occurrence.course.name)
        val longText = if (room.isEmpty()) {
            "$clock ${occurrence.course.name}"
        } else {
            "$clock ${occurrence.course.name} · $room"
        }

        return NextClassPresentation(
            hasTimetable = true,
            hasClass = true,
            ongoing = occurrence.isOngoing(time),
            dayLabel = occurrence.dayLabel(today),
            clock = clock,
            courseName = occurrence.course.name,
            room = room,
            periodLabel = occurrence.periodLabel,
            shortText = "$clock $shortName",
            title = occurrence.dayLabel(today),
            longText = longText,
            description = occurrence.subtitle(today, time),
        )
    }

    /**
     * 短文本里的课程名：超长截断。
     *
     * `高等数学`（4 字）原样保留，`机器学习与模式识别` 这类取前 6 字 + `…`。
     */
    fun shortCourseName(name: String, maxLength: Int = SHORT_NAME_MAX_LENGTH): String {
        val compact = name.trim()
        if (compact.length <= maxLength) return compact
        return compact.take(maxLength) + "…"
    }

    /**
     * 信息块（Tile）里"当天第 N 门课"的简写：`10:15 高数`；节次没有对应时间时返回 null。
     *
     * 与 [shortText] 同源（同样的截断规则），只是取的是列表里的任意一门课。
     */
    fun briefLine(course: Course, timetable: Timetable): String? {
        val start = course.effectiveStartAt(timetable) ?: return null
        return "${formatClock(start)} ${shortCourseName(course.name)}"
    }

    /** 信息块里的「下一节」一行：`下一节 10:15 高等数学 · F-105`；无课时返回 null。 */
    fun nextClassLine(
        timetable: Timetable?,
        now: LocalDateTime = LocalDateTime.now(Term.TERM_ZONE),
    ): String? {
        val presentation = present(timetable, now)
        if (!presentation.hasClass) return null
        return "下一节 ${presentation.longText}"
    }

    private fun formatClock(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /** 供 UI 直接格式化任意时间（避免各处再写一遍 `%02d:%02d`）。 */
    fun clockOf(time: LocalTime): String = formatClock(time)

    /** 供 UI 格式化日期（`10月28日`）。 */
    fun dateLabel(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"
}
