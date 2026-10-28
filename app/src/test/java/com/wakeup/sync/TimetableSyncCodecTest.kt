package com.wakeup.sync

import com.wakeup.sync.TimetableSyncCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random
import java.util.zip.GZIPOutputStream

/**
 * 课表同步编解码的单元测试。
 *
 * 这里锁住几件"坏了就会静默丢数据"的事：
 * - 一份多 KB 的中文 JSON 压缩后必须能**逐字**还原；
 * - 任何损坏的输入（非法 Base64、不是 gzip、被截断、非 UTF-8）都必须返回 null 而不是抛异常；
 * - 编码结果必须在 Data Layer 的 90 KB 预算内，超了就不许推。
 */
class TimetableSyncCodecTest {

    /** 构造一份结构同真实课表、含大量中文的多 KB JSON。 */
    private fun chinesePayload(entries: Int = 120): String = buildString {
        append("{\"code\":200,\"user\":\"测试同学\",\"table\":{\"code\":200,")
        append("\"term\":{\"total\":\"20\",\"starttime\":1787500800,\"term\":\"2026-2027-1\"},\"list\":[")
        for (index in 0 until entries) {
            if (index > 0) append(',')
            append("{\"JSXM\":\"张老师\",\"KCMC\":\"高等数学A（一）第${index + 1}组\",")
            append("\"JX0404ID\":\"K${1000 + index}\",")
            append("\"INFO\":[{\"KCSJMX\":[\"101\",\"102\"],")
            append("\"KKZCMX\":[\"1\",\"2\",\"3\",\"4\",\"5\"],")
            append("\"JX0601MC\":\"教学楼A-${index + 1}\",")
            append("\"KKZC\":\"1-5\",\"KCSJ\":\"10102\"}]}")
        }
        append("]}}")
    }

    /** 不可压缩的伪随机文本（用固定种子，结果稳定）。 */
    private fun noisePayload(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = Random(20261028L)
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    // ---- 往返 ----

    @Test
    fun `多 KB 中文 payload 压缩后能逐字还原`() {
        val payload = chinesePayload()
        assertTrue(
            "示例 payload 应当是多 KB 级别，而不是空壳",
            payload.toByteArray(Charsets.UTF_8).size > 8_000,
        )

        val encoded = TimetableSyncCodec.compressPayload(payload)

        assertEquals(payload, TimetableSyncCodec.decompressPayload(encoded))
        assertTrue("重复结构的 JSON 应当被明显压缩", encoded.length < payload.length)
        assertTrue("正常课表必须塞得进一条 DataItem", TimetableSyncCodec.fitsInDataLayer(encoded))
    }

    @Test
    fun `同样的输入必须得到同样的输出`() {
        // 手表端可能对同一份数据反复重试；编码器不能引入时间戳/随机数
        val payload = chinesePayload(entries = 10)
        assertEquals(
            TimetableSyncCodec.compressPayload(payload),
            TimetableSyncCodec.compressPayload(payload),
        )
    }

    @Test
    fun `空 payload 也能往返`() {
        val encoded = TimetableSyncCodec.compressPayload("")
        assertEquals("", TimetableSyncCodec.decompressPayload(encoded))
        assertTrue(TimetableSyncCodec.fitsInDataLayer(encoded))
    }

    // ---- 损坏输入 ----

    @Test
    fun `非法 base64 返回 null`() {
        assertNull(TimetableSyncCodec.decompressPayload("这不是 base64!!!"))
        assertNull(TimetableSyncCodec.decompressPayload("payload with spaces"))
        assertNull(TimetableSyncCodec.decompressPayload(""))
    }

    @Test
    fun `合法 base64 但不是 gzip 时返回 null`() {
        // "hello" 的 Base64：解码能过，但 gzip 头不对
        assertNull(TimetableSyncCodec.decompressPayload("aGVsbG8="))
    }

    @Test
    fun `截断的 gzip 数据返回 null`() {
        val encoded = TimetableSyncCodec.compressPayload(chinesePayload())
        val raw = Base64.getDecoder().decode(encoded)
        assertTrue("示例数据应足够长，截断才有意义", raw.size > 64)

        val truncated = Base64.getEncoder().encodeToString(raw.copyOf(raw.size - 24))
        assertNull(TimetableSyncCodec.decompressPayload(truncated))
    }

    @Test
    fun `非 UTF-8 内容返回 null`() {
        // 0xFF 在 UTF-8 里永远非法；严格解码应当拒绝，而不是替换成 U+FFFD
        val invalid = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x80.toByte())
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(invalid) }
        val encoded = Base64.getEncoder().encodeToString(output.toByteArray())

        assertNull(TimetableSyncCodec.decompressPayload(encoded))
    }

    // ---- 大小预算 ----

    @Test
    fun `fitsInDataLayer 按 UTF-8 字节数判断边界`() {
        assertTrue("空串当然放得下", TimetableSyncCodec.fitsInDataLayer(""))
        assertTrue(
            "正好等于上限应当放得下",
            TimetableSyncCodec.fitsInDataLayer("A".repeat(TimetableSyncCodec.MAX_ENCODED_BYTES)),
        )
        assertFalse(
            "超出一个字节就不行",
            TimetableSyncCodec.fitsInDataLayer("A".repeat(TimetableSyncCodec.MAX_ENCODED_BYTES + 1)),
        )
        assertEquals(
            TimetableSyncCodec.MAX_ENCODED_BYTES,
            TimetableSyncCodec.encodedSizeBytes("A".repeat(TimetableSyncCodec.MAX_ENCODED_BYTES)),
        )

        // 多字节字符要按字节算：字符数没超但字节数超了，同样不行
        val wide = "表".repeat(TimetableSyncCodec.MAX_ENCODED_BYTES / 3 + 1)
        assertTrue("每个汉字 3 字节", wide.length < TimetableSyncCodec.MAX_ENCODED_BYTES)
        assertFalse(TimetableSyncCodec.fitsInDataLayer(wide))
    }

    @Test
    fun `压缩后仍超过 90KB 的 payload 被拒绝`() {
        val payload = noisePayload(140_000)
        val encoded = TimetableSyncCodec.compressPayload(payload)

        assertEquals(payload, TimetableSyncCodec.decompressPayload(encoded))
        assertTrue(
            "噪声数据压不下去，编码后必然超过上限",
            TimetableSyncCodec.encodedSizeBytes(encoded) > TimetableSyncCodec.MAX_ENCODED_BYTES,
        )
        assertFalse(TimetableSyncCodec.fitsInDataLayer(encoded))
    }
}
