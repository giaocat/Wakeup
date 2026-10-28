package com.wakeup.data.remote

import com.wakeup.data.model.TimetableResponse

/** 课表同步结果。 */
sealed interface SyncResult {
    /** [rawPayload] 是从页面里抽取出来的原始 JSON，直接用于本地缓存。 */
    data class Success(
        val response: TimetableResponse,
        val rawPayload: String,
    ) : SyncResult

    data class Failure(val message: String, val cause: Throwable? = null) : SyncResult
}

/**
 * 负责"拼地址 → 拉取页面 → 抽取 JSON → 反序列化"这一条链路。
 */
class TimetableRemoteDataSource(
    private val api: WakeupApi = WakeupApiFactory.create(),
) {

    /**
     * 把用户填写的课表来源解析成可请求的地址。
     *
     * - `https://.../user/kb/table/token/<token>` → 原样使用（支持任意学校的域名）
     * - `<token>` → 用 [baseUrl] 补全成默认服务地址下的 token 链接
     */
    fun urlFor(source: String, baseUrl: String = WakeupApiFactory.DEFAULT_BASE_URL): String {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        // 只给了 token（或 token/）时补全默认路径
        val token = trimmed.trim('/').substringAfterLast('/')
        return "${baseUrl.ensureTrailingSlash()}user/kb/table/token/$token"
    }

    suspend fun fetch(source: String, baseUrl: String = WakeupApiFactory.DEFAULT_BASE_URL): SyncResult {
        val url = urlFor(source, baseUrl)
        if (url.isEmpty()) {
            return SyncResult.Failure("还没有设置课表链接，请到「设置」里粘贴你的课表链接或 token")
        }
        return try {
            val html = api.timetableHtml(url)
            val payload = TimetablePayloadParser.extractPayload(html)
                ?: throw IllegalArgumentException("课表页面里没有找到课表数据，请确认链接是否有效或未过期")
            val response = TimetablePayloadParser.parsePayload(payload)
            when {
                response.code != 200 -> SyncResult.Failure(response.errmsg ?: "课表返回错误码 ${response.code}")
                response.table == null -> SyncResult.Failure("课表数据为空")
                else -> SyncResult.Success(response = response, rawPayload = payload)
            }
        } catch (error: Exception) {
            SyncResult.Failure(error.message ?: "网络请求失败", error)
        }
    }
}
