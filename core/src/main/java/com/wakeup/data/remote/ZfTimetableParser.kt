package com.wakeup.data.remote

import com.wakeup.data.model.CourseEntry
import com.wakeup.data.model.CourseSlot
import com.wakeup.data.model.TermInfo
import com.wakeup.data.model.TimetableResponse
import com.wakeup.data.model.TimetableTable
import com.wakeup.domain.Term
import com.wakeup.domain.WeekExpression
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 正方教务系统（`jwxt.sut.edu.cn/jsxsd/...`）课表页面的解析器。
 *
 * 与旧的"分享页内联 JSON"不同，正方返回的是**服务端渲染的 HTML 表格**，
 * 而且各校（甚至同校不同版本）的模板细节差别不小，所以这里写成**宽松解析**：
 *
 * 1. 先找课表网格表格（`id="kbtable"`，退而求其次找含「节次/星期一」表头的表格）：
 *    行 = 节次、列 = 星期，单元格文本形如
 *    `高等数学` / `1-16周` / `张三` / `一教A101`（用 `<br>` 分隔，顺序各校可能不同）；
 * 2. 网格找不到时退回"课程列表"表格（`id="kblist"`，每行一门课，时间列形如
 *    `星期一第1-2节{第1-16周}`）。
 *
 * 输出统一转成旧的 [TimetableResponse] 结构，这样缓存、领域映射（含 G/F 座作息规则）
 * 全部沿用原有代码，只多一个"页面解析"入口。
 */
object ZfTimetableParser {

    /** 抓到的是登录页：会话失效，需要用户重新登录。 */
    class LoginRequiredException : Exception("登录状态已失效，请重新登录教务系统")

    /** 抓到了页面但认不出课表。 */
    class UnsupportedPageException(message: String) : Exception(message)

    private const val MAX_PERIOD = 12
    private val MAX_PERIODS_PER_ROW = 12

    /**
     * 解析课表页面。
     *
     * @param termStartEpochSeconds 学期"第一周周一"的时间戳（秒）。正方页面通常只给周次、
     *   不给开学日期，所以优先用用户在设置里填的开学日期；没填时用页面里的"当前周"反推。
     */
    fun parse(html: String, termStartEpochSeconds: Long? = null): TimetableResponse {
        if (isLoginPage(html)) throw LoginRequiredException()

        // 周次信息（`第10周 / 20周`）夹在标签之间，先转纯文本再匹配
        val plain = stripTags(html)
        val currentWeek = detectCurrentWeek(plain)
        val detectedTotal = detectTotalWeeks(plain)
        val totalGuess = detectedTotal ?: maxOf(currentWeek ?: 0, 20)

        val table = findGridTable(html)
        val slots = if (table != null) {
            parseGridTable(table, currentWeek, totalGuess)
        } else {
            val listTable = findListTable(html)
                ?: throw UnsupportedPageException(
                    "没有在页面里找到课表表格（既没有 kbtable，也没有 kblist）。" +
                        "请确认打开的确实是「我的课表」页面，并把页面地址填对。",
                )
            parseListTable(listTable)
        }
        if (slots.isEmpty()) {
            throw UnsupportedPageException("课表页面解析出来是空的，可能是学期未选对或页面结构有变化")
        }

        val maxWeek = slots.flatMap { it.weekDetail.mapNotNull { week -> week.toIntOrNull() } }.maxOrNull() ?: 20
        val totalWeeks = detectedTotal ?: maxOf(maxWeek, currentWeek ?: 0, 20)
        val startEpoch = termStartEpochSeconds
            ?: currentWeek?.let { week -> mondayOfCurrentWeek().minusWeeks(week - 1L).startEpoch() }
            ?: mondayOfCurrentWeek().startEpoch()

        val entries = slots
            .groupBy { it.courseId }
            .map { (courseId, courseSlots) ->
                val first = courseSlots.first()
                CourseEntry(
                    teacher = first.teacher,
                    courseName = first.courseName,
                    courseId = courseId,
                    info = courseSlots.map { it.slot },
                )
            }

        return TimetableResponse(
            code = 200,
            table = TimetableTable(
                code = 200,
                term = TermInfo(
                    total = totalWeeks.toString(),
                    starttime = startEpoch,
                    term = detectTermName(html),
                ),
                list = entries,
            ),
        )
    }

