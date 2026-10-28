package com.wakeup.data.cache

import android.content.Context
import com.wakeup.data.model.TimetableResponse
import com.wakeup.data.remote.TimetablePayloadParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地缓存的课表数据。
 *
 * 直接保存从页面抽取出来的**原始 JSON 字符串**，而不是解析后的领域模型：
 * 这样缓存依赖面最小（不需要为领域模型维护序列化），页面结构变化时也只会走一次
 * "重新解析 → 成功覆盖 / 失败丢弃缓存" 的流程。
 */
@Serializable
data class CachedTimetable(
    /** 缓存对应的课表页面地址，用于判断缓存是否属于当前设置。 */
    val sourceUrl: String = "",
    /** 页面里抽取出来的原始 JSON。 */
    val payload: String = "",
    /** 该地址最后一次同步成功的时间（毫秒）。 */
    val syncedAt: Long = 0L,
) {
    val isValid: Boolean get() = payload.isNotBlank()
}

/**
 * 基于文件的课表缓存（`filesDir/wakeup/timetable_cache.json`）。
 *
 * 使用方式：启动时 [load]，同步成功后 [save]，同步失败时把 [markSynced] 的结果当兜底数据展示。
 */
class TimetableCache(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {

    private val file: File = File(File(context.filesDir, "wakeup"), "timetable_cache.json")

    /** 读取缓存；文件不存在或内容损坏时返回 null（并顺手清掉坏文件）。 */
    suspend fun load(): CachedTimetable? = loadNow()

    /**
     * 同步读取缓存，供 ViewModel 初始化时使用。
     *
     * 缓存是本地小文件（几十 KB），初始化时同步读取可以避免首屏先闪空状态。
     */
    fun loadNow(): CachedTimetable? {
        if (!file.exists()) return null
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return null
            json.decodeFromString(CachedTimetable.serializer(), text)
                .takeIf { it.isValid }
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    /** 写入缓存；先写临时文件再改名，避免写一半被中断留下坏文件。 */
    suspend fun save(cached: CachedTimetable) {
        if (!cached.isValid) return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(CachedTimetable.serializer(), cached), Charsets.UTF_8)
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                temp.delete()
            }
        }
    }

    /** 仅更新同步时间（同步失败但内容未变时用，避免误导"最后同步"时间）。 */
    suspend fun markSynced(cached: CachedTimetable, syncedAt: Long): CachedTimetable =
        cached.copy(syncedAt = syncedAt).also { save(it) }

    suspend fun clear() {
        clearNow()
    }

    /** 同步删除缓存文件。 */
    fun clearNow() {
        runCatching { if (file.exists()) file.delete() }
    }

    fun exists(): Boolean = file.exists()

    /** 缓存文件大小，用于在设置页展示。 */
    fun sizeBytes(): Long = if (file.exists()) file.length() else 0L
}

/** 用缓存里的原始 JSON 反序列化出接口模型；解析失败返回 null。 */
fun CachedTimetable.toResponseOrNull(): TimetableResponse? = try {
    TimetablePayloadParser.parsePayload(payload)
} catch (_: Exception) {
    null
}
