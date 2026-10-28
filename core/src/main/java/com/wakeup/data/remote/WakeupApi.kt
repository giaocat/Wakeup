package com.wakeup.data.remote

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/**
 * 课表页面接口。
 *
 * 服务端把整份课表内联在 HTML 中，所以这里以纯文本方式获取页面，
 * 再由 [TimetablePayloadParser] 抽取内联 JSON。
 */
interface WakeupApi {

    @GET
    suspend fun timetableHtml(@Url url: String): String
}

object WakeupApiFactory {

    /** 默认课表服务地址；只填 token 时用它补全链接。 */
    const val DEFAULT_BASE_URL: String = "https://sutjwt.sut.edu.cn"

    /** 输入框里的占位示例，仅用于提示格式，不是任何人的真实课表。 */
    const val TIMETABLE_URL_EXAMPLE: String = "$DEFAULT_BASE_URL/user/kb/table/token/100000"

    fun create(
        baseUrl: String = DEFAULT_BASE_URL,
        debugLogging: Boolean = false,
    ): WakeupApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                if (debugLogging) {
                    addInterceptor(
                        okhttp3.logging.HttpLoggingInterceptor().apply {
                            level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl.ensureTrailingSlash())
            .client(client)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(WakeupApi::class.java)
    }
}

/** 统一补齐结尾斜杠，供 Retrofit baseUrl 与页面地址拼接复用。 */
internal fun String.ensureTrailingSlash(): String =
    if (endsWith("/")) this else "$this/"

/**
 * 用 token 拼出完整课表链接。
 *
 * 手表端（`:wear`）没有输入框，也不该在表盘上敲一长串 URL，
 * 所以那里只让用户填数字 token，这里负责拼成和手机端一样的链接。
 */
fun tokenToTimetableUrl(
    token: String,
    baseUrl: String = WakeupApiFactory.DEFAULT_BASE_URL,
): String = "${baseUrl.ensureTrailingSlash()}user/kb/table/token/${token.trim().trim('/')}"

/** 从完整链接里取回 token；取不到时返回空串（供手表端回显当前 token）。 */
fun timetableUrlToToken(url: String): String {
    val value = url.trim()
    if (value.isEmpty()) return ""
    val afterToken = value.substringAfter("/token/", missingDelimiterValue = "")
    return afterToken.trim('/').substringBefore('?').substringBefore('#')
}
