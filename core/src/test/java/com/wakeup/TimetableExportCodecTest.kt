package com.wakeup

import com.wakeup.data.export.ImportResult
import com.wakeup.data.export.TimetableExportCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表导入导出文件的编解码。
 *
 * 这里锁的是"文件能被自己再读回来、不是本 App 的文件要失败、未来版本要提示升级、
 * 空文件/坏 JSON 不能崩"——导入是用户从文件管理器里随便选文件，容错必须稳。
 */
class TimetableExportCodecTest {

    private val payload = """{"code":200,"table":{"code":200,"term":{"total":"20","starttime":1787500800},"list":[]}}"""

    @Test
    fun `导出再导入能还原原始数据`() {
        val text = TimetableExportCodec.encode(payload, "https://sutjwt.sut.edu.cn/user/kb/table/token/100000", 1234L)
        val decoded = TimetableExportCodec.decode(text).getOrThrow()

        assertEquals(TimetableExportCodec.FORMAT, decoded.format)
        assertEquals(TimetableExportCodec.VERSION, decoded.version)
        assertEquals(1234L, decoded.exportedAt)
        assertEquals(payload, decoded.payload)
        assertEquals("https://sutjwt.sut.edu.cn/user/kb/table/token/100000", decoded.sourceUrl)
    }

    @Test
    fun `不是本 App 的文件要给出可读的失败原因`() {
        val error = TimetableExportCodec.decode("""{"foo":1}""").exceptionOrNull()
        assertTrue(error is TimetableExportCodec.ImportException)
        assertTrue(error!!.message.orEmpty().contains("不是 Wakeup 导出的课表文件"))
    }

    @Test
    fun `坏 JSON 不会抛异常`() {
        val error = TimetableExportCodec.decode("not json at all").exceptionOrNull()
        assertTrue(error is TimetableExportCodec.ImportException)
    }

    @Test
    fun `空文件被拒绝`() {
        val error = TimetableExportCodec.decode("   ").exceptionOrNull()
        assertTrue(error!!.message.orEmpty().contains("空"))
    }

    @Test
    fun `来自更高版本的文件提示升级`() {
        val text = """{"format":"wakeup-timetable","version":99,"payload":"abc"}"""
        val error = TimetableExportCodec.decode(text).exceptionOrNull()
        assertTrue(error!!.message.orEmpty().contains("更新版本"))
    }

    @Test
    fun `没有课表数据被拒绝`() {
        val text = """{"format":"wakeup-timetable","version":1,"payload":""}"""
        val error = TimetableExportCodec.decode(text).exceptionOrNull()
        assertTrue(error!!.message.orEmpty().contains("没有课表数据"))
    }

    @Test
    fun `默认文件名带日期`() {
        assertEquals("wakeup-课表-2026-10-29.json", TimetableExportCodec.defaultFileName("2026-10-29"))
    }
}
