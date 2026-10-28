package com.wakeup.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.wakeup.appContainer
import com.wakeup.sync.TimetableSyncBus
import com.wakeup.sync.TimetableSyncCodec
import com.wakeup.sync.TimetableSyncPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 手表端接收手机推来的课表。
 *
 * 手机端见 `:app` 的 `com.wakeup.sync.PhoneTimetableSync`。
 *
 * 处理链路：
 * `DataItem(/wakeup/timetable)` → DataMap 取出 `payload` → gzip+Base64 解码
 * → [com.wakeup.data.repository.TimetableRepository.adoptSyncedTimetable]
 * → 回填设置里的课表链接 → [TimetableSyncBus] 通知界面刷新。
 *
 * 可靠性约定：
 * - 手表本来就能自己联网取课表，所以这里每一条数据都只是"更好的来源"，不是唯一来源；
 * - 任何一条坏数据（路径不对、没有 payload、解压失败、JSON 解析失败）都只跳过它自己，
 *   不抛出去 —— 系统会在多条事件时复用同一个回调，抛异常会让后面的正常数据也收不到；
 * - 不打印 payload 内容（里面是用户的真实课表）。
 *
 * 说明：Data Layer 的回调在主线程，而这些回调返回后进程随时可能被回收，所以这里用
 * [runBlocking] 把"解析 + 写缓存"做完再返回。工作量很小（几十 KB JSON + 一个小文件），
 * 用 `Dispatchers.IO` 跑，避免在主线程做磁盘 I/O。
 */
class WatchTimetableSyncService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)

        for (event in dataEvents) {
            try {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val dataItem = event.dataItem
                if (dataItem.uri.path != TimetableSyncPaths.TIMETABLE) continue

                val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
                val encoded = dataMap.getString(TimetableSyncPaths.KEY_PAYLOAD)
                if (encoded.isNullOrEmpty()) continue
                val payload = TimetableSyncCodec.decompressPayload(encoded) ?: continue

                val sourceUrl = dataMap.getString(TimetableSyncPaths.KEY_SOURCE_URL).orEmpty()
                val syncedAt = dataMap.getLong(TimetableSyncPaths.KEY_SYNCED_AT)

                runBlocking(Dispatchers.IO) {
                    adopt(payload = payload, sourceUrl = sourceUrl, syncedAt = syncedAt)
                }
            } catch (error: Exception) {
                Log.w(TAG, "处理课表同步数据失败", error)
            }
        }
    }

    /**
     * 备用通道：万一某次同步走的是 MessageClient（不落盘、只发一次），
     * 这里也按同样的格式接收一次。消息体本身就是 gzip+Base64 的 payload，没有 DataMap 可用，
     * 所以来源链接/同步时间只能就近推断（链接留空 → 沿用本地设置，时间取当前时间）。
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        try {
            if (messageEvent.path != TimetableSyncPaths.TIMETABLE) return
            val bytes = messageEvent.data
            if (bytes.isEmpty()) return
            val payload = TimetableSyncCodec.decompressPayload(String(bytes, Charsets.UTF_8)) ?: return
            runBlocking(Dispatchers.IO) {
                adopt(payload = payload, sourceUrl = "", syncedAt = System.currentTimeMillis())
            }
        } catch (error: Exception) {
            Log.w(TAG, "处理课表同步消息失败", error)
        }
    }

    /** 落库：解析成功才覆盖缓存，并把手机的课表链接回填到本地设置。 */
    private suspend fun adopt(payload: String, sourceUrl: String, syncedAt: Long) {
        val container = appContainer
        val adopted = container.timetableRepository.adoptSyncedTimetable(
            sourceUrl = sourceUrl,
            payload = payload,
            syncedAt = syncedAt,
        ) ?: return

        // 手机是"课表来源"的唯一权威：链接一致之后，手表设置页回显的 token 才和手机对得上
        if (sourceUrl.isNotBlank()) {
            runCatching { container.settingsRepository.setTimetableUrl(sourceUrl) }
                .onFailure { Log.w(TAG, "回填课表链接失败", it) }
        }

        // 用仓库里的时间戳而不是入参：syncedAt <= 0 时仓库会兜底成当前时间
        TimetableSyncBus.notifyReceived(container.timetableRepository.lastSyncedAt)

        // 表盘复杂功能与信息块也要换成新数据（系统不会自己知道课表变了）
        com.wakeup.wear.complication.ComplicationRefresher.requestUpdate(this)
        com.wakeup.wear.tile.TileRefresher.requestUpdate(this)

        Log.i(TAG, "已采纳手机同步的课表（adopted=${adopted.courses.size} 门课）")
    }

    private companion object {
        const val TAG = "WatchTimetableSync"
    }
}
