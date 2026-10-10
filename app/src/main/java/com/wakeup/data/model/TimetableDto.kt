package com.wakeup.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 沈工大教务通（sutjwt）课表接口返回结构的映射。
 *
 * 页面把整份数据直接内联在 `this.init({...})` 里，因此这里描述的是那段 JSON 的结构。
 */
@Serializable
data class TimetableResponse(
    val code: Int = 0,
    val errmsg: String? = null,
    val user: String? = null,
    val table: TimetableTable? = null,
)

@Serializable
data class TimetableTable(
    val code: Int = 0,
    val errmsg: String? = null,
    val term: TermInfo? = null,
    val list: List<CourseEntry> = emptyList(),
)

@Serializable
data class TermInfo(
    /** 学期总周数，形如 "20" */
    val total: String? = null,
    /** 学期起始时间戳（秒） */
    val starttime: Long = 0L,
    /** 学期标识，形如 "2026-2027-1" */
    val term: String? = null,
)

/** 一门课程（可能包含多个上课时段）。 */
@Serializable
data class CourseEntry(
    @SerialName("JSXM") val teacher: String? = null,
    @SerialName("KCMC") val courseName: String = "",
    @SerialName("JX0404ID") val courseId: String = "",
    @SerialName("INFO") val info: List<CourseSlot> = emptyList(),
) {
    val isScheduled: Boolean get() = info.isNotEmpty()
}

/** 课程的一个上课时段。 */
@Serializable
data class CourseSlot(
    /** 节次明细，如 ["301", "302"]，每位首字符为星期。 */
    @SerialName("KCSJMX") val periodDetail: List<String> = emptyList(),
    /** 上课周次明细，如 ["7", "8", "9"]。 */
    @SerialName("KKZCMX") val weekDetail: List<String> = emptyList(),
    /** 上课地点，可能为 null（如体育课未指定教室）。 */
    @SerialName("JX0601MC") val room: String? = null,
    /** 周次表达式，如 "4-5,7-17"。 */
    @SerialName("KKZC") val weekExpression: String = "",
    /** 节次编码，如 "30102"：首字符星期，其后每两位一个节次。 */
    @SerialName("KCSJ") val periodCode: String = "",
)
