package ai.argvid.gen0.gimbal

/** Reassembles the project-local F3 2C notification format without interpreting JSON. */
class F32cFragmentReassembler(
    private val maxBytes: Int = F32cBleContract.maxReassembledBytes,
    private val timeoutMs: Long = F32cBleContract.fragmentTimeoutMs,
) {
    private data class Pending(
        val total: Int,
        val bytes: ByteArray,
        val covered: BooleanArray,
        var received: Int,
        var lastUpdatedMs: Long,
    )

    private val pending = mutableMapOf<Int, Pending>()

    /** Returns a complete payload, or null while a fragmented payload is incomplete/invalid. */
    fun accept(packet: ByteArray, nowMs: Long): ByteArray? {
        require(nowMs >= 0)
        expire(nowMs)
        if (packet.size < HEADER_BYTES || packet[0] != MAGIC_0 || packet[1] != MAGIC_1) {
            return packet.copyOf()
        }
        val messageId = u16(packet, 2)
        val offset = u16(packet, 4)
        val total = u16(packet, 6)
        val payload = packet.copyOfRange(HEADER_BYTES, packet.size)
        if (total == 0 || total > maxBytes || offset > total || payload.size > total - offset) {
            pending.remove(messageId)
            return null
        }
        val entry = pending.getOrPut(messageId) {
            Pending(total, ByteArray(total), BooleanArray(total), 0, nowMs)
        }
        if (entry.total != total) {
            pending.remove(messageId)
            return null
        }
        payload.copyInto(entry.bytes, destinationOffset = offset)
        for (index in payload.indices) {
            if (!entry.covered[offset + index]) {
                entry.covered[offset + index] = true
                entry.received++
            }
        }
        entry.lastUpdatedMs = nowMs
        if (entry.received < total) return null
        pending.remove(messageId)
        return entry.bytes.copyOf()
    }

    private fun expire(nowMs: Long) {
        pending.entries.removeIf { nowMs - it.value.lastUpdatedMs > timeoutMs }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val HEADER_BYTES = 8
        const val MAGIC_0: Byte = 0xF3.toByte()
        const val MAGIC_1: Byte = 0x2C.toByte()
    }
}
