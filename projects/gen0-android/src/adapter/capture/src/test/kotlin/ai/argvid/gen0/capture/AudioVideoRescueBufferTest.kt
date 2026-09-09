package ai.argvid.gen0.capture

import ai.argvid.gen0.domain.moment.PcmAudioBuffer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AudioVideoRescueBufferTest {
    @Test fun rescueRequiresBothTracksAndWipePreventsReuseAcrossSessions() = runTest {
        val video = RescueProxyBuffer(ProxyConfiguration.p540())
        val audio = PcmAudioBuffer(sampleRate = 1_000)
        var microphoneRunning = true
        val combined = AudioVideoRescueBuffer(video, audio) { microphoneRunning }
        repeat(121) { video.append(ProxyFrame(it * 125_000L, 960, 540, byteArrayOf(1))) }
        assertFalse(combined.hasCompleteCoverage(15_000_000, 15_000_000))
        assertFalse(combined.ownedMomentSnapshot(15_000_000, 15_000_000).coverageComplete)
        audio.append(0, ByteArray(30_000) { 3 })
        assertTrue(combined.hasCompleteCoverage(15_000_000, 15_000_000))
        val snapshot = combined.ownedMomentSnapshot(15_010_000, 15_000_000)
        assertTrue(snapshot.coverageComplete)
        assertEquals(15_000_000, snapshot.audio!!.durationUs)
        microphoneRunning = false
        assertFalse(combined.ownedMomentSnapshot(15_010_000, 15_000_000).coverageComplete)
        combined.wipe()
        assertEquals(0, combined.frameCount())
        assertNull(audio.snapshot(0, 15_000_000))
        assertEquals(3.toByte(), snapshot.audio!!.pcm16.first())
    }
}
