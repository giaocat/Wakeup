package com.wakeup.data.update

import com.wakeup.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 发现新版本时返回的信息。 */
data class UpdateInfo(
    /** 例如 `1.0.1`（已去掉开头的 `v`）。 */
    val version: String,
    /** 发布页地址，用户点「去下载」时打开。 */
    val releaseUrl: String,
    val releaseName: String?,
    val notes: String?,
) {
    val versionLabel: String get() = "v$version"
}

/** GitHub Releases `latest` 接口里我们需要的字段。 */
@Serializable
private data class GithubRelease(
    val tag_name: String = "",
    val name: String? = null,
    val body: String? = null,
    val html_url: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

/**
 * 版本比较：把 `v1.2.3`、`1.2`、`1.2.3-beta1` 归一成数字段再逐段比较。
 *
 * 非数字后缀（如 `-beta1`）按**预发布**处理，同一个三段版本号下小于正式版。
 */
object VersionCompare {

    fun parse(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        val core = cleaned.substringBefore('-').substringBefore('+')
        if (core.isBlank()) return emptyList()
        return core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    private fun isPreRelease(raw: String): Boolean {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        return cleaned.contains('-') || cleaned.contains('+')
    }

    /** 大于返回正数，相等返回 0，小于返回负数。 */
    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val size = maxOf(pa.size, pb.size)
        for (index in 0 until size) {
            val va = pa.getOrElse(index) { 0 }
            val vb = pb.getOrElse(index) { 0 }
            // 用 compare 而不是相减：段值极大时相减会整型溢出、把符号弄反
            if (va != vb) return Integer.compare(va, vb)
        }
        // 数字段相同时，预发布版本小于正式版本
        val preA = isPreRelease(a)
        val preB = isPreRelease(b)
        return when {
            preA && !preB -> -1
            !preA && preB -> 1
            else -> 0
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}

/** 「检查更新」的结果。 */
sealed interface UpdateResult {
    /** 有新版本。 */
    data class Available(val info: UpdateInfo) : UpdateResult

    /** 已经是最新。 */
    data object UpToDate : UpdateResult

    /** 检查失败（断网、接口限流等）。 */
    data class Failed(val message: String) : UpdateResult
}

/**
 * 读取 GitHub Releases 判断是否有新版本。
 *
 * 只做「检查 + 跳转发布页」，不做应用内下载安装 —— 这样不需要额外权限，
 * 也避开 Android 对未知来源安装的限制。
 */
class UpdateChecker(
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val apiUrl: String = BuildConfig.RELEASES_API_URL,
    private val releasesPageUrl: String = BuildConfig.RELEASES_PAGE_URL,
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Wakeup-Android")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateResult.Failed(
                        "检查更新失败（HTTP ${response.code}）",
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext UpdateResult.Failed("检查更新失败：响应为空")

                val release = json.decodeFromString(GithubRelease.serializer(), body)
                if (release.tag_name.isBlank()) {
                    return@withContext UpdateResult.Failed("检查更新失败：发布信息不完整")
                }
                if (release.draft || release.prerelease) {
                    // 预发布/草稿不作为更新推送
                    return@withContext UpdateResult.UpToDate
                }

                val latest = release.tag_name.removePrefix("v").removePrefix("V")
                if (!VersionCompare.isNewer(latest, currentVersion)) {
                    return@withContext UpdateResult.UpToDate
                }
                UpdateResult.Available(
                    UpdateInfo(
                        version = latest,
                        releaseUrl = release.html_url.ifBlank { releasesPageUrl },
                        releaseName = release.name,
                        notes = release.body?.trim()?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        } catch (error: Exception) {
            UpdateResult.Failed(error.message ?: "检查更新失败：网络异常")
        }
    }
}