    /**
     * 某一周的课表摘要（调试用）：`周1 01-02 高等数学A（一）` 列表。
     *
     * 排查"课表位置/周次不对"时，能直接把每一周抓到的内容打出来跟网页对照。
     */
    internal fun weekSummary(html: String, week: Int, totalWeeks: Int): List<String> {
        val rows = findGridTable(html) ?: return emptyList()
        return parseGridTable(rows, currentWeek = week, totalWeeks = totalWeeks, weeksOverride = setOf(week))
            .map { slot ->
                val codes = slot.slot.periodDetail
                val day = codes.firstOrNull()?.firstOrNull() ?: '?'
                val start = codes.firstOrNull()?.drop(1) ?: "?"
                val end = codes.lastOrNull()?.drop(1) ?: "?"
                "周$day ${start}-${end} ${slot.courseName}"
            }
    }

    /**
     * 片段里"服务器认为这是第几周"。
     *
     * 个人中心的课表格子里写着 `上课时间：第10周 星期一 [01-02]节`，
     * 这个数字才是服务器认定的周次。按日期自己推算容易与教务处的周次口径差几天，
     * 所以合并时以它为准（取出现次数最多的那个）。
     */
    internal fun weekOfFragment(html: String): Int? {
        val counts = linkedMapOf<Int, Int>()
        WEEK_IN_PAGE.findAll(html).forEach { match ->
            val week = match.groupValues[1].toIntOrNull() ?: return@forEach
            if (week in 1..30) counts[week] = (counts[week] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    private val WEEK_IN_PAGE = Regex("第\\s*(\\d{1,2})\\s*周")

    // region 按周合并（个人中心是"一周一渲染"的）

    /**
     * 把按周抓回来的表格片段合并成整学期课表。
     *
     * 强智个人中心的课表是**按周**渲染的：换周就是带着 `rq=那一周的日期` 重新请求
     * `/jsxsd/framework/main_index_loadkb.jsp`，返回的 HTML 只包含那一周的课。
     * 所以整学期要一周一周取，再把同一门课同一时段出现过的周次合并成 `weeks`。
     *
     * @param weekHtml 周次 → 该周的表格片段 HTML（取不到的周可以不传）
     * @param termStartEpochSeconds 学期第一周周一（秒级时间戳），用于把周次落到日期
     */
    fun mergeWeeks(
        weekHtml: List<Pair<Int, String>>,
        termStartEpochSeconds: Long,
        totalWeeks: Int,
    ): TimetableResponse {
        /** 同一门课同一个时段在"周次"之外完全相同，用它来合并。 */
        data class Key(
            val name: String,
            val teacher: String,
            val day: Int,
            val start: Int,
            val end: Int,
        )

        val merged = linkedMapOf<Key, MutableSet<Int>>()
        // 同一门课同一时段可能有的周填了教室、有的没填，合并时保留第一个非空的
        val rooms = linkedMapOf<Key, String>()
        weekHtml.forEach { (week, html) ->
            val rows = findGridTable(html) ?: return@forEach
            val slots = parseGridTable(
                rows = rows,
                currentWeek = week,
                totalWeeks = totalWeeks,
                weeksOverride = setOf(week),
            )
            slots.forEach { slot ->
                val codes = slot.slot.periodDetail
                val day = codes.firstOrNull()?.firstOrNull()?.digitToIntOrNull()
                val start = codes.firstOrNull()?.drop(1)?.toIntOrNull()
                val end = codes.lastOrNull()?.drop(1)?.toIntOrNull() ?: start
                if (day == null || start == null || end == null) return@forEach
                val key = Key(
                    name = slot.courseName,
                    teacher = slot.teacher,
                    day = day,
                    start = start,
                    end = end,
                )
                merged.getOrPut(key) { linkedSetOf() }.add(week)
                val room = slot.slot.room.orEmpty()
                if (room.isNotBlank() && rooms[key].isNullOrBlank()) rooms[key] = room
            }
        }

        val entries = merged.entries
            .groupBy { it.key.name to it.key.teacher }
            .map { (nameAndTeacher, rows) ->
                val (courseName, teacher) = nameAndTeacher
                CourseEntry(
                    teacher = teacher,
                    courseName = courseName,
                    courseId = "$courseName@$teacher",
                    info = rows.map { (key, weeks) ->
                        val periodCodes = (key.start..key.end).map { period -> "${key.day}%02d".format(period) }
                        CourseSlot(
                            periodDetail = periodCodes,
                            weekDetail = weeks.sorted().map { it.toString() },
                            room = rooms[key]?.takeIf { it.isNotBlank() },
                            weekExpression = WeekExpression.format(weeks),
                            periodCode = "${key.day}" + periodCodes.joinToString(separator = "") { it.drop(1) },
                        )
                    },
                )
            }

        return TimetableResponse(
            code = 200,
            table = TimetableTable(
                code = 200,
                term = TermInfo(
                    total = totalWeeks.toString(),
                    starttime = termStartEpochSeconds,
                    term = null,
                ),
                list = entries,
            ),
        )
    }

    // endregion

    // region 页面判定

    /**
     * 是不是登录页。
     *
     * 不能用"包含『登录』两个字"这种糙判断（课表页导航里也可能有"重新登录"），
     * 这里认两个稳定信号：登录表单的提交地址 `LoginToXk`，或页面标题就是「登录」。
     */
    fun isLoginPage(html: String): Boolean {
        if (html.isBlank()) return true
        if (html.contains("LoginToXk", ignoreCase = true)) return true
        if (html.contains("userAccount") && html.contains("userPassword")) return true
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        return title == "登录" || title.contains("登录-") || title.contains("统一身份认证")
    }

    /** `2026-2027-1` 这种学期标识；页面里没有就返回 null。 */
    private fun detectTermName(html: String): String? =
        Regex("(\\d{4}-\\d{4}-[12])").find(html)?.groupValues?.get(1)

    /**
     * 页面里的"当前周"。
     *
     * 只认明显的措辞，避免把课程单元格里的 `第1-16周` 误当成当前周。
     */
    internal fun detectCurrentWeek(html: String): Int? {
        val patterns = listOf(
            // 个人中心顶部：`第10周 / 20周`
            Regex("第\\s*(\\d{1,2})\\s*周\\s*[/／]\\s*(\\d{1,2})\\s*周"),
            Regex("当前周[次]?\\s*[：:]?\\s*第?\\s*(\\d{1,2})\\s*周"),
            Regex("第\\s*(\\d{1,2})\\s*周\\s*[（(]\\s*本\\s*周"),
            Regex("本\\s*周\\s*[：:]?\\s*第?\\s*(\\d{1,2})\\s*周"),
        )
        for (pattern in patterns) {
            val value = pattern.find(html)?.groupValues?.get(1)?.toIntOrNull()
            if (value != null && value in 1..30) return value
        }
        return null
    }

    /** 页面里声明的学期总周数（`第10周 / 20周` 里的 20）。 */
    internal fun detectTotalWeeks(html: String): Int? {
        val match = Regex("第\\s*\\d{1,2}\\s*周\\s*[/／]\\s*(\\d{1,2})\\s*周").find(html)
            ?: Regex("共\\s*(\\d{1,2})\\s*周").find(html)
            ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1..30 }
    }

    private fun mondayOfCurrentWeek(): LocalDate =
        LocalDate.now(Term.TERM_ZONE).minusDays((LocalDate.now(Term.TERM_ZONE).dayOfWeek.value - 1).toLong())

    private fun LocalDate.startEpoch(): Long = atStartOfDay(Term.TERM_ZONE).toEpochSecond()

    /** `LocalDate.minusWeeks` 在低版本 desugar 下没有特殊问题，这里显式用天数更直观。 */
    private fun LocalDate.minusWeeks(weeks: Long): LocalDate = minusDays(weeks * 7L)

    // endregion

    // region 表格提取（不引入 HTML 解析库，保持 :core 依赖面最小）

    internal data class Cell(
        val text: String,
        val rowspan: Int = 1,
        val colspan: Int = 1,
        val isHeader: Boolean = false,
    ) {
        val lines: List<String> get() = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    internal data class Row(val cells: List<Cell>)

    /** 找 `id="kbtable"`；找不到时找同时含「节次」与「星期」表头的表格（避免把课程列表表误当网格）。 */
    internal fun findGridTable(html: String): List<Row>? {
        val byId = findTableById(html, "kbtable")
        if (byId != null) return parseRows(byId)
        val byHeader = allTables(html).firstOrNull { table ->
            val text = stripTags(table)
            text.contains("节次") && (text.contains("星期一") || text.contains("周日"))
        }
        return byHeader?.let { parseRows(it) }
    }

    /** 找"课程列表"表格（`id="kblist"`，或表头含「课程名称」+「上课时间」）。 */
    internal fun findListTable(html: String): List<Row>? {
        val byId = findTableById(html, "kblist")
        if (byId != null) return parseRows(byId)
        val byHeader = allTables(html).firstOrNull { table ->
            val text = stripTags(table)
            (text.contains("课程名称") || text.contains("课程名")) &&
                (text.contains("上课时间") || text.contains("上课地点"))
        }
        return byHeader?.let { parseRows(it) }
    }

    private fun findTableById(html: String, id: String): String? {
        val pattern = Regex(
            "<table[^>]*\\bid\\s*=\\s*[\"']?$id[\"']?[^>]*>(.*?)</table>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        return pattern.find(html)?.value
    }

    private fun allTables(html: String): List<String> = Regex(
        "<table[^>]*>(.*?)</table>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    ).findAll(html).map { it.value }.toList()

    private val ROW_PATTERN = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CELL_PATTERN = Regex(
        "<(t[dh])([^>]*)>(.*?)</\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val ROWSPAN_PATTERN = Regex("rowspan\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE)
    private val COLSPAN_PATTERN = Regex("colspan\\s*=\\s*[\"']?(\\d+)", RegexOption.IGNORE_CASE)
    private val TITLE_PATTERN = Regex("title\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    internal fun parseRows(tableHtml: String): List<Row> =
        ROW_PATTERN.findAll(tableHtml).map { rowMatch ->
            val cells = CELL_PATTERN.findAll(rowMatch.groupValues[1]).map { cellMatch ->
                val attrs = cellMatch.groupValues[2]
                val inner = cellMatch.groupValues[3]
                // 正方的课表单元格经常把完整信息塞在 title 里（界面上被 CSS 截断成 `高数A（...`），
                // 所以把 title 也当成一行文本喂给解析逻辑
                val titleText = TITLE_PATTERN.find(inner)?.groupValues?.get(1)
                Cell(
                    text = buildString {
                        append(stripTags(inner))
                        if (!titleText.isNullOrBlank()) {
                            append('\n')
                            append(stripTags(titleText))
                        }
                    },
                    rowspan = ROWSPAN_PATTERN.find(attrs)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, MAX_PERIODS_PER_ROW) ?: 1,
                    colspan = COLSPAN_PATTERN.find(attrs)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 7) ?: 1,
                    isHeader = cellMatch.groupValues[1].equals("th", ignoreCase = true),
                )
            }.toList()
            Row(cells)
        }.filter { it.cells.isNotEmpty() }.toList()

    /** HTML → 纯文本：先解实体（title 里是 `&lt;br/&gt;`），再把 `<br>` 变行、去标签、压空白。 */
    internal fun stripTags(html: String): String {
        var text = html
            // 顺序很重要：title 里的换行是转义过的 `&lt;br/&gt;`，
            // 必须先解实体，后面再把 <br> 变成换行，否则会剩下一堆字面量
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
        text = text
            .replace(Regex("<\\s*br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</\\s*(p|div|li|tr|font|span)\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
        return text.lines()
            .map { it.replace(Regex("[\\t\\u00a0 ]+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    // endregion

    // region 网格表格 → 课程时段

    private data class ParsedSlot(
        val courseId: String,
        val courseName: String,
        val teacher: String,
        val slot: CourseSlot,
    ) {
        val weekDetail: List<String> get() = slot.weekDetail
    }

    private fun parseGridTable(
        rows: List<Row>,
        currentWeek: Int?,
        totalWeeks: Int,
        weeksOverride: Set<Int>? = null,
    ): List<ParsedSlot> {
        // 表头行：包含「节次」或「星期」的那一行；找不到就假定第一行是表头
        val headerIndex = rows.indexOfFirst { row ->
            row.cells.any { it.text.contains("节次") || it.text.contains("星期") }
        }
        val headerRow = rows.getOrNull(headerIndex)
        val dayByColumn = headerRow?.let { resolveDayByColumn(it) } ?: emptyMap()
        val dataRows = if (headerIndex >= 0) rows.drop(headerIndex + 1) else rows

        val slots = mutableListOf<ParsedSlot>()
        var rowPeriod = 0
        // 上方 rowspan 占住了哪些列（列号 → 占用到第几行为止，不含）
        val occupiedUntilRow = mutableMapOf<Int, Int>()
        dataRows.forEachIndexed { rowIndex, row ->
            val firstCell = row.cells.firstOrNull() ?: return@forEachIndexed
            val rowRange = parsePeriodRange(firstCell.text)
            // 强智的个人中心把"两节连排"直接写在行首（`第一二节 / (01,02小节)`），
            // 所以一行可能覆盖多节；也可能用 rowspan 跨行，两者取较大值
            val rowSpan = rowRange?.let { it.last - it.first + 1 } ?: 1
            rowPeriod = when {
                rowRange != null -> rowRange.first
                firstCell.isHeader -> rowPeriod
                else -> rowPeriod + 1
            }
            var column = 0
            row.cells.drop(1).forEach { cell ->
                // HTML 里被上方 rowspan 覆盖的格会直接缺省，所以先跳到下一个空闲列
                while ((occupiedUntilRow[column] ?: 0) > rowIndex) column++
                val day = dayByColumn[column] ?: (column + 1)
                val span = cell.colspan
                if (cell.rowspan > 1) {
                    for (covered in column until column + span) {
                        occupiedUntilRow[covered] = rowIndex + cell.rowspan
                    }
                }
                column += span
                // 跨列的大格子是「备注」这类整行说明，不是课程
                if (span > 1) return@forEach
                if (cell.text.contains("备注") || cell.text.contains("请注意") || cell.text.contains("望周知")) {
                    return@forEach
                }
                if (day !in 1..7) return@forEach
                val startPeriod = rowPeriod
                if (startPeriod !in 1..MAX_PERIOD) return@forEach
                val endPeriod = (startPeriod + maxOf(cell.rowspan, rowSpan) - 1).coerceAtMost(MAX_PERIOD)
                val parsed = parseCellContent(
                    cell = cell,
                    gridDayOfWeek = day,
                    gridStartPeriod = startPeriod,
                    gridEndPeriod = endPeriod,
                    currentWeek = currentWeek,
                    totalWeeks = totalWeeks,
                    weeksOverride = weeksOverride,
                ) ?: return@forEach
                slots += parsed
            }
        }
        return slots
    }

    /** 表头 → 每列对应星期几；认不出时返回空表（调用方按列号处理）。 */
    private fun resolveDayByColumn(header: Row): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        var column = 0
        header.cells.drop(1).forEach { cell ->
            val day = parseDay(cell.text)
            if (day != null) map[column] = day
            column += cell.colspan
        }
        return map
    }

    private fun parseDay(text: String): Int? {
        val match = Regex("[星期周]([一二三四五六日天])").find(text) ?: return null
        return when (match.groupValues[1]) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天" -> 7
            else -> null
        }
    }

    /** 第一列里的节次：`第1节` / `1` / `1-2` / `(01,02小节)` / `第一二节`（正方的个人中心用中文两节连排）。 */
    internal fun parsePeriodRange(text: String): IntRange? {
        // 1) `(01,02小节)` 这种直接给数字的最可靠
        Regex("[(（]\\s*(\\d{1,2})\\s*[,，]\\s*(\\d{1,2})\\s*小节\\s*[)）]").find(text)?.let {
            val start = it.groupValues[1].toIntOrNull() ?: return@let
            val end = it.groupValues[2].toIntOrNull() ?: return@let
            if (start in 1..MAX_PERIOD && end in start..MAX_PERIOD) return start..end
        }
        // 2) 第5-6节 / 第5节
        Regex("第\\s*(\\d{1,2})\\s*[-~至]\\s*(\\d{1,2})\\s*节").find(text)?.let {
            val start = it.groupValues[1].toIntOrNull() ?: return@let
            val end = it.groupValues[2].toIntOrNull() ?: return@let
            if (start in 1..MAX_PERIOD && end in start..MAX_PERIOD) return start..end
        }
        Regex("第\\s*(\\d{1,2})\\s*节").find(text)?.let {
            val period = it.groupValues[1].toIntOrNull() ?: return@let
            if (period in 1..MAX_PERIOD) return period..period
        }
        // 3) 中文数字：第一二节 / 第三四节 / 第九十节 / 第十一十二节 / 第五节
        Regex("第\\s*([一二三四五六七八九十]{1,4})\\s*节").find(text)?.let {
            val periods = chinesePeriods(it.groupValues[1])
            if (periods.isNotEmpty()) return periods.first()..periods.last()
        }
        // 4) 纯数字（可能带范围）
        Regex("^(\\d{1,2})(?:\\s*[-~]\\s*(\\d{1,2}))?\\s*$").find(text.lines().firstOrNull().orEmpty())?.let {
            val start = it.groupValues[1].toIntOrNull() ?: return@let
            val end = it.groupValues.getOrNull(2)?.takeIf { value -> value.isNotBlank() }?.toIntOrNull() ?: start
            if (start in 1..MAX_PERIOD && end in start..MAX_PERIOD) return start..end
        }
        return null
    }

    /**
     * 中文数字节次 → 单个节次列表。
     *
     * `一二` → [1,2]；`九十` → [9,10]；`十一十二` → [11,12]；`十一` → [11]。
     */
    internal fun chinesePeriods(raw: String): List<Int> {
        val periods = mutableListOf<Int>()
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            val next = raw.getOrNull(index + 1)
            if (char == '十' && (next == '一' || next == '二')) {
                periods += if (next == '一') 11 else 12
                index += 2
                continue
            }
            val value = when (char) {
                '一' -> 1
                '二' -> 2
                '三' -> 3
                '四' -> 4
                '五' -> 5
                '六' -> 6
                '七' -> 7
                '八' -> 8
                '九' -> 9
                '十' -> 10
                else -> null
            }
            if (value != null) periods += value
            index += 1
        }
        return periods.distinct().sorted()
    }

    /** 一个单元格 → 若干课程时段（同一格可能叠着两门课，用换行/分号分隔）。 */
    private fun parseCellContent(
        cell: Cell,
        gridDayOfWeek: Int,
        gridStartPeriod: Int,
        gridEndPeriod: Int,
        currentWeek: Int?,
        totalWeeks: Int,
        weeksOverride: Set<Int>? = null,
    ): ParsedSlot? {
        val lines = cell.lines
        if (lines.isEmpty()) return null
        // 强智的格子常把完整信息放在 title 里（界面上被 CSS 截断成「高等数学A（..」），
        // 且带 `课程名称：/上课时间：/上课地点：` 标签，优先按标签取字段，拿不到再退回启发式
        val labeled = parseLabeledFields(cell.text)
        val parsedWeeks = labeled.weeks ?: parseWeeks(cell.text)
        // 按周遍历时周次由调用方给定（这一页只代表第 N 周），不需要猜
        val weeks = if (weeksOverride != null) {
            weeksOverride.map { it.toString() }
        } else when {
            parsedWeeks == null && currentWeek != null && totalWeeks > 1 ->
                (1..totalWeeks).map { it.toString() }
            parsedWeeks == null -> return null
            currentWeek != null && parsedWeeks.size == 1 &&
                parsedWeeks.first().toIntOrNull() == currentWeek && totalWeeks > 1 ->
                (1..totalWeeks).map { it.toString() }
            else -> parsedWeeks
        }
        val room = labeled.room ?: lines.lastOrNull { looksLikeRoom(it) }.orEmpty()
        val teacher = labeled.teacher ?: lines.firstOrNull { looksLikeTeacher(it, weeksText = cell.text) }.orEmpty()
        val rawName = labeled.name
            ?: lines.firstOrNull { it != room && it != teacher && !it.contains("周") }
            ?: lines.firstOrNull()
            ?: return null
        // 没有 title 的格子只能拿可见文字，清掉被 CSS 截断留下的 `..`
        val name = cleanCourseName(rawName)

        // 单元格自带的星期/节次（`上课时间：星期一 [01-02]节`）比表格行列更可靠
        val dayOfWeek = labeled.dayOfWeek ?: gridDayOfWeek
        val startPeriod = labeled.periodRange?.first ?: gridStartPeriod
        val endPeriod = labeled.periodRange?.last ?: gridEndPeriod

        val periodCodes = (startPeriod..endPeriod).map { period -> "$dayOfWeek%02d".format(period) }
        return ParsedSlot(
            courseId = "$name@$teacher",
            courseName = name,
            teacher = teacher,
            slot = CourseSlot(
                periodDetail = periodCodes,
                weekDetail = weeks,
                room = room.takeIf { it.isNotBlank() },
                weekExpression = WeekExpression.format(weeks.mapNotNull { it.toIntOrNull() }.toSet()),
                periodCode = periodCodes.joinToString(separator = "") { it.drop(1) }.let { "$dayOfWeek$it" },
            ),
        )
    }

    /** 单元格里的标签字段（强智 title 常见格式：`课程名称：高等数学 上课时间：第10周 星期一 [01-02]节 上课地点：F-105`）。 */
    private data class LabeledCell(
        val name: String?,
        val teacher: String?,
        val weeks: List<String>?,
        val room: String?,
        /** 星期几：`上课时间：… 星期一 …` 里读出来的；读不到为 null（用表格列号）。 */
        val dayOfWeek: Int? = null,
        /** 节次：`[01-02]节` / `第1-2节`；读不到为 null（用表格行）。 */
        val periodRange: IntRange? = null,
    )

    private fun parseLabeledFields(text: String): LabeledCell {
        val teacher = Regex("(?:任课教师|教师|老师)\\s*[:：]\\s*([^\\s，,;；、\\n]+)")
            .find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val room = Regex("(?:上课地点|教室|地点)\\s*[:：]\\s*([^\\s，,;；、\\n<]+)")
            .find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        // 名称：优先 `课程名称：` 标签；否则退回第一个字段标签之前的内容
        val nameByLabel = Regex("(?:课程名称|课程名)\\s*[:：]\\s*([^\\n]+?)(?=\\s*(?:上课时间|上课地点|课程学分|课程属性|$))")
            .find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val namePart = Regex(
            "^(.*?)(?:课程名称|课程名|任课教师|教师|老师|上课周次|开课周次|周次|上课时间|上课地点|教室|地点)\\s*[:：]",
            RegexOption.DOT_MATCHES_ALL,
        ).find(text)?.groupValues?.get(1)
        val name = (nameByLabel ?: namePart
            ?.substringAfterLast('\n')
            ?.trim()
            ?.trim('（', '(', ' ', '\n')
            ?.takeIf { it.isNotEmpty() && it.length <= 40 })
            ?.let { cleanCourseName(it) }

        // 上课时间：`第10周 星期一 [01-02]节` / `第1-16周 星期二 第3-4节`
        val timeText = Regex("上课时间\\s*[:：]\\s*([^\\n]+)")
            .find(text)?.groupValues?.get(1)?.trim()
        val dayOfWeek = timeText?.let { parseDay(it) }
        val periodsByBracket = timeText?.let { time ->
            Regex("\\[\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s*]").find(time)?.let { match ->
                val start = match.groupValues[1].toIntOrNull() ?: return@let null
                val end = match.groupValues[2].toIntOrNull() ?: return@let null
                if (start in 1..MAX_PERIOD && end in start..MAX_PERIOD) start..end else null
            }
        }
        val periodRange = periodsByBracket ?: timeText?.let { parsePeriodRange(it) }

        val weeksRaw = Regex("(?:上课周次|开课周次|周次)\\s*[:：]\\s*([0-9][0-9,\\-，、~至()（）单双\\s]*?)(?=\\s*[$\\n]|\\s*(?:教师|老师|教室|地点))")
            .find(text)?.groupValues?.get(1)?.trim()
        val weeks = weeksRaw?.let { parseWeeks(it + "周") } ?: timeText?.let { parseWeeks(it) }
        return LabeledCell(
            name = name,
            teacher = teacher,
            weeks = weeks,
            room = room,
            dayOfWeek = dayOfWeek,
            periodRange = periodRange,
        )
    }

    /** `{第1-16周}`、`1-16周`、`第1,3,5-9周(单)` → 周次列表。 */
    internal fun parseWeeks(text: String): List<String>? {
        val match = Regex("\\{?\\s*第?\\s*([0-9][0-9,\\-，、~至\\s]*?)\\s*周").find(text) ?: return null
        val raw = match.groupValues[1].replace("~", "-").replace("至", "-")
        val isOdd = text.contains("(单)") || text.contains("（单）")
        val isEven = text.contains("(双)") || text.contains("（双）")
        val weeks = WeekExpression.parse(raw).sorted()
            .filter { week ->
                when {
                    isOdd -> week % 2 == 1
                    isEven -> week % 2 == 0
                    else -> true
                }
            }
        return weeks.takeIf { it.isNotEmpty() }?.map { it.toString() }
    }

    /**
     * 清掉"可见文字被 CSS 截断"留下的尾巴：`大学体育（一..` → `大学体育（一`。
     *
     * 页面上有些格子连 `title` 都没有（实测 `title="undefined"`），只能拿可见文字，
     * 而可见文字是被视觉截断过的，带着 `..` / `…`，显示到 App 里就显得脏。
     */
    internal fun cleanCourseName(name: String): String {
        var cleaned = name.trim()
        while (true) {
            val next = cleaned
                .removeSuffix("..")
                .removeSuffix("...")
                .removeSuffix("…")
                .removeSuffix("…")
                .trim()
            if (next == cleaned || next.isEmpty()) return cleaned
            cleaned = next
        }
    }

    private val ROOM_PATTERN = Regex("(楼|教|室|号|场|馆|机房|实验|中心|校区|[A-Za-z]-?\\d{2,}|\\d{3,})")

    internal fun looksLikeRoom(line: String): Boolean =
        line.length in 2..24 && ROOM_PATTERN.containsMatchIn(line) && !line.contains("周")

    internal fun looksLikeTeacher(line: String, weeksText: String): Boolean {
        if (line.contains("周")) return false
        if (looksLikeRoom(line)) return false
        if (line == weeksText) return false
        // 中文姓名 2-4 字，或"张三,李四"这种多教师
        return Regex("^[\\u4e00-\\u9fa5·]{2,4}(?:[、,，][\\u4e00-\\u9fa5·]{2,4})*$").matches(line)
    }

    // endregion

    // region 课程列表表格（kblist）兜底

    private fun parseListTable(rows: List<Row>): List<ParsedSlot> {
        if (rows.size < 2) return emptyList()
        val header = rows.first().cells.map { it.text }
        val nameColumn = header.indexOfFirst { it.contains("课程名") }
        val timeColumn = header.indexOfFirst { it.contains("时间") }
        val roomColumn = header.indexOfFirst { it.contains("地点") || it.contains("教室") }
        val teacherColumn = header.indexOfFirst { it.contains("教师") || it.contains("老师") }
        if (nameColumn < 0) return emptyList()

        val slots = mutableListOf<ParsedSlot>()
        rows.drop(1).forEach { row ->
            val name = row.cells.getOrNull(nameColumn)?.text?.lines()?.firstOrNull() ?: return@forEach
            val timeText = row.cells.getOrNull(timeColumn)?.text.orEmpty()
            val room = roomColumn.takeIf { it >= 0 }?.let { row.cells.getOrNull(it)?.text?.lines()?.firstOrNull() }.orEmpty()
            val teacher = teacherColumn.takeIf { it >= 0 }?.let { row.cells.getOrNull(it)?.text?.lines()?.firstOrNull() }.orEmpty()
            val weeks = parseWeeks(timeText) ?: return@forEach
            splitTimes(timeText).forEach { (day, periodRange) ->
                val periodCodes = (periodRange.first..periodRange.last).map { period -> "$day%02d".format(period) }
                slots += ParsedSlot(
                    courseId = "$name@$teacher",
                    courseName = name,
                    teacher = teacher,
                    slot = CourseSlot(
                        periodDetail = periodCodes,
                        weekDetail = weeks,
                        room = room.takeIf { it.isNotBlank() },
                        weekExpression = WeekExpression.format(weeks.mapNotNull { it.toIntOrNull() }.toSet()),
                        periodCode = "$day" + periodCodes.joinToString("") { it.drop(1) },
                    ),
                )
            }
        }
        return slots
    }

    /** `星期一第1-2节` 可能在一格里出现多段（不同星期），逐段拆开。 */
    private fun splitTimes(text: String): List<Pair<Int, IntRange>> {
        val results = mutableListOf<Pair<Int, IntRange>>()
        val dayMatches = Regex("[星期周]([一二三四五六日天])第?\\s*([0-9,\\-~至]+)\\s*节")
            .findAll(text)
        dayMatches.forEach { match ->
            val day = parseDay(match.value) ?: return@forEach
            // 拼回 `第5-6节` 的写法再交给统一解析器，避免两处各写一套节次规则
            val periods = parsePeriodRange("第${match.groupValues[2]}节") ?: return@forEach
            results += day to periods
        }
        return results
    }

    // endregion
}
