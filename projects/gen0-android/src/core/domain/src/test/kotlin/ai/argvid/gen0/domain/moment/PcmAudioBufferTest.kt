package ai.argvid.gen0.domain.moment

import org.junit.Assert.*
import org.junit.Test

class PcmAudioBufferTest {
    @Test fun trimsToTheVideoWindowAndOwnsItsSamples() {
        val buffer = PcmAudioBuffer(sampleRate = 1_000, retentionUs = 2_000_000)
        val first = ByteArray(2_000) { 1 }
        buffer.append(10_000_000, first)
        first.fill(9)
        buffer.append(11_000_000, ByteArray(2_000) { 2 })
        val clip = checkNotNull(buffer.snapshot(10_500_000, 11_500_000))
        assertEquals(2_000, clip.pcm16.size)
        assertTrue(clip.pcm16.take(1_000).all { it == 1.toByte() })
        assertTrue(clip.pcm16.drop(1_000).all { it == 2.toByte() })
        buffer.wipe()
        assertNull(buffer.snapshot(10_500_000, 11_500_000))
        assertEquals(1.toByte(), clip.pcm16.first())
    }

    @Test fun rejectsMissingAudioInsteadOfReturningASilentClip() {
        val buffer = PcmAudioBuffer(sampleRate = 1_000)
        buffer.append(0, ByteArray(1_000))
        buffer.append(600_000, ByteArray(1_000))
        assertNull(buffer.snapshot(0, 1_000_000))
        assertNull(buffer.snapshot(0, 1_500_000))
    }

    @Test fun oldAudioIsEvictedAndInvalidSamplesAreRejected() {
        val buffer = PcmAudioBuffer(sampleRate = 1_000, retentionUs = 1_000_000)
        repeat(4) { buffer.append(it * 1_000_000L, ByteArray(2_000)) }
        assertNull(buffer.snapshot(0, 1_000_000))
        assertNotNull(buffer.snapshot(3_000_000, 4_000_000))
        assertThrows(IllegalArgumentException::class.java) { buffer.append(4_000_000, byteArrayOf(1)) }
    }
}
