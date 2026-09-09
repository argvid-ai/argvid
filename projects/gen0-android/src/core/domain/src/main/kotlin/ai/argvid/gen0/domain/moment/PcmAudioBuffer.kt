package ai.argvid.gen0.domain.moment

import java.util.ArrayDeque

/** Owned little-endian PCM16, trimmed to the same zero-based timeline as the video. */
data class OwnedPcmAudio(val sampleRate: Int, val pcm16: ByteArray) {
    val durationUs: Long get() = pcm16.size / 2L * 1_000_000 / sampleRate
}

/** Bounded mono audio ring. All timestamps are capture times on CLOCK_MONOTONIC. */
class PcmAudioBuffer(
    private val sampleRate: Int = 48_000,
    private val retentionUs: Long = 20_000_000,
) {
    private data class Block(val startUs: Long, val bytes: ByteArray)
    private val blocks = ArrayDeque<Block>()
    private var byteCount = 0L
    init { require(sampleRate > 0 && retentionUs > 0) }

    @Synchronized fun append(startUs: Long, pcm16: ByteArray) {
        require(startUs >= 0 && pcm16.isNotEmpty() && pcm16.size % 2 == 0)
        require(pcm16.size / 2L * 1_000_000 / sampleRate <= retentionUs)
        val last = blocks.lastOrNull()
        if (last != null && startUs <= last.startUs) return
        blocks.addLast(Block(startUs, pcm16.copyOf()))
        byteCount += pcm16.size
        val endUs = startUs + pcm16.size / 2L * 1_000_000 / sampleRate
        while (blocks.firstOrNull()?.let {
                it.startUs + it.bytes.size / 2L * 1_000_000 / sampleRate <= endUs - retentionUs
            } == true || byteCount > retentionUs * sampleRate / 1_000_000 * 2 + pcm16.size) {
            val removed = blocks.removeFirst().bytes
            byteCount -= removed.size
            removed.fill(0)
        }
    }

    @Synchronized fun wipe() {
        blocks.forEach { it.bytes.fill(0) }
        blocks.clear()
        byteCount = 0
    }

    @Synchronized fun hasCoverage(startUs: Long, endUs: Long): Boolean =
        copyWindow(startUs, endUs, null)

    @Synchronized fun snapshot(startUs: Long, endUs: Long): OwnedPcmAudio? {
        if (endUs <= startUs || endUs - startUs > retentionUs) return null
        val output = ByteArray(((endUs - startUs) * sampleRate / 1_000_000).toInt() * 2)
        return if (copyWindow(startUs, endUs, output)) OwnedPcmAudio(sampleRate, output)
        else { output.fill(0); null }
    }

    private fun copyWindow(startUs: Long, endUs: Long, output: ByteArray?): Boolean {
        if (endUs <= startUs || endUs - startUs > retentionUs) return false
        val count = ((endUs - startUs) * sampleRate / 1_000_000).toInt()
        if (count <= 0) return false
        var filled = 0
        // Hardware timestamp rounding/jitter may leave at most 2 ms between blocks.
        val tolerance = (sampleRate / 500).coerceAtLeast(1)
        for (block in blocks) {
            val offset = Math.floorDiv((block.startUs - startUs) * sampleRate, 1_000_000).toInt()
            val end = (offset + block.bytes.size / 2).coerceAtMost(count)
            val start = maxOf(offset, filled, 0)
            if (end <= start) continue
            if (start - filled > tolerance) return false
            if (output != null) block.bytes.copyInto(output, start * 2, (start - offset) * 2, (end - offset) * 2)
            filled = end
            if (filled >= count) return true
        }
        return count - filled <= tolerance && filled > 0
    }
}
