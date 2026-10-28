package com.wakeup.wear.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.wakeup.appContainer
import com.wakeup.domain.NextClassPresentation
import com.wakeup.domain.NextClassPresenter
import com.wakeup.wear.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 表盘复杂功能（Complication）：在系统表盘上显示**下一节课**。
 *
 * 数据来源与手机端、手表端界面完全一致，都是 `:core` 的
 * [com.wakeup.data.repository.TimetableRepository]：这里只读内存/磁盘缓存
 * （[com.wakeup.data.repository.TimetableRepository.timetableForDisplay]），**不联网**。
 * 表盘每分钟甚至每次抬腕都可能来要数据，绝不能在这条链路上做网络请求。
 *
 * 只声明支持 `SHORT_TEXT`（表盘上一小段文字）与 `LONG_TEXT`（标题 + 正文），
 * 其它类型（`NO_DATA` / 图片 / 进度等）返回 null 是协议允许的。
 *
 * 显示内容约定：
 * - 有下一节课：短文本 `10:15 高数`，长文本 `今天` + `10:15 高等数学 · F-105`；
 * - 有课表但近期没课：`无课`；
 * - 从来没同步过课表：`未同步`。
 *
 * 注意：**支持的两种类型永远不返回 null**。返回 null 时表盘会显示"无数据"，
 * 分不清"没课"和"坏了"，对用户来说是很差的体验。
 */
class NextClassComplicationService : SuspendingComplicationDataSourceService() {

    /**
     * 把 `:core` 的统一呈现结果映射成表盘这一层需要的内容。
     *
     * 只做字段挑选，不再拼文案：`shortText` / `title` / `longText` / `description`
     * 都已经在 [NextClassPresenter] 里按"有课 / 无课 / 未同步"三种情况算好了。
     */
    private fun NextClassPresentation.toContent(): NextClassContent = when {
        !hasTimetable -> NextClassContent.NO_TIMETABLE
        !hasClass -> NextClassContent.NO_CLASS
        else -> NextClassContent(
            shortText = shortText,
            title = title,
            longText = longText,
            description = description,
        )
    }

    /**
     * 表盘来要数据。
     *
     * 读缓存会碰磁盘（几十 KB 的 JSON），挪到 IO 线程；换算周次/时间都在领域层完成，
     * 这里只负责拼文案。
     */
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val type = request.complicationType
        // 只声明支持 SHORT_TEXT / LONG_TEXT；其它类型返回 null 是允许的
        if (type != ComplicationType.SHORT_TEXT && type != ComplicationType.LONG_TEXT) return null

        val content = withContext(Dispatchers.IO) { loadContent() }
        return buildData(content, type, tapAction = openAppAction())
    }

    /**
     * 表盘编辑器里的预览数据。
     *
     * 必须是**确定**的内容：不能依赖这台手表当下有没有同步过课表，
     * 否则用户在选择复杂功能时看到的是"未同步"，以为功能坏了。
     */
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildData(NextClassContent.PREVIEW, type, tapAction = null)

    /**
     * 读本地缓存拼出要显示的内容。
     *
     * 文案全部来自 `:core` 的 [NextClassPresenter] —— 和手机今日页、Smartspacer、信息块
     * 用的是同一份实现，四处不会出现"桌面 10:15、表盘 10:20"这种矛盾。
     *
     * 任何异常（缓存损坏、解析失败）都退化成"未同步"：表盘数据源里抛异常，
     * 系统会直接显示"无数据"，用户没有任何办法自救。
     */
    private fun loadContent(): NextClassContent = runCatching {
        val timetable = appContainer.timetableRepository.timetableForDisplay()
        NextClassPresenter.present(timetable).toContent()
    }.getOrElse { NextClassContent.NO_TIMETABLE }

    /** 按类型拼 [ComplicationData]；不支持的返回 null。 */
    private fun buildData(
        content: NextClassContent,
        type: ComplicationType,
        tapAction: PendingIntent?,
    ): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            plainText(content.shortText),
            plainText(content.description),
        ).apply {
            tapAction?.let { setTapAction(it) }
        }.build()

        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            plainText(content.longText),
            plainText(content.description),
        ).apply {
            setTitle(plainText(content.title))
            tapAction?.let { setTapAction(it) }
        }.build()

        else -> null
    }

    /** 点一下复杂功能就打开手表端应用；创建失败时宁可不带点击，也不能影响数据本身。 */
    private fun openAppAction(): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }.getOrNull()

    private fun plainText(text: String): ComplicationText = PlainComplicationText.Builder(text).build()
}

/**
 * 表盘要显示的一份内容（文案已经算好）。
 *
 * 真实数据与预览数据共用同一条拼装路径，避免预览和实际显示长得不一样。
 */
private data class NextClassContent(
    /** 短文本：`10:15 高数`。 */
    val shortText: String,
    /** 长文本标题：`今天` / `明天` / `周三`。 */
    val title: String,
    /** 长文本正文：`10:15 高等数学 · F-105`。 */
    val longText: String,
    /** 无障碍描述（整句话）：`今天 第3-4节 10:15 高等数学 F-105`。 */
    val description: String,
) {
    companion object {
        /** 从来没有同步过课表。 */
        val NO_TIMETABLE = NextClassContent(
            shortText = "未同步",
            title = "课表",
            longText = "未同步",
            description = "Wakeup 还没有同步课表",
        )

        /** 有课表，但未来一周都没有课（`nextOccurrence` 最多往后看一周多）。 */
        val NO_CLASS = NextClassContent(
            shortText = "无课",
            title = "今天",
            longText = "无课",
            description = "近期没有课",
        )

        /** 表盘编辑器里的固定样例，不读任何本地数据（与 Smartspacer / 信息块同源）。 */
        val PREVIEW = NextClassContent(
            shortText = NextClassPresentation.PREVIEW.shortText,
            title = NextClassPresentation.PREVIEW.title,
            longText = NextClassPresentation.PREVIEW.longText,
            description = NextClassPresentation.PREVIEW.description,
        )
    }
}

/**
 * 课表发生变化后，主动让系统表盘重新拉一次数据。
 *
 * 系统的更新周期（`UPDATE_PERIOD_SECONDS`）通常很长，光靠它表盘上的"下一节课"
 * 会停在旧数据上；手机同步完课表、手表自己刷新完、用户清缓存之后都应该调一次。
 *
 * 表盘没有把这个服务添加为数据源时，`requestUpdateAll` 会抛
 * `IllegalArgumentException`（没有可更新的表盘），这属于正常情况，静默忽略。
 */
object ComplicationRefresher {

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                appContext,
                ComponentName(appContext, NextClassComplicationService::class.java),
            ).requestUpdateAll()
        }
    }
}
