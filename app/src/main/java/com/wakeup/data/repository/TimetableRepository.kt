package com.wakeup.data.repository

import com.wakeup.data.cache.CachedTimetable
import com.wakeup.data.cache.TimetableCache
import com.wakeup.data.cache.toResponseOrNull
import com.wakeup.data.remote.SyncResult
import com.wakeup.data.remote.TimetableMapper
import com.wakeup.data.remote.TimetableRemoteDataSource
import com.wakeup.data.remote.WakeupApiFactory
import com.wakeup.domain.Timetable

/** 课表仓库：负责取数、转换、内存缓存与本地磁盘缓存。 */
class TimetableRepository(
    private val remote: TimetableRemoteDataSource = TimetableRemoteDataSource(),
    private val cache: TimetableCache? = null,
    /** 缓存发生变化时的回调，用于让设置页刷新缓存占用显示。 */
    private val onCacheChanged: (() -> Unit)? = null,
) {

    /** 内存缓存，进程存活期间有效。 */
    @Volatile
    private var cached: Timetable? = null

    /** 内存里保存的原始页面数据，同步成功后写盘。 */
    @Volatile
    private var cachedPayload: String? = null

    @Volatile
    private var usingCache: Boolean = false

    /** 最近一次同步到课表时的用户名。 */
    @Volatile
    var lastUser: String? = null
        private set

    /** 最近一次同步成功时间（毫秒）。 */
    @Volatile
    var lastSyncedAt: Long = 0L
        private set

    /** 缓存对应的课表来源，用于判断缓存是否还属于当前设置。 */
    @Volatile
    var cachedSourceUrl: String = ""
        private set

    fun cachedTimetable(): Timetable? = cached

    /** 当前展示的是否来自本地缓存（而非本次网络同步）。 */
    fun isUsingCache(): Boolean = usingCache

    /** 磁盘缓存的字节数，供设置页展示。 */
    fun cacheSizeBytes(): Long = cache?.sizeBytes() ?: 0L

    /**
     * 从本地缓存恢复课表。
     *
     * 返回 true 表示成功恢复。缓存属于**另一个**课表链接时会被忽略，
     * 避免换了 token 之后先闪一下别人的课表。
     */
    suspend fun restoreFromCache(expectedSourceUrl: String): Boolean = restoreFromCacheNow(expectedSourceUrl)

    /** 同步版本，供 ViewModel 初始化时立即铺数据。 */
    fun restoreFromCacheNow(expectedSourceUrl: String): Boolean {
        val store = cache ?: return false
        val stored = store.loadNow() ?: return false
        val normalizedExpected = expectedSourceUrl.trim()
        if (stored.sourceUrl.isNotBlank() && normalizedExpected.isNotBlank() &&
            stored.sourceUrl != normalizedExpected
        ) {
            return false
        }
        val response = stored.toResponseOrNull() ?: run {
            store.clearNow()
            return false
        }
        val domain = runCatching {
            TimetableMapper.toDomain(response, sourceUrl = stored.sourceUrl)
        }.getOrNull() ?: run {
            store.clearNow()
            return false
        }
        cached = domain
        cachedPayload = stored.payload
        cachedSourceUrl = stored.sourceUrl
        lastSyncedAt = stored.syncedAt
        usingCache = true
        return true
    }

    /** 拉取并解析课表；成功时写入内存与磁盘缓存。 */
    suspend fun refresh(source: String, baseUrl: String? = null): SyncResult {
        val resolvedBase = baseUrl?.takeIf { it.isNotBlank() } ?: WakeupApiFactory.DEFAULT_BASE_URL
        val result = try {
            remote.fetch(source, resolvedBase)
        } catch (error: Exception) {
            SyncResult.Failure(error.message ?: "网络请求失败", error)
        }

        if (result is SyncResult.Success) {
            val url = remote.urlFor(source, resolvedBase)
            val domain = try {
                TimetableMapper.toDomain(result.response, sourceUrl = url)
            } catch (error: Exception) {
                return SyncResult.Failure(error.message ?: "课表数据无法识别", error)
            }
            val now = System.currentTimeMillis()
            cached = domain
            cachedPayload = result.rawPayload
            cachedSourceUrl = url
            lastSyncedAt = now
            lastUser = result.response.user
            usingCache = false
            cache?.save(
                CachedTimetable(sourceUrl = url, payload = result.rawPayload, syncedAt = now),
            )
            onCacheChanged?.invoke()
        }
        return result
    }

    /** 清空内存与磁盘缓存。 */
    suspend fun clearCache() {
        cached = null
        cachedPayload = null
        cachedSourceUrl = ""
        lastUser = null
        lastSyncedAt = 0L
        usingCache = false
        cache?.clear()
        onCacheChanged?.invoke()
    }
}
