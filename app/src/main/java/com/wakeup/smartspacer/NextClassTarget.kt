package com.wakeup.smartspacer

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon as AndroidIcon
import com.kieronquinn.app.smartspacer.sdk.model.CompatibilityState
import com.kieronquinn.app.smartspacer.sdk.model.SmartspaceTarget
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Icon as SmartspacerIcon
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.TapAction
import com.kieronquinn.app.smartspacer.sdk.model.uitemplatedata.Text
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerTargetProvider
import com.kieronquinn.app.smartspacer.sdk.utils.TargetTemplate
import com.wakeup.MainActivity
import com.wakeup.R
import com.wakeup.appContainer
import com.wakeup.domain.NextClassPresenter

/**
 * Smartspacer 的 Target：在桌面/锁屏的「一览」里显示**下一节课**。
 *
 * Target 本质上是一个 ContentProvider，由 SDK 负责 Binder 协议；这里只需要
 * 返回要展示的内容（[getSmartspaceTargets]）和注册信息（[getConfig]）。
 *
 * 显示的是**绝对时间**（如 `今天 第3-4节 10:15 · F-105`）而不是"还有几分钟"——
 * 桌面小组件的刷新并不及时也不保证，绝对时间不会随时间变旧。
 */
class NextClassTarget : SmartspacerTargetProvider() {

    override fun getSmartspaceTargets(smartspacerId: String): List<SmartspaceTarget> {
        val context = provideContext()
        // 读缓存可能抛（文件损坏等），失败就当作没有课表，不要让 Smartspacer 崩
        val timetable = runCatching {
            context.appContainer.timetableRepository.timetableForDisplay()
        }.getOrNull() ?: return emptyList()

        // 文案与表盘复杂功能、信息块、今日页共用 :core 的 NextClassPresenter，
        // 四个展示端不会各写一套而出现内容对不上
        val presentation = NextClassPresenter.present(timetable)
        if (!presentation.hasClass) return emptyList()

        val icon = SmartspacerIcon(
            icon = AndroidIcon.createWithResource(context, R.drawable.ic_smartspacer_class),
            contentDescription = null,
            shouldTint = true,
        )

        return listOf(
            TargetTemplate.Basic(
                // ID 里带上 smartspacerId：同一个 Target 被添加多次时也要彼此独立
                id = "next_class_$smartspacerId",
                componentName = ComponentName(context, NextClassTarget::class.java),
                title = Text(presentation.courseName),
                subtitle = Text(presentation.description),
                icon = icon,
                onClick = TapAction(
                    intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                ),
            ).create().apply {
                // 不支持手动移除：内容完全由课表推导，移除了也没意义
                canBeDismissed = false
            },
        )
    }

    override fun getConfig(smartspacerId: String?): Config {
        val context = provideContext()
        return Config(
            label = context.getString(R.string.smartspacer_next_class_label),
            description = context.getString(R.string.smartspacer_next_class_description),
            icon = AndroidIcon.createWithResource(context, R.drawable.ic_smartspacer_class),
            compatibilityState = CompatibilityState.Compatible,
            // Smartspace 的大致更新频率是每分钟一次，这里限制成至少 15 分钟一次，
            // 只为"跨到下节课/跨天"时能翻页；内容本身用绝对时间，过期也不会错
            refreshPeriodMinutes = REFRESH_PERIOD_MINUTES,
        )
    }

    /** 不支持移除，见 [getSmartspaceTargets] 里的 `canBeDismissed`。 */
    override fun onDismiss(smartspacerId: String, targetId: String): Boolean = false

    companion object {
        internal const val REFRESH_PERIOD_MINUTES = 15
    }
}
