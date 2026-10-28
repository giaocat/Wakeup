package com.wakeup.data.remote

import com.wakeup.data.model.TimetableResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 课表页面并没有独立的 JSON 接口：整份数据直接内联在页面脚本里。
 *
 * 页面结构形如：
 * ```
 * <script>
 *   var vm = new Vue({ ... created: function () { this.init({"code":200,...}); } ... });
 * </script>
 * ```
 *
 * 因此这里做两件事：
 * 1. [extractPayload] 从 HTML 中截取 `this.init(...)` 的 JSON 实参；
 * 2. [parse] 用 kotlinx.serialization 反序列化成 [TimetableResponse]。
 */
object TimetablePayloadParser {

    private const val MARKER = "this.init("

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        coerceInputValues = true
    }

    /** 从课表页面 HTML 中取出内联的 JSON 文本；找不到时返回 null。 */
    fun extractPayload(html: String): String? {
        val markerIndex = html.indexOf(MARKER)
        if (markerIndex < 0) return null

        val openIndex = html.indexOf('{', markerIndex + MARKER.length)
        if (openIndex < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (index in openIndex until html.length) {
            val char = html[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(openIndex, index + 1)
                }
            }
        }
        return null
    }

    /** 解析内联 JSON。格式异常时抛出 [IllegalArgumentException]。 */
    fun parse(html: String): TimetableResponse {
        val payload = extractPayload(html)
            ?: throw IllegalArgumentException("课表页面里没有找到 this.init(...) 数据，请确认链接是否有效或未过期")
        return parsePayload(payload)
    }

    /** 直接解析 JSON 文本（离线示例数据走这条路径）。 */
    fun parsePayload(payload: String): TimetableResponse =
        try {
            json.decodeFromString(TimetableResponse.serializer(), payload)
        } catch (error: Exception) {
            throw IllegalArgumentException("课表数据解析失败：${error.message}", error)
        }

    /** 宽松解析：即使外层多了包装也能拿到内容，主要用于调试。 */
    fun parseObject(payload: String): JsonObject =
        json.decodeFromString(JsonObject.serializer(), payload)
}
