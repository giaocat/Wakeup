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

    /**
     * 内存里保存的原始课表 JSON（页面 `this.init(...)` 里抽出来的那段）。
     *
     * 手机端把它压缩后经 Wearable Data Layer 推给手表；没有同步成功过、或刚清过缓存时返回 null。
     * 只暴露只读视图，不提供 setter —— 原始数据的唯一写入口是 [refresh] / [adoptSyncedTimetable]。
     */
    fun cachedRawPayload(): String? = cachedPayload

    /**
     * 立刻给出一份可用课表：优先内存缓存，其次磁盘缓存。
     *
     * 供 **ContentProvider**（Smartspacer 的 Target）这类拿不到挂起上下文、
     * 也不在主线程的场景使用。磁盘缓存里自带 `sourceUrl`，所以这里不需要读设置，
     * 也就不必等待 DataStore 的 Flow。缓存文件只有几十 KB，同步读取可以接受。
     */
    fun timetableForDisplay(): Timetable? {
        cached?.let { return it }
        val store = cache ?: return null
        val stored = store.loadNow() ?: return null
        val response = stored.toResponseOrNull() ?: return null
        val domain = runCatching {
            TimetableMapper.toDomain(response, sourceUrl = stored.sourceUrl)
        }.getOrNull() ?: return null
        cached = domain
        cachedPayload = stored.payload
        cachedSourceUrl = stored.sourceUrl
        lastSyncedAt = stored.syncedAt
        usingCache = true
        return domain
    }

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

    /**
     * 采纳外部推来的课表（手机端经 Wearable Data Layer 同步给手表）。
     *
     * 处理链路和 [restoreFromCacheNow] 一致：原始 JSON → 接口模型 → 领域模型；
     * 区别是数据不是从磁盘读的，所以成功后**内存和磁盘都要更新**，并把
     * [lastSyncedAt] 记成手机那边给出的同步时间（手机和手表显示同一个"最后同步"）。
     *
     * 解析失败时返回 null，**并且完全不动现有缓存**：宁可手表继续用旧课表，
     * 也不能让一条坏数据把已经能用的缓存顶掉。
     *
     * @param sourceUrl 手机端当前使用的课表来源；为空时沿用本地已知来源，避免清空缓存里的来源。
     * @param payload 从课表页面抽出来的原始 JSON。
     * @param syncedAt 手机端同步成功的时间（毫秒）；<= 0 时用当前时间兜底。
     */
    suspend fun adoptSyncedTimetable(
        sourceUrl: String,
        payload: String,
        syncedAt: Long,
    ): Timetable? {
        if (payload.isBlank()) return null
        val effectiveSourceUrl = sourceUrl.trim().ifBlank { cachedSourceUrl }
        val response = CachedTimetable(
            sourceUrl = effectiveSourceUrl,
            payload = payload,
            syncedAt = syncedAt,
        ).toResponseOrNull() ?: return null
        val domain = runCatching {
            TimetableMapper.toDomain(response, sourceUrl = effectiveSourceUrl)
        }.getOrNull() ?: return null
        val effectiveSyncedAt = if (syncedAt > 0L) syncedAt else System.currentTimeMillis()

        cached = domain
        cachedPayload = payload
        cachedSourceUrl = effectiveSourceUrl
        lastSyncedAt = effectiveSyncedAt
        // 这份数据是刚同步过来的新数据，不是"从磁盘恢复的旧缓存"
        usingCache = false
        cache?.save(
            CachedTimetable(
                sourceUrl = effectiveSourceUrl,
                payload = payload,
                syncedAt = effectiveSyncedAt,
            ),
        )
        onCacheChanged?.invoke()
        return domain
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
