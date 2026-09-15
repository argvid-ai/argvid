package ai.argvid.gen0.gimbal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The JSON literals below mirror the FF04 events the PR #5 firmware emits
 * (projects/gen0-gimbal/src/firmware/esp32-firmware/cmd_handler.cpp:
 * _notifyScanResult / _notifyResult and the query notify path), as observed in the
 * 2026-09-13 read-only device run (scan_result and query_result payloads).
 */
class F32cResponsesTest {
    @Test
    fun parsesFirmwareShapedScanResult() {
        val result = F32cResponses.scanResult(
            """{"event":"scan_result","ok":true,"motors":[{"id":1,"volt":12.04},{"id":2,"volt":12.07}]}""",
        )
        assertEquals(
            F32cScanResult(true, listOf(F32cScanMotor(1, 12.04), F32cScanMotor(2, 12.07))),
            result,
        )
    }

    @Test
    fun parsesFailedScanWithEmptyMotors() {
        val result = F32cResponses.scanResult("""{"event":"scan_result","ok":false,"motors":[]}""")
        assertEquals(F32cScanResult(false, emptyList()), result)
    }

    @Test
    fun toleratesReorderedAndUnknownFields() {
        val result = F32cResponses.scanResult(
            """{"motors":[{"volt":11.5,"id":3}],"ok":true,"note":[1,2],"event":"scan_result"}""",
        )
        assertEquals(F32cScanResult(true, listOf(F32cScanMotor(3, 11.5))), result)
    }

    @Test
    fun voltMayBeOmitted() {
        val result = F32cResponses.scanResult("""{"event":"scan_result","ok":true,"motors":[{"id":7}]}""")
        assertEquals(F32cScanResult(true, listOf(F32cScanMotor(7, null))), result)
    }

    @Test
    fun skipsMotorsOutsideTheProtocolAddressRange() {
        val result = F32cResponses.scanResult(
            """{"event":"scan_result","ok":true,"motors":[{"id":0},{"id":128},{"id":"x"},{"id":16}]}""",
        )
        assertEquals(listOf(F32cScanMotor(16, null)), result?.motors)
    }

    @Test
    fun parsesFirmwareShapedQueryResult() {
        val result = F32cResponses.queryResult(
            """{"event":"query_result","addr":1,"type":"voltage","value":12.04,"text":"12.04 V"}""",
        )
        assertEquals(F32cQueryResult(addr = 1, type = "voltage", value = 12.04, text = "12.04 V"), result)
    }

    @Test
    fun queryResultToleratesMissingValueAndText() {
        val result = F32cResponses.queryResult("""{"event":"query_result","addr":2,"type":"speed"}""")
        assertEquals(F32cQueryResult(addr = 2, type = "speed", value = null, text = null), result)
    }

    @Test
    fun ignoresOtherFf04Events() {
        assertNull(
            F32cResponses.scanResult("""{"event":"cmd_result","ok":true,"msg":"查询失败: 帧太短 (0 字节)"}"""),
        )
        assertNull(F32cResponses.scanResult("""{"event":"log","lines":["[TX] 7A 01 04 00 00 04 B4 CF 7B"]}"""))
        assertNull(F32cResponses.scanResult("""{"event":"gimbal_state","pan":1,"tilt":2}"""))
        assertNull(
            F32cResponses.queryResult("""{"event":"scan_result","ok":true,"motors":[{"id":1,"volt":12.04}]}"""),
        )
        assertNull(F32cResponses.queryResult("""{"event":"cmd_result","ok":false,"msg":"x"}"""))
    }

    @Test
    fun returnsNullForMalformedOrIncompletePayloads() {
        assertNull(F32cResponses.scanResult("""{"event":"scan_result","ok":tr"""))
        assertNull(F32cResponses.scanResult("scan_result"))
        assertNull(F32cResponses.scanResult(""))
        assertNull(F32cResponses.scanResult("[]"))
        assertNull(F32cResponses.scanResult("""{"event":"scan_result"}"""))
        assertNull(F32cResponses.scanResult("""{"event":"scan_result","ok":true}"""))
    }

    @Test
    fun reassembledFragmentsDecodeAsScanResult() {
        // One scan_result split into two F3 2C fragments, reassembled and then decoded.
        val json = """{"event":"scan_result","ok":true,"motors":[{"id":1,"volt":12.04},{"id":2,"volt":12.07}]}"""
            .toByteArray(Charsets.UTF_8)
        val splitAt = json.size / 2
        val reassembler = F32cFragmentReassembler()
        assertNull(reassembler.accept(fragment(messageId = 1, offset = 0, total = json.size, json.copyOfRange(0, splitAt)), nowMs = 0))
        val reassembled = reassembler.accept(
            fragment(messageId = 1, offset = splitAt, total = json.size, json.copyOfRange(splitAt, json.size)),
            nowMs = 50,
        )
        assertArrayEquals(json, reassembled)
        assertEquals(
            F32cScanResult(true, listOf(F32cScanMotor(1, 12.04), F32cScanMotor(2, 12.07))),
            F32cResponses.scanResult(reassembled!!.toString(Charsets.UTF_8)),
        )
    }

    @Test
    fun plainJsonFragmentWithoutMagicPassesThroughUnchanged() {
        val plain = """{"event":"query_result","addr":3,"type":"voltage","value":9.5}""".toByteArray()
        val reassembler = F32cFragmentReassembler()
        assertArrayEquals(plain, reassembler.accept(plain, nowMs = 0))
    }

    private fun fragment(messageId: Int, offset: Int, total: Int, chunk: ByteArray): ByteArray =
        byteArrayOf(0xF3.toByte(), 0x2C.toByte()) +
            u16(messageId) + u16(offset) + u16(total) + chunk

    private fun u16(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
    )
}
