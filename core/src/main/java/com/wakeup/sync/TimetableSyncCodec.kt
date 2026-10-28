package com.wakeup.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 一份待同步的课表数据。
 *
 * 字段和 Data Layer 里的 DataMap 一一对应；真正写进 DataMap 的是
 * [payloadGzipBase64]（已经压缩、编码过），而不是原始 JSON 字符串。
 */
data class TimetableSyncData(
    /** 课表来源链接；手表端拿它回填自己的设置。 */
    val sourceUrl: String,
    /** 同步时间（毫秒）。 */
    val syncedAt: Long,
    /** [TimetableSyncCodec.compressPayload] 的输出。 */
    val payloadGzipBase64: String,
)

/**
 * 课表同步数据的编解码：`原始 JSON → gzip → Base64`，反向亦然。
 *
 * 为什么要压缩：课表原始 JSON 通常有几十 KB 到上百 KB 的中文文本，而 Wearable Data Layer
 * 单条 DataItem **上限 100 KB**。GZIP 对这种高度重复的 JSON 一般能压到 1/5 以下，
 * Base64 再膨胀 1/3，最终通常只有几 KB 到十几 KB。
 *
 * 这个类是纯 JVM 实现（只用 `java.util.zip` / `java.util.Base64`），
 * 不依赖 Android，也不依赖 GMS —— 手机端和手表端共用同一份，单元测试可以直接跑。
 *
 * 约定：
 * - 无状态、无副作用，同样的输入永远得到同样的输出（Java 的 GZIP 头里 mtime 固定为 0）；
 * - **不打印任何日志**：payload 里是用户的真实课表，不进 logcat。
 */
object TimetableSyncCodec {

    /**
     * 编码后允许的最大 UTF-8 字节数。
     *
     * Data Layer 单条 DataItem 的硬上限是 100 KB，这里留 ~10% 的余量给 DataMap 的键名、
     * 类型标记等额外开销，超了就不推（宁可不推，也不要让 putDataItem 静默失败）。
     */
    const val MAX_ENCODED_BYTES = 90_000

    /**
     * 解压后允许的最大字节数。
     *
     * 正常情况下课表 JSON 只有几百 KB 以内；设上限是为了避免一条恶意/损坏的
     * "压缩炸弹"把手表内存吃光。
     */
    private const val MAX_DECODED_BYTES = 4 * 1024 * 1024

    private const val READ_CHUNK_BYTES = 8 * 1024

    /** 压缩并 Base64 编码。空串也会得到一个合法的空 gzip 流。 */
    fun compressPayload(payload: String): String {
        val raw = payload.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream(raw.size.coerceAtLeast(64))
        // close() 会写 gzip 尾部（CRC32 + 原始长度）；ByteArrayOutputStream 的 close() 是空操作
        GZIPOutputStream(output).use { gzip -> gzip.write(raw) }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    /**
     * 解开 [compressPayload] 的结果；任何异常（非法 Base64、不是 gzip、被截断、非 UTF-8、
     * 解压后超限）都返回 null，调用方按"这条数据不能用"处理即可。
     */
    fun decompressPayload(encoded: String): String? {
        if (encoded.isEmpty()) return null
        return try {
            val compressed = Base64.getDecoder().decode(encoded)
            if (compressed.isEmpty()) return null
            val decoded = GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
                readBounded(input)
            } ?: return null
            // 严格 UTF-8：遇到非法字节序列直接抛异常 → null，而不是悄悄替换成 U+FFFD
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    /** 编码结果是否塞得进一条 DataItem（按 UTF-8 字节数算，不是字符数）。 */
    fun fitsInDataLayer(encoded: String): Boolean =
        encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES

    /** 编码结果的 UTF-8 字节数，便于日志之外的地方判断余量。 */
    fun encodedSizeBytes(encoded: String): Int = encoded.toByteArray(Charsets.UTF_8).size

    /** 读到 EOF 或读满 [MAX_DECODED_BYTES] 为止；超限返回 null。 */
    private fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_DECODED_BYTES) return null
            output.write(chunk, 0, read)
        }
        return output.toByteArray()
    }
}
