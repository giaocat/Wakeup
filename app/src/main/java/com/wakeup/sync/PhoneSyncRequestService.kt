package com.wakeup.sync

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * 手机端：处理手表发来的「请把课表推过来」请求。
 *
 * 手表没有浏览器、登录不了教务系统，所以点「从手机同步」时由手机把缓存里的课表推过去；
 * 处理完再回一条结果消息，手表据此显示成功还是失败。
 */
class PhoneSyncRequestService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path != TimetableSyncPaths.REQUEST_SYNC) return

        val pushed = runBlocking(Dispatchers.IO) { PhoneTimetableSync.push(this@PhoneSyncRequestService) }
        Log.i(TAG, "手表请求同步，推送${if (pushed) "成功" else "失败"}")

        // 回执：手表用它显示「成功 / 没有可推送的课表」
        runCatching {
            runBlocking(Dispatchers.IO) {
                Tasks.await(
                    Wearable.getMessageClient(this@PhoneSyncRequestService).sendMessage(
                        messageEvent.sourceNodeId,
                        TimetableSyncPaths.SYNC_RESULT,
                        if (pushed) RESULT_OK.toByteArray() else RESULT_EMPTY.toByteArray(),
                    ),
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }
        }.onFailure { Log.w(TAG, "回执发送失败", it) }
    }

    private companion object {
        const val TAG = "PhoneSyncRequest"
        const val RESULT_OK = "ok"
        const val RESULT_EMPTY = "empty"
        const val TIMEOUT_SECONDS = 5L
    }
}
