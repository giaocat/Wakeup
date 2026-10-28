package com.wakeup.wear.tile

import android.content.Context
import androidx.wear.tiles.TileService

/**
 * 课表变化后，让系统把信息块（Tile）重新拉一次。
 *
 * Tile 自己的 `freshnessIntervalMillis`（15 分钟）只是兜底；手机同步完课表、
 * 手表自己刷新完、用户在设置里改了 token/清了缓存，都应该立刻刷新一次，
 * 否则滑到信息块还是旧课表。
 *
 * 没有把 Wakeup 的信息块添加到信息块列表时，`requestUpdate` 会抛异常，属于正常情况，静默忽略。
 */
object TileRefresher {

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            TileService.getUpdater(appContext).requestUpdate(TimetableTileService::class.java)
        }
    }
}
