package com.wakeup.sync

/**
 * 手机 → 手表同步用的 Data Layer 路径与 DataMap 键。
 *
 * 这些常量必须在 `:app` 与 `:wear` 之间**逐字一致**（两端各自编译，没法共享字符串），
 * 所以统一放在 `:core` 里，任何一端改键名都会立刻反映到两端。
 *
 * 路径形如 `/wakeup/timetable`：`/wakeup` 是命名空间，方便以后加 `/wakeup/settings` 之类，
 * 手表端 manifest 里的 `<data android:pathPrefix="/wakeup" />` 就是按这个前缀过滤的。
 */
object TimetableSyncPaths {

    /** 本应用所有同步路径的公共前缀。 */
    const val PREFIX = "/wakeup"

    /** 整份课表（原始 JSON，gzip + Base64 后放在 [KEY_PAYLOAD] 里）。 */
    const val TIMETABLE = "$PREFIX/timetable"

    /** 课表来源链接（DataMap 里是 String）。 */
    const val KEY_SOURCE_URL = "sourceUrl"

    /** 该份课表的同步时间戳，毫秒（DataMap 里是 Long）。 */
    const val KEY_SYNCED_AT = "syncedAt"

    /** gzip + Base64 之后的原始课表 JSON（DataMap 里是 String）。 */
    const val KEY_PAYLOAD = "payload"
}
