package com.wakeup.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 进程内的"收到课表同步"通知。
 *
 * 手表上真正写缓存的是 [com.wakeup.data.repository.TimetableRepository.adoptSyncedTimetable]，
 * 但它执行时手表界面可能正在前台、也可能只是后台进程；界面层不需要知道同步是走
 * Data Layer 还是其它路径，只要订阅 [events] 就能在数据更新后刷新一次。
 *
 * 注意两点：
 * - 这是**进程内**通道，跨进程/跨设备一律走 Wearable Data Layer，不靠它；
 * - `replay = 0`：只通知"此刻在线"的订阅者，不重放历史，避免界面重复刷新；
 *   缓冲区只有 1 个且冲突时丢最旧的 —— 同步事件只关心"最新时间戳"，丢中间的没有损失。
 */
object TimetableSyncBus {

    private val _events = MutableSharedFlow<Long>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 每次成功采纳一份同步来的课表，会在这条流上发出该课表的同步时间（毫秒）。 */
    val events: SharedFlow<Long> = _events.asSharedFlow()

    /** 发出一次通知；不挂起、不抛异常（没有订阅者时直接丢弃）。 */
    fun notifyReceived(syncedAt: Long) {
        _events.tryEmit(syncedAt)
    }
}
