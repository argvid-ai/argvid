package ai.argvid.gen0.gimbal

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class F32cFragmentReassemblerTest {
    @Test
    fun reassemblesOutOfOrderFragments() {
        val reassembler = F32cFragmentReassembler()
        val payload = "{\"cmd_result\":{\"ok\":true}}".toByteArray(StandardCharsets.UTF_8)
        val split = payload.size / 2
        assertNull(reassembler.accept(fragment(7, split, payload.size, payload.copyOfRange(split, payload.size)), 10))
        assertArrayEquals(payload, reassembler.accept(fragment(7, 0, payload.size, payload.copyOfRange(0, split)), 11))
    }

    @Test
    fun rejectsOversizedOrOutOfBoundsFragment() {
        val reassembler = F32cFragmentReassembler()
        assertNull(reassembler.accept(fragment(1, 0, 9000, byteArrayOf(1)), 0))
        assertNull(reassembler.accept(fragment(2, 4, 3, byteArrayOf(1)), 0))
    }

    @Test
    fun expiresIncompleteMessage() {
        val reassembler = F32cFragmentReassembler(timeoutMs = 3_000)
        assertNull(reassembler.accept(fragment(9, 0, 4, byteArrayOf(1, 2)), 0))
        assertNull(reassembler.accept(fragment(9, 2, 4, byteArrayOf(3, 4)), 3_001))
    }

    private fun fragment(id: Int, offset: Int, total: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            0xF3.toByte(), 0x2C.toByte(),
            id.toByte(), (id shr 8).toByte(),
            offset.toByte(), (offset shr 8).toByte(),
            total.toByte(), (total shr 8).toByte(),
        ) + payload
}
