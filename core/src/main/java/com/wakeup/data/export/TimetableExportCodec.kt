package com.wakeup.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 课表导出文件的信封。
 *
 * 导出的就是**抓取到的原始课表数据**（`payload`，与本地缓存同源），
 * 这样导入时走的是和同步完全一样的"重新解析 → 写缓存"链路，不需要另外维护一套序列化。
 *
 * [format] / [version] 用来识别"这是不是本 App 导出的文件"以及未来的格式升级。
 */
@Serializable
data class TimetableExport(
    val format: String = "",
    val version: Int = TimetableExportCodec.VERSION,
    /** 导出时间（毫秒）。 */
    val exportedAt: Long = 0L,
    /** 导出时的课表来源（用于 G/F 座作息等按学校生效的规则）。 */
    val sourceUrl: String = "",
    /** 原始课表数据（JSON 字符串）。 */
    val payload: String = "",
)

/** 导入结果：成功返回课程数量，失败返回原因。 */
sealed interface ImportResult {
    data class Success(val courseCount: Int) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * 课表导入 / 导出的编解码。
 *
 * 只做"包装与校验"，不碰 Android 的文件系统：写文件 / 选文件由界面层用 SAF 完成，
 * 因此这里可以纯 JVM 单测。
 */
object TimetableExportCodec {

    /** 文件里的格式标识。 */
    const val FORMAT: String = "wakeup-timetable"

    /** 当前格式版本；导入时拒绝比自己更高的版本。 */
    const val VERSION: Int = 1

    /** 导出文件的默认文件名（按日期）。 */
    fun defaultFileName(date: String): String = "wakeup-课表-$date.json"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

    /** 把课表 payload 包成可导出的 JSON 文本。 */
    fun encode(
        payload: String,
        sourceUrl: String,
        exportedAt: Long = System.currentTimeMillis(),
    ): String = json.encodeToString(
        TimetableExport.serializer(),
        TimetableExport(
            format = FORMAT,
            exportedAt = exportedAt,
            sourceUrl = sourceUrl,
            payload = payload,
        ),
    )

    /**
     * 解析导入文件。
     *
     * 失败时给出**用户能看懂**的原因（不是 JSON 异常堆栈），因为这是用户从文件管理器里选的文件，
     * 很可能选错成别的 JSON 或旧版本文件。
     */
    fun decode(text: String): Result<TimetableExport> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(ImportException("文件是空的"))
        val parsed = try {
            json.decodeFromString(TimetableExport.serializer(), trimmed)
        } catch (_: Exception) {
            return Result.failure(ImportException("不是 Wakeup 导出的课表文件（JSON 格式不对）"))
        }
        if (parsed.format != FORMAT) {
            return Result.failure(ImportException("不是 Wakeup 导出的课表文件（缺少 $FORMAT 标识）"))
        }
        if (parsed.version > VERSION) {
            return Result.failure(ImportException("文件来自更新版本的 Wakeup（格式 v${parsed.version}），请先升级 App"))
        }
        if (parsed.payload.isBlank()) {
            return Result.failure(ImportException("文件里没有课表数据"))
        }
        return Result.success(parsed)
    }

    /** 导入失败的内部异常（消息直接展示给用户）。 */
    class ImportException(message: String) : Exception(message)
}
