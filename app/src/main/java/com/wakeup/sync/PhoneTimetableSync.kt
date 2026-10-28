package com.wakeup.sync

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.wakeup.appContainer
import com.wakeup.sync.TimetableSyncCodec
import com.wakeup.sync.TimetableSyncPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 手机端把课表推给手表（Wearable Data Layer）。
 *
 * 触发时机由 ViewModel 决定（一般是"本次网络同步成功、写进缓存之后"），这里只负责：
 * 1. 取出内存里的原始课表 JSON；
 * 2. gzip + Base64；
 * 3. 塞进路径为 [TimetableSyncPaths.TIMETABLE] 的 DataItem。
 *
 * 手表端的接收方见 `:wear` 的 `WatchTimetableSyncService`。
 *
 * 设计上的取舍：
 * - **绝不抛异常**：手表没配对、GMS 缺失/被禁用、Data Layer 服务不可用……都只返回 false。
 *   同步手表是"锦上添花"，不能因此让手机的课表刷新失败。
 * - **超限就不推**：单条 DataItem 上限 100 KB，压缩后仍超过
 *   [TimetableSyncCodec.MAX_ENCODED_BYTES] 时直接放弃（手表本来也能自己联网取）。
 * - `setUrgent()`：课表是用户"刷新完就想在表上看"的数据，标记为紧急可以尽快通过
 *   Bluetooth/Wi-Fi 传给手表，而不是等系统的批量同步时机。
 */
object PhoneTimetableSync {

    /** 等待 Data Layer 完成的超时；超时按失败处理，不阻塞调用方太久。 */
    private const val PUSH_TIMEOUT_SECONDS = 10L

    /**
     * 把当前内存里的课表推给已配对的手表。
     *
     * @return true 仅表示"有数据、且 putDataItem 调用成功返回"。
     *         注意这**不**代表手表已经收到（Data Layer 是异步传输的）。
     */
    suspend fun push(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val repository = appContext.appContainer.timetableRepository

        // 没有同步成功过就没有可推的数据
        val payload = repository.cachedRawPayload()
        if (payload.isNullOrBlank()) return@withContext false

        val encoded = runCatching { TimetableSyncCodec.compressPayload(payload) }.getOrNull()
            ?: return@withContext false
        if (!TimetableSyncCodec.fitsInDataLayer(encoded)) return@withContext false

        val syncedAt = repository.lastSyncedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        // 注意：PutDataRequest 只接收原始字节，DataMap 要先 toByteArray() 序列化
        // （手表端用 DataMapItem.fromDataItem(...).dataMap 还原）
        val dataMap = DataMap().apply {
            putString(TimetableSyncPaths.KEY_SOURCE_URL, repository.cachedSourceUrl)
            putLong(TimetableSyncPaths.KEY_SYNCED_AT, syncedAt)
            putString(TimetableSyncPaths.KEY_PAYLOAD, encoded)
        }
        val request = PutDataRequest.create(TimetableSyncPaths.TIMETABLE)
            .setUrgent()
            .setData(dataMap.toByteArray())

        runCatching {
            Tasks.await(
                Wearable.getDataClient(appContext).putDataItem(request),
                PUSH_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            true
        }.getOrDefault(false)
    }
}
