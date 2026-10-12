package com.wakeup.smartspacer

import android.content.Context
import com.kieronquinn.app.smartspacer.sdk.provider.SmartspacerTargetProvider
import com.kieronquinn.app.smartspacer.sdk.receivers.SmartspacerTargetUpdateReceiver

/**
 * 接收 Smartspacer 的定时刷新请求。
 *
 * 注册了 `refreshPeriodMinutes` 就必须有这个接收器：Smartspacer 只是**发广播通知**
 * "该更新了"，真正让内容重算是插件调用 [SmartspacerTargetProvider.notifyChange]，
 * 之后 Smartspacer 才会重新向 [NextClassTarget] 取内容。
 *
 * 这里的"重算"其实是空操作 —— [NextClassTarget.getSmartspaceTargets] 每次都是按
 * 当前时间现算的，notifyChange 只是让 Smartspacer 重新来取一次。
 */
class NextClassUpdateReceiver : SmartspacerTargetUpdateReceiver() {

    override fun onRequestSmartspaceTargetUpdate(
        context: Context,
        requestTargets: List<RequestTarget>,
    ) {
        // 只回应属于本插件的请求（Smartspacer 会批量广播给所有插件）
        if (requestTargets.none { it.authority == authorityOf(context) }) return
        SmartspacerTargetProvider.notifyChange(context, NextClassTarget::class.java)
    }

    private fun authorityOf(context: Context): String =
        "${context.packageName}.target.nextclass"
}
