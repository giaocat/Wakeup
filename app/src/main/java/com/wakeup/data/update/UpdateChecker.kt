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
    data class Failed(
        val message: String,
        /** 属于限流之类的临时情况：不应因此长时间不再检查。 */
        val transient: Boolean = false,
    ) : UpdateResult
}

/**
 * 检查 GitHub Releases 是否有新版本。
 *
 * **优先走 `releases.atom`**：它是网页资源，没有 API 的匿名限流
 * （API 只有 60 次/小时/IP，同一个出口 IP 或 NAT 后面的用户很容易撞满，
 * 一旦返回 403 更新检测就废了）。只有当 atom 解析不出任何版本时，
 * 才退回 REST API。
 *
 * 只做「检查 + 跳转发布页」，不做应用内下载安装 —— 这样不需要额外权限，
 * 也避开 Android 对未知来源安装的限制。
 */
class UpdateChecker(
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val apiUrl: String = BuildConfig.RELEASES_API_URL,
    private val releasesPageUrl: String = BuildConfig.RELEASES_PAGE_URL,
    /** 例如 `https://github.com/giaocat/Wakeup/releases.atom`。 */
    private val atomUrl: String = BuildConfig.RELEASES_ATOM_URL,
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        // 1) atom：无限流，且能拿到发布说明
        runCatching { fetchAtom() }.getOrNull()?.let { releases ->
            pickNewest(releases)?.let { return@withContext it }
            // 能取到 feed 但没有比当前更新的版本
            if (releases.isNotEmpty()) return@withContext UpdateResult.UpToDate
        }
        // 2) 退回 REST API
        runCatching { fetchApi() }.getOrNull()?.let { return@withContext it }
        UpdateResult.Failed("检查更新失败：网络异常或接口不可用")
    }

    /** 解析 atom feed，取出所有正式发布。 */
    private fun fetchAtom(): List<AtomRelease> {
        val request = Request.Builder()
            .url(atomUrl)
            .header("Accept", "application/atom+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val xml = response.body?.string().orEmpty()
            if (xml.isBlank()) return emptyList()
            return parseAtom(xml)
        }
    }

    /** 从一批发布里挑出版本最高的一个；没有比当前版本新的则返回 null。 */
    private fun pickNewest(releases: List<AtomRelease>): UpdateResult? {
        // List<Int> 不是 Comparable，用 VersionCompare 自己比
        val best = releases
            .filter { it.version.isNotBlank() }
            .reduceOrNull { acc, item ->
                if (VersionCompare.compare(item.version, acc.version) > 0) item else acc
            }
            ?: return null
        if (!VersionCompare.isNewer(best.version, currentVersion)) return null
        return UpdateResult.Available(
            UpdateInfo(
                version = best.version,
                releaseUrl = best.url.ifBlank { releasesPageUrl },
                releaseName = best.title.ifBlank { null },
                notes = best.content.takeIf { it.isNotBlank() },
            ),
        )
    }

    /** 退回 REST API（受限流影响，只作后备）。 */
    private fun fetchApi(): UpdateResult {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 429) {
                val rateLimited = response.code == 429 ||
                    response.header("retry-after") != null ||
                    response.header("x-ratelimit-remaining") == "0"
                return if (rateLimited) {
                    UpdateResult.Failed("检查过于频繁，请稍后再试", transient = true)
                } else {
                    UpdateResult.Failed("检查更新失败（HTTP ${response.code}）")
                }
            }
            if (!response.isSuccessful) {
                return UpdateResult.Failed("检查更新失败（HTTP ${response.code}）")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return UpdateResult.Failed("检查更新失败：响应为空")

            val release = json.decodeFromString(GithubRelease.serializer(), body)
            if (release.tag_name.isBlank()) {
                return UpdateResult.Failed("检查更新失败：发布信息不完整")
            }
            // 预发布/草稿不作为更新推送
            if (release.draft || release.prerelease) return UpdateResult.UpToDate

            val latest = release.tag_name.removePrefix("v").removePrefix("V")
            if (!VersionCompare.isNewer(latest, currentVersion)) return UpdateResult.UpToDate
            return UpdateResult.Available(
                UpdateInfo(
                    version = latest,
                    releaseUrl = release.html_url.ifBlank { releasesPageUrl },
                    releaseName = release.name,
                    notes = release.body?.trim()?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    companion object {
        private const val USER_AGENT = "Wakeup-Android"

        private val ENTRY_REGEX = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
        private val ID_REGEX =
            Regex("<id>tag:github\\.com,2008:Repository/\\d+/([^<]+)</id>")
        private val TITLE_REGEX = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        private val LINK_REGEX = Regex("<link[^>]*rel=\"alternate\"[^>]*href=\"([^\"]+)\"")
        private val CONTENT_REGEX =
            Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)

        /**
         * 极简 atom 解析：只取需要的四个字段。
         *
         * 不引 XML 解析库 —— 发布说明里可能带 HTML，正则取文本比专门装个解析器省事。
         * 解析结果由单元测试锁住（喂真实 feed 片段）。
         */
        internal fun parseAtom(xml: String): List<AtomRelease> =
            ENTRY_REGEX.findAll(xml).mapNotNull { match ->
                val entry = match.groupValues[1]
                val rawTag = ID_REGEX.find(entry)?.groupValues?.get(1)?.trim().orEmpty()
                if (rawTag.isBlank()) return@mapNotNull null
                AtomRelease(
                    version = rawTag.removePrefix("v").removePrefix("V"),
                    title = decodeEntities(TITLE_REGEX.find(entry)?.groupValues?.get(1).orEmpty()),
                    url = LINK_REGEX.find(entry)?.groupValues?.get(1).orEmpty(),
                    content = decodeEntities(
                        CONTENT_REGEX.find(entry)?.groupValues?.get(1).orEmpty(),
                    ).trim(),
                )
            }.toList()

        /**
         * 把发布说明转成纯文本：**先还原 HTML 实体，再去掉标签**。
         *
         * 顺序很关键。atom feed 的 `<content>` 是被转义的 HTML
         * （`&lt;p&gt;…&lt;/p&gt;`），实体还原之后标签才会"现身"；如果先剥标签，
         * 这些标签还没出现，就会残留在结果里。该行为由单元测试锁住。
         *
         * 已知取舍：`&lt;` 既可能是作者想要的字面量尖括号，也可能是转义后的真标签，
         * 二者在解码后**无法区分**。这里选择让发布说明保持干净可读
         * （`&lt;p&gt;X&lt;/p&gt;` → `X`），代价是文本里的字面量尖括号会被一并去掉。
         * 该字段仅用于弹窗展示，不做任何解析，所以这个取舍是安全的。
         */
        internal fun decodeEntities(raw: String): String {
            if (raw.isBlank()) return ""
            val decoded = StringBuilder(raw.length)
            var index = 0
            while (index < raw.length) {
                val char = raw[index]
                if (char == '&') {
                    val semi = raw.indexOf(';', index)
                    if (semi in (index + 1)..(index + 10)) {
                        val name = raw.substring(index + 1, semi)
                        val resolved = HTML_ENTITIES[name] ?: if (name.startsWith("#")) {
                            val code = name.drop(1).let {
                                if (it.startsWith("x") || it.startsWith("X")) {
                                    it.drop(1).toIntOrNull(16)
                                } else {
                                    it.toIntOrNull()
                                }
                            }
                            code?.let { String(Character.toChars(it)) }
                        } else {
                            null
                        }
                        if (resolved != null) {
                            decoded.append(resolved)
                            index = semi + 1
                            continue
                        }
                    }
                }
                decoded.append(char)
                index++
            }
            return decoded.toString()
                .replace(TAG_REGEX, "")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex(" ?\\n ?"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }

        private val TAG_REGEX = Regex("<[^>]+>")

        private val HTML_ENTITIES: Map<String, String> = mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
        )
    }
}

/** atom feed 里的一个发布条目。 */
internal data class AtomRelease(
    val version: String,
    val title: String,
    val url: String,
    val content: String,
)
