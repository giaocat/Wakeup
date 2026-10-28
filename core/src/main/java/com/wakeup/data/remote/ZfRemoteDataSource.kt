package com.wakeup.data.remote

import com.wakeup.data.model.TimetableResponse
import com.wakeup.domain.Term
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 正方教务系统（`jwxt.sut.edu.cn`）的课表抓取。
 *
 * 与旧分享链接的区别：**必须带登录会话**（Cookie），否则只会拿回登录页。
 * 所以这里只负责"带上 Cookie 抓页面 + 交给解析器"，登录本身交给应用内的 WebView。
 */
class ZfRemoteDataSource(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {

    /** 拼出课表页地址：`https://jwxt.sut.edu.cn` + `/jsxsd/xskb/xskb_list.do`。 */
    fun timetableUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        val normalizedPath = path.trim().let { if (it.startsWith("/")) it else "/$it" }
        return base + normalizedPath.ifEmpty { DEFAULT_PATH }
    }

    /**
     * 抓取并解析课表。
     *
     * @param termStartEpochSeconds 学期第一周周一的时间戳（秒），由设置里的开学日期决定；
     *   为 null 时解析器会用页面里的"当前周"反推。
     */
    suspend fun fetch(
        baseUrl: String,
        path: String,
        cookie: String,
        termStartEpochSeconds: Long? = null,
    ): SyncResult = withContext(Dispatchers.IO) {
        val url = timetableUrl(baseUrl, path)
        if (url.isEmpty()) {
            return@withContext SyncResult.Failure("还没有设置教务系统地址，请到「设置」里检查")
        }
        if (cookie.isBlank()) {
            return@withContext SyncResult.Failure(
                message = "还没有登录教务系统",
                needsLogin = true,
            )
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                // 教务系统常把"未登录"做成 302 回登录页，跟着跳一次才能判断到底是哪一步出了问题
                .header("Referer", baseUrl.trimEnd('/') + "/jsxsd/framework/xsMain.jsp")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                )
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (ZfTimetableParser.isLoginPage(body)) {
                    return@withContext SyncResult.Failure(
                        message = "教务系统登录已过期，请重新登录",
                        needsLogin = true,
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext SyncResult.Failure(
                        "教务系统返回 HTTP ${response.code}，请稍后重试或检查课表页地址",
                    )
                }
                if (body.isBlank()) {
                    return@withContext SyncResult.Failure("教务系统返回了空页面，请检查课表页地址")
                }

                val parsed = ZfTimetableParser.parse(body, termStartEpochSeconds)
                // 缓存里存的是解析后的 DTO JSON（不是原始 HTML）：
                // 这样缓存能继续走原来"重新解析 → 映射领域模型"的一条路径。
                val payload = json.encodeToString(TimetableResponse.serializer(), parsed)
                SyncResult.Success(response = parsed, rawPayload = payload)
            }
        } catch (error: ZfTimetableParser.LoginRequiredException) {
            SyncResult.Failure("教务系统登录已过期，请重新登录", error, needsLogin = true)
        } catch (error: ZfTimetableParser.UnsupportedPageException) {
            SyncResult.Failure(error.message ?: "课表页面无法识别", error)
        } catch (error: Exception) {
            SyncResult.Failure(error.message ?: "网络请求失败", error)
        }
    }

    /**
     * 按周抓整学期课表（个人中心的课表是"一周一渲染"的）。
     *
     * 抓取链路（与页面自己的 JS 一致）：
     * 1. GET `xsMain.jsp`，读当前周 / 总周数 / 时间模式（`#sjms` 的值）；
     * 2. 对第 1..总周数 周，POST `main_index_loadkb.jsp`（表单 `rq=那一周的日期&sjmsValue=…`）
     *    拿到那一周的表格片段；
     * 3. 交给 [ZfTimetableParser.mergeWeeks] 合并：同一门课同一时段出现过的周次即它的真实周次。
     *
     * 这样即使页面只给"当前周"，也能得到**准确的单双周/分段周次**，不必依赖其它页面。
     */
    suspend fun fetchWeekly(
        baseUrl: String,
        cookie: String,
        termStartEpochSeconds: Long? = null,
        maxWeeks: Int = 25,
    ): SyncResult = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_BASE_URL }
        if (cookie.isBlank()) {
            return@withContext SyncResult.Failure("还没有登录教务系统", needsLogin = true)
        }
        try {
            // 先打开登录入口建立会话上下文：直接 GET 框架页时系统会返回
            // `{"flag":2,"msgContent":"跳转链接地址异常"}`，不是真正的课表
            val entryHtml = get("$base$ENTRY_PATH", cookie)
            // xsMain.jsp 只是外壳（iframe 框架），周次/表格在 xsMain_new.jsp 里
            val frameHtml = get("$base$MAIN_PATH", cookie, referer = "$base$ENTRY_PATH")
            val contentHtml = get("$base$CONTENT_PATH", cookie, referer = "$base$MAIN_PATH")
            val page = when {
                contentHtml.contains("kb_table") || contentHtml.contains("li_showWeek") -> contentHtml
                frameHtml.contains("kb_table") || frameHtml.contains("li_showWeek") -> frameHtml
                else -> contentHtml
            }
            Log.i(
                TAG,
                "cookie=${cookie.length}B entry=${entryHtml.length}B frame=${frameHtml.length}B " +
                    "content=${contentHtml.length}B 使用=${page.length}B",
            )
            if (page.contains(JUMP_ERROR_MARKER) || page.trimStart().startsWith("{\"flag\"")) {
                Log.w(TAG, "教务系统拒绝直接打开课表页（跳转链接地址异常）")
                return@withContext SyncResult.Failure(
                    "教务系统拒绝直接打开课表页（跳转链接地址异常），请重新登录后再试",
                    needsLogin = true,
                )
            }
            if (ZfTimetableParser.isLoginPage(page)) {
                Log.w(TAG, "课表页返回登录页，会话无效")
                return@withContext SyncResult.Failure("教务系统登录已过期，请重新登录", needsLogin = true)
            }
            val plain = ZfTimetableParser.stripTags(page)
            val currentWeek = ZfTimetableParser.detectCurrentWeek(plain)
            val totalWeeks = (ZfTimetableParser.detectTotalWeeks(plain) ?: 20).coerceIn(1, maxWeeks)
            val sjms = SJMS_PATTERN.find(page)
                ?.groupValues?.get(1)
                ?.let { block -> SJMS_VALUE_PATTERN.find(block)?.groupValues?.get(1) }
                .orEmpty()
            Log.i(
                TAG,
                "当前周=$currentWeek 总周数=$totalWeeks 时间模式=${if (sjms.isEmpty()) "未找到" else sjms.take(8) + "…（${sjms.length}B）"}",
            )

            val today = LocalDate.now(Term.TERM_ZONE)
            val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            val firstMondayEpoch = termStartEpochSeconds
                ?: currentWeek?.let { week ->
                    monday.minusDays((week - 1) * 7L).atStartOfDay(Term.TERM_ZONE).toEpochSecond()
                }
                ?: return@withContext SyncResult.Failure(
                    "页面里没有当前周信息；请在「设置 → 开学第一周周一」里填写开学日期",
                )

            val weeks = mutableListOf<Pair<Int, String>>()
            val sizes = mutableListOf<Int>()
            // 与页面自己的行为一致：以"今天"为基准前后平移整周，
            // 而不是用"本周周一"，避免服务器的周起点与我们的推算差一天
            val todayDate = LocalDate.now(Term.TERM_ZONE)
            val baseWeek = currentWeek ?: 1
            for (week in 1..totalWeeks) {
                val date = todayDate.plusDays((week - baseWeek) * 7L)
                val body = post(
                    url = "$base$LOAD_KB_PATH",
                    cookie = cookie,
                    form = mapOf("rq" to date.toString(), "sjmsValue" to sjms),
                )
                sizes += body.length
                if (ZfTimetableParser.isLoginPage(body)) {
                    return@withContext SyncResult.Failure("教务系统登录已过期，请重新登录", needsLogin = true)
                }
                if (body.isNotBlank() && body.contains("<table", ignoreCase = true)) {
                    // 以服务器片段里写的「第N周」为准来编号，避免日期推算与教务处口径差几天
                    val actualWeek = ZfTimetableParser.weekOfFragment(body) ?: week
                    if (actualWeek != week) {
                        Log.i(TAG, "日期 $date 推算第${week}周，服务器认定第${actualWeek}周")
                    }
                    if (weeks.none { it.first == actualWeek }) {
                        weeks += actualWeek to body
                        // 每周都打一行摘要：排查"某门课某几周缺失"时能直接对照教务系统
                        Log.i(
                            TAG,
                            "第${actualWeek}周 $date → " +
                                ZfTimetableParser.weekSummary(body, actualWeek, totalWeeks).joinToString(", "),
                        )
                    }
                }
            }
            if (weeks.isEmpty()) {
                Log.w(TAG, "一周课表都没抓到（总周数=$totalWeeks，当前周=$currentWeek，sjms=${sjms.length}B，" +
                    "换周响应长度=${sizes.take(5)}）")
                return@withContext SyncResult.Failure("没有抓到任何一周的课表，请检查教务系统地址或稍后重试")
            }

            val merged = ZfTimetableParser.mergeWeeks(
                weekHtml = weeks,
                termStartEpochSeconds = firstMondayEpoch,
                totalWeeks = totalWeeks,
            )
            Log.i(
                TAG,
                "抓到 ${weeks.size}/$totalWeeks 周，合并出 ${merged.table?.list?.size ?: 0} 门课" +
                    "（当前周=$currentWeek）",
            )
            // 结构化日志：排查"课表位置不对"时能直接看到每门课落在星期几第几节、覆盖哪些周
            runCatching {
                val detail = merged.table?.list?.joinToString(" | ") { entry ->
                    entry.courseName + "[" + entry.info.joinToString(",") { slot ->
                        val day = slot.periodDetail.firstOrNull()?.firstOrNull() ?: '?'
                        val start = slot.periodDetail.firstOrNull()?.drop(1) ?: "?"
                        val end = slot.periodDetail.lastOrNull()?.drop(1) ?: "?"
                        "周$day ${start}-${end} {${
                            com.wakeup.domain.WeekExpression.format(
                                slot.weekDetail.mapNotNull { it.toIntOrNull() }.toSet(),
                            )
                        }}"
                    } + "]"
                }
                Log.i(TAG, "课表=${detail?.take(1500)}")
            }
            val payload = json.encodeToString(TimetableResponse.serializer(), merged)
            SyncResult.Success(response = merged, rawPayload = payload)
        } catch (error: Exception) {
            Log.w(TAG, "抓取教务系统课表失败", error)
            SyncResult.Failure(error.message ?: "网络请求失败", error)
        }
    }

    /** 带 Cookie 的 GET，返回响应体。 */
    private fun get(url: String, cookie: String, referer: String = url): String {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }
    }

    /** 带 Cookie 的表单 POST，返回响应体（教务系统的换周接口用 POST）。 */
    private fun post(url: String, cookie: String, form: Map<String, String>, referer: String = url): String {
        val bodyBuilder = FormBody.Builder()
        form.forEach { (key, value) -> bodyBuilder.add(key, value) }
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .post(bodyBuilder.build())
            .build()
        return client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val TAG = "ZfRemote"
        /**
         * 兜底用的单页抓取地址：**学期理论课表**（`/jsxsd/xskb/xskb_list.do`）。
         *
         * 正常情况下走 [fetchWeekly] 的"按周遍历"，不需要这个地址；
         * 各校模板不同/按周遍历失败时才退回单页解析，所以保留在设置里可改。
         */
        const val DEFAULT_PATH: String = "/jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL"

        /** 教务系统默认地址。 */
        const val DEFAULT_BASE_URL: String = "https://jwxt.sut.edu.cn"

        /** 登录入口：先在它上面建立会话上下文，否则直接开框架页会报「跳转链接地址异常」。 */
        const val ENTRY_PATH: String = "/jsxsd/"

        /** 个人中心（框架页），只是外壳；周次/表格在 [CONTENT_PATH] 里。 */
        const val MAIN_PATH: String = "/jsxsd/framework/xsMain.jsp"

        /** 个人中心真正的课表页（iframe 内容）：有 `第N周/N周`、时间模式下拉和 `kb_table`。 */
        const val CONTENT_PATH: String = "/jsxsd/framework/xsMain_new.jsp"

        /** 换周接口：表单 `rq=那一周的日期&sjmsValue=时间模式`，返回课表表格片段。 */
        const val LOAD_KB_PATH: String = "/jsxsd/framework/main_index_loadkb.jsp"

        /** 页面对"脱离入口直接开框架页"的报错标记。 */
        const val JUMP_ERROR_MARKER: String = "跳转链接地址异常"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private val SJMS_PATTERN = Regex(
            "<select[^>]*id=[\"']sjms[\"'][^>]*>(.*?)</select>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val SJMS_VALUE_PATTERN = Regex("value=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }
}
