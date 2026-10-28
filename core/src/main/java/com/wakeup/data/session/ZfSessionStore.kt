package com.wakeup.data.session

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 教务系统（正方）的登录会话。
 *
 * 新教务系统 `jwxt.sut.edu.cn` 必须在登录态下才能拿到课表，而它的登录页带**图形验证码**
 * 和扫码登录，没法在后台用账号密码静默登录。所以流程是：
 *
 * 1. 应用内打开学校登录页，用户自己输账号 / 验证码（或扫码）；
 * 2. 登录成功后，从 WebView 的 CookieManager 里取出 Cookie 头，存到这里；
 * 3. 之后抓课表页时带上这串 Cookie；失效（被重定向回登录页）时提示用户重新登录。
 *
 * 安全说明：这里保存的是**会话 Cookie**，等价于"当前登录凭证"，和课表 token 一样属于敏感数据，
 * 但只存在应用私有目录（`filesDir/wakeup/zf_session.json`），不出设备，也不写到日志里。
 */
@Serializable
data class ZfSession(
    /** 直接作为 `Cookie:` 请求头发出去的整串 Cookie（`JSESSIONID=...; route=...`）。 */
    val cookie: String = "",
    /** 登录成功（写入会话）的时间，毫秒。 */
    val savedAt: Long = 0L,
    /** 登录时使用的教务系统地址。 */
    val baseUrl: String = "",
) {
    val isValid: Boolean get() = cookie.isNotBlank()

    /** 会话是否已经老到该提醒重新登录（默认 7 天；校方实际过期时间可能更短）。 */
    fun isStale(now: Long = System.currentTimeMillis(), maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean =
        savedAt <= 0L || now - savedAt > maxAgeMs

    companion object {
        const val DEFAULT_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * 会话的本地存储（`filesDir/wakeup/zf_session.json`）。
 *
 * 与课表缓存一样：只存一个私有小文件，同步读写（文件只有几百字节），
 * 损坏时直接丢弃并让用户重新登录，而不是带着坏 Cookie 去请求。
 */
class ZfSessionStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {

    private val file: File = File(File(context.filesDir, "wakeup"), "zf_session.json")

    fun loadNow(): ZfSession? {
        if (!file.exists()) return null
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return null
            json.decodeFromString(ZfSession.serializer(), text).takeIf { it.isValid }
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    suspend fun save(session: ZfSession) {
        if (!session.isValid) return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(ZfSession.serializer(), session), Charsets.UTF_8)
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
                temp.delete()
            }
        }
    }

    suspend fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }
}
